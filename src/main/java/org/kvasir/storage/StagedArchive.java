package org.kvasir.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.jboss.logging.Logger;

/**
 * An {@link Archive} that was copied to local disk before it could be opened, and whose copy has to
 * disappear again afterwards.
 * <p>
 * Deliberately a decorator rather than something the openers know about: staging depends on where
 * the archive came from, not on its format, so every present and future format gets it for free.
 */
record StagedArchive(Archive delegate, Path stagedCopy) implements Archive {

    private static final Logger LOG = Logger.getLogger(StagedArchive.class);

    @Override
    public Path root() {
        return delegate.root();
    }

    @Override
    public void close() throws IOException {
        try {
            delegate.close();
        } finally {
            try {
                Files.deleteIfExists(stagedCopy);
            } catch (IOException e) {
                LOG.warnf(e, "Could not delete staged archive %s", stagedCopy);
            }
        }
    }
}
