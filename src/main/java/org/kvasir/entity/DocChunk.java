package org.kvasir.entity;

import org.hibernate.search.engine.backend.types.Aggregable;
import org.hibernate.search.engine.backend.types.VectorSimilarity;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.DocumentId;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.FullTextField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.Indexed;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.KeywordField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.SearchEntity;
import org.hibernate.search.mapper.pojo.bridge.mapping.annotation.ValueBridgeRef;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.VectorField;

/**
 * One indexed chunk of documentation, Javadoc or Java source code.
 * <p>
 * All chunks of all projects and versions live in this single index; projects and versions are
 * kept apart purely by filtering on the keyword fields {@link #project} and {@link #version}, not
 * by using separate indexes.
 * <p>
 * The three mandatory filter fields are aggregable, so that the distinct values occurring in the
 * index can be asked for directly — which projects exist, which versions a project has, how the
 * chunks split across types. Aggregability is a mapping property: turning it on later would mean a
 * full reindex, so the three fields that plausibly need it carry it from the start.
 */
@SearchEntity
@Indexed(index = DocChunk.INDEX_NAME)
public class DocChunk {

    /**
     * Explicit index name. The OpenSearch cluster is shared with other applications, so the index
     * carries the application prefix instead of the default name derived from the class.
     */
    public static final String INDEX_NAME = "kvasir-doc-chunk";

    /**
     * Dimension of the embedding vector. Must match {@code docs.embedding.dimension}; changing the
     * embedding model to one with a different dimension requires a full reindex of all projects
     * and versions rather than an incremental update.
     */
    public static final int EMBEDDING_DIMENSION = 384;

    @DocumentId
    private String id;

    /** The project this chunk belongs to, e.g. {@code jackson}. */
    @KeywordField(aggregable = Aggregable.YES)
    private String project;

    /**
     * The version this chunk belongs to, or {@code null} for cross-version chunks that come from
     * {@code data/<project>/doc/} and apply to the whole project.
     */
    @KeywordField(aggregable = Aggregable.YES)
    private String version;

    /** What kind of content this chunk holds; indexed under its wire name. */
    @KeywordField(valueBridge = @ValueBridgeRef(type = ChunkTypeBridge.class),
            aggregable = Aggregable.YES)
    private ChunkType type;

    /** File or class path the chunk was extracted from. */
    @KeywordField
    private String source;

    /** Fully qualified class name, for chunks that belong to a Java type. */
    @KeywordField
    private String fullyQualifiedClassName;

    /** Value of the Javadoc {@code @since} tag, when present. */
    @KeywordField
    private String since;

    /** Heading (documentation) or signature (Java source). */
    @FullTextField
    private String heading;

    /** The chunk text itself; carries the BM25 part of the hybrid search. */
    @FullTextField
    private String text;

    /** Embedding of {@link #text}; carries the kNN part of the hybrid search. */
    @VectorField(dimension = EMBEDDING_DIMENSION, vectorSimilarity = VectorSimilarity.COSINE)
    private float[] embedding;

    public DocChunk() {
    }

    public DocChunk(String id, String project, String version, ChunkType type, String source,
            String heading, String text) {
        this.id = id;
        this.project = project;
        this.version = version;
        this.type = type;
        this.source = source;
        this.heading = heading;
        this.text = text;
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

    public ChunkType getType() {
        return type;
    }

    public void setType(ChunkType type) {
        this.type = type;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getFullyQualifiedClassName() {
        return fullyQualifiedClassName;
    }

    public void setFullyQualifiedClassName(String fullyQualifiedClassName) {
        this.fullyQualifiedClassName = fullyQualifiedClassName;
    }

    public String getSince() {
        return since;
    }

    public void setSince(String since) {
        this.since = since;
    }

    public String getHeading() {
        return heading;
    }

    public void setHeading(String heading) {
        this.heading = heading;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public float[] getEmbedding() {
        return embedding;
    }

    public void setEmbedding(float[] embedding) {
        this.embedding = embedding;
    }
}
