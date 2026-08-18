package org.kvasir.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kvasir.dto.SearchHit;
import org.kvasir.entity.ChunkType;
import org.kvasir.scan.ScanService;
import org.kvasir.scan.ScanStatus;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/** Searches the indexed fixture, which holds the projects {@code demo} and {@code other}. */
@QuarkusTest
class DocSearchServiceTest {

    @Inject
    DocSearchService search;

    @Inject
    ScanService scanService;

    @BeforeEach
    void indexTheFixture() {
        ScanStatus status = scanService.startScan(null, null).scan();
        Awaitility.await().atMost(2, TimeUnit.MINUTES)
                .until(() -> status.getState() != ScanStatus.State.RUNNING);
    }

    @Test
    void aHitCarriesEverythingNeededToCiteIt() {
        SearchHit hit = search.search("demo", "1.0.0", "Module", 5, null).getFirst();

        assertEquals("demo", hit.project());
        assertTrue(hit.version() == null || "1.0.0".equals(hit.version()));
        assertFalse(hit.source().isBlank(), "the agent needs to know where this came from");
        assertFalse(hit.text().isBlank());
        assertTrue(hit.type() != null);
    }

    /** Never mix projects: an answer stitched from two of them would be worse than none. */
    @Test
    void resultsNeverCrossProjectBoundaries() {
        List<SearchHit> hits = search.search("demo", "1.0.0", "guide overview module", 20, null);

        assertFalse(hits.isEmpty());
        assertEquals(Set.of("demo"), hits.stream().map(SearchHit::project).collect(toSet()));
    }

    /** Nor versions — except the cross-version documentation, which belongs to all of them. */
    @Test
    void onlyTheRequestedVersionAndTheCrossVersionDocumentationComeBack() {
        List<SearchHit> hits = search.search("demo", "1.0.0", "Aufbau Module Notes", 20, null);

        assertFalse(hits.isEmpty());
        hits.forEach(hit -> assertTrue(hit.version() == null || "1.0.0".equals(hit.version()),
                "unexpected version " + hit.version()));
    }

    /**
     * doc/overview.md applies to every version. It has to be findable from any of them, and it has
     * to be recognisable as cross-version so the agent does not quote it as version specific.
     */
    @Test
    void crossVersionDocumentationIsFoundFromEveryVersionAndMarkedAsSuch() {
        for (String version : List.of("1.0.0", "1.9.0", "1.10.0")) {
            List<SearchHit> hits = search.search("demo", version, "Aufbau zwei Module", 20, null);

            assertTrue(hits.stream()
                    .anyMatch(hit -> hit.version() == null && hit.source().startsWith("doc/")),
                    "no cross-version hit when searching version " + version);
        }
    }

    @Test
    void theTypeFilterNarrowsTheResult() {
        List<SearchHit> hits = search.search("other", "2.0.0", "guide", 10, ChunkType.HTML);

        assertFalse(hits.isEmpty());
        assertEquals(Set.of(ChunkType.HTML), hits.stream().map(SearchHit::type).collect(toSet()));
    }

    @Test
    void topKLimitsTheResult() {
        assertTrue(search.search("demo", "1.0.0", "Module Notes Aufbau", 2, null).size() <= 2);
    }

    @Test
    void badArgumentsAreRejectedByName() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> search.search(" ", "1.0.0", "x", 5, null)).getMessage().contains("project"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> search.search("demo", "", "x", 5, null)).getMessage().contains("version"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> search.search("demo", "1.0.0", "  ", 5, null)).getMessage().contains("query"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> search.search("demo", "1.0.0", "x", 0, null)).getMessage().contains("topK"));
    }

    private static <T> java.util.stream.Collector<T, ?, Set<T>> toSet() {
        return java.util.stream.Collectors.toSet();
    }
}
