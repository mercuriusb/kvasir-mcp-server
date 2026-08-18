package org.kvasir.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hibernate.search.mapper.pojo.standalone.mapping.SearchMapping;
import org.hibernate.search.mapper.pojo.standalone.session.SearchSession;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;

/**
 * Checks that {@link ChunkType} survives the trip through the index: package doc really is
 * filterable separately from class Javadoc, and the stored value is the wire name an agent passes
 * to the {@code search_docs} type filter rather than the Java constant name.
 */
@QuarkusTest
class ChunkTypeIndexingTest {

    private static final String TEST_PROJECT = "__chunk-type-test__";

    @Inject
    SearchMapping searchMapping;

    @ConfigProperty(name = "quarkus.hibernate-search-standalone.elasticsearch.hosts")
    List<String> hosts;

    @ConfigProperty(name = "quarkus.hibernate-search-standalone.elasticsearch.protocol")
    String protocol;

    @ConfigProperty(name = "docs.index.prefix")
    Optional<String> indexPrefix;

    @Test
    void packageDocIsFilterableSeparatelyFromJavadoc() throws Exception {
        String packageDocId = index(ChunkType.PACKAGE_DOC, "com.fasterxml.jackson.databind.deser");
        String javadocId = index(ChunkType.JAVADOC, "com.fasterxml.jackson.databind.ObjectMapper");

        try {
            // this is the whole point of keeping the two types apart
            assertEquals(List.of(packageDocId), idsOfType(ChunkType.PACKAGE_DOC));
            assertEquals(List.of(javadocId), idsOfType(ChunkType.JAVADOC));

            // what actually sits in the document is "package-doc", not "PACKAGE_DOC"
            assertEquals("package-doc", storedTypeOf(packageDocId));
        } finally {
            purge(packageDocId, javadocId);
        }
    }

    private String index(ChunkType type, String source) {
        String id = type.wireName() + "-" + UUID.randomUUID();
        DocChunk chunk = new DocChunk(id, TEST_PROJECT, "1.0.0", type, source, "Heading",
                "Some indexed prose about deserialization.");
        try (SearchSession session = searchMapping.createSession()) {
            session.indexingPlan().add(chunk);
        }
        return id;
    }

    private List<String> idsOfType(ChunkType type) {
        try (SearchSession session = searchMapping.createSession()) {
            return session.search(DocChunk.class)
                    .select(f -> f.id(String.class))
                    .where(f -> f.bool()
                            .must(f.match().field("project").matching(TEST_PROJECT))
                            .must(f.match().field("type").matching(type)))
                    .fetchHits(20);
        }
    }

    /** Reads the raw document straight from the cluster, bypassing the value bridge. */
    private String storedTypeOf(String id) throws Exception {
        URI uri = URI.create("%s://%s/%s/_doc/%s".formatted(protocol, hosts.getFirst(),
                indexPrefix.orElse("") + DocChunk.INDEX_NAME + "-read", id));
        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newHttpClient()) {
            response = client.send(HttpRequest.newBuilder(uri).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }
        assertTrue(response.statusCode() == 200, () -> "document not found at " + uri);
        return new JsonObject(response.body()).getJsonObject("_source").getString("type");
    }

    private void purge(String... ids) {
        try (SearchSession session = searchMapping.createSession()) {
            for (String id : ids) {
                session.indexingPlan().purge(DocChunk.class, id, null);
            }
        }
    }
}
