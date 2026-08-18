package org.kvasir.embedding;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Checks that the query and passage prefixes are applied inside the embedder — the acceptance
 * criterion is that they are added before the text reaches the model, not in the parsers.
 */
class DocumentEmbedderTest {

    private final RecordingEmbeddingModel model = new RecordingEmbeddingModel(384);

    @Test
    void aSearchQueryGetsTheQueryPrefix() {
        embedder(StubEmbeddingConfig.withDefaults())
                .embedQuery("jackson polymorphism deserialization");

        assertEquals(List.of("query: jackson polymorphism deserialization"), model.embeddedTexts());
    }

    @Test
    void anIndexedChunkGetsThePassagePrefix() {
        embedder(StubEmbeddingConfig.withDefaults()).embedPassage("Some chunk of documentation.");

        assertEquals(List.of("passage: Some chunk of documentation."), model.embeddedTexts());
    }

    @Test
    void everyChunkOfABatchGetsThePassagePrefix() {
        embedder(StubEmbeddingConfig.withDefaults()).embedPassages(List.of("first", "second"));

        assertEquals(List.of("passage: first", "passage: second"), model.embeddedTexts());
    }

    /** Switching to a model that does not want prefixes must leave the text untouched. */
    @Test
    void emptyPrefixesLeaveTheTextAlone() {
        DocumentEmbedder embedder = embedder(StubEmbeddingConfig.withDefaults().withPrefixes("", ""));

        embedder.embedQuery("a query");
        embedder.embedPassage("a passage");

        assertEquals(List.of("a query", "a passage"), model.embeddedTexts());
    }

    @Test
    void theEmbeddingHasTheDimensionOfTheModel() {
        assertEquals(384, embedder(StubEmbeddingConfig.withDefaults()).embedQuery("x").length);
    }

    private DocumentEmbedder embedder(StubEmbeddingConfig config) {
        return new DocumentEmbedder(model, config);
    }
}
