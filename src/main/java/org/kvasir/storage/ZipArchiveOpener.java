package org.kvasir.storage;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Locale;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Opens zip-based archives — a sources JAR is one — through the JDK's zip filesystem provider.
 * <p>
 * Zip keeps its central directory at the end of the file, so reading it means seeking; hence
 * {@link #requiresRandomAccess()} stays at its default of {@code true}.
 */
@ApplicationScoped
public class ZipArchiveOpener implements ArchiveOpener {

    @Override
    public boolean supports(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jar") || lower.endsWith(".zip");
    }

    @Override
    public Archive open(Path archive) throws IOException {
        return new FileSystemArchive(FileSystems.newFileSystem(archive));
    }
}
