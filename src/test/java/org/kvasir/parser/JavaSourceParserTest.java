package org.kvasir.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.kvasir.entity.ChunkType;
import org.kvasir.entity.DocChunk;

class JavaSourceParserTest {

    private static final String CLASS_NAME = "com.example.databind.ObjectMapper";

    private final JavaSourceParser parser = new JavaSourceParser();

    private List<DocChunk> parseObjectMapper() {
        return parser.parse(Samples.at("com/example/databind/ObjectMapper.java"),
                Samples.read("java/ObjectMapper.java"));
    }

    private List<DocChunk> ofType(List<DocChunk> chunks, ChunkType type) {
        return chunks.stream().filter(chunk -> chunk.getType() == type).toList();
    }

    @Test
    void theClassDescriptionBecomesItsOwnChunk() {
        DocChunk classChunk = ofType(parseObjectMapper(), ChunkType.JAVADOC).getFirst();

        assertEquals(CLASS_NAME, classChunk.getHeading());
        assertTrue(classChunk.getText().contains("Maps between JSON and Java objects"),
                classChunk.getText());
        assertEquals("2.0", classChunk.getSince());
    }

    @Test
    void everyPublicMethodBecomesItsOwnChunk() {
        List<String> headings = ofType(parseObjectMapper(), ChunkType.JAVADOC).stream()
                .skip(1)
                .map(DocChunk::getHeading)
                .toList();

        assertEquals(List.of(
                "<T> T readValue(String json, Class<T> type)",
                "String writeValueAsString(Object value)",
                "void registerModules(List<String> modules)"), headings);
    }

    @Test
    void nonPublicMethodsAreLeftOut() {
        String all = parseObjectMapper().stream()
                .filter(chunk -> chunk.getType() == ChunkType.JAVADOC)
                .map(DocChunk::getText)
                .reduce("", String::concat);

        assertFalse(all.contains("resetCache"), "private method must not become a chunk");
        assertFalse(all.contains("warmUp"), "package private method must not become a chunk");
    }

    @Test
    void aMethodChunkCarriesSignatureJavadocAndItsClass() {
        DocChunk readValue = ofType(parseObjectMapper(), ChunkType.JAVADOC).get(1);

        assertTrue(readValue.getText().startsWith("<T> T readValue(String json, Class<T> type)"),
                readValue.getText());
        assertTrue(readValue.getText().contains("Reads JSON and turns it into an instance"),
                readValue.getText());
        assertEquals(CLASS_NAME, readValue.getFullyQualifiedClassName());
        assertEquals("2.1", readValue.getSince(), "the method has its own @since");
    }

    /** Interface methods are public even without the modifier and must not be skipped. */
    @Test
    void interfaceMethodsCountAsPublic() {
        List<DocChunk> chunks = parser.parse(
                Samples.at("com/example/databind/JsonSerializable.java"),
                Samples.read("java/JsonSerializable.java"));

        List<String> headings = ofType(chunks, ChunkType.JAVADOC).stream()
                .skip(1)
                .map(DocChunk::getHeading)
                .toList();

        assertEquals(List.of("void serialize(Object generator)", "boolean isEmpty()"), headings);
    }

    /**
     * The whole point of keeping package-doc apart: this file has no type declaration at all, so a
     * parser that only walks the classes would silently produce nothing.
     */
    @Test
    void packageInfoBecomesAPackageDocChunk() {
        List<DocChunk> chunks = parser.parse(Samples.at("com/example/databind/package-info.java"),
                Samples.read("java/package-info.java"));

        assertEquals(1, chunks.size());
        DocChunk chunk = chunks.getFirst();
        assertEquals(ChunkType.PACKAGE_DOC, chunk.getType());
        assertEquals("com.example.databind", chunk.getSource(),
                "the package name takes the place of a class path");
        assertNull(chunk.getFullyQualifiedClassName(), "a package belongs to no class");
        assertTrue(chunk.getText().contains("Data binding between JSON documents"), chunk.getText());
        assertEquals("2.0", chunk.getSince());
    }

    /** get_class_source needs the complete source, so it is indexed as its own chunk. */
    @Test
    void theCompleteSourceIsKeptAsASourceChunk() {
        List<DocChunk> source = ofType(parseObjectMapper(), ChunkType.SOURCE);

        assertEquals(1, source.size());
        assertEquals(CLASS_NAME, source.getFirst().getFullyQualifiedClassName());
        assertEquals(Samples.read("java/ObjectMapper.java"), source.getFirst().getText());
    }

    @Test
    void chunkIdsAreStableAndUnique() {
        List<String> first = parseObjectMapper().stream().map(DocChunk::getId).toList();
        List<String> second = parseObjectMapper().stream().map(DocChunk::getId).toList();

        assertEquals(first, second);
        assertEquals(first.size(), first.stream().distinct().count());
    }

    @Test
    void anUnparsableFileDoesNotFailTheScan() {
        assertEquals(List.of(), parser.parse(Samples.at("Broken.java"), "this is not java {{{"));
    }

    @Test
    void supportsJavaFilesOnly() {
        assertTrue(parser.supports("ObjectMapper.java"));
        assertFalse(parser.supports("ObjectMapper.class"));
    }
}
