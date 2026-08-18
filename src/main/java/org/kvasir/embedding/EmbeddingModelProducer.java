package org.kvasir.embedding;

import java.nio.file.Files;
import java.nio.file.Path;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.OnnxEmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallenq.BgeSmallEnQuantizedEmbeddingModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Picks the in-process embedding model from {@code docs.embedding.model}.
 * <p>
 * All four options are on the classpath, so switching between them is a configuration change and
 * never a code change. Everything but {@code custom} ships its model inside its jar; {@code custom}
 * — the default, {@code multilingual-e5-small} — reads the ONNX file and tokenizer from disk.
 */
@ApplicationScoped
public class EmbeddingModelProducer {

    @Produces
    @Singleton
    public EmbeddingModel embeddingModel(EmbeddingConfig config) {
        return switch (config.model()) {
            case MINILM -> new AllMiniLmL6V2EmbeddingModel();
            case MINILM_QUANTIZED -> new AllMiniLmL6V2QuantizedEmbeddingModel();
            case BGE_SMALL -> new BgeSmallEnQuantizedEmbeddingModel();
            case CUSTOM -> customModel(config);
        };
    }

    private static EmbeddingModel customModel(EmbeddingConfig config) {
        Path onnxPath = required(config.onnxPath().orElse(null), "docs.embedding.onnx-path");
        Path tokenizerPath = required(config.tokenizerPath().orElse(null),
                "docs.embedding.tokenizer-path");
        return new OnnxEmbeddingModel(onnxPath, tokenizerPath, config.poolingMode());
    }

    /**
     * Fails with a message naming the property and the download script, rather than letting the
     * ONNX runtime report a missing file from somewhere deep in its own stack.
     */
    private static Path required(Path path, String property) {
        if (path == null) {
            throw new IllegalStateException(
                    "%s must be set when docs.embedding.model=custom".formatted(property));
        }
        if (!Files.isReadable(path)) {
            throw new IllegalStateException(
                    "%s points at '%s', which does not exist or is not readable. Run scripts/download-embedding-model.sh to fetch the default model."
                            .formatted(property, path));
        }
        return path;
    }
}
