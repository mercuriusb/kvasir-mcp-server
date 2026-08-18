package org.kvasir.embedding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;

/**
 * Proves the acceptance criterion end to end: changing {@code docs.embedding.model} alone swaps the
 * model, with no code touched and the application still starting.
 */
@QuarkusTest
@TestProfile(SwitchedEmbeddingModelTest.MiniLmQuantizedProfile.class)
class SwitchedEmbeddingModelTest {

    /**
     * Switches to the quantized MiniLM and empties the prefixes, which are E5 specific. MiniLM also
     * produces 384 dimensions, so the index mapping stays valid.
     */
    public static class MiniLmQuantizedProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "docs.embedding.model", "minilm-quantized",
                    "docs.embedding.query-prefix", "",
                    "docs.embedding.passage-prefix", "");
        }
    }

    @Inject
    EmbeddingModel model;

    @Inject
    DocumentEmbedder embedder;

    @Inject
    EmbeddingConfig config;

    @Test
    void configurationAloneSwitchesTheImplementation() {
        assertEquals(EmbeddingConfig.ModelChoice.MINILM_QUANTIZED, config.model());
        assertInstanceOf(AllMiniLmL6V2QuantizedEmbeddingModel.class, model);
    }

    @Test
    void theSwitchedModelStillMatchesTheIndexDimension() {
        assertEquals(384, embedder.embedPassage("some text").length);
    }
}
