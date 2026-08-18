package org.kvasir.admin;

import java.time.Instant;

import org.kvasir.scan.ScanStatus;

/**
 * What the status endpoint returns for one scan.
 *
 * @param scanId        id handed out when the scan was triggered
 * @param status        RUNNING, COMPLETED or FAILED
 * @param project       {@code null} for a scan of the whole data directory
 * @param version       {@code null} unless the scan was narrowed to one version
 * @param scannedFiles  files the scanner picked up
 * @param indexedFiles  files that were parsed and written
 * @param skippedFiles  files whose content hash was unchanged
 * @param failedFiles    files that could not be indexed; details are in the server log
 * @param failedProjects projects that could not be walked at all
 * @param removedFiles   files that were in the index but no longer on disk
 * @param removedChunks  chunks removed with them
 * @param indexedChunks chunks written across all indexed files
 * @param errorMessage  set when the scan itself failed, {@code null} otherwise
 */
public record ScanStatusResponse(
        String scanId,
        ScanStatus.State status,
        String project,
        String version,
        Instant startedAt,
        Instant finishedAt,
        int scannedFiles,
        int indexedFiles,
        int skippedFiles,
        int failedFiles,
        int failedProjects,
        int removedFiles,
        int removedChunks,
        int indexedChunks,
        String errorMessage) {

    public static ScanStatusResponse of(ScanStatus status) {
        return new ScanStatusResponse(status.getScanId(), status.getState(), status.getProject(),
                status.getVersion(), status.getStartedAt(), status.getFinishedAt(),
                status.getScannedFiles(), status.getIndexedFiles(), status.getSkippedFiles(),
                status.getFailedFiles(), status.getFailedProjects(), status.getRemovedFiles(),
                status.getRemovedChunks(), status.getIndexedChunks(),
                status.getErrorMessage());
    }
}
