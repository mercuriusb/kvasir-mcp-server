package org.kvasir.search;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/** Everything that shapes how {@code search_docs} queries the index. */
@ConfigMapping(prefix = "docs.search")
public interface SearchConfig {

    /**
     * Up to this many terms, the full text part requires <em>all</em> of them.
     * <p>
     * Short queries are usually precise — for {@code "jackson polymorphism deserialization"} a hit
     * containing one of the three words is rarely useful. Longer, natural language questions are
     * the opposite: insisting on every word would let one rare term empty the result.
     */
    @WithDefault("3")
    int exactMatchMaxTerms();

    /** How many of the terms a longer query has to match. */
    @WithDefault("75")
    int minShouldMatchPercent();

    Rrf rrf();

    /**
     * Parameters of OpenSearch's reciprocal rank fusion. They are passed straight into the search
     * pipeline; nothing here recomputes scores.
     */
    interface Rrf {

        /**
         * RRF's k. Larger values flatten the ranking and weaken the pull of the top hits, smaller
         * ones sharpen it. 60 is the value that holds up across corpora.
         */
        @WithDefault("60")
        int rankConstant();

        @WithDefault("0.5")
        double weightBm25();

        @WithDefault("0.5")
        double weightVector();
    }
}
