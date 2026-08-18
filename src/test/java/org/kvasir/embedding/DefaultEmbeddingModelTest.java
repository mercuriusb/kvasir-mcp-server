package org.kvasir.embedding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.kvasir.entity.DocChunk;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.OnnxEmbeddingModel;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * The default configuration has to end up at multilingual-e5-small through the generic custom/ONNX
 * option, and text put in has to come back out as a vector of the dimension the index expects.
 */
@QuarkusTest
class DefaultEmbeddingModelTest {

    @Inject
    EmbeddingModel model;

    @Inject
    DocumentEmbedder embedder;

    @Inject
    EmbeddingConfig config;

    @Test
    void theDefaultIsTheCustomOnnxModel() {
        assertEquals(EmbeddingConfig.ModelChoice.CUSTOM, config.model());
        assertInstanceOf(OnnxEmbeddingModel.class, model);
        assertTrue(config.onnxPath().orElseThrow().toString().contains("multilingual-e5-small"));
    }

    @Test
    void textGoesInAndAVectorOfTheExpectedDimensionComesOut() {
        float[] vector = embedder.embedPassage("Jackson kann polymorphe Typen deserialisieren.");

        assertEquals(384, vector.length);
        assertEquals(DocChunk.EMBEDDING_DIMENSION, vector.length);
    }

    /**
     * Guards the prefixes: with E5, embedding the same text as a query and as a passage has to give
     * different vectors, otherwise the prefix never reached the model.
     */
    @Test
    void queryAndPassageEmbeddingsDifferForTheSameText() {
        String text = "polymorphic deserialization";

        assertNotEquals(java.util.Arrays.toString(embedder.embedQuery(text)),
                java.util.Arrays.toString(embedder.embedPassage(text)));
    }

    /** Semantically close German and English text has to land closer than unrelated text. */
    @Test
    void theModelIsActuallyMultilingual() {
        float[] german = embedder.embedPassage("Wie deserialisiere ich polymorphe Typen?");
        float[] english = embedder.embedPassage("How do I deserialize polymorphic types?");
        float[] unrelated = embedder.embedPassage("Das Wetter in Hamburg ist heute regnerisch.");

        assertTrue(cosine(german, english) > cosine(german, unrelated),
                "translation should embed closer than an unrelated sentence");
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
