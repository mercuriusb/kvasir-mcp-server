package org.kvasir.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class IdUtilsTest {

    /**
     * The discriminator that broke a scan: one {@code quarkus-core} method whose signature alone is
     * 851 bytes. It is the reason the discriminator has to go into the digest — hashing only the
     * path would have left this in clear text and still blown the limit.
     */
    private static final String HUGE_DISCRIMINATOR =
            "io.quarkus.runtime.logging.LoggingSetupRecorder#ShutdownListener initializeLogging("
                    + "final DiscoveredLogComponents discoveredLogComponents, "
                    + "final Map<String, InheritableLevel> categoryDefaultMinLevels, "
                    + "final boolean enableWebStream, "
                    + "final RuntimeValue<Optional<Handler>> streamingDevUiConsoleHandler, "
                    + "final List<RuntimeValue<Optional<Handler>>> additionalHandlers, "
                    + "final List<RuntimeValue<Map<String, Handler>>> additionalNamedHandlers, "
                    + "final List<RuntimeValue<Optional<Formatter>>> possibleConsoleFormatters, "
                    + "final List<RuntimeValue<Optional<Formatter>>> possibleFileFormatters, "
                    + "final List<RuntimeValue<Optional<Formatter>>> possibleSyslogFormatters, "
                    + "final List<RuntimeValue<Optional<Formatter>>> possibleSocketFormatters, "
                    + "final RuntimeValue<Optional<Supplier<String>>> possibleBannerSupplier, "
                    + "final LaunchMode launchMode, final boolean includeFilters)";

    private static int bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    @Test
    void keepsProjectAndVersionReadable() {
        assertTrue(IdUtils.chunkId("jackson", "2.22.1", "a.jar!/A.java", "A#m()")
                .startsWith("jackson/2.22.1/"));
    }

    @Test
    void marksACrossVersionFileInsteadOfWritingNull() {
        assertTrue(IdUtils.chunkId("jackson", null, "doc/overview.md", "0").startsWith("jackson/-/"));
    }

    @Test
    void staysFarInsideTheLimitEvenForTheWorstDiscriminator() {
        assertTrue(bytes(HUGE_DISCRIMINATOR) > IdUtils.MAX_BYTES,
                "the sample is supposed to be oversized on its own");

        String id = IdUtils.chunkId("quarkus-core", "3.33.3.1",
                "3.33.3.1/quarkus-core-3.33.3.1-sources.jar!/io/quarkus/runtime/logging/"
                        + "LoggingSetupRecorder.java",
                HUGE_DISCRIMINATOR);

        assertTrue(bytes(id) <= IdUtils.MAX_BYTES, "id is too long: " + bytes(id));
    }

    /** Re-indexing an unchanged file has to overwrite the same documents, not add duplicates. */
    @Test
    void isStableAcrossCalls() {
        assertEquals(IdUtils.chunkId("p", "1.0", "a.jar!/A.java", "A#m()"),
                IdUtils.chunkId("p", "1.0", "a.jar!/A.java", "A#m()"));
    }

    /**
     * The reason the discriminator belongs in the digest and not just the path: every chunk of a
     * file shares the path, so hashing the path alone would collapse a whole class onto one
     * document.
     */
    @Test
    void keepsTheChunksOfOneFileApart() {
        assertNotEquals(IdUtils.chunkId("p", "1.0", "a.jar!/A.java", "A"),
                IdUtils.chunkId("p", "1.0", "a.jar!/A.java", "A#m()"));
        assertNotEquals(IdUtils.chunkId("p", "1.0", "a.jar!/A.java", "source"),
                IdUtils.chunkId("p", "1.0", "a.jar!/A.java", "A"));
    }

    /** Two overloads differ only at the very end of the signature. */
    @Test
    void keepsOverloadsApart() {
        assertNotEquals(IdUtils.chunkId("p", "1.0", "a.jar!/A.java", "A#void m(String a)"),
                IdUtils.chunkId("p", "1.0", "a.jar!/A.java", "A#void m(String b)"));
    }

    @Test
    void separatesAFileFromItsChunks() {
        assertNotEquals(IdUtils.fileId("p", "1.0", "a.jar"),
                IdUtils.chunkId("p", "1.0", "a.jar", "0"));
    }

    /**
     * The backstop, which only a pathological directory name can reach. Cutting on bytes must not
     * leave half a character behind, and must not overshoot by substituting one either.
     */
    @Test
    void holdsTheLimitEvenWhenProjectAndVersionAreAbsurd() {
        String id = IdUtils.chunkId("ä".repeat(300), "ä".repeat(300), "a.java", "A#m()");

        assertTrue(bytes(id) <= IdUtils.MAX_BYTES, "id is too long: " + bytes(id));
        assertFalse(id.contains("�"), "a character was split");
    }
}
