package org.kvasir.entity;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The kind of content a {@link DocChunk} holds.
 * <p>
 * These are the only values the {@code type} filter of {@code search_docs} accepts, and four
 * different parsers produce them, so they are an enum rather than free-form strings: a typo would
 * otherwise be indexed happily and make the chunk unreachable through the filter forever.
 * <p>
 * The wire name — the value stored in the index and exchanged over MCP — is derived from the
 * constant name, so that {@link #PACKAGE_DOC} appears as {@code package-doc} and the two can never
 * drift apart.
 */
public enum ChunkType {

    MARKDOWN,
    TXT,
    HTML,

    /** Class and method Javadoc, extracted from a {@code .java} file. */
    JAVADOC,

    /**
     * Package Javadoc from {@code package-info.java} (or a legacy {@code package.html}). Kept apart
     * from {@link #JAVADOC} because such a chunk belongs to no class at all: it carries the package
     * name instead of a {@code fullyQualifiedClassName}, {@code get_class_source} cannot reach it,
     * and it answers a different question — what a package is for rather than what a method does.
     */
    PACKAGE_DOC,

    /** Java source code. */
    SOURCE;

    private final String wireName = name().toLowerCase(Locale.ROOT).replace('_', '-');

    /**
     * The value stored in the index and used by the {@code search_docs} type filter. Also what
     * appears in a serialized MCP response, so that a hit reports {@code package-doc} rather than
     * the Java constant name.
     */
    @JsonValue
    public String wireName() {
        return wireName;
    }

    /**
     * @throws IllegalArgumentException if {@code wireName} is not one of the known types
     */
    public static ChunkType fromWireName(String wireName) {
        return Arrays.stream(values())
                .filter(type -> type.wireName.equals(wireName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown chunk type '%s', expected one of %s".formatted(wireName, wireNames())));
    }

    /** All valid wire names, for error messages and tool descriptions. */
    public static String wireNames() {
        return Arrays.stream(values()).map(ChunkType::wireName).collect(Collectors.joining(", "));
    }

    @Override
    public String toString() {
        return wireName;
    }
}
