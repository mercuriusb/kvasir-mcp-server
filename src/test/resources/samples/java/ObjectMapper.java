package com.example.databind;

import java.util.List;

/**
 * Maps between JSON and Java objects.
 *
 * <p>An instance is thread safe once configured and is meant to be shared.</p>
 *
 * @since 2.0
 */
public class ObjectMapper {

    private int cacheSize;

    /**
     * Reads JSON and turns it into an instance of the given type.
     *
     * @param json    the JSON document
     * @param type    the target type
     * @return the deserialized value
     * @since 2.1
     */
    public <T> T readValue(String json, Class<T> type) {
        return null;
    }

    /**
     * Writes a value as a JSON string.
     */
    public String writeValueAsString(Object value) {
        return null;
    }

    /** Registers modules that add support for further types. */
    public void registerModules(List<String> modules) {
    }

    /**
     * Internal bookkeeping; must not show up as its own chunk.
     */
    private void resetCache() {
    }

    /**
     * Package private helper; also must not show up.
     */
    void warmUp() {
    }
}
