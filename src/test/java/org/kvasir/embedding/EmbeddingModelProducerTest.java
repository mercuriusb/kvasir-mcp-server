package org.kvasir.embedding;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.kvasir.embedding.EmbeddingConfig.ModelChoice;

import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallenq.BgeSmallEnQuantizedEmbeddingModel;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Every value of {@code docs.embedding.model} has to select a different implementation, and the
 * custom option has to complain understandably when its files are not where it was told.
 * <p>
 * Runs as a {@code @QuarkusTest} even though it needs nothing from the container: the in-process
 * models load a native tokenizer library, and that can only be loaded by one class loader per JVM.
 * Sharing the class loader with the other tests keeps it at one.
 */
@QuarkusTest
class EmbeddingModelProducerTest {

    private final EmbeddingModelProducer producer = new EmbeddingModelProducer();

    @Test
    void everyBundledChoiceSelectsItsOwnImplementation() {
        assertInstanceOf(AllMiniLmL6V2EmbeddingModel.class, produce(ModelChoice.MINILM));
        assertInstanceOf(AllMiniLmL6V2QuantizedEmbeddingModel.class,
                produce(ModelChoice.MINILM_QUANTIZED));
        assertInstanceOf(BgeSmallEnQuantizedEmbeddingModel.class, produce(ModelChoice.BGE_SMALL));
    }

    @Test
    void theCustomChoiceWithoutAPathNamesTheMissingProperty() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> produce(ModelChoice.CUSTOM));

        assertTrue(failure.getMessage().contains("docs.embedding.onnx-path"), failure.getMessage());
    }

    @Test
    void theCustomChoiceWithAMissingFilePointsAtTheDownloadScript() {
        Path absent = Path.of("models/does-not-exist/model.onnx");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> producer.embeddingModel(new CustomPathConfig(absent, absent)));

        assertTrue(failure.getMessage().contains("scripts/download-embedding-model.sh"),
                failure.getMessage());
    }

    private Object produce(ModelChoice choice) {
        return producer.embeddingModel(new StubEmbeddingConfig(choice, 384, Optional.of("query: "),
                Optional.of("passage: ")));
    }

    /** Custom choice with paths that are set but point nowhere. */
    private record CustomPathConfig(Path onnx, Path tokenizer) implements EmbeddingConfig {

        @Override
        public ModelChoice model() {
            return ModelChoice.CUSTOM;
        }

        @Override
        public int dimension() {
            return 384;
        }

        @Override
        public Optional<Path> onnxPath() {
            return Optional.of(onnx);
        }

        @Override
        public Optional<Path> tokenizerPath() {
            return Optional.of(tokenizer);
        }

        @Override
        public dev.langchain4j.model.embedding.onnx.PoolingMode poolingMode() {
            return dev.langchain4j.model.embedding.onnx.PoolingMode.MEAN;
        }

        @Override
        public Optional<String> queryPrefix() {
            return Optional.of("query: ");
        }

        @Override
        public Optional<String> passagePrefix() {
            return Optional.of("passage: ");
        }
    }
}
