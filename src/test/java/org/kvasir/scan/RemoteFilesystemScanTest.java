package org.kvasir.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.hibernate.search.mapper.pojo.standalone.mapping.SearchMapping;
import org.hibernate.search.mapper.pojo.standalone.session.SearchSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kvasir.entity.ChunkType;
import org.kvasir.entity.DocChunk;
import org.kvasir.parser.DocumentParser;
import org.kvasir.storage.DataStore;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Runs a complete scan — documentation and a sources JAR — against a filesystem that is not the
 * local disk, which is what pointing {@code docs.data-dir} at object storage would amount to.
 * <p>
 * An in-memory filesystem stands in for S3 here: what matters is that it is a different NIO
 * provider, so any place that quietly assumed local disk would fail. The JAR is the interesting
 * part, because the zip provider needs random access and therefore goes through the staging path in
 * {@link DataStore}.
 */
@QuarkusTest
class RemoteFilesystemScanTest {

    private static final String PROJECT = "remote-demo";

    @Inject
    Instance<DocumentParser> parsers;

    @Inject
    ChunkIngestor ingestor;

    @Inject
    ScanRegistry registry;

    @Inject
    SearchMapping searchMapping;

    private FileSystem remote;

    @BeforeEach
    void createRemoteTree() throws IOException {
        remote = Jimfs.newFileSystem(Configuration.unix());

        Path doc = remote.getPath("/corpus", PROJECT, "doc");
        Files.createDirectories(doc);
        Files.writeString(doc.resolve("overview.md"),
                "# Remote overview\n\nLiegt auf einem anderen Dateisystem.\n");

        Path version = remote.getPath("/corpus", PROJECT, "3.0.0");
        Files.createDirectories(version);
        Files.writeString(version.resolve("notes.md"), "# Notes 3.0.0\n\nVersionsspezifisch.\n");
        Files.copy(buildSourcesJar(), version.resolve("remote-demo-3.0.0-sources.jar"));
    }

    /**
     * The test index is shared with the other test classes, so this project must not outlive its
     * test — otherwise it would turn up in whatever else asks the index what it holds.
     */
    @AfterEach
    void closeRemoteTreeAndForgetTheProject() throws IOException {
        remote.close();

        List<String> chunkIds;
        try (SearchSession session = searchMapping.createSession()) {
            chunkIds = session.search(DocChunk.class)
                    .select(f -> f.id(String.class))
                    .where(f -> f.match().field("project").matching(PROJECT))
                    .fetchHits(1000);
        }
        try (SearchSession session = searchMapping.createSession()) {
            chunkIds.forEach(id -> session.indexingPlan().purge(DocChunk.class, id, null));
        }
    }

    @Test
    void aTreeOnAnotherFilesystemIsScannedLikeAnyOther() {
        DataStore store = new DataStore(remote.getPath("/corpus"));
        DataScanner scanner = new DataScanner(store, parsers.stream().toList(), ingestor);
        assertNotSame(FileSystems.getDefault(), scanner.dataDir().getFileSystem(),
                "the fixture has to live off the default provider, otherwise this proves nothing");

        ScanStatus status = registry.start(PROJECT, null).scan();
        scanner.scan(status, PROJECT, null, false);
        status.completed();

        assertEquals(3, status.getScannedFiles(), "two markdown files and one sources JAR");
        assertEquals(3, status.getIndexedFiles());
        assertEquals(0, status.getFailedFiles());

        // documentation
        List<String> crossVersion = versionsOf("doc/overview.md");
        assertEquals(1, crossVersion.size());
        assertNull(crossVersion.getFirst(), "a file under doc/ applies to every version");
        assertEquals(ChunkType.MARKDOWN, typeOf("3.0.0/notes.md"));

        // the JAR: read through the staging path, chunks anchored at <jar>!/<entry>
        List<String> sources = sourcesOfType(ChunkType.JAVADOC);
        assertTrue(sources.stream().anyMatch(source -> source.contains(
                "remote-demo-3.0.0-sources.jar!/com/example/remote/Client.java")), sources.toString());
        assertEquals(1, countOfType(ChunkType.PACKAGE_DOC), "package-info.java inside the JAR");
        assertTrue(countOfType(ChunkType.SOURCE) >= 1);
    }

    /** Builds a small sources JAR on local disk; it is copied into the in-memory tree afterwards. */
    private static Path buildSourcesJar() throws IOException {
        Path jar = Files.createTempFile("remote-demo-", ".jar");
        Files.delete(jar);
        try (FileSystem zip = FileSystems.newFileSystem(jar, Map.of("create", "true"))) {
            Path packageDir = zip.getPath("/com/example/remote");
            Files.createDirectories(packageDir);
            Files.writeString(packageDir.resolve("Client.java"), """
                    package com.example.remote;

                    /** Talks to the remote side. */
                    public class Client {

                        /** Sends a request and waits for the answer. */
                        public String send(String request) {
                            return null;
                        }
                    }
                    """, StandardCharsets.UTF_8);
            Files.writeString(packageDir.resolve("package-info.java"), """
                    /**
                     * Everything needed to talk to the remote side.
                     */
                    package com.example.remote;
                    """, StandardCharsets.UTF_8);
        }
        jar.toFile().deleteOnExit();
        return jar;
    }

    private List<String> versionsOf(String source) {
        return project(source, "version", String.class);
    }

    private ChunkType typeOf(String source) {
        return project(source, "type", ChunkType.class).getFirst();
    }

    private <T> List<T> project(String source, String field, Class<T> type) {
        try (SearchSession session = searchMapping.createSession()) {
            return session.search(DocChunk.class)
                    .select(f -> f.field(field, type))
                    .where(f -> f.bool()
                            .must(f.match().field("project").matching(PROJECT))
                            .must(f.match().field("source").matching(source)))
                    .fetchHits(50);
        }
    }

    private List<String> sourcesOfType(ChunkType type) {
        try (SearchSession session = searchMapping.createSession()) {
            return session.search(DocChunk.class)
                    .select(f -> f.field("source", String.class))
                    .where(f -> f.bool()
                            .must(f.match().field("project").matching(PROJECT))
                            .must(f.match().field("type").matching(type)))
                    .fetchHits(50);
        }
    }

    private long countOfType(ChunkType type) {
        try (SearchSession session = searchMapping.createSession()) {
            return session.search(DocChunk.class)
                    .where(f -> f.bool()
                            .must(f.match().field("project").matching(PROJECT))
                            .must(f.match().field("type").matching(type)))
                    .fetchTotalHitCount();
        }
    }
}
