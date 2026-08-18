package org.kvasir.storage;

import java.io.IOException;
import java.nio.file.Path;

/**
 * An opened archive, presented as a {@link Path} tree so that callers walk it exactly like any
 * other directory and never learn which format it was.
 * <p>
 * Closing releases whatever the implementation held — a mounted filesystem, an extracted directory,
 * a staged local copy.
 */
public interface Archive extends AutoCloseable {

    /** Root of the archive content. */
    Path root();

    @Override
    void close() throws IOException;
}
