package com.example.databind;

/**
 * Implemented by values that know how to write themselves as JSON.
 */
public interface JsonSerializable {

    /**
     * Writes this value to the given generator.
     */
    void serialize(Object generator);

    /**
     * Whether this value would write nothing at all.
     */
    boolean isEmpty();

    private void notPartOfTheApi() {
    }
}
