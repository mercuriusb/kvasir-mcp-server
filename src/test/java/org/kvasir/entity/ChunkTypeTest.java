package org.kvasir.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.kvasir.dto.SearchHit;

import io.vertx.core.json.JsonObject;

/** Plain unit tests for the wire names of {@link ChunkType}; no cluster involved. */
class ChunkTypeTest {

    @ParameterizedTest
    @EnumSource(ChunkType.class)
    void everyTypeRoundTripsThroughItsWireName(ChunkType type) {
        assertSame(type, ChunkType.fromWireName(type.wireName()));
    }

    @Test
    void packageDocUsesAHyphenRatherThanTheConstantName() {
        assertEquals("package-doc", ChunkType.PACKAGE_DOC.wireName());
        assertEquals("javadoc", ChunkType.JAVADOC.wireName());
    }

    @Test
    void anUnknownWireNameIsRejectedWithAHelpfulMessage() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ChunkType.fromWireName("packagedoc"));
        assertEquals("Unknown chunk type 'packagedoc', expected one of "
                + "markdown, txt, html, javadoc, package-doc, source", failure.getMessage());
    }

    /** The constant name is never the wire name, so it must not be accepted either. */
    @Test
    void theConstantNameIsNotAValidWireName() {
        assertThrows(IllegalArgumentException.class, () -> ChunkType.fromWireName("PACKAGE_DOC"));
    }

    /**
     * A hit goes back to the agent as JSON, and the type in it has to be the same value the agent
     * may pass to the {@code search_docs} type filter.
     */
    @Test
    void aSearchHitSerializesTheTypeAsItsWireName() {
        SearchHit hit = new SearchHit("jackson", null, ChunkType.PACKAGE_DOC,
                "com.fasterxml.jackson.databind.deser", "Heading", "Text");
        assertEquals("package-doc", JsonObject.mapFrom(hit).getString("type"));
    }
}
