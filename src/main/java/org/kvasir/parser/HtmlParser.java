package org.kvasir.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.kvasir.entity.ChunkType;
import org.kvasir.entity.DocChunk;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Chunks HTML documentation along its {@code <h1>} to {@code <h3>} elements, using Jsoup to get at
 * the text without the markup.
 * <p>
 * Deeper headings ({@code <h4>} and below) deliberately do not start a chunk: they usually sit
 * inside a section and splitting there would tear apart what belongs together.
 */
@ApplicationScoped
public class HtmlParser extends TextDocumentParser {

    private static final Set<String> SPLITTING_HEADINGS = Set.of("h1", "h2", "h3");

    @Override
    public boolean supports(String fileName) {
        return fileName.endsWith(".html") || fileName.endsWith(".htm");
    }

    @Override
    public List<DocChunk> parse(SourceLocation location, String content) {
        Document document = Jsoup.parse(content);

        List<DocChunk> chunks = new ArrayList<>();
        String heading = null;
        StringBuilder text = new StringBuilder();

        for (Element element : document.body().children()) {
            if (SPLITTING_HEADINGS.contains(element.tagName())) {
                addChunk(chunks, location, heading, text);
                heading = element.text().strip();
                text = new StringBuilder(heading);
            } else {
                appendText(text, element);
            }
        }
        addChunk(chunks, location, heading, text);

        return chunks;
    }

    /**
     * Extracts the package description from a legacy {@code package.html}, which predates
     * {@code package-info.java} but means the same thing.
     *
     * @param packageName the package the file belongs to; it becomes the chunk's source reference,
     *                    because such a chunk belongs to no class
     */
    public List<DocChunk> parsePackageHtml(SourceLocation location, String content,
            String packageName) {
        String text = Jsoup.parse(content).body().text().strip();
        if (text.isEmpty()) {
            return List.of();
        }
        return List.of(new DocChunk(location.chunkId(0), location.project(), location.version(),
                ChunkType.PACKAGE_DOC, packageName, packageName, text));
    }

    private static void appendText(StringBuilder text, Element element) {
        String elementText = element.text().strip();
        if (elementText.isEmpty()) {
            return;
        }
        if (!text.isEmpty()) {
            text.append("\n\n");
        }
        text.append(elementText);
    }

    private static void addChunk(List<DocChunk> chunks, SourceLocation location, String heading,
            StringBuilder text) {
        String body = text.toString().strip();
        if (body.isEmpty()) {
            return;
        }
        chunks.add(new DocChunk(location.chunkId(chunks.size()), location.project(),
                location.version(), ChunkType.HTML, location.path(), heading, body));
    }
}
