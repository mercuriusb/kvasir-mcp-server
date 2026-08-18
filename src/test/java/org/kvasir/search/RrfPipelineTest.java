package org.kvasir.search;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;

import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.hibernate.search.backend.elasticsearch.ElasticsearchBackend;
import org.hibernate.search.mapper.pojo.standalone.mapping.SearchMapping;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * The fusion parameters have to reach OpenSearch unchanged. Nothing in the application recomputes
 * scores, so if these values did not arrive, the ranking would silently be someone else's.
 */
@QuarkusTest
class RrfPipelineTest {

    @Inject
    SearchMapping searchMapping;

    @Inject
    SearchConfig config;

    @Test
    void theConfiguredParametersArriveInTheClusterUnchanged() throws Exception {
        JsonObject combination = installedPipeline()
                .getAsJsonObject(RrfPipeline.NAME)
                .getAsJsonArray("phase_results_processors")
                .get(0).getAsJsonObject()
                .getAsJsonObject("score-ranker-processor")
                .getAsJsonObject("combination");

        assertEquals("rrf", combination.get("technique").getAsString(),
                "the fusion is OpenSearch's, not a formula of ours");
        assertEquals(config.rrf().rankConstant(), combination.get("rank_constant").getAsInt());
        assertEquals(config.rrf().weightBm25(),
                combination.getAsJsonArray("weights").get(0).getAsDouble());
        assertEquals(config.rrf().weightVector(),
                combination.getAsJsonArray("weights").get(1).getAsDouble());
    }

    private JsonObject installedPipeline() throws Exception {
        RestClient client = searchMapping.backend().unwrap(ElasticsearchBackend.class)
                .client(RestClient.class);
        String body = new String(client
                .performRequest(new Request("GET", "/_search/pipeline/" + RrfPipeline.NAME))
                .getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);
        return JsonParser.parseString(body).getAsJsonObject();
    }
}
