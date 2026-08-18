package org.kvasir.parser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.kvasir.entity.DocChunk;

/**
 * Turns one file into indexable chunks.
 * <p>
 * Takes the {@link Path} rather than already decoded text, which is what lets an archive be a
 * parser like any other: a sources JAR is not decodable text, but it is a file that yields chunks.
 * Text based parsers get the decoding for free from {@link TextDocumentParser}.
 * <p>
 * Parsers produce {@link DocChunk} objects and nothing else: no embedding, no persistence. That is
 * the ingestion pipeline's job, which keeps them free of any knowledge about the search backend or
 * the embedding model — including the {@code query: }/{@code passage: } prefixes, which live in the
 * embedder.
 * <p>
 * The {@code supports} sets of the implementations are disjoint, so the scanner may take the first
 * parser that claims a file.
 */
public interface DocumentParser {

    /** Whether this parser handles the given file name, judged by its extension. */
    boolean supports(String fileName);

    /**
     * @param location where the file came from
     * @param file     the file itself
     * @return the chunks, in document order; empty if the file has no indexable content
     */
    List<DocChunk> parse(SourceLocation location, Path file) throws IOException;
}
