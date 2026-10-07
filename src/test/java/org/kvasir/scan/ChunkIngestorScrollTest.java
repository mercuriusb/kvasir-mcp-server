package org.kvasir.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.hibernate.search.backend.elasticsearch.ElasticsearchBackend;
import org.hibernate.search.mapper.pojo.standalone.mapping.SearchMapping;
import org.junit.jupiter.api.Test;
import org.kvasir.entity.IndexedFile;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * A scroll holds a snapshot of the index open on the cluster. Closing the {@code SearchSession}
 * does not release it — {@code StandalonePojoSearchSession.close()} only executes the indexing
 * plan — so an unclosed scroll pins segments until its timeout (60 s by default) and counts
 * against {@code search.max_open_scroll_context} (500 by default).
 * <p>
 * The leak this guards against was invisible in the test suite for a while, because the test
 * profile drops its index at shutdown and a deleted index takes its contexts with it. Asserting
 * <em>during</em> the run is what makes it visible.
 * <p>
 * This talks to the shared cluster, so it compares the count before and after rather than
 * expecting zero: another application may legitimately have a scroll of its own open.
 */
@QuarkusTest
class ChunkIngestorScrollTest {

    private static final String PROJECT = "scroll-test";
    private static final String VERSION = "1.0.0";
    private static final String PATH = "1.0.0/notes.md";

    @Inject
    ChunkIngestor ingestor;

    @Inject
    SearchMapping searchMapping;

    @Test
    void readingTheFingerprintsLeavesNoScrollOpen() {
        long before = openScrolls();

        ingestor.knownHashes(PROJECT, null);

        assertEquals(before, openScrolls(), "knownHashes left a scroll context open");
    }

    @Test
    void replacingAFileLeavesNoScrollOpen() {
        long before = openScrolls();

        // an empty chunk list is enough: replace() still runs deletePreviousChunks, which is the
        // path that scrolls, and writing no chunks keeps the embedding model out of it
        ingestor.replace(PROJECT, VERSION, PATH, "some-hash", List.of());

        assertEquals(before, openScrolls(), "deletePreviousChunks left a scroll context open");
    }

    @Test
    void forgettingAFileLeavesNoScrollOpen() {
        ingestor.replace(PROJECT, VERSION, PATH, "some-hash", List.of());
        long before = openScrolls();

        ingestor.forget(IndexedFile.idOf(PROJECT, VERSION, PATH),
                new IndexedFile.FileRef(PROJECT, VERSION, PATH));

        assertEquals(before, openScrolls(), "forget left a scroll context open");
    }

    /** Currently open scroll contexts across the cluster, from the node stats. */
    private long openScrolls() {
        RestClient client = searchMapping.backend().unwrap(ElasticsearchBackend.class)
                .client(RestClient.class);
        try {
            String body = new String(client
                    .performRequest(new Request("GET", "/_nodes/stats/indices/search"))
                    .getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);
            JsonObject nodes = JsonParser.parseString(body).getAsJsonObject()
                    .getAsJsonObject("nodes");
            long total = 0;
            for (Map.Entry<String, com.google.gson.JsonElement> node : nodes.entrySet()) {
                total += node.getValue().getAsJsonObject()
                        .getAsJsonObject("indices")
                        .getAsJsonObject("search")
                        .get("scroll_current").getAsLong();
            }
            return total;
        } catch (Exception e) {
            throw new IllegalStateException("Could not read the scroll statistics", e);
        }
    }
}
