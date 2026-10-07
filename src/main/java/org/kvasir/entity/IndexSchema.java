package org.kvasir.entity;

/**
 * Version of everything that determines what ends up in the index.
 * <p>
 * Not just the field mapping: the chunking rules count too. If markdown were suddenly split
 * differently, the stored chunks would be stale even though every mapping stayed identical — and
 * the content hash of the file would not have changed either, so a rescan would happily skip it.
 * <p>
 * Raising this number invalidates every recorded file fingerprint at once. The next scan therefore
 * treats all files as changed and rebuilds them, without anyone having to remember to delete the
 * fingerprint index by hand. That is the mistake this constant exists to prevent: deleting
 * {@code kvasir-doc-chunk} but not {@code kvasir-indexed-file} leaves a scan skipping everything
 * and an empty chunk index behind.
 *
 * <h2>When to raise it</h2>
 * <ul>
 * <li>a field is added, removed or retyped in {@link DocChunk}</li>
 * <li>the embedding model or its dimension changes</li>
 * <li>a parser starts chunking differently</li>
 * </ul>
 * Not needed for changes that leave the stored documents identical.
 */
public final class IndexSchema {

    /** Raise on any change to the mapping, the embedding or the chunking rules. */
    public static final int VERSION = 2;

    private IndexSchema() {
    }
}
