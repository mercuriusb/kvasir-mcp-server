package org.kvasir.scan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.jboss.logging.Logger;
import org.kvasir.entity.DocChunk;
import org.kvasir.entity.IndexedFile;
import org.kvasir.parser.DocumentParser;
import org.kvasir.parser.SourceLocation;
import org.kvasir.storage.DataStore;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Walks the data directory and feeds what it finds to the parsers and the ingestor.
 * <p>
 * Expected layout, where {@code doc/} holds documentation that applies to every version:
 *
 * <pre>
 * &lt;data-dir&gt;/&lt;project&gt;/doc/**
 * &lt;data-dir&gt;/&lt;project&gt;/&lt;version&gt;/**
 * </pre>
 *
 * This class knows the layout and nothing else. Which file types exist is the parsers' business —
 * it simply asks who claims a file, so a new format is a new {@link DocumentParser} and no change
 * here. Where the tree lives is {@link DataStore}'s business, so pointing {@code docs.data-dir} at
 * object storage needs no change either.
 */
@ApplicationScoped
public class DataScanner {

    private static final Logger LOG = Logger.getLogger(DataScanner.class);

    /** Directory that holds documentation valid for every version of a project. */
    private static final String CROSS_VERSION_DIRECTORY = "doc";

    private final DataStore dataStore;
    private final List<DocumentParser> parsers;
    private final ChunkIngestor ingestor;

    @Inject
    public DataScanner(DataStore dataStore, Instance<DocumentParser> parsers,
            ChunkIngestor ingestor) {
        this(dataStore, parsers.stream().toList(), ingestor);
    }

    public DataScanner(DataStore dataStore, List<DocumentParser> parsers, ChunkIngestor ingestor) {
        this.dataStore = dataStore;
        this.parsers = List.copyOf(parsers);
        this.ingestor = ingestor;
    }

    public Path dataDir() {
        return dataStore.root();
    }

    /**
     * The part of the tree one scan covers, and the metadata every chunk from it inherits.
     *
     * @param projectDir directory of the project, the base every path is made relative to
     * @param version    version directory, or {@code null} for the cross-version {@code doc/}
     */
    private record ScanScope(Path projectDir, String version) {

        String project() {
            return name(projectDir);
        }

        SourceLocation locationOf(Path file) {
            return new SourceLocation(project(), version, projectDir.relativize(file).toString());
        }
    }

    /**
     * Scans the data directory, optionally narrowed down.
     *
     * @param onlyProject {@code null} to scan every project
     * @param onlyVersion {@code null} to scan every version including {@code doc/}; a value scans
     *                    that version's directory only, not the cross-version documentation
     * @param force       ignore the recorded fingerprints and rebuild everything in scope. The way
     *                    out when the index is known to be wrong but the fingerprints still claim
     *                    otherwise — after half a restore, for instance
     */
    public void scan(ScanStatus status, String onlyProject, String onlyVersion, boolean force) {
        Path dataRoot = dataStore.root();
        if (!Files.isDirectory(dataRoot)) {
            throw new IllegalStateException(
                    "docs.data-dir points at '%s', which is not a directory".formatted(dataRoot));
        }
        for (Path projectDir : directoriesIn(dataRoot)) {
            if (onlyProject != null && !onlyProject.equals(name(projectDir))) {
                continue;
            }
            scanProject(status, projectDir, onlyVersion, force);
        }
    }

    /**
     * One unreadable project must not cost the other nineteen, so a failure here is contained the
     * same way a failure on a single file is.
     */
    private void scanProject(ScanStatus status, Path projectDir, String onlyVersion,
            boolean force) {
        try {
            // one query per project instead of one per file: the scan would otherwise spend its
            // time waiting on round trips before it has parsed anything
            KnownFileHashes known = force
                    ? KnownFileHashes.NONE
                    : ingestor.knownHashes(name(projectDir), onlyVersion);
            LOG.debugf("Project %s: %d files already indexed", name(projectDir), known.size());
            Set<String> seen = new HashSet<>();

            for (Path versionDir : directoriesIn(projectDir)) {
                String version = CROSS_VERSION_DIRECTORY.equals(name(versionDir))
                        ? null
                        : name(versionDir);
                // narrowing to a version means that version's directory, nothing else
                if (onlyVersion != null && !onlyVersion.equals(version)) {
                    continue;
                }
                scanDirectory(status, new ScanScope(projectDir, version), versionDir, known, seen);
            }
            // only once every directory of the scope was walked without incident: anything still
            // recorded but never seen is gone from disk, and its chunks have to go with it. Skipped
            // on a forced run, where the fingerprints were deliberately ignored and say nothing.
            if (!force) {
                forgetVanishedFiles(status, known, seen);
            }
        } catch (Exception e) {
            LOG.errorf(e, "Failed to scan project %s", name(projectDir));
            status.projectFailed();
        }
    }

    /**
     * Removes what is in the index but no longer on disk.
     * <p>
     * The scan itself can never notice a deleted file — it only ever visits files that exist. Without
     * this step a removed document would keep turning up in searches with content that is gone.
     */
    private void forgetVanishedFiles(ScanStatus status, KnownFileHashes known, Set<String> seen) {
        for (String fileId : known.fileIds()) {
            if (seen.contains(fileId)) {
                continue;
            }
            LOG.infof("Removing %s: no longer present on disk", fileId);
            status.fileRemoved(ingestor.forget(fileId));
        }
    }

    private void scanDirectory(ScanStatus status, ScanScope scope, Path directory,
            KnownFileHashes known, Set<String> seen) throws IOException {
        try (Stream<Path> files = Files.walk(directory)) {
            files.filter(Files::isRegularFile)
                    .sorted()
                    .forEach(file -> scanFile(status, scope, file, known, seen));
        }
    }

    private void scanFile(ScanStatus status, ScanScope scope, Path file,
            KnownFileHashes known, Set<String> seen) {
        DocumentParser parser = parserFor(name(file));
        if (parser == null) {
            return;
        }
        status.fileScanned();

        SourceLocation location = scope.locationOf(file);
        // marked as seen even if indexing fails below: the file is on disk, so it is not deleted
        seen.add(IndexedFile.idOf(location.project(), location.version(), location.path()));
        try {
            // hashed straight off the stream: the file may be an archive of any size, and its
            // content is the parser's business, not ours
            String contentHash = ChunkIngestor.hash(file);
            if (known.isUnchanged(location, contentHash)) {
                status.fileSkipped();
                return;
            }

            List<DocChunk> chunks = parser.parse(location, file);
            status.fileIndexed(ingestor.replace(location.project(), location.version(),
                    location.path(), contentHash, chunks));
        } catch (Exception e) {
            LOG.errorf(e, "Failed to index %s", location.path());
            status.fileFailed();
        }
    }

    /** The parsers' {@code supports} sets are disjoint, so the first match is the only match. */
    private DocumentParser parserFor(String fileName) {
        return parsers.stream()
                .filter(parser -> parser.supports(fileName))
                .findFirst()
                .orElse(null);
    }

    /** Sorted so that a scan walks the tree in the same order every time. */
    private static List<Path> directoriesIn(Path parent) {
        try (Stream<Path> children = Files.list(parent)) {
            return children.filter(Files::isDirectory)
                    .filter(child -> !name(child).startsWith("."))
                    .sorted(Comparator.comparing(DataScanner::name))
                    .toList();
        } catch (IOException e) {
            // the URI and not the path: at the root of an S3 bucket the path prints as the empty
            // string, and "Could not list " tells whoever reads the failed scan nothing at all.
            throw new IllegalStateException("Could not list " + parent.toUri() + ": " + e, e);
        }
    }

    /**
     * File or directory name, without a trailing separator.
     * <p>
     * Not cosmetic: the S3 provider returns directory names <em>with</em> the separator, so a
     * project would be indexed as {@code "jackson/"} and every value the agent gets back — and
     * passes to the next call — would carry it. On local disk the trailing slash never appears, so
     * this only shows up against real object storage.
     */
    private static String name(Path path) {
        String name = path.getFileName().toString();
        String separator = path.getFileSystem().getSeparator();
        return name.endsWith(separator)
                ? name.substring(0, name.length() - separator.length())
                : name;
    }
}
