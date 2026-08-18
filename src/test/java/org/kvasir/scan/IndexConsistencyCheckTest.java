package org.kvasir.scan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.hibernate.search.mapper.pojo.standalone.mapping.SearchMapping;
import org.hibernate.search.mapper.pojo.standalone.session.SearchSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.kvasir.entity.IndexedFile;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * Recreates the mismatch that would otherwise pass unnoticed: fingerprints restored without their
 * chunks. A scan then skips every file, the chunk index stays empty, and searches quietly return
 * nothing.
 */
@QuarkusTest
class IndexConsistencyCheckTest {

    private static final String PROJECT = "__half-restored__";

    @Inject
    IndexConsistencyCheck check;

    @Inject
    SearchMapping searchMapping;

    @AfterEach
    void removeTheFingerprint() {
        try (SearchSession session = searchMapping.createSession()) {
            session.indexingPlan().purge(IndexedFile.class,
                    IndexedFile.idOf(PROJECT, "1.0.0", "notes.md"), null);
        }
    }

    @Test
    void aProjectWithFingerprintsButNoChunksIsReported() {
        assertFalse(check.projectsWithoutChunks().contains(PROJECT), "nothing there yet");

        try (SearchSession session = searchMapping.createSession()) {
            session.indexingPlan().addOrUpdate(
                    new IndexedFile(PROJECT, "1.0.0", "notes.md", "a-hash"));
        }

        List<String> broken = check.projectsWithoutChunks();

        assertTrue(broken.contains(PROJECT),
                "a project recorded as indexed but holding no chunks: " + broken);
    }

    /** The healthy projects of the fixture must not be flagged along with it. */
    @Test
    void projectsThatDoHaveChunksAreNotReported() {
        assertFalse(check.projectsWithoutChunks().contains("demo"));
        assertFalse(check.projectsWithoutChunks().contains("other"));
    }
}
