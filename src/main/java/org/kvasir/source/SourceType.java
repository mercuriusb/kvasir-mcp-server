package org.kvasir.source;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The kind of system a {@link ContentSource} pulls from, and with it the resolver that knows how.
 * <p>
 * An enum and not a lookup table, for the same reason {@code ChunkType} is one — only sharper here.
 * The set is closed and bound to code: every type needs a resolver class, so a new type is
 * impossible without a deployment anyway, which is the only thing a table would have bought. What
 * it would cost instead is an opaque id in every log line and a state that must not exist: a row
 * without a matching resolver, whose sources are silently never fetched.
 * <p>
 * The database column holds the wire name and is guarded by a {@code CHECK} constraint listing
 * exactly these values. Note that Hibernate's schema validation does not verify check constraints:
 * adding a constant here without a migration compiles, starts up cleanly and fails on the first
 * insert. {@code SourceTypeConstraintTest} exists to catch that.
 */
public enum SourceType {

    /** A Maven repository, typically Artifactory: coordinates resolve to a {@code -sources.jar}. */
    MAVEN,

    /** A Git forge, read through its archive endpoint rather than by cloning. */
    GIT,

    /** A wiki, read through its API within one named space. */
    WIKI,

    /** A single document behind a URL. */
    HTTP,

    /**
     * The directory tree under {@code docs.data-dir}. The one type without a
     * {@link SourceSystem}: there is no remote system to name, and the path is configuration
     * rather than a runtime value.
     */
    LOCAL;

    private final String wireName = name().toLowerCase(Locale.ROOT).replace('_', '-');

    /** The value stored in the database column and used in the admin API. */
    @JsonValue
    public String wireName() {
        return wireName;
    }

    /**
     * @throws IllegalArgumentException if {@code wireName} is not one of the known types
     */
    public static SourceType fromWireName(String wireName) {
        return Arrays.stream(values())
                .filter(type -> type.wireName.equals(wireName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown source type '%s', expected one of %s".formatted(wireName, wireNames())));
    }

    /** All valid wire names, for error messages and for checking the database constraint. */
    public static String wireNames() {
        return Arrays.stream(values()).map(SourceType::wireName).collect(Collectors.joining(", "));
    }

    @Override
    public String toString() {
        return wireName;
    }
}
