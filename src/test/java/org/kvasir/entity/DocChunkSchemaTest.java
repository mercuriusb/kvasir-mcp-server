package org.kvasir.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonObject;

/**
 * Verifies that the index the application creates on startup really carries the field types the
 * hybrid search depends on: keyword fields for the mandatory filters, a full text field for the
 * BM25 part and a vector field of the expected dimension for the kNN part.
 * <p>
 * The mapping is read straight from the cluster rather than from the Java annotations, so that a
 * mismatch between the mapping and what the backend actually created cannot slip through. That
 * matters most for the embedding dimension: a wrong one would produce silently wrong hits.
 */
@QuarkusTest
class DocChunkSchemaTest {

    @ConfigProperty(name = "quarkus.hibernate-search-standalone.elasticsearch.hosts")
    List<String> hosts;

    @ConfigProperty(name = "quarkus.hibernate-search-standalone.elasticsearch.protocol")
    String protocol;

    /** Test runs get an index of their own; see PrefixedIndexLayoutStrategy. */
    @ConfigProperty(name = "docs.index.prefix")
    Optional<String> indexPrefix;

    @Test
    void indexIsCreatedWithTheExpectedFieldTypes() throws Exception {
        JsonObject properties = fetchMappingProperties();

        for (String keywordField : List.of("project", "version", "type", "source", "since",
                "fullyQualifiedClassName")) {
            assertEquals("keyword", properties.getJsonObject(keywordField).getString("type"),
                    "expected a keyword field: " + keywordField);
        }

        for (String textField : List.of("text", "heading")) {
            assertEquals("text", properties.getJsonObject(textField).getString("type"),
                    "expected a full text field: " + textField);
        }

        // the three mandatory filter fields need doc_values, otherwise a terms aggregation over
        // them - "which projects exist?" - is not possible at all
        for (String aggregableField : List.of("project", "version", "type")) {
            assertNotEquals(Boolean.FALSE,
                    properties.getJsonObject(aggregableField).getBoolean("doc_values"),
                    "expected an aggregable field: " + aggregableField);
        }

        JsonObject embedding = properties.getJsonObject("embedding");
        assertEquals("knn_vector", embedding.getString("type"));
        assertEquals(DocChunk.EMBEDDING_DIMENSION, embedding.getInteger("dimension"));
        assertEquals("cosinesimil", embedding.getJsonObject("method").getString("space_type"));
    }

    private JsonObject fetchMappingProperties() throws Exception {
        String writeAlias = indexPrefix.orElse("") + DocChunk.INDEX_NAME + "-write";
        URI uri = URI.create("%s://%s/%s/_mapping".formatted(protocol, hosts.getFirst(), writeAlias));
        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newHttpClient()) {
            response = client.send(HttpRequest.newBuilder(uri).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }
        assertEquals(200, response.statusCode(), () -> "could not read mapping from " + uri);

        JsonObject body = new JsonObject(response.body());
        // the response is keyed by the physical index name behind the alias
        String index = body.fieldNames().iterator().next();
        return body.getJsonObject(index).getJsonObject("mappings").getJsonObject("properties");
    }
}
