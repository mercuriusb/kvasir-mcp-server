package org.kvasir.scan;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Keeps track of every scan, running or finished.
 * <p>
 * Several scans may run at the same time — one per project, say — so this holds a map rather than
 * "the last scan". State is in memory only; losing it on restart is acceptable because indexing is
 * idempotent: an unchanged file is skipped by its hash anyway.
 */
@ApplicationScoped
public class ScanRegistry {

    /** How many finished scans to keep before the oldest ones are dropped. */
    static final int RETAINED_SCANS = 50;

    private final ConcurrentHashMap<String, ScanStatus> scans = new ConcurrentHashMap<>();

    /**
     * Result of trying to start a scan.
     *
     * @param scan  the newly registered scan, or the running one that stands in its way
     * @param isNew whether the scan was actually started
     */
    public record Started(ScanStatus scan, boolean isNew) {
    }

    /**
     * Registers a scan unless one whose scope overlaps is already running.
     * <p>
     * Synchronized so that two requests arriving at the same moment cannot both pass the check.
     *
     * @param project {@code null} for a scan of the whole data directory
     * @param version {@code null} unless the scan is narrowed to one version
     */
    public synchronized Started start(String project, String version) {
        Optional<ScanStatus> conflict = runningScanOverlapping(project, version);
        if (conflict.isPresent()) {
            return new Started(conflict.get(), false);
        }
        ScanStatus status = new ScanStatus(UUID.randomUUID().toString(), project, version);
        scans.put(status.getScanId(), status);
        evictOldestFinished();
        return new Started(status, true);
    }

    /**
     * The running scan whose scope intersects the requested one, if any.
     * <p>
     * Two scans may run at the same time exactly when they cannot touch the same files. A full scan
     * therefore excludes everything, a project scan excludes that project, and two versions of one
     * project are independent — they write disjoint documents and disjoint hash entries.
     */
    public synchronized Optional<ScanStatus> runningScanOverlapping(String project, String version) {
        return scans.values().stream()
                .filter(scan -> scan.getState() == ScanStatus.State.RUNNING)
                .filter(scan -> overlaps(scan.getProject(), scan.getVersion(), project, version))
                .findFirst();
    }

    static boolean overlaps(String projectA, String versionA, String projectB, String versionB) {
        if (projectA == null || projectB == null) {
            return true; // a full scan covers every project
        }
        if (!projectA.equals(projectB)) {
            return false;
        }
        return versionA == null || versionB == null || versionA.equals(versionB);
    }

    public Optional<ScanStatus> find(String scanId) {
        return Optional.ofNullable(scans.get(scanId));
    }

    /** All known scans, newest first. */
    public List<ScanStatus> all() {
        return scans.values().stream()
                .sorted(Comparator.comparing(ScanStatus::getStartedAt).reversed())
                .toList();
    }

    private void evictOldestFinished() {
        if (scans.size() <= RETAINED_SCANS) {
            return;
        }
        scans.values().stream()
                .filter(status -> status.getState() != ScanStatus.State.RUNNING)
                .sorted(Comparator.comparing(ScanStatus::getStartedAt))
                .limit(scans.size() - RETAINED_SCANS)
                .forEach(status -> scans.remove(status.getScanId()));
    }
}
