package org.kvasir.scan;

import java.util.Map;
import java.util.Set;

import org.kvasir.entity.IndexSchema;
import org.kvasir.entity.IndexedFile;
import org.kvasir.parser.SourceLocation;

/**
 * The content hashes already recorded for one scan scope, read in one go before the scan starts.
 * <p>
 * Asking the index per file would mean one round trip per file — fine for a handful, latency bound
 * for ten thousand, and all of it before any real work happens. One query up front turns that into
 * a lookup in memory.
 * <p>
 * A snapshot on purpose: every file is visited once per scan, so entries written while the scan
 * runs cannot be needed again by it.
 */
public record KnownFileHashes(Map<String, Fingerprint> byFileId) {

    public static final KnownFileHashes NONE = new KnownFileHashes(Map.of());

    /**
     * What was recorded for a file: which file it was, what it contained, and under which schema
     * that content was turned into chunks.
     * <p>
     * The {@code file} is carried along because the id is hashed and cannot be taken apart again.
     * Reading it here, in the query that runs once per project anyway, keeps the deletion of a
     * vanished file from needing a lookup of its own.
     */
    public record Fingerprint(IndexedFile.FileRef file, String contentHash, int schemaVersion) {
    }

    public KnownFileHashes {
        byFileId = Map.copyOf(byFileId);
    }

    /**
     * Whether this file can be skipped: same content <em>and</em> indexed under the schema in force
     * today. Equal content alone is not enough — if the chunking or the mapping changed since, what
     * is in the index is no longer what would be written now.
     */
    public boolean isUnchanged(SourceLocation location, String contentHash) {
        Fingerprint known = byFileId.get(
                IndexedFile.idOf(location.project(), location.version(), location.path()));
        return known != null
                && known.contentHash().equals(contentHash)
                && known.schemaVersion() == IndexSchema.VERSION;
    }

    /** Ids of every file recorded for this scope, whether or not the scan has reached it. */
    public Set<String> fileIds() {
        return byFileId.keySet();
    }

    /** What was recorded under this id, or {@code null} if nothing was. */
    public Fingerprint get(String fileId) {
        return byFileId.get(fileId);
    }

    public int size() {
        return byFileId.size();
    }
}
