package org.kvasir.parser;

import java.util.ArrayList;
import java.util.List;

import org.kvasir.entity.ChunkType;
import org.kvasir.entity.DocChunk;

import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Chunks Markdown along its headings: every heading starts a new chunk that runs until the next
 * heading, at any level.
 * <p>
 * Content before the first heading becomes a chunk of its own without a heading, so an intro
 * paragraph is not silently dropped.
 * <p>
 * Each chunk keeps its heading line in the text as well, not only in the {@code heading} field.
 * That way a chunk is self-contained: whoever reads it — an agent or the embedding model — sees
 * what the section is about without having to look at a second field.
 */
@ApplicationScoped
public class MarkdownParser extends TextDocumentParser {

    private final Parser parser = Parser.builder().build();

    @Override
    public boolean supports(String fileName) {
        return fileName.endsWith(".md") || fileName.endsWith(".markdown");
    }

    @Override
    public List<DocChunk> parse(SourceLocation location, String content) {
        Document document = parser.parse(content);

        List<DocChunk> chunks = new ArrayList<>();
        String heading = null;
        StringBuilder text = new StringBuilder();

        for (Node node : document.getChildren()) {
            if (node instanceof Heading nodeHeading) {
                addChunk(chunks, location, heading, text);
                heading = nodeHeading.getText().toString().trim();
                text = new StringBuilder(nodeHeading.getChars().toString());
            } else {
                text.append(node.getChars());
            }
        }
        addChunk(chunks, location, heading, text);

        return chunks;
    }

    private static void addChunk(List<DocChunk> chunks, SourceLocation location, String heading,
            StringBuilder text) {
        String body = text.toString().strip();
        if (body.isEmpty()) {
            return;
        }
        chunks.add(new DocChunk(location.chunkId(chunks.size()), location.project(),
                location.version(), ChunkType.MARKDOWN, location.path(), heading, body));
    }
}
