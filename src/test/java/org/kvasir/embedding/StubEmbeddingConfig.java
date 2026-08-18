package org.kvasir.embedding;

import java.nio.file.Path;
import java.util.Optional;

import dev.langchain4j.model.embedding.onnx.PoolingMode;

/**
 * Hand-built {@link EmbeddingConfig} for unit tests, so they do not need a running Quarkus.
 * Defaults mirror the real ones.
 */
record StubEmbeddingConfig(ModelChoice model, int dimension, Optional<String> queryPrefix,
        Optional<String> passagePrefix) implements EmbeddingConfig {

    static StubEmbeddingConfig withDefaults() {
        return new StubEmbeddingConfig(ModelChoice.CUSTOM, 384, Optional.of("query: "),
                Optional.of("passage: "));
    }

    StubEmbeddingConfig withPrefixes(String query, String passage) {
        return new StubEmbeddingConfig(model, dimension, Optional.of(query), Optional.of(passage));
    }

    StubEmbeddingConfig withDimension(int newDimension) {
        return new StubEmbeddingConfig(model, newDimension, queryPrefix, passagePrefix);
    }

    @Override
    public Optional<Path> onnxPath() {
        return Optional.empty();
    }

    @Override
    public Optional<Path> tokenizerPath() {
        return Optional.empty();
    }

    @Override
    public PoolingMode poolingMode() {
        return PoolingMode.MEAN;
    }
}
