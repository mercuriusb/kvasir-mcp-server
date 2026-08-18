package org.kvasir.parser;

import java.util.ArrayList;
import java.util.List;

import org.kvasir.entity.ChunkType;
import org.kvasir.entity.DocChunk;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Chunks plain text into fixed windows of words.
 * <p>
 * A {@code .txt} file carries no structure to chunk along, so there is nothing to be clever about:
 * the text is split on whitespace and regrouped into windows of {@value #WORDS_PER_WINDOW} words.
 * The windows do not overlap — a chunk that appears twice would also be found twice and compete
 * with itself in the ranking.
 * <p>
 * These chunks have no heading, because there is none to be had.
 */
@ApplicationScoped
public class TextParser extends TextDocumentParser {

    /**
     * Roughly the size at which a chunk still fits comfortably into the embedding model's context
     * while staying specific enough to rank meaningfully.
     */
    static final int WORDS_PER_WINDOW = 300;

    @Override
    public boolean supports(String fileName) {
        return fileName.endsWith(".txt");
    }

    @Override
    public List<DocChunk> parse(SourceLocation location, String content) {
        String[] words = content.strip().split("\\s+");
        if (words.length == 1 && words[0].isEmpty()) {
            return List.of();
        }

        List<DocChunk> chunks = new ArrayList<>();
        for (int start = 0; start < words.length; start += WORDS_PER_WINDOW) {
            int end = Math.min(start + WORDS_PER_WINDOW, words.length);
            String window = String.join(" ", List.of(words).subList(start, end));
            chunks.add(new DocChunk(location.chunkId(chunks.size()), location.project(),
                    location.version(), ChunkType.TXT, location.path(), null, window));
        }
        return chunks;
    }
}
