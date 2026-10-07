package org.kvasir.entity;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The one place that builds document ids, for chunks and for file fingerprints alike.
 * <p>
 * Both OpenSearch and Elasticsearch reject an {@code _id} longer than 512 bytes, and they reject it
 * during request validation — so a single oversized id fails the <em>entire</em> bulk request, and
 * with it every other document that happened to travel in it. An id may therefore not grow with the
 * content it describes.
 *
 * <h2>Why the variable part is hashed</h2>
 * A chunk is identified by its file plus a discriminator, and for a method that discriminator is
 * the full signature including type parameters. Measured over 27 libraries: the longest path is 207
 * bytes, but the longest discriminator is 851 — a single {@code quarkus-core} method. Hashing the
 * path alone would therefore not help; the length comes from the discriminator. Hashing both
 * together bounds every id at about 84 bytes.
 * <p>
 * {@code project} and {@code version} stay in clear text. They cost little, they make an id
 * recognisable in a log line or a bulk error, and they keep an id listing grouped by project.
 *
 * <h2>Why not a random id</h2>
 * A UUID would be unique too, but not derived: writing the same file twice would produce different
 * documents, and nothing but {@code deletePreviousChunks} would stand between a rescan and a
 * duplicated corpus. A derived id makes re-indexing converge by construction and leaves that query
 * as a second, independent guarantee rather than the only one.
 */
public final class IdUtils {

    /** The limit both OpenSearch and Elasticsearch enforce on {@code _id}. */
    public static final int MAX_BYTES = 512;

    /** Stands in for a version, so a cross-version file never puts "null" into an id. */
    private static final String NO_VERSION = "-";

    private IdUtils() {
    }

    /**
     * @param version       {@code null} for a file that applies to every version
     * @param path          path of the file relative to its project directory
     * @param discriminator what tells this chunk apart from the others of the same file
     */
    public static String chunkId(String project, String version, String path,
            String discriminator) {
        return id(project, version, digestOf(path + "#" + discriminator));
    }

    /**
     * @param version {@code null} for a file that applies to every version
     * @param path    path of the file relative to its project directory
     */
    public static String fileId(String project, String version, String path) {
        return id(project, version, digestOf(path));
    }

    private static String id(String project, String version, String digest) {
        return capped("%s/%s/%s".formatted(project, version == null ? NO_VERSION : version, digest));
    }

    /**
     * MD5 is chosen for its length, not for any security property: nothing here defends against a
     * chosen input, and 128 bits put a collision out of reach for a corpus of documents.
     */
    private static String digestOf(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // MD5 is required of every Java platform, so this cannot happen
            throw new IllegalStateException("MD5 is unavailable", e);
        }
    }

    /**
     * The backstop. The digest bounds what varies per chunk, but project and version are directory
     * names and this code does not control how long those get — so the guarantee is made here
     * rather than assumed. Cutting alone could let two ids collide, so what is dropped is replaced
     * by a digest of the whole id.
     */
    private static String capped(String id) {
        byte[] bytes = id.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_BYTES) {
            return id;
        }
        String digest = digestOf(id);
        int headBytes = MAX_BYTES - digest.length() - 1;
        // decoded with IGNORE rather than via new String(byte[]): the plain constructor SUBSTITUTES
        // a character the byte cut split, and U+FFFD is three bytes - which would push the result
        // back over the very limit this method exists to hold
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.IGNORE)
                .onUnmappableCharacter(CodingErrorAction.IGNORE);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes, 0, headBytes)) + "#" + digest;
        } catch (CharacterCodingException e) {
            // both error actions are IGNORE, so there is nothing left to throw for
            throw new IllegalStateException("Could not shorten an id", e);
        }
    }
}
