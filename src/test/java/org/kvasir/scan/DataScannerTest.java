package org.kvasir.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.hibernate.search.engine.search.aggregation.AggregationKey;
import org.hibernate.search.mapper.pojo.standalone.mapping.SearchMapping;
import org.hibernate.search.mapper.pojo.standalone.session.SearchSession;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.kvasir.entity.ChunkType;
import org.kvasir.entity.DocChunk;
import org.kvasir.entity.IndexSchema;
import org.kvasir.entity.IndexedFile;
import org.kvasir.parser.SourceLocation;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * Scans the fixture tree under {@code target/test-classes/data-fixture} and checks what ends up in
 * the index. Runs against the real cluster, but on the test-prefixed index that is dropped again
 * when the test run ends.
 * <p>
 * Ordered on purpose: the later tests build on the state the first scan leaves behind, which is
 * exactly what "scanning again must not duplicate anything" is about.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DataScannerTest {

    @Inject
    DataScanner scanner;

    @Inject
    ScanRegistry registry;

    @Inject
    SearchMapping searchMapping;

    @Inject
    ChunkIngestor ingestor;

    private ScanStatus scan(String project, String version) {
        ScanStatus status = registry.start(project, version).scan();
        scanner.scan(status, project, version, false);
        status.completed();
        return status;
    }

    @Test
    @Order(1)
    void aFullScanIndexesEveryProject() {
        // another test class may have scanned the same fixture already; drop the recorded hashes
        // so that this really exercises a first scan rather than the skip path
        forgetAllHashes();

        ScanStatus status = scan(null, null);

        assertEquals(6, status.getScannedFiles(), "five files under demo, one under other");
        assertEquals(6, status.getIndexedFiles());
        assertEquals(0, status.getFailedFiles());
        assertTrue(status.getIndexedChunks() >= 4);

        assertEquals(List.of("demo", "other"), projectsInIndex());
    }

    @Test
    @Order(2)
    void documentationUnderDocIsIndexedWithoutAVersion() {
        List<String> versions = fieldOf("demo", "doc/overview.md", "version", String.class);

        assertTrue(versions.size() >= 2, "the file has an intro and two headings");
        versions.forEach(version -> assertNull(version,
                "a file under doc/ applies to every version"));
        assertEquals(ChunkType.MARKDOWN,
                fieldOf("demo", "doc/overview.md", "type", ChunkType.class).getFirst());
    }

    @Test
    @Order(3)
    void eachFileTypeReachesItsOwnParser() {
        assertEquals(ChunkType.MARKDOWN, typeOf("demo", "1.0.0/notes.md"));
        assertEquals(ChunkType.TXT, typeOf("demo", "1.0.0/api.txt"));
        assertEquals(ChunkType.HTML, typeOf("other", "2.0.0/guide.html"));
    }

    @Test
    @Order(4)
    void everyIndexedChunkExceptSourceCarriesAVector() {
        fieldOf("demo", "1.0.0/notes.md", "embedding", float[].class)
                .forEach(vector -> assertEquals(384, vector.length,
                        "chunks have to be searchable by kNN"));
    }

    @Test
    @Order(5)
    void scanningAgainSkipsUnchangedFilesAndAddsNothing() {
        long before = countChunks();

        ScanStatus status = scan(null, null);

        assertEquals(6, status.getScannedFiles());
        assertEquals(6, status.getSkippedFiles(), "nothing changed, so everything is skipped");
        assertEquals(0, status.getIndexedFiles());
        assertEquals(before, countChunks(), "a repeated scan must not duplicate chunks");
    }

    @Test
    @Order(6)
    void narrowingToOneProjectLeavesTheOthersAlone() {
        ScanStatus status = scan("demo", null);

        assertEquals(5, status.getScannedFiles(), "only the five files of demo");
    }

    @Test
    @Order(7)
    void narrowingToOneVersionExcludesTheCrossVersionDirectory() {
        ScanStatus status = scan("demo", "1.0.0");

        assertEquals(2, status.getScannedFiles(), "doc/ is a scope of its own and stays untouched");
    }

    @Test
    @Order(8)
    void anUnknownProjectScansNothingRatherThanFailing() {
        ScanStatus status = scan("does-not-exist", null);

        assertEquals(ScanStatus.State.COMPLETED, status.getState());
        assertEquals(0, status.getScannedFiles());
    }

    /**
     * Chunk ids are position based, so a file that shrinks would leave its surplus chunks behind
     * unless the previous ones are removed first.
     */
    @Test
    @Order(9)
    void aShrinkingFileLeavesNoOrphansBehind() throws IOException {
        Path notes = scanner.dataDir().resolve("demo/1.0.0/notes.md");
        String original = Files.readString(notes, StandardCharsets.UTF_8);
        long before = countChunksOf("demo", "1.0.0/notes.md");

        try {
            Files.writeString(notes, "# Only one section left\n\nNothing else.\n");
            scan("demo", "1.0.0");

            assertEquals(1, countChunksOf("demo", "1.0.0/notes.md"),
                    "the old chunks of this file have to be gone, not merely overwritten");
        } finally {
            Files.writeString(notes, original);
            scan("demo", "1.0.0");
        }

        assertEquals(before, countChunksOf("demo", "1.0.0/notes.md"), "restored again");
    }

    /**
     * The scan only ever visits files that exist, so a deleted one can never be noticed by the walk
     * itself. Without an explicit step it would keep answering searches with content that is gone.
     */
    @Test
    @Order(12)
    void aFileRemovedFromDiskLosesItsChunks() throws IOException {
        Path extra = scanner.dataDir().resolve("demo/1.0.0/temporary.md");
        Files.writeString(extra, "# Temporary\n\nWird gleich wieder geloescht.\n");
        scan("demo", "1.0.0");
        assertEquals(1, countChunksOf("demo", "1.0.0/temporary.md"));

        Files.delete(extra);
        ScanStatus status = scan("demo", "1.0.0");

        assertEquals(1, status.getRemovedFiles());
        assertEquals(1, status.getRemovedChunks());
        assertEquals(0, countChunksOf("demo", "1.0.0/temporary.md"),
                "the chunks of a deleted file have to go with it");
    }

    /**
     * Raising the schema version means what is stored is no longer what would be written today,
     * even though the files themselves did not change.
     */
    @Test
    @Order(13)
    void aFileIndexedUnderAnOlderSchemaCountsAsChanged() {
        SourceLocation location = new SourceLocation("demo", "1.0.0", "notes.md");
        String hash = "some-hash";

        IndexedFile.FileRef file = new IndexedFile.FileRef("demo", "1.0.0", "notes.md");
        KnownFileHashes current = new KnownFileHashes(Map.of(
                IndexedFile.idOf("demo", "1.0.0", "notes.md"),
                new KnownFileHashes.Fingerprint(file, hash, IndexSchema.VERSION)));
        KnownFileHashes outdated = new KnownFileHashes(Map.of(
                IndexedFile.idOf("demo", "1.0.0", "notes.md"),
                new KnownFileHashes.Fingerprint(file, hash, IndexSchema.VERSION - 1)));

        assertTrue(current.isUnchanged(location, hash));
        assertFalse(outdated.isUnchanged(location, hash),
                "same content, but indexed under a schema that no longer applies");
    }

    /** The way out when the fingerprints are known to be lying. */
    @Test
    @Order(14)
    void aForcedScanRebuildsEvenWhatLooksUnchanged() {
        scan("demo", "1.0.0");

        ScanStatus forced = registry.start("demo", "1.0.0").scan();
        scanner.scan(forced, "demo", "1.0.0", true);
        forced.completed();

        assertEquals(2, forced.getIndexedFiles(), "nothing is skipped on a forced run");
        assertEquals(0, forced.getSkippedFiles());
    }

    /**
     * The hashes are read once per scope rather than once per file. This checks that the scope is
     * respected and that a cross-version file — whose version is a missing field, not a value — is
     * keyed and found correctly all the same.
     */
    @Test
    @Order(11)
    void hashesAreLoadedPerScopeAndCoverCrossVersionFilesToo() throws IOException {
        KnownFileHashes wholeProject = ingestor.knownHashes("demo", null);
        assertEquals(5, wholeProject.size(), "four files across the versions plus the one under doc/");

        Path crossVersionFile = scanner.dataDir().resolve("demo/doc/overview.md");
        assertTrue(wholeProject.isUnchanged(new SourceLocation("demo", null, "doc/overview.md"),
                ChunkIngestor.hash(crossVersionFile)));
        assertFalse(wholeProject.isUnchanged(new SourceLocation("demo", null, "doc/overview.md"),
                "a-different-hash"));

        KnownFileHashes oneVersion = ingestor.knownHashes("demo", "1.0.0");
        assertEquals(2, oneVersion.size(), "doc/ belongs to no version and stays out");

        assertEquals(0, ingestor.knownHashes("does-not-exist", null).size());
    }

    /**
     * The distinct values of the filter fields are asked for by aggregation, which is how
     * list_projects and list_versions can be answered from the index rather than from the
     * directory listing.
     */
    @Test
    @Order(10)
    void theFilterFieldsCanBeAggregated() {
        assertEquals(List.of("demo", "other"),
                List.copyOf(termsOf("project", String.class).keySet()));
        assertTrue(termsOf("version", String.class).containsKey("1.0.0"));

        // the value bridge applies to aggregation results too, so the buckets are typed
        assertTrue(termsOf("type", ChunkType.class).containsKey(ChunkType.MARKDOWN));
    }

    private <T> Map<T, Long> termsOf(String field, Class<T> type) {
        AggregationKey<Map<T, Long>> key = AggregationKey.of(field);
        try (SearchSession session = searchMapping.createSession()) {
            return session.search(DocChunk.class)
                    .where(f -> f.matchAll())
                    .aggregation(key, f -> f.terms().field(field, type))
                    .fetch(0)
                    .aggregation(key);
        }
    }

    private List<String> projectsInIndex() {
        try (SearchSession session = searchMapping.createSession()) {
            return session.search(DocChunk.class)
                    .select(f -> f.field("project", String.class))
                    .where(f -> f.matchAll())
                    .fetchHits(500)
                    .stream()
                    .distinct()
                    .sorted()
                    .toList();
        }
    }

    private ChunkType typeOf(String project, String source) {
        return fieldOf(project, source, "type", ChunkType.class).getFirst();
    }

    /** Projects one field of the chunks that came from the given file. */
    private <T> List<T> fieldOf(String project, String source, String field, Class<T> type) {
        try (SearchSession session = searchMapping.createSession()) {
            return session.search(DocChunk.class)
                    .select(f -> f.field(field, type))
                    .where(f -> f.bool()
                            .must(f.match().field("project").matching(project))
                            .must(f.match().field("source").matching(source)))
                    .fetchHits(200);
        }
    }

    private long countChunksOf(String project, String source) {
        try (SearchSession session = searchMapping.createSession()) {
            return session.search(DocChunk.class)
                    .where(f -> f.bool()
                            .must(f.match().field("project").matching(project))
                            .must(f.match().field("source").matching(source)))
                    .fetchTotalHitCount();
        }
    }

    /** Makes every file look new again, without touching the chunks themselves. */
    private void forgetAllHashes() {
        List<String> ids;
        try (SearchSession session = searchMapping.createSession()) {
            ids = session.search(IndexedFile.class)
                    .select(f -> f.id(String.class))
                    .where(f -> f.matchAll())
                    .fetchHits(500);
        }
        try (SearchSession session = searchMapping.createSession()) {
            ids.forEach(id -> session.indexingPlan().purge(IndexedFile.class, id, null));
        }
    }

    private long countChunks() {
        try (SearchSession session = searchMapping.createSession()) {
            return session.search(DocChunk.class).where(f -> f.matchAll()).fetchTotalHitCount();
        }
    }
}
