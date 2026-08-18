package org.kvasir.storage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Runs against a real S3 store, because the question this has to answer cannot be answered by a
 * mock: S3 has no directories, only keys with prefixes, and whether
 * {@code Files.isDirectory("/project/version")} says {@code true} depends entirely on how the
 * provider emulates them — and possibly on the implementation behind the endpoint.
 * <p>
 * Skipped unless {@code KVASIR_S3_LOCATION} is set, so an ordinary build needs no credentials:
 *
 * <pre>
 * export KVASIR_S3_LOCATION=s3x://s3.example.com/my-bucket/kvasir-test
 * export AWS_ACCESS_KEY_ID=...  AWS_SECRET_ACCESS_KEY=...  AWS_REGION=us-east-1
 * mvn test -Dtest=S3DataStoreTest
 * </pre>
 *
 * The bucket has to hold the layout the scanner expects, e.g.
 * {@code demo/doc/overview.md} and {@code demo/1.0.0/notes.md}.
 */
@EnabledIfEnvironmentVariable(named = "KVASIR_S3_LOCATION", matches = ".+")
class S3DataStoreTest {

    /**
     * Path style is not optional for a self-hosted store: without it the bucket is addressed as a
     * host name, and the request dies in DNS resolution rather than at the endpoint.
     */
    private static final Map<String, String> PROPERTIES = Map.of(
            "s3.spi.force-path-style", "true");

    private static DataStore store() {
        return new DataStore(System.getenv("KVASIR_S3_LOCATION"), Optional.of(PROPERTIES));
    }

    @Test
    void theLocationResolvesToSomethingOtherThanLocalDisk() {
        Path root = store().root();

        assertNotSame(FileSystems.getDefault(), root.getFileSystem(),
                "otherwise this test proves nothing");
        assertTrue(Files.isDirectory(root), "the bucket prefix has to look like a directory");
    }

    /**
     * The heart of it: the scanner discovers projects and versions by listing directories. If the
     * provider does not emulate prefixes as directories, the layout detection collapses.
     */
    @Test
    void prefixesAreSeenAsDirectories() throws IOException {
        Path root = store().root();

        List<Path> projects;
        try (Stream<Path> children = Files.list(root)) {
            projects = children.filter(Files::isDirectory).toList();
        }

        assertFalse(projects.isEmpty(),
                "no prefix was recognised as a directory - the layout detection would find nothing");

        Path project = projects.getFirst();
        try (Stream<Path> versions = Files.list(project)) {
            assertFalse(versions.filter(Files::isDirectory).toList().isEmpty(),
                    "no version directory below " + project);
        }
    }

    /**
     * Pins the reason DataScanner strips a trailing separator: this provider returns directory
     * names <em>with</em> one. Left in, a project would be indexed as {@code "jackson/"} and the
     * cross-version directory would not be recognised as {@code doc} at all — it would become a
     * version called {@code doc/}. Neither shows up on local disk.
     */
    @Test
    void directoryNamesComeBackWithATrailingSeparator() throws IOException {
        try (Stream<Path> children = Files.list(store().root())) {
            Path directory = children.filter(Files::isDirectory).findFirst().orElseThrow();

            assertTrue(directory.getFileName().toString().endsWith("/"),
                    "if this ever stops being true the stripping in DataScanner can go: "
                            + directory.getFileName());
        }
    }

    @Test
    void filesCanBeWalkedAndRead() throws IOException {
        try (Stream<Path> files = Files.walk(store().root())) {
            Path file = files.filter(Files::isRegularFile).findFirst().orElseThrow(
                    () -> new AssertionError("no readable file found in the bucket"));

            assertFalse(Files.readString(file).isEmpty(), "could not read " + file);
        }
    }

    /**
     * Archives need random access, which S3 cannot serve sensibly, so ArchiveReader stages them
     * locally. Only runs if the bucket actually holds one.
     */
    @Test
    void anArchiveIsStagedLocallyAndReadable() throws IOException {
        Path archive;
        try (Stream<Path> files = Files.walk(store().root())) {
            archive = files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .findFirst()
                    .orElse(null);
        }
        if (archive == null) {
            return; // nothing to check in this bucket
        }

        ArchiveReader reader = new ArchiveReader(List.of(new ZipArchiveOpener()));
        Path staged;
        try (Archive opened = reader.open(archive)) {
            StagedArchive stagedArchive = (StagedArchive) opened;
            staged = stagedArchive.stagedCopy();
            assertSame(FileSystems.getDefault(), staged.getFileSystem(), "staged onto local disk");

            try (Stream<Path> entries = Files.walk(opened.root())) {
                assertFalse(entries.filter(Files::isRegularFile).toList().isEmpty(),
                        "the archive appears to be empty");
            }
        }
        assertFalse(Files.exists(staged), "the staged copy has to be gone once the archive closes");
    }

}
