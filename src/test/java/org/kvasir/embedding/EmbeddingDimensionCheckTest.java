package org.kvasir.embedding;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.kvasir.entity.DocChunk;

/**
 * A wrong embedding dimension has no symptom of its own — the search just returns nonsense. These
 * tests pin down that it is caught at startup and reported clearly instead.
 */
class EmbeddingDimensionCheckTest {

    @Test
    void matchingDimensionsPass() {
        assertDoesNotThrow(() -> check(DocChunk.EMBEDDING_DIMENSION, DocChunk.EMBEDDING_DIMENSION)
                .verifyOnStartup(null));
    }

    @Test
    void aConfiguredDimensionOtherThanTheIndexIsRejected() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> check(512, 512).verifyOnStartup(null));

        assertAll(
                () -> assertTrue(failure.getMessage().contains("docs.embedding.dimension is 512")),
                () -> assertTrue(failure.getMessage()
                        .contains("index vector field is " + DocChunk.EMBEDDING_DIMENSION)),
                () -> assertTrue(failure.getMessage().contains("reindex")));
    }

    @Test
    void aModelThatProducesADifferentDimensionThanConfiguredIsRejected() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> check(DocChunk.EMBEDDING_DIMENSION, 768).verifyOnStartup(null));

        assertAll(
                () -> assertTrue(failure.getMessage().contains("produces 768 dimensions")),
                () -> assertTrue(failure.getMessage()
                        .contains("docs.embedding.dimension is " + DocChunk.EMBEDDING_DIMENSION)),
                () -> assertTrue(failure.getMessage().contains("reindex")));
    }

    private static EmbeddingDimensionCheck check(int configuredDimension, int modelDimension) {
        StubEmbeddingConfig config = StubEmbeddingConfig.withDefaults()
                .withDimension(configuredDimension);
        DocumentEmbedder embedder = new DocumentEmbedder(new RecordingEmbeddingModel(modelDimension),
                config);
        return new EmbeddingDimensionCheck(config, embedder);
    }
}
