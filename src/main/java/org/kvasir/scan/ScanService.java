package org.kvasir.scan;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Starts scans and lets them run in the background.
 * <p>
 * A scan reads a whole directory tree, parses it and embeds every chunk, which takes far too long
 * to hold an HTTP request open. The caller gets a scan id straight away and polls the status
 * endpoint. Several scans may be in flight at once — one per project, for instance.
 */
@ApplicationScoped
public class ScanService {

    private static final Logger LOG = Logger.getLogger(ScanService.class);

    private final DataScanner scanner;
    private final ScanRegistry registry;
    private final ManagedExecutor executor;

    public ScanService(DataScanner scanner, ScanRegistry registry, ManagedExecutor executor) {
        this.scanner = scanner;
        this.registry = registry;
        this.executor = executor;
    }

    /**
     * Starts a scan in the background, unless one whose scope overlaps is already running.
     *
     * @return the new scan — already registered and therefore immediately visible to the status
     *         endpoint — or, with {@code isNew() == false}, the running scan that stands in its way
     */
    /** Convenience for the ordinary case: honour the recorded fingerprints. */
    public ScanRegistry.Started startScan(String project, String version) {
        return startScan(project, version, false);
    }

    public ScanRegistry.Started startScan(String project, String version, boolean force) {
        ScanRegistry.Started started = registry.start(project, version);
        if (!started.isNew()) {
            LOG.infof("Rejected scan (project=%s, version=%s): %s is already running",
                    project, version, started.scan().getScanId());
            return started;
        }
        executor.execute(() -> run(started.scan(), project, version, force));
        return started;
    }

    private void run(ScanStatus status, String project, String version, boolean force) {
        LOG.infof("Scan %s started (project=%s, version=%s, force=%s)", status.getScanId(), project,
                version, force);
        try {
            scanner.scan(status, project, version, force);
            status.completed();
            LOG.infof("Scan %s completed: %d scanned, %d indexed, %d skipped, %d failed",
                    status.getScanId(), status.getScannedFiles(), status.getIndexedFiles(),
                    status.getSkippedFiles(), status.getFailedFiles());
        } catch (Exception e) {
            LOG.errorf(e, "Scan %s failed", status.getScanId());
            status.failed(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }
}
