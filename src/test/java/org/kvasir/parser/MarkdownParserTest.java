package org.kvasir.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.kvasir.entity.ChunkType;
import org.kvasir.entity.DocChunk;

class MarkdownParserTest {

    private final MarkdownParser parser = new MarkdownParser();

    private List<DocChunk> parseSample() {
        return parser.parse(Samples.at("doc/polymorphism.md"),
                Samples.read("markdown/polymorphism.md"));
    }

    @Test
    void everyHeadingStartsItsOwnChunk() {
        List<DocChunk> chunks = parseSample();

        assertEquals(List.of("Polymorphic deserialization", "Type identifiers", "Fallbacks", "Grenzen"),
                chunks.stream().skip(1).map(DocChunk::getHeading).toList());
    }

    @Test
    void textBeforeTheFirstHeadingIsNotDropped() {
        DocChunk intro = parseSample().getFirst();

        assertNull(intro.getHeading());
        assertTrue(intro.getText().startsWith("Dieses Dokument beschreibt"), intro.getText());
    }

    @Test
    void aChunkEndsWhereTheNextHeadingBegins() {
        DocChunk typeIdentifiers = parseSample().stream()
                .filter(chunk -> "Type identifiers".equals(chunk.getHeading()))
                .findFirst()
                .orElseThrow();

        assertTrue(typeIdentifiers.getText().contains("@JsonTypeInfo"), typeIdentifiers.getText());
        assertFalse(typeIdentifiers.getText().contains("Fallbacks"), typeIdentifiers.getText());
    }

    /** A chunk has to make sense on its own, so it keeps its heading line in the text. */
    @Test
    void theHeadingLineStaysPartOfTheText() {
        DocChunk chunk = parseSample().get(1);

        assertTrue(chunk.getText().startsWith("# Polymorphic deserialization"), chunk.getText());
    }

    @Test
    void metadataComesFromTheLocation() {
        DocChunk chunk = parser.parse(Samples.crossVersion("doc/intro.md"), "# Titel\n\nText.")
                .getFirst();

        assertEquals("jackson", chunk.getProject());
        assertNull(chunk.getVersion(), "a file under doc/ applies to every version");
        assertEquals(ChunkType.MARKDOWN, chunk.getType());
        assertEquals("doc/intro.md", chunk.getSource());
    }

    @Test
    void chunkIdsAreStableAndUnique() {
        List<String> first = parseSample().stream().map(DocChunk::getId).toList();
        List<String> second = parseSample().stream().map(DocChunk::getId).toList();

        assertEquals(first, second, "re-parsing has to produce the same ids");
        assertEquals(first.size(), first.stream().distinct().count(), "ids have to be unique");
    }

    @Test
    void supportsMarkdownExtensionsOnly() {
        assertTrue(parser.supports("README.md"));
        assertTrue(parser.supports("README.markdown"));
        assertFalse(parser.supports("README.txt"));
    }

    @Test
    void anEmptyDocumentYieldsNoChunks() {
        assertEquals(List.of(), parser.parse(Samples.at("doc/empty.md"), "   \n\n  "));
    }
}
