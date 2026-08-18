package org.kvasir.embedding;

import java.util.List;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Thin wrapper around the {@link EmbeddingModel} that applies the prefix belonging to the calling
 * context: a search query is embedded differently from a chunk that goes into the index.
 * <p>
 * E5 models — the default — expect {@code "query: "} in front of a search query and
 * {@code "passage: "} in front of an indexed chunk; without them embedding quality drops
 * noticeably. Keeping this here rather than in the parsers means the chunking logic never has to
 * know which model is configured, and callers cannot forget the prefix: there is no method on this
 * class that embeds unprefixed text.
 */
@ApplicationScoped
public class DocumentEmbedder {

    private final EmbeddingModel model;
    private final String queryPrefix;
    private final String passagePrefix;

    public DocumentEmbedder(EmbeddingModel model, EmbeddingConfig config) {
        this.model = model;
        this.queryPrefix = config.queryPrefix().orElse("");
        this.passagePrefix = config.passagePrefix().orElse("");
    }

    /** Embeds a search query, for the kNN side of {@code search_docs}. */
    public float[] embedQuery(String query) {
        return model.embed(queryPrefix + query).content().vector();
    }

    /** Embeds a chunk that is about to be indexed. */
    public float[] embedPassage(String text) {
        return model.embed(passagePrefix + text).content().vector();
    }

    /** Embeds several chunks in one call; ingestion works in batches. */
    public List<float[]> embedPassages(List<String> texts) {
        List<TextSegment> segments = texts.stream()
                .map(text -> TextSegment.from(passagePrefix + text))
                .toList();
        return model.embedAll(segments).content().stream().map(Embedding::vector).toList();
    }

    /** The dimension of the vectors this embedder produces. */
    public int dimension() {
        return model.dimension();
    }
}
