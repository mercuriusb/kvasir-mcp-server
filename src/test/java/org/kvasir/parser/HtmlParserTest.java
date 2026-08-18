package org.kvasir.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.kvasir.entity.ChunkType;
import org.kvasir.entity.DocChunk;

class HtmlParserTest {

    private final HtmlParser parser = new HtmlParser();

    private List<DocChunk> parseSample() {
        return parser.parse(Samples.at("guide.html"), Samples.read("html/guide.html"));
    }

    @Test
    void h1ToH3EachStartAChunk() {
        List<DocChunk> chunks = parseSample();

        assertEquals(List.of("Configuration", "Serialization features", "Date handling",
                "Troubleshooting"), chunks.stream().skip(1).map(DocChunk::getHeading).toList());
    }

    /** h4 sits inside a section; splitting there would tear apart what belongs together. */
    @Test
    void h4DoesNotStartAChunk() {
        DocChunk dateHandling = parseSample().stream()
                .filter(chunk -> "Date handling".equals(chunk.getHeading()))
                .findFirst()
                .orElseThrow();

        assertTrue(dateHandling.getText().contains("Zeitzonen"), dateHandling.getText());
        assertTrue(dateHandling.getText().contains("weil h4 nicht trennt"), dateHandling.getText());
    }

    @Test
    void textBeforeTheFirstHeadingIsNotDropped() {
        DocChunk intro = parseSample().getFirst();

        assertNull(intro.getHeading());
        assertTrue(intro.getText().contains("Kurze Einleitung"), intro.getText());
    }

    @Test
    void markupIsStrippedButListItemsSurvive() {
        DocChunk features = parseSample().stream()
                .filter(chunk -> "Serialization features".equals(chunk.getHeading()))
                .findFirst()
                .orElseThrow();

        assertFalse(features.getText().contains("<li>"), features.getText());
        assertTrue(features.getText().contains("INDENT_OUTPUT"), features.getText());
    }

    @Test
    void metadataComesFromTheLocation() {
        DocChunk chunk = parseSample().getFirst();

        assertEquals("jackson", chunk.getProject());
        assertEquals("2.22.1", chunk.getVersion());
        assertEquals(ChunkType.HTML, chunk.getType());
        assertEquals("guide.html", chunk.getSource());
    }

    /**
     * A legacy {@code package.html} describes a package, not a document, so it becomes a
     * package-doc chunk anchored at the package name rather than an html chunk.
     */
    @Test
    void packageHtmlBecomesAPackageDocChunk() {
        DocChunk chunk = parser.parsePackageHtml(Samples.at("com/example/core/package.html"),
                Samples.read("html/package.html"), "com.example.core").getFirst();

        assertEquals(ChunkType.PACKAGE_DOC, chunk.getType());
        assertEquals("com.example.core", chunk.getSource());
        assertEquals("com.example.core", chunk.getHeading());
        assertTrue(chunk.getText().contains("Streaming access to JSON documents"), chunk.getText());
        assertNull(chunk.getFullyQualifiedClassName(), "a package belongs to no class");
    }

    @Test
    void supportsHtmlExtensionsOnly() {
        assertTrue(parser.supports("guide.html"));
        assertTrue(parser.supports("guide.htm"));
        assertFalse(parser.supports("guide.md"));
    }
}
