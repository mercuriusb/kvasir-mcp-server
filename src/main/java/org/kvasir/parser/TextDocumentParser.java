package org.kvasir.parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.kvasir.entity.DocChunk;

/**
 * Base for parsers whose input is text.
 * <p>
 * Reads the file and hands the content on, so that subclasses stay pure functions from text to
 * chunks — which is also what makes them testable without touching a filesystem.
 */
public abstract class TextDocumentParser implements DocumentParser {

    @Override
    public final List<DocChunk> parse(SourceLocation location, Path file) throws IOException {
        return parse(location, Files.readString(file, StandardCharsets.UTF_8));
    }

    /**
     * @param content the decoded file content
     */
    public abstract List<DocChunk> parse(SourceLocation location, String content);
}
