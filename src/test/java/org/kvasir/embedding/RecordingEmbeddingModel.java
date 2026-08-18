package org.kvasir.embedding;

import java.util.ArrayList;
import java.util.List;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

/**
 * Records the text that actually reaches the model, which is the only way to tell whether the
 * prefix was applied before embedding rather than somewhere else.
 */
class RecordingEmbeddingModel implements EmbeddingModel {

    private final List<String> embeddedTexts = new ArrayList<>();
    private final int dimension;

    RecordingEmbeddingModel(int dimension) {
        this.dimension = dimension;
    }

    List<String> embeddedTexts() {
        return embeddedTexts;
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
        segments.forEach(segment -> embeddedTexts.add(segment.text()));
        return Response.from(segments.stream()
                .map(segment -> Embedding.from(new float[dimension]))
                .toList());
    }
}
