package org.kvasir.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.kvasir.entity.ChunkType;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * Asserts on the query that actually goes to OpenSearch.
 * <p>
 * The all-terms rule and the version filter are decisions taken while building the request; reading
 * them off the result list instead would only ever be circumstantial evidence.
 */
@QuarkusTest
class DocSearchQueryTest {

    @Inject
    DocSearchService search;

    @Inject
    SearchConfig config;

    private JsonObject fullTextClause(String query) {
        return search.hybridQuery("demo", "1.0.0", query, 5, null)
                .getAsJsonObject("hybrid").getAsJsonArray("queries")
                .get(0).getAsJsonObject();
    }

    private String minimumShouldMatch(String query) {
        return fullTextClause(query).getAsJsonObject("bool")
                .getAsJsonArray("must").get(0).getAsJsonObject()
                .getAsJsonObject("match").getAsJsonObject("text")
                .get("minimum_should_match").getAsString();
    }

    /** Few terms means the caller was precise, so a hit missing one of them is rarely useful. */
    @Test
    void aShortQueryRequiresEveryTerm() {
        assertEquals(3, config.exactMatchMaxTerms(), "the test is written for this default");

        assertEquals("100%", minimumShouldMatch("jackson"));
        assertEquals("100%", minimumShouldMatch("polymorphism deserialization"));
        assertEquals("100%", minimumShouldMatch("jackson polymorphism deserialization"));
    }

    /** A long question must not run empty because of one rare word in it. */
    @Test
    void aLongerQueryFallsBackToTheConfiguredShare() {
        String expected = config.minShouldMatchPercent() + "%";

        assertEquals(expected, minimumShouldMatch("how do i deserialize polymorphic types here"));
    }

    /**
     * The term count comes from the analyzer, not from splitting on spaces — otherwise punctuation
     * and stop words would shift the boundary against which exactMatchMaxTerms is compared.
     */
    @Test
    void theTermCountComesFromTheAnalyzerNotFromWordCount() {
        // four words, but the punctuation does not add terms, so it stays a four term query
        assertEquals(config.minShouldMatchPercent() + "%",
                minimumShouldMatch("parser, generator, constraints, filter"));
    }

    /** The all-terms rule is for the full text side only; the vector side stays untouched by it. */
    @Test
    void theVectorClauseCarriesNoTermRule() {
        JsonObject knn = search.hybridQuery("demo", "1.0.0", "one two", 5, null)
                .getAsJsonObject("hybrid").getAsJsonArray("queries")
                .get(1).getAsJsonObject();

        JsonObject embedding = knn.getAsJsonObject("knn").getAsJsonObject("embedding");
        assertEquals(384, embedding.getAsJsonArray("vector").size());
        assertEquals(List.of("vector", "k", "filter"), List.copyOf(embedding.keySet()),
                "vector, candidate count and filters - no term matching of any kind");
        // "100%" is the term rule; the version filter also carries a minimum_should_match, so
        // searching for that key alone would prove nothing
        assertFalse(embedding.toString().contains("100%"), embedding.toString());
    }

    /**
     * project = X AND (version = Y OR version missing). The second half is what makes documentation
     * under doc/ findable for every version without duplicating it per version.
     */
    @Test
    void bothClausesAreNarrowedToProjectAndVersionIncludingTheCrossVersionCase() {
        JsonObject query = search.hybridQuery("demo", "1.0.0", "text", 5, null);
        JsonArray clauses = query.getAsJsonObject("hybrid").getAsJsonArray("queries");

        JsonArray fullTextFilters = clauses.get(0).getAsJsonObject()
                .getAsJsonObject("bool").getAsJsonArray("filter");
        JsonArray vectorFilters = clauses.get(1).getAsJsonObject()
                .getAsJsonObject("knn").getAsJsonObject("embedding").getAsJsonObject("filter")
                .getAsJsonObject("bool").getAsJsonArray("filter");

        for (JsonArray filters : new JsonArray[] { fullTextFilters, vectorFilters }) {
            String json = filters.toString();
            assertTrue(json.contains("\"project\":\"demo\""), json);
            assertTrue(json.contains("\"version\":\"1.0.0\""), json);
            assertTrue(json.contains("must_not") && json.contains("exists"),
                    "cross-version chunks have no version field and must be matched as missing: "
                            + json);
        }
    }

    @Test
    void aTypeFilterIsAddedOnlyWhenAskedFor() {
        assertFalse(search.hybridQuery("demo", "1.0.0", "text", 5, null).toString()
                .contains("\"type\""));

        assertTrue(search.hybridQuery("demo", "1.0.0", "text", 5, ChunkType.PACKAGE_DOC).toString()
                .contains("\"type\":\"package-doc\""), "the wire name, not the constant name");
    }
}
