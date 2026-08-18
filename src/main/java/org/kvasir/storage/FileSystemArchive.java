package org.kvasir.storage;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;

/** An {@link Archive} backed by a mounted {@link FileSystem}, such as the zip one. */
record FileSystemArchive(FileSystem fileSystem) implements Archive {

    @Override
    public Path root() {
        return fileSystem.getPath("/");
    }

    @Override
    public void close() throws IOException {
        fileSystem.close();
    }
}
