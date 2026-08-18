package org.kvasir.embedding;

import org.jboss.logging.Logger;
import org.kvasir.entity.DocChunk;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Fails the startup if the configured embedding dimension, the dimension the model actually
 * produces and the vector field of the index do not all agree.
 * <p>
 * A mismatch here has no symptom of its own: nothing throws, the kNN part of the search simply
 * returns nonsense. Checking it once at startup — by really embedding a probe text rather than
 * trusting the configuration — turns a silent quality problem into a loud one.
 * <p>
 * Changing the model to one with a different dimension therefore requires a full reindex of all
 * projects and versions; it is not an incremental update.
 */
@ApplicationScoped
public class EmbeddingDimensionCheck {

    private static final Logger LOG = Logger.getLogger(EmbeddingDimensionCheck.class);

    private final EmbeddingConfig config;
    private final DocumentEmbedder embedder;

    public EmbeddingDimensionCheck(EmbeddingConfig config, DocumentEmbedder embedder) {
        this.config = config;
        this.embedder = embedder;
    }

    void verifyOnStartup(@Observes StartupEvent event) {
        if (config.dimension() != DocChunk.EMBEDDING_DIMENSION) {
            throw new IllegalStateException("""
                    docs.embedding.dimension is %d but the index vector field is %d. Both have to \
                    match; adjust the configuration or the entity and reindex everything."""
                    .formatted(config.dimension(), DocChunk.EMBEDDING_DIMENSION));
        }

        int actual = embedder.embedPassage("dimension probe").length;
        if (actual != config.dimension()) {
            throw new IllegalStateException("""
                    Embedding model '%s' produces %d dimensions but docs.embedding.dimension is \
                    %d. Fix the configuration and reindex all projects and versions - a model with \
                    a different dimension cannot be applied incrementally."""
                    .formatted(config.model(), actual, config.dimension()));
        }

        LOG.infof("Embedding model '%s' ready, %d dimensions", config.model(), actual);
    }
}
