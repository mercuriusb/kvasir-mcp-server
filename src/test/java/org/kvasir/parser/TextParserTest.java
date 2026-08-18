package org.kvasir.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.kvasir.entity.ChunkType;
import org.kvasir.entity.DocChunk;

class TextParserTest {

    private final TextParser parser = new TextParser();

    private List<DocChunk> parseSample() {
        return parser.parse(Samples.at("release-notes.txt"), Samples.read("txt/release-notes.txt"));
    }

    @Test
    void theSampleIsSplitIntoWordWindows() {
        List<DocChunk> chunks = parseSample();

        assertEquals(2, chunks.size(), "369 words have to become two windows of 300");
        assertEquals(TextParser.WORDS_PER_WINDOW, wordCount(chunks.getFirst()));
        assertEquals(69, wordCount(chunks.get(1)), "the last window holds the remainder");
    }

    @Test
    void everyWindowHoldsAtMostTheConfiguredNumberOfWords() {
        String text = IntStream.range(0, 1000).mapToObj(i -> "word" + i)
                .collect(Collectors.joining(" "));

        List<DocChunk> chunks = parser.parse(Samples.at("generated.txt"), text);

        assertEquals(4, chunks.size());
        assertTrue(chunks.stream().allMatch(chunk -> wordCount(chunk) <= TextParser.WORDS_PER_WINDOW));
    }

    /** No overlap: a sentence that appeared twice would compete with itself in the ranking. */
    @Test
    void windowsDoNotOverlapAndLoseNothing() {
        String text = IntStream.range(0, 700).mapToObj(i -> "word" + i)
                .collect(Collectors.joining(" "));

        String rejoined = parser.parse(Samples.at("generated.txt"), text).stream()
                .map(DocChunk::getText)
                .collect(Collectors.joining(" "));

        assertEquals(text, rejoined);
    }

    @Test
    void plainTextHasNoHeading() {
        assertTrue(parseSample().stream().allMatch(chunk -> chunk.getHeading() == null));
    }

    @Test
    void metadataComesFromTheLocation() {
        DocChunk chunk = parseSample().getFirst();

        assertEquals("jackson", chunk.getProject());
        assertEquals("2.22.1", chunk.getVersion());
        assertEquals(ChunkType.TXT, chunk.getType());
        assertEquals("release-notes.txt", chunk.getSource());
        assertNull(chunk.getHeading());
    }

    @Test
    void supportsTextFilesOnly() {
        assertTrue(parser.supports("notes.txt"));
        assertFalse(parser.supports("notes.md"));
    }

    @Test
    void anEmptyFileYieldsNoChunks() {
        assertEquals(List.of(), parser.parse(Samples.at("empty.txt"), "  \n \t "));
    }

    private static int wordCount(DocChunk chunk) {
        return chunk.getText().split("\\s+").length;
    }
}
