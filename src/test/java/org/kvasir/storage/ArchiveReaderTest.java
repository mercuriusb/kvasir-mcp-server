package org.kvasir.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;

/**
 * Opening archives, including the case this is really meant for: an archive that does not lie on
 * local disk. An in-memory filesystem stands in for object storage — what matters is only that it
 * is a different NIO provider.
 */
class ArchiveReaderTest {

    private final ArchiveReader reader = new ArchiveReader(List.of(new ZipArchiveOpener()));

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
    void zipBasedFormatsAreClaimed() {
        assertTrue(reader.supports("demo-1.0.0-sources.jar"));
        assertTrue(reader.supports("docs.zip"));
        assertTrue(reader.supports("DOCS.ZIP"), "the extension is matched case insensitively");

        assertFalse(reader.supports("notes.md"));
        assertFalse(reader.supports("sources.tar.gz"), "no opener for tar yet");
    }

    @Test
    void anArchiveWithoutAnOpenerIsRejectedByName() {
        Path tar = inMemory.getPath("/bucket/sources.tar.gz");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> reader.open(tar));

        assertTrue(failure.getMessage().contains("sources.tar.gz"), failure.getMessage());
    }

    @Test
    void anArchiveOnTheDefaultFilesystemIsReadInPlace() throws IOException {
        Path jar = jarOnLocalDisk("com/example/Demo.java");

        try (Archive archive = reader.open(jar)) {
            assertInstanceOf(FileSystemArchive.class, archive, "no staging needed on local disk");
            assertEquals(List.of("/com/example/Demo.java"), entriesOf(archive));
        } finally {
            Files.deleteIfExists(jar);
        }
    }

    /**
     * Zip has to seek, which an object store cannot serve sensibly, so a remote archive is copied
     * once and read from the copy — and the copy has to disappear again.
     */
    @Test
    void aRemoteArchiveIsStagedLocallyAndCleanedUpAfterwards() throws IOException {
        Path remote = copyToInMemory(jarOnLocalDisk("com/example/Remote.java"));

        Path staged;
        try (Archive archive = reader.open(remote)) {
            StagedArchive stagedArchive = assertInstanceOf(StagedArchive.class, archive);
            staged = stagedArchive.stagedCopy();

            assertNotSame(inMemory, staged.getFileSystem(), "staged onto local disk");
            assertTrue(Files.exists(staged));
            assertEquals(List.of("/com/example/Remote.java"), entriesOf(archive));
        }
        assertFalse(Files.exists(staged), "the staged copy has to be gone once the archive closes");
    }

    private Path copyToInMemory(Path localJar) throws IOException {
        Path remote = inMemory.getPath("/bucket/demo-sources.jar");
        Files.createDirectories(remote.getParent());
        Files.copy(localJar, remote);
        Files.delete(localJar);
        return remote;
    }

    private static List<String> entriesOf(Archive archive) throws IOException {
        try (Stream<Path> entries = Files.walk(archive.root())) {
            return entries.filter(Files::isRegularFile).map(Path::toString).sorted().toList();
        }
    }

    private static Path jarOnLocalDisk(String entry) throws IOException {
        Path jar = Files.createTempFile("kvasir-test-", ".jar");
        Files.delete(jar);
        try (FileSystem zip = FileSystems.newFileSystem(jar, Map.of("create", "true"))) {
            Path target = zip.getPath("/" + entry);
            Files.createDirectories(target.getParent());
            Files.writeString(target, "package com.example; public class X {}",
                    StandardCharsets.UTF_8);
        }
        return jar;
    }
}
