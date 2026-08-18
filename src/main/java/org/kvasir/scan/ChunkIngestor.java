package org.kvasir.scan;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.hibernate.search.engine.search.predicate.dsl.PredicateFinalStep;
import org.hibernate.search.engine.search.predicate.dsl.SearchPredicateFactory;
import org.hibernate.search.engine.search.query.SearchScroll;
import org.hibernate.search.engine.search.query.SearchScrollResult;
import org.hibernate.search.mapper.pojo.standalone.mapping.SearchMapping;
import org.hibernate.search.mapper.pojo.standalone.session.SearchSession;
import org.kvasir.embedding.DocumentEmbedder;
import org.kvasir.entity.ChunkType;
import org.kvasir.entity.DocChunk;
import org.kvasir.entity.IndexedFile;
import org.kvasir.parser.SourceLocation;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Takes the chunks of one file, embeds them and writes them to the index.
 * <p>
 * Also owns the two things that make a repeated scan cheap and correct: the content hash that lets
 * an unchanged file be skipped entirely, and the removal of a file's previous chunks before its new
 * ones are written. Without the second part a file that shrinks would leave orphans behind, because
 * chunk ids are position based.
 */
@ApplicationScoped
public class ChunkIngestor {

    /** How many chunks are embedded in one call to the model. */
    private static final int EMBEDDING_BATCH_SIZE = 32;

    /** Page size when collecting the ids of chunks that have to go. */
    private static final int SCROLL_SIZE = 500;

    private final SearchMapping searchMapping;
    private final DocumentEmbedder embedder;

    public ChunkIngestor(SearchMapping searchMapping, DocumentEmbedder embedder) {
        this.searchMapping = searchMapping;
        this.embedder = embedder;
    }

    /**
     * SHA-256 of the file content, hex encoded.
     * <p>
     * Read as a stream rather than into a byte array: the file may be a sources archive of any
     * size, and hashing it must not mean holding it in memory — nor reading it a second time, since
     * the parser opens it itself.
     */
    public static String hash(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
                in.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }

    /**
     * Reads the content hashes recorded for a scan scope in one query.
     * <p>
     * The alternative — looking each file up as the scanner reaches it — costs one round trip per
     * file. At a few files that is invisible; at ten thousand the scan spends its time waiting on
     * the network before it has parsed anything at all.
     *
     * @param project {@code null} for every project
     * @param version {@code null} for every version of the project, including the cross-version
     *                documentation
     */
    public KnownFileHashes knownHashes(String project, String version) {
        Map<String, KnownFileHashes.Fingerprint> fingerprints = new HashMap<>();
        try (SearchSession session = searchMapping.createSession()) {
            SearchScroll<List<?>> scroll = session.search(IndexedFile.class)
                    .select(f -> f.composite(f.id(String.class),
                            f.field("contentHash", String.class),
                            f.field("schemaVersion", Integer.class)))
                    .where((f, root) -> {
                        root.add(f.matchAll());
                        if (project != null) {
                            root.add(f.match().field("project").matching(project));
                        }
                        if (version != null) {
                            root.add(f.match().field("version").matching(version));
                        }
                    })
                    .scroll(SCROLL_SIZE);
            for (SearchScrollResult<List<?>> page = scroll.next(); page.hasHits();
                    page = scroll.next()) {
                for (List<?> row : page.hits()) {
                    fingerprints.put((String) row.get(0), new KnownFileHashes.Fingerprint(
                            (String) row.get(1), (Integer) row.get(2)));
                }
            }
        }
        return new KnownFileHashes(fingerprints);
    }

    /**
     * Replaces everything previously indexed for this file with the given chunks and records the
     * new content hash.
     *
     * @param filePath path of the file that produced the chunks. For an archive this is the archive
     *                 itself, whose chunks carry a source of {@code <archive>!/<entry>} — they are
     *                 all removed together.
     * @return the number of chunks written
     */
    public int replace(String project, String version, String filePath, String contentHash,
            List<DocChunk> chunks) {
        deletePreviousChunks(project, version, filePath);
        embed(chunks);

        try (SearchSession session = searchMapping.createSession()) {
            for (DocChunk chunk : chunks) {
                session.indexingPlan().addOrUpdate(chunk);
            }
            session.indexingPlan()
                    .addOrUpdate(new IndexedFile(project, version, filePath, contentHash));
        }
        return chunks.size();
    }

    /**
     * Embeds the chunks in batches.
     * <p>
     * Chunks of type {@link ChunkType#SOURCE} deliberately get no vector: they exist so that
     * {@code get_class_source} can hand back a whole file, and they are looked up by class name,
     * never by semantic similarity. Embedding a complete source file would also mean embedding
     * mostly whatever fits into the model's first few hundred tokens.
     */
    private void embed(List<DocChunk> chunks) {
        List<DocChunk> toEmbed = chunks.stream()
                .filter(chunk -> chunk.getType() != ChunkType.SOURCE)
                .toList();

        for (int start = 0; start < toEmbed.size(); start += EMBEDDING_BATCH_SIZE) {
            List<DocChunk> batch = toEmbed.subList(start,
                    Math.min(start + EMBEDDING_BATCH_SIZE, toEmbed.size()));
            List<float[]> vectors = embedder.embedPassages(batch.stream()
                    .map(DocChunk::getText)
                    .toList());
            // embedPassages keeps the order of its input, which is what makes this mapping valid
            for (int i = 0; i < batch.size(); i++) {
                batch.get(i).setEmbedding(vectors.get(i));
            }
        }
    }

    /**
     * Removes every trace of a file that is no longer on disk: its chunks and its fingerprint.
     * <p>
     * Nothing else does this. The scanner only ever visits files that exist, so without this a
     * deleted file would keep answering searches forever with content that is gone.
     *
     * @return the number of chunks removed
     */
    public int forget(String fileId) {
        IndexedFile.FileRef ref = IndexedFile.parseId(fileId);
        int removed = deletePreviousChunks(ref.project(), ref.version(), ref.path());
        try (SearchSession session = searchMapping.createSession()) {
            session.indexingPlan().purge(IndexedFile.class, fileId, null);
        }
        return removed;
    }

    private int deletePreviousChunks(String project, String version, String filePath) {
        List<String> ids = new ArrayList<>();
        try (SearchSession session = searchMapping.createSession()) {
            SearchScroll<String> scroll = session.search(DocChunk.class)
                    .select(f -> f.id(String.class))
                    .where(f -> f.bool()
                            .must(f.match().field("project").matching(project))
                            .must(versionMatches(f, version))
                            .must(f.bool()
                                    .should(f.match().field("source").matching(filePath))
                                    .should(f.wildcard().field("source")
                                            .matching(SourceLocation.archiveEntryPrefix(filePath) + "*"))))
                    .scroll(SCROLL_SIZE);
            for (SearchScrollResult<String> page = scroll.next(); page.hasHits(); page = scroll.next()) {
                ids.addAll(page.hits());
            }
        }
        if (ids.isEmpty()) {
            return 0;
        }
        try (SearchSession session = searchMapping.createSession()) {
            for (String id : ids) {
                session.indexingPlan().purge(DocChunk.class, id, null);
            }
        }
        return ids.size();
    }

    /**
     * A {@code null} version means "applies to the whole project", which is stored as a missing
     * field rather than as a value — so it has to be matched as a missing field too.
     */
    static PredicateFinalStep versionMatches(SearchPredicateFactory factory, String version) {
        return version == null
                ? factory.bool().mustNot(factory.exists().field("version"))
                : factory.match().field("version").matching(version);
    }
}
