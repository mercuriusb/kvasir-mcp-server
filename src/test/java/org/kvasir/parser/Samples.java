package org.kvasir.parser;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Loads the sample files that ship with the tests under {@code src/test/resources/samples}. */
final class Samples {

    private Samples() {
    }

    static String read(String name) {
        try (InputStream stream = Samples.class.getResourceAsStream("/samples/" + name)) {
            if (stream == null) {
                throw new IllegalArgumentException("no such sample: " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A version specific file of the sample project. */
    static SourceLocation at(String path) {
        return new SourceLocation("jackson", "2.22.1", path);
    }

    /** A file under {@code <project>/doc/}, which applies to every version. */
    static SourceLocation crossVersion(String path) {
        return new SourceLocation("jackson", null, path);
    }
}
