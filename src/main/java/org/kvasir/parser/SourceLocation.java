package org.kvasir.parser;

import java.util.Objects;

import org.kvasir.entity.IdUtils;

/**
 * Where a file came from, everything a parser needs to know beyond the content itself.
 *
 * @param project the project the file belongs to, derived from the path under {@code docs.data-dir}
 * @param version the version, or {@code null} for files under {@code <project>/doc/}, which apply
 *                to the whole project rather than to one version
 * @param path    path of the file relative to its project directory, or the entry path inside a
 *                sources JAR
 */
public record SourceLocation(String project, String version, String path) {

    /**
     * Separates an archive from an entry inside it, as in a JAR URL.
     * <p>
     * Defined here, in the one place that builds such a path, because the ingestion side has to
     * recognise the same shape again in order to remove all chunks of an archive together. Two
     * copies of this string would drift apart silently and leave orphans behind.
     */
    public static final String ARCHIVE_ENTRY_SEPARATOR = "!/";

    public SourceLocation {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(path, "path");
    }

    /**
     * Builds a stable id for the {@code index}-th chunk of this file. Stable matters: re-indexing an
     * unchanged file has to overwrite the same documents instead of adding duplicates.
     */
    public String chunkId(int index) {
        return IdUtils.chunkId(project, version, path, String.valueOf(index));
    }

    /** The location of an entry inside this archive. */
    public SourceLocation insideArchive(String entryPath) {
        return new SourceLocation(project, version, path + ARCHIVE_ENTRY_SEPARATOR + entryPath);
    }

    /** Prefix shared by every chunk that came out of the archive at {@code archivePath}. */
    public static String archiveEntryPrefix(String archivePath) {
        return archivePath + ARCHIVE_ENTRY_SEPARATOR;
    }

    /** Same, but for chunks that are identified by a name rather than by their position. */
    public String chunkId(String discriminator) {
        return IdUtils.chunkId(project, version, path, discriminator);
    }
}
