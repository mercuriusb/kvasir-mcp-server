package org.kvasir.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.awaitility.Awaitility;
import org.hibernate.search.mapper.pojo.standalone.mapping.SearchMapping;
import org.hibernate.search.mapper.pojo.standalone.session.SearchSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kvasir.dto.Page;
import org.kvasir.entity.DocChunk;
import org.kvasir.scan.DataScanner;
import org.kvasir.scan.ScanService;
import org.kvasir.scan.ScanStatus;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * The fixture tree holds two projects, {@code demo} and {@code other}, so the list has something to
 * distinguish. Runs against the test-prefixed index, which is dropped when the test run ends.
 */
@QuarkusTest
class IndexCatalogTest {

    @Inject
    IndexCatalog catalog;

    @Inject
    DataScanner scanner;

    @Inject
    ScanService scanService;

    @Inject
    SearchMapping searchMapping;

    /** Goes through the public API; ScanStatus is deliberately not mutable from outside its package. */
    @BeforeEach
    void indexTheFixture() {
        ScanStatus status = scanService.startScan(null, null).scan();
        Awaitility.await().atMost(2, TimeUnit.MINUTES)
                .until(() -> status.getState() != ScanStatus.State.RUNNING);
    }

    private List<String> allProjects() {
        return catalog.allProjects().items();
    }

    @Test
    void everyIndexedProjectIsListedExactlyOnceAndSorted() {
        assertEquals(List.of("demo", "other"), allProjects(),
                "both fixture projects, alphabetically, no duplicates");
    }

    @Test
    void aPageReportsTheTotalAndWhetherMoreFollows() {
        Page<String> firstOfOne = catalog.projects(0, 1);

        assertEquals(List.of("demo"), firstOfOne.items());
        assertEquals(0, firstOfOne.offset());
        assertEquals(1, firstOfOne.limit());
        assertEquals(2, firstOfOne.total(), "the total counts everything, not just this page");
        assertTrue(firstOfOne.hasMore());

        Page<String> secondOfOne = catalog.projects(1, 1);

        assertEquals(List.of("other"), secondOfOne.items());
        assertEquals(2, secondOfOne.total());
        assertFalse(secondOfOne.hasMore(), "nothing follows the last page");
    }

    /** Walking to the end must not need special casing on the caller's side. */
    @Test
    void anOffsetPastTheEndYieldsAnEmptyPageRatherThanAnError() {
        Page<String> beyond = catalog.projects(99, 10);

        assertEquals(List.of(), beyond.items());
        assertEquals(2, beyond.total());
        assertFalse(beyond.hasMore());
    }

    /**
     * Paging has to be available, not mandatory: for a handful of projects or versions it is pure
     * friction, so leaving the limit out returns everything in one go.
     */
    @Test
    void withoutALimitEverythingComesBackAtOnce() {
        Page<String> projects = catalog.allProjects();

        assertEquals(List.of("demo", "other"), projects.items());
        assertEquals(2, projects.total());
        assertEquals(projects.items().size(), projects.limit(),
                "the reported limit is what was actually returned");
        assertFalse(projects.hasMore());

        Page<String> versions = catalog.allVersions("demo");

        assertEquals(3, versions.items().size());
        assertFalse(versions.hasMore());
    }

    /** An offset still applies without a limit, so nothing is silently ignored. */
    @Test
    void anOffsetWithoutALimitReturnsTheRest() {
        Page<String> rest = catalog.versions("demo", 1, null);

        assertEquals(List.of("1.9.0", "1.10.0"), rest.items());
        assertEquals(3, rest.total());
        assertFalse(rest.hasMore());
    }

    @Test
    void anOversizedPageIsCappedAndNonsenseIsRejected() {
        assertEquals(IndexCatalog.MAX_PAGE_SIZE,
                catalog.projects(0, IndexCatalog.MAX_PAGE_SIZE * 10).limit(),
                "one call must not be able to pull the whole catalogue");

        assertThrows(IllegalArgumentException.class, () -> catalog.projects(-1, 10));
        assertThrows(IllegalArgumentException.class, () -> catalog.projects(0, 0));
    }

    /**
     * The projects have several chunks each, so a naive implementation that returned one entry per
     * document would show them repeatedly.
     */
    @Test
    void projectsWithManyChunksStillAppearOnce() {
        List<String> projects = allProjects();

        assertEquals(projects.size(), projects.stream().distinct().count());
    }

    /** The answer is a list of names — no ids, no paths, nothing internal. */
    @Test
    void theAnswerCarriesNothingInternal() {
        assertTrue(allProjects().stream()
                .noneMatch(project -> project.contains("/") || project.contains("#")),
                allProjects().toString());
    }

    @Test
    void theVersionsOfAProjectAreListedSemanticallySorted() {
        List<String> versions = catalog.allVersions("demo").items();

        assertEquals(List.of("1.0.0", "1.9.0", "1.10.0"), versions,
                "alphabetically 1.10.0 would land before 1.9.0");
    }

    /**
     * Documentation under doc/ applies to every version and carries no version field, so it must
     * not turn into a null entry — null is not a version, it means "applies to all of them".
     */
    @Test
    void crossVersionDocumentationProducesNoNullEntry() {
        List<String> versions = catalog.allVersions("demo").items();

        // contains(null) on the immutable list would throw; the page is built with List.copyOf,
        // which would already have refused a null on the way in
        assertFalse(versions.stream().anyMatch(Objects::isNull), versions.toString());
        assertEquals(3, versions.size(), "three version directories, doc/ is not one of them");
    }

    @Test
    void anUnknownProjectYieldsAnEmptyPageRatherThanAnError() {
        Page<String> versions = catalog.versions("does-not-exist", 0, 10);

        assertEquals(List.of(), versions.items());
        assertEquals(0, versions.total());
        assertFalse(versions.hasMore());
    }

    @Test
    void aBlankProjectIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> catalog.versions("  ", 0, 10));
        assertThrows(IllegalArgumentException.class, () -> catalog.versions(null, 0, 10));
    }

    /** The page has to be cut from the sorted list, not the other way round. */
    @Test
    void versionsArePagedAfterSortingNotBefore() {
        assertEquals(List.of("1.0.0"), catalog.versions("demo", 0, 1).items());
        assertEquals(List.of("1.9.0"), catalog.versions("demo", 1, 1).items());
        assertEquals(List.of("1.10.0"), catalog.versions("demo", 2, 1).items());

        Page<String> middle = catalog.versions("demo", 1, 1);
        assertEquals(3, middle.total());
        assertTrue(middle.hasMore());
    }

    /**
     * The list describes the index, not the directory. A project that exists on disk but has never
     * been indexed is nothing the agent could search, so offering it would send it into a search
     * that comes back empty by construction.
     */
    @Test
    void aProjectAppearsOnlyOnceItHasActuallyBeenIndexed() throws IOException {
        Path lateArrival = scanner.dataDir().resolve("late-arrival");
        Files.createDirectories(lateArrival.resolve("1.0.0"));
        Files.writeString(lateArrival.resolve("1.0.0/notes.md"), "# Late arrival\n\nNeu dazu.\n");

        try {
            assertFalse(allProjects().contains("late-arrival"),
                    "on disk, but no scan has seen it yet");

            indexTheFixture();

            assertTrue(allProjects().contains("late-arrival"), "now it is searchable");
        } finally {
            deleteRecursively(lateArrival);
            purgeChunksOf("late-arrival");
        }
    }

    private static void deleteRecursively(Path directory) throws IOException {
        try (Stream<Path> entries = Files.walk(directory)) {
            for (Path path : entries.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /** Keeps the test self-contained: the other tests must not see this project afterwards. */
    private void purgeChunksOf(String project) {
        List<String> ids;
        try (SearchSession session = searchMapping.createSession()) {
            ids = session.search(DocChunk.class)
                    .select(f -> f.id(String.class))
                    .where(f -> f.match().field("project").matching(project))
                    .fetchHits(500);
        }
        try (SearchSession session = searchMapping.createSession()) {
            ids.forEach(id -> session.indexingPlan().purge(DocChunk.class, id, null));
        }
    }
}
