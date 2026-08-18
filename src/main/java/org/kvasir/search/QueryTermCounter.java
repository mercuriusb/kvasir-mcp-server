package org.kvasir.search;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.hibernate.search.backend.elasticsearch.ElasticsearchBackend;
import org.hibernate.search.mapper.pojo.standalone.mapping.SearchMapping;
import org.jboss.logging.Logger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Counts how many terms a query actually contributes.
 * <p>
 * Counting words in Java would be the obvious shortcut and would be wrong: the number that matters
 * is the number of terms the <em>analyzer</em> produces. Stop words, punctuation and case folding
 * all change it, and if the count differs from what the query is really made of, the rule "up to
 * three terms, require all of them" applies to the wrong queries. So the analyzer of the very field
 * that will be searched is asked, through the index's {@code _analyze} endpoint.
 */
@ApplicationScoped
public class QueryTermCounter {

    private static final Logger LOG = Logger.getLogger(QueryTermCounter.class);

    private final SearchMapping searchMapping;

    public QueryTermCounter(SearchMapping searchMapping) {
        this.searchMapping = searchMapping;
    }

    /**
     * @param index the index or alias whose field analyzer applies
     * @param field the full text field the query will run against
     * @return the number of analyzed terms; falls back to counting whitespace-separated words if
     *         the cluster cannot answer, because a failed count must not fail the search
     */
    public int countTerms(String index, String field, String query) {
        JsonObject body = new JsonObject();
        body.addProperty("field", field);
        body.addProperty("text", query);

        Request request = new Request("POST", "/" + index + "/_analyze");
        request.setJsonEntity(body.toString());

        try {
            String response = new String(client().performRequest(request).getEntity().getContent()
                    .readAllBytes(), StandardCharsets.UTF_8);
            return JsonParser.parseString(response).getAsJsonObject().getAsJsonArray("tokens").size();
        } catch (IOException | RuntimeException e) {
            int fallback = query.strip().isEmpty() ? 0 : query.strip().split("\\s+").length;
            LOG.warnf(e, "Could not analyze the query, falling back to %d whitespace separated words",
                    fallback);
            return fallback;
        }
    }

    private RestClient client() {
        return searchMapping.backend().unwrap(ElasticsearchBackend.class).client(RestClient.class);
    }
}
