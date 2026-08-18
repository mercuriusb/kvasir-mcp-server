package org.kvasir.parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.jboss.logging.Logger;
import org.kvasir.entity.DocChunk;
import org.kvasir.storage.Archive;
import org.kvasir.storage.ArchiveReader;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Parses an archive of Java sources by walking its entries and handing each to the parser that
 * fits.
 * <p>
 * An archive is a {@link DocumentParser} like any other: the scanner does not know it is dealing
 * with a container, it only asks who claims the file. Which formats are claimed is decided by
 * {@link ArchiveReader} — adding tar support means adding an opener there, not touching this class.
 * <p>
 * Every chunk from inside an archive carries a source of {@code <archive>!/<entry>}, which is what
 * lets them all be removed together when the archive changes.
 */
@ApplicationScoped
public class SourceArchiveParser implements DocumentParser {

    private static final Logger LOG = Logger.getLogger(SourceArchiveParser.class);

    /** Legacy counterpart of {@code package-info.java}. */
    private static final String PACKAGE_HTML = "package.html";

    private final ArchiveReader archiveReader;
    private final JavaSourceParser javaSourceParser;
    private final HtmlParser htmlParser;

    public SourceArchiveParser(ArchiveReader archiveReader, JavaSourceParser javaSourceParser,
            HtmlParser htmlParser) {
        this.archiveReader = archiveReader;
        this.javaSourceParser = javaSourceParser;
        this.htmlParser = htmlParser;
    }

    @Override
    public boolean supports(String fileName) {
        return archiveReader.supports(fileName);
    }

    @Override
    public List<DocChunk> parse(SourceLocation location, Path file) throws IOException {
        List<DocChunk> chunks = new ArrayList<>();
        try (Archive archive = archiveReader.open(file);
                Stream<Path> entries = Files.walk(archive.root())) {
            Path root = archive.root();
            entries.filter(Files::isRegularFile)
                    .sorted()
                    .forEach(entry -> parseEntry(location, root, entry, chunks));
        }
        return chunks;
    }

    private void parseEntry(SourceLocation archiveLocation, Path root, Path entry,
            List<DocChunk> chunks) {
        String entryPath = root.relativize(entry).toString();
        SourceLocation location = archiveLocation.insideArchive(entryPath);
        String entryName = entry.getFileName().toString();
        try {
            if (javaSourceParser.supports(entryName)) {
                chunks.addAll(javaSourceParser.parse(location,
                        Files.readString(entry, StandardCharsets.UTF_8)));
            } else if (PACKAGE_HTML.equals(entryName)) {
                chunks.addAll(htmlParser.parsePackageHtml(location,
                        Files.readString(entry, StandardCharsets.UTF_8), packageNameOf(entryPath)));
            }
        } catch (IOException | RuntimeException e) {
            // one damaged entry must not cost the whole archive
            LOG.warnf(e, "Skipping unreadable archive entry %s", location.path());
        }
    }

    /** {@code com/example/core/package.html} describes the package {@code com.example.core}. */
    private static String packageNameOf(String entryPath) {
        int lastSlash = entryPath.lastIndexOf('/');
        return lastSlash < 0 ? "" : entryPath.substring(0, lastSlash).replace('/', '.');
    }
}
