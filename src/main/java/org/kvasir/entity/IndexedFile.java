package org.kvasir.entity;

import org.hibernate.search.engine.backend.types.Aggregable;
import org.hibernate.search.engine.backend.types.Projectable;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.DocumentId;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.GenericField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.Indexed;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.KeywordField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.SearchEntity;

/**
 * Remembers that a file has been indexed and what its content looked like at the time.
 * <p>
 * A scan compares the current content hash against the stored one and skips the file when they
 * match. Without that, every scan would re-embed everything, which is by far the most expensive
 * part of ingestion.
 * <p>
 * This lives in the search index rather than in a database on purpose: a separate store for raw
 * data is an explicit non-goal, and the index already survives restarts.
 */
@SearchEntity
@Indexed(index = IndexedFile.INDEX_NAME)
public class IndexedFile {

    public static final String INDEX_NAME = "kvasir-indexed-file";

    @DocumentId
    private String id;

    /**
     * Aggregable so the consistency check can ask which projects have fingerprints at all.
     * Projectable because the id no longer carries the parts — see {@link #idOf}.
     */
    @KeywordField(aggregable = Aggregable.YES, projectable = Projectable.YES)
    private String project;

    @KeywordField(projectable = Projectable.YES)
    private String version;

    /** Path of the file relative to the project directory. */
    @KeywordField(projectable = Projectable.YES)
    private String path;

    /** SHA-256 of the file content, hex encoded. */
    @KeywordField(projectable = Projectable.YES)
    private String contentHash;

    /**
     * The {@link IndexSchema#VERSION} this file was indexed under.
     * <p>
     * Part of the skip decision alongside the hash: unchanged content indexed under an older schema
     * still has to be rebuilt, because what was written from it is no longer what would be written
     * today.
     */
    @GenericField(projectable = Projectable.YES)
    private int schemaVersion;

    public IndexedFile() {
    }

    public IndexedFile(String project, String version, String path, String contentHash) {
        this.id = idOf(project, version, path);
        this.project = project;
        this.version = version;
        this.path = path;
        this.contentHash = contentHash;
        this.schemaVersion = IndexSchema.VERSION;
    }

    /** The three values an id is made of. */
    public record FileRef(String project, String version, String path) {
    }

    /**
     * Same shape as the chunk ids, so a file and its chunks are recognisably related.
     * <p>
     * The path is hashed and therefore cannot be read back out of an id. Whoever needs the parts
     * projects them off the document instead — {@link ChunkIngestor#knownHashes} does, which is why
     * project, version and path are all projectable above.
     */
    public static String idOf(String project, String version, String path) {
        return IdUtils.fileId(project, version, path);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getProject() {
        return project;
    }

    public void setProject(String project) {
        this.project = project;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }
}
