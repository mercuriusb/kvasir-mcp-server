package org.kvasir.scan;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * State of one scan. Mutable and thread safe, because the scan updates its counters while the
 * status endpoint reads them.
 */
public class ScanStatus {

    public enum State {
        RUNNING,
        COMPLETED,
        FAILED
    }

    private final String scanId;
    private final String project;
    private final String version;
    private final Instant startedAt;

    private final AtomicInteger scannedFiles = new AtomicInteger();
    private final AtomicInteger indexedFiles = new AtomicInteger();
    private final AtomicInteger skippedFiles = new AtomicInteger();
    private final AtomicInteger failedFiles = new AtomicInteger();
    private final AtomicInteger indexedChunks = new AtomicInteger();
    private final AtomicInteger failedProjects = new AtomicInteger();
    private final AtomicInteger removedFiles = new AtomicInteger();
    private final AtomicInteger removedChunks = new AtomicInteger();

    private volatile State state = State.RUNNING;
    private volatile Instant finishedAt;
    private volatile String errorMessage;

    ScanStatus(String scanId, String project, String version) {
        this.scanId = scanId;
        this.project = project;
        this.version = version;
        this.startedAt = Instant.now();
    }

    public String getScanId() {
        return scanId;
    }

    /** {@code null} for a full scan of the whole data directory. */
    public String getProject() {
        return project;
    }

    /** {@code null} unless the scan was narrowed to one version. */
    public String getVersion() {
        return version;
    }

    public State getState() {
        return state;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getScannedFiles() {
        return scannedFiles.get();
    }

    public int getIndexedFiles() {
        return indexedFiles.get();
    }

    public int getSkippedFiles() {
        return skippedFiles.get();
    }

    public int getFailedFiles() {
        return failedFiles.get();
    }

    public int getIndexedChunks() {
        return indexedChunks.get();
    }

    /** Projects that could not be walked at all; the scan carried on with the others. */
    public int getFailedProjects() {
        return failedProjects.get();
    }

    /** Files that were in the index but no longer on disk, and were therefore removed. */
    public int getRemovedFiles() {
        return removedFiles.get();
    }

    public int getRemovedChunks() {
        return removedChunks.get();
    }

    void fileScanned() {
        scannedFiles.incrementAndGet();
    }

    void fileIndexed(int chunks) {
        indexedFiles.incrementAndGet();
        indexedChunks.addAndGet(chunks);
    }

    void fileSkipped() {
        skippedFiles.incrementAndGet();
    }

    void fileFailed() {
        failedFiles.incrementAndGet();
    }

    void projectFailed() {
        failedProjects.incrementAndGet();
    }

    void fileRemoved(int chunks) {
        removedFiles.incrementAndGet();
        removedChunks.addAndGet(chunks);
    }

    void completed() {
        this.finishedAt = Instant.now();
        this.state = State.COMPLETED;
    }

    void failed(String message) {
        this.errorMessage = message;
        this.finishedAt = Instant.now();
        this.state = State.FAILED;
    }
}
