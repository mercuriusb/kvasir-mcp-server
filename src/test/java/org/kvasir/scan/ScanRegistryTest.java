package org.kvasir.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.kvasir.scan.ScanRegistry.Started;

/**
 * Two scans may run side by side exactly when they cannot touch the same files. These tests pin
 * down where that line runs and that it holds when requests arrive simultaneously.
 */
class ScanRegistryTest {

    private final ScanRegistry registry = new ScanRegistry();

    @Test
    void aFullScanBlocksEveryOtherScan() {
        assertTrue(registry.start(null, null).isNew());

        assertFalse(registry.start(null, null).isNew(), "a second full scan");
        assertFalse(registry.start("demo", null).isNew(), "a project scan");
        assertFalse(registry.start("demo", "1.0.0").isNew(), "a project version scan");
    }

    @Test
    void aProjectScanBlocksThatProjectOnly() {
        assertTrue(registry.start("demo", null).isNew());

        assertFalse(registry.start("demo", null).isNew(), "the same project again");
        assertFalse(registry.start("demo", "1.0.0").isNew(), "a version of that project");
        assertFalse(registry.start(null, null).isNew(), "a full scan covers it too");

        assertTrue(registry.start("other", null).isNew(), "a different project is unaffected");
    }

    @Test
    void theSameProjectVersionCannotBeScannedTwiceAtOnce() {
        assertTrue(registry.start("demo", "1.0.0").isNew());

        assertFalse(registry.start("demo", "1.0.0").isNew());
        assertFalse(registry.start("demo", null).isNew(), "the whole project includes this version");
    }

    /** Different versions write disjoint documents, so blocking them would be needless. */
    @Test
    void twoVersionsOfOneProjectRunSideBySide() {
        assertTrue(registry.start("demo", "1.0.0").isNew());

        assertTrue(registry.start("demo", "2.0.0").isNew());
    }

    @Test
    void aRejectedRequestPointsAtTheScanThatIsInTheWay() {
        ScanStatus running = registry.start("demo", null).scan();

        Started rejected = registry.start("demo", "1.0.0");

        assertFalse(rejected.isNew());
        assertSame(running, rejected.scan(), "the caller has to learn which scan to follow");
    }

    @Test
    void aFinishedScanNoLongerBlocksAnything() {
        ScanStatus first = registry.start("demo", null).scan();
        assertFalse(registry.start("demo", null).isNew());

        first.completed();

        Started second = registry.start("demo", null);
        assertTrue(second.isNew());
        assertNotEquals(first.getScanId(), second.scan().getScanId());
    }

    @Test
    void aFailedScanAlsoStopsBlocking() {
        registry.start("demo", null).scan().failed("something broke");

        assertTrue(registry.start("demo", null).isNew());
    }

    /**
     * The check and the registration have to happen together — otherwise two requests arriving at
     * the same moment would both find nothing running and both start.
     */
    @Test
    void simultaneousRequestsProduceExactlyOneScan() throws InterruptedException {
        int threads = 16;
        CountDownLatch startTogether = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<Started> results = new ConcurrentLinkedQueue<>();

        List<Thread> workers = IntStream.range(0, threads)
                .mapToObj(i -> Thread.ofVirtual().unstarted(() -> {
                    try {
                        startTogether.await();
                        results.add(registry.start("demo", null));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                }))
                .toList();
        workers.forEach(Thread::start);
        startTogether.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));

        assertEquals(1, results.stream().filter(Started::isNew).count(),
                "exactly one request may win");
        assertEquals(1, Set.copyOf(results.stream().map(r -> r.scan().getScanId()).toList()).size(),
                "every caller has to be pointed at the same scan");
    }
}
