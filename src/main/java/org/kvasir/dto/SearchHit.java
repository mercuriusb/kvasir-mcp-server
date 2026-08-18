package org.kvasir.dto;

import org.kvasir.entity.ChunkType;

/**
 * A single hit returned by {@code search_docs}.
 * <p>
 * The provenance fields are mandatory so that an agent can cite its answer. A {@code version} of
 * {@code null} explicitly means "cross-version": the chunk originates from
 * {@code data/<project>/doc/} and applies to the whole project rather than to one specific
 * version.
 *
 * @param project the project the chunk belongs to
 * @param version the version, or {@code null} for cross-version chunks
 * @param type    what kind of content the chunk holds; serialized as its wire name
 * @param source  file or class path of the chunk
 * @param heading heading (documentation) or signature (Java source)
 * @param text    the chunk text itself
 */
public record SearchHit(
        String project,
        String version,
        ChunkType type,
        String source,
        String heading,
        String text) {
}
