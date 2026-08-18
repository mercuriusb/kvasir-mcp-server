package org.kvasir.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Opens archives, whatever their format and wherever they lie.
 * <p>
 * Knows two things that no {@link ArchiveOpener} should have to: which openers exist, and that an
 * archive on remote storage may have to be copied locally before a format that seeks can read it.
 * Adding a format therefore means adding one {@link ArchiveOpener} and touching nothing else.
 */
@ApplicationScoped
public class ArchiveReader {

    private static final Logger LOG = Logger.getLogger(ArchiveReader.class);

    private final List<ArchiveOpener> openers;

    @Inject
    public ArchiveReader(Instance<ArchiveOpener> openers) {
        this(openers.stream().toList());
    }

    public ArchiveReader(List<ArchiveOpener> openers) {
        this.openers = List.copyOf(openers);
    }

    /** Whether any known format claims this file name. */
    public boolean supports(String fileName) {
        return openers.stream().anyMatch(opener -> opener.supports(fileName));
    }

    /**
     * @throws IllegalArgumentException if no opener handles this kind of archive
     */
    public Archive open(Path archive) throws IOException {
        String fileName = archive.getFileName().toString();
        ArchiveOpener opener = openers.stream()
                .filter(candidate -> candidate.supports(fileName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No opener for archive " + fileName));

        if (!opener.requiresRandomAccess() || archive.getFileSystem() == FileSystems.getDefault()) {
            return opener.open(archive);
        }
        return openStaged(opener, archive);
    }

    /**
     * Copies a remote archive to local disk in one sequential read.
     * <p>
     * Serving thousands of seeks out of an object store would mean thousands of range requests; one
     * download is cheaper by any measure. The copy is removed when the archive is closed.
     */
    private Archive openStaged(ArchiveOpener opener, Path archive) throws IOException {
        Path staged = Files.createTempFile("kvasir-archive-", ".tmp");
        LOG.debugf("Staging %s to %s for random access", archive, staged);
        try (InputStream source = Files.newInputStream(archive)) {
            Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING);
            return new StagedArchive(opener.open(staged), staged);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(staged);
            throw e;
        }
    }
}
