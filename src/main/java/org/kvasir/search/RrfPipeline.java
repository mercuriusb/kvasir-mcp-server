package org.kvasir.search;

import java.io.IOException;

import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.hibernate.search.backend.elasticsearch.ElasticsearchBackend;
import org.hibernate.search.mapper.pojo.standalone.mapping.SearchMapping;
import org.jboss.logging.Logger;

import io.quarkus.runtime.Startup;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Creates the search pipeline that fuses the full text and vector rankings.
 * <p>
 * The fusion itself is OpenSearch's, not ours: the pipeline holds a {@code score-ranker-processor}
 * with technique {@code rrf}, and this class only passes the configured parameters into it. That is
 * deliberate — BM25 scores are unbounded and cosine similarity runs from -1 to 1, so any homemade
 * formula mixing the two raw values would be arbitrary. RRF combines <em>ranks</em>, which sidesteps
 * the problem entirely.
 * <p>
 * Written at startup so that a configuration change takes effect on the next boot, and so that a
 * cluster missing the neural-search plugin fails loudly rather than at the first search.
 */
@Startup
@ApplicationScoped
public class RrfPipeline {

    private static final Logger LOG = Logger.getLogger(RrfPipeline.class);

    /** Name the pipeline is registered under and referenced by in every search request. */
    public static final String NAME = "kvasir-rrf";

    private final SearchMapping searchMapping;
    private final SearchConfig config;

    public RrfPipeline(SearchMapping searchMapping, SearchConfig config) {
        this.searchMapping = searchMapping;
        this.config = config;
    }

    void install(@Observes StartupEvent event) {
        SearchConfig.Rrf rrf = config.rrf();
        String body = """
                {
                  "description": "Reciprocal rank fusion of the BM25 and kNN rankings of kvasir",
                  "phase_results_processors": [
                    {
                      "score-ranker-processor": {
                        "combination": {
                          "technique": "rrf",
                          "rank_constant": %d,
                          "weights": [%s, %s]
                        }
                      }
                    }
                  ]
                }""".formatted(rrf.rankConstant(), rrf.weightBm25(), rrf.weightVector());

        Request request = new Request("PUT", "/_search/pipeline/" + NAME);
        request.setJsonEntity(body);
        try {
            client().performRequest(request);
            LOG.infof("Search pipeline '%s' installed: rank_constant=%d, weights=[%s, %s]", NAME,
                    rrf.rankConstant(), rrf.weightBm25(), rrf.weightVector());
        } catch (IOException e) {
            throw new IllegalStateException("""
                    Could not install the search pipeline '%s'. It needs the neural-search plugin, \
                    which provides the score-ranker-processor; check the cluster with \
                    GET /_cat/plugins.""".formatted(NAME), e);
        }
    }

    private RestClient client() {
        return searchMapping.backend().unwrap(ElasticsearchBackend.class).client(RestClient.class);
    }

}
