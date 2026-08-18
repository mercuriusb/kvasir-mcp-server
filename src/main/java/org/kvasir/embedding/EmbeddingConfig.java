package org.kvasir.embedding;

import java.nio.file.Path;
import java.util.Optional;

import dev.langchain4j.model.embedding.onnx.PoolingMode;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/** Everything about which embedding model is used and how it is called. */
@ConfigMapping(prefix = "docs.embedding")
public interface EmbeddingConfig {

    /** Which in-process model to use. */
    @WithDefault("custom")
    ModelChoice model();

    /**
     * Dimension the model is expected to produce. Checked against the model's actual output at
     * startup and against the vector field of the index, because a mismatch would not fail loudly
     * on its own — it would just produce wrong hits.
     */
    @WithDefault("384")
    int dimension();

    /** Path to the ONNX file; required for {@link ModelChoice#CUSTOM}. */
    Optional<Path> onnxPath();

    /** Path to the tokenizer; required for {@link ModelChoice#CUSTOM}. */
    Optional<Path> tokenizerPath();

    /** Pooling mode of the custom model. */
    @WithDefault("MEAN")
    PoolingMode poolingMode();

    /**
     * Prefix put in front of a search query before embedding it. E5 models expect
     * {@code "query: "}; without it embedding quality drops noticeably. Configurable because the
     * prefixes are model specific — for the MiniLM and BGE options they are noise and should be
     * set to an empty value.
     * <p>
     * Declared {@code Optional} for exactly that reason: SmallRye reads an empty property value as
     * a missing one, so a plain {@code String} could never be emptied through configuration.
     */
    @WithDefault("query: ")
    Optional<String> queryPrefix();

    /** Prefix put in front of a chunk before indexing it; see {@link #queryPrefix()}. */
    @WithDefault("passage: ")
    Optional<String> passagePrefix();

    /** The available in-process models, matching the values of {@code docs.embedding.model}. */
    enum ModelChoice {
        MINILM,
        MINILM_QUANTIZED,
        BGE_SMALL,
        CUSTOM
    }
}
