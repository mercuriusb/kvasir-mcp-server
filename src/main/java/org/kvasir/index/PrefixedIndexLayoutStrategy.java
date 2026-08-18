package org.kvasir.index;

import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hibernate.search.backend.elasticsearch.index.layout.IndexLayoutStrategy;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;

/**
 * Puts a configurable prefix in front of every index and alias name.
 * <p>
 * The OpenSearch cluster is shared, so tests must not write into — let alone drop — the index the
 * application uses. Setting {@code docs.index.prefix} for the test profile gives the test run an
 * index of its own while the mapping and all queries stay exactly the same.
 * <p>
 * Apart from the prefix this reproduces Hibernate Search's default (simple) layout: an initial
 * index {@code <name>-000001} behind a {@code -write} and a {@code -read} alias.
 */
@ApplicationScoped
@Named(PrefixedIndexLayoutStrategy.NAME)
public class PrefixedIndexLayoutStrategy implements IndexLayoutStrategy {

    /** Referenced from {@code application.properties} as {@code bean:prefixed-index-layout}. */
    public static final String NAME = "prefixed-index-layout";

    private final String prefix;

    /**
     * The prefix is {@code Optional} rather than a {@code String} with an empty default, because
     * SmallRye turns an empty value into a missing one and then fails to convert it to a String —
     * "no prefix" has to be expressible.
     */
    public PrefixedIndexLayoutStrategy(
            @ConfigProperty(name = "docs.index.prefix") Optional<String> prefix) {
        this.prefix = prefix.orElse("");
    }

    @Override
    public String createInitialElasticsearchIndexName(String hibernateSearchIndexName) {
        return prefix + hibernateSearchIndexName + "-000001";
    }

    @Override
    public String createWriteAlias(String hibernateSearchIndexName) {
        return prefix + hibernateSearchIndexName + "-write";
    }

    @Override
    public String createReadAlias(String hibernateSearchIndexName) {
        return prefix + hibernateSearchIndexName + "-read";
    }
}
