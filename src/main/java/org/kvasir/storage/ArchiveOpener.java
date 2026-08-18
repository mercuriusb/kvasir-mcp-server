package org.kvasir.storage;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Opens one archive format.
 * <p>
 * This is the seam for further formats: a {@code TarArchiveOpener} would be a new implementation
 * and nothing else — neither {@link ArchiveReader} nor the scanner nor the parsers change, because
 * they only ever see the {@link Path} tree an {@link Archive} exposes.
 */
public interface ArchiveOpener {

    /** Whether this opener handles the given file name, judged by its extension. */
    boolean supports(String fileName);

    /**
     * Whether the format needs to seek within the archive.
     * <p>
     * Zip-based formats keep their directory at the end of the file and jump around; a tar is read
     * front to back and could be streamed straight off remote storage. {@link ArchiveReader} uses
     * this to decide whether a remote archive has to be copied locally first.
     */
    default boolean requiresRandomAccess() {
        return true;
    }

    Archive open(Path archive) throws IOException;
}
