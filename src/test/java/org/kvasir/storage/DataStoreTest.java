package org.kvasir.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.spi.FileSystemProvider;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;

/**
 * Checks how {@code docs.data-dir} is resolved. Opening archives lives in {@link ArchiveReader} and
 * is covered by its own test.
 */
class DataStoreTest {

    private FileSystem inMemory;

    @BeforeEach
    void createInMemoryFilesystem() {
        inMemory = Jimfs.newFileSystem(Configuration.unix());
    }

    @AfterEach
    void closeInMemoryFilesystem() throws IOException {
        inMemory.close();
    }

    @Test
    void aPlainLocationResolvesOnTheDefaultFilesystem() {
        DataStore store = new DataStore("data");

        assertSame(FileSystems.getDefault(), store.root().getFileSystem());
        assertEquals(Path.of("data"), store.root());
    }

    @Test
    void aFileUriIsTreatedAsAnOrdinaryPath() {
        DataStore store = new DataStore("file:///srv/java-docs");

        assertSame(FileSystems.getDefault(), store.root().getFileSystem());
    }

    /** A scheme nobody provides has to say exactly that, not fail somewhere further down. */
    @Test
    void aSchemeWithoutAProviderIsReportedAsSuch() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new DataStore("wobble://somewhere/java-docs"));

        assertTrue(failure.getMessage().contains("wobble"), failure.getMessage());
        assertTrue(failure.getMessage().contains("classpath"), failure.getMessage());
    }

    /**
     * The S3 provider ships with the application, so its schemes must resolve — and resolve without
     * contacting anything. It builds its filesystem lazily, so an unreachable endpoint is not an
     * error here; it surfaces as a warning at startup and as a failure on the first scan.
     */
    @Test
    void theS3SchemesResolveWithoutContactingAnything() {
        assertTrue(FileSystemProvider.installedProviders().stream()
                .map(FileSystemProvider::getScheme)
                .toList()
                .containsAll(List.of("s3", "s3x")), "S3 provider not registered");

        DataStore store = new DataStore("s3x://s3.invalid/bucket");

        assertEquals("s3x", store.root().getFileSystem().provider().getScheme());
    }

}
