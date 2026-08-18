package org.kvasir.search;

import java.util.List;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hibernate.search.backend.elasticsearch.ElasticsearchExtension;
import org.hibernate.search.mapper.pojo.standalone.mapping.SearchMapping;
import org.hibernate.search.mapper.pojo.standalone.session.SearchSession;
import org.kvasir.dto.SearchHit;
import org.kvasir.embedding.DocumentEmbedder;
import org.kvasir.entity.ChunkType;
import org.kvasir.entity.DocChunk;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * The hybrid search behind {@code search_docs}.
 * <p>
 * Two rankings are produced and then fused: BM25 over the chunk text, and kNN over its embedding.
 * The fusion is <em>OpenSearch's</em> reciprocal rank fusion, requested through the search pipeline
 * from {@link RrfPipeline}; nothing here reads a BM25 or cosine score, let alone combines them. RRF
 * works on ranks, which is what makes the two comparable at all — BM25 is unbounded, cosine runs
 * from -1 to 1.
 * <p>
 * That fusion step is the one place where the backend shows through: {@code hybrid} queries and
 * search pipelines are OpenSearch's, Elasticsearch fuses through a different construct entirely.
 * It is confined to {@link #hybridQuery} so that supporting another backend means another
 * implementation of that method, not a rewrite.
 */
@ApplicationScoped
public class DocSearchService {

    /**
     * How many candidates the vector side contributes at least, regardless of how few hits the
     * caller asked for. Fusion can only work with what both rankings offer, and a list of five is
     * thin material for it.
     */
    private static final int MIN_VECTOR_CANDIDATES = 20;

    private final SearchMapping searchMapping;
    private final DocumentEmbedder embedder;
    private final QueryTermCounter termCounter;
    private final SearchConfig config;
    private final String readAlias;

    public DocSearchService(SearchMapping searchMapping, DocumentEmbedder embedder,
            QueryTermCounter termCounter, SearchConfig config,
            @ConfigProperty(name = "docs.index.prefix") Optional<String> indexPrefix) {
        this.searchMapping = searchMapping;
        this.embedder = embedder;
        this.termCounter = termCounter;
        this.config = config;
        this.readAlias = indexPrefix.orElse("") + DocChunk.INDEX_NAME + "-read";
    }

    /**
     * @param project mandatory; results never cross project boundaries
     * @param version mandatory; cross-version documentation from {@code <project>/doc/} is included
     *                and comes back with {@code version = null}
     * @param type    optional filter on the chunk type
     * @param topK    how many hits to return
     */
    public List<SearchHit> search(String project, String version, String query, int topK,
            ChunkType type) {
        require(project, "project");
        require(version, "version");
        require(query, "query");
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be positive, was " + topK);
        }

        JsonObject hybrid = hybridQuery(project, version, query, topK, type);

        try (SearchSession session = searchMapping.createSession()) {
            return session.search(DocChunk.class)
                    .extension(ElasticsearchExtension.get())
                    .select(f -> f.composite(
                            f.field("project", String.class),
                            f.field("version", String.class),
                            f.field("type", ChunkType.class),
                            f.field("source", String.class),
                            f.field("heading", String.class),
                            f.field("text", String.class)))
                    .where(f -> f.extension(ElasticsearchExtension.get()).fromJson(hybrid))
                    // the pipeline is what turns the two rankings into one; without it OpenSearch
                    // would return the sub-query scores unfused
                    .requestTransformer(context -> context.parametersMap()
                            .put("search_pipeline", RrfPipeline.NAME))
                    .fetchHits(topK)
                    .stream()
                    .map(DocSearchService::toHit)
                    .toList();
        }
    }

    /**
     * Builds the {@code hybrid} query: one BM25 clause and one kNN clause, both narrowed by the same
     * filters.
     * <p>
     * Package private so a test can assert on the query that is actually sent — the all-terms rule
     * lives in here, and checking it through search results alone would be indirect.
     */
    JsonObject hybridQuery(String project, String version, String query, int topK,
            ChunkType type) {
        JsonArray filters = filters(project, version, type);

        JsonArray clauses = new JsonArray();
        clauses.add(fullTextClause(query, filters));
        clauses.add(vectorClause(query, topK, filters));

        JsonObject hybrid = new JsonObject();
        JsonObject queries = new JsonObject();
        queries.add("queries", clauses);
        hybrid.add("hybrid", queries);
        return hybrid;
    }

    /**
     * The full text side, with the all-terms rule from the configuration.
     * <p>
     * Applies to this clause only — the vector side keeps finding semantically close chunks whether
     * or not they contain the words. Only the fusion of the two decides the outcome.
     */
    private JsonObject fullTextClause(String query, JsonArray filters) {
        int terms = termCounter.countTerms(readAlias, "text", query);
        String minimumShouldMatch = terms <= config.exactMatchMaxTerms()
                ? "100%"
                : config.minShouldMatchPercent() + "%";

        JsonObject match = new JsonObject();
        match.addProperty("query", query);
        match.addProperty("minimum_should_match", minimumShouldMatch);

        JsonObject text = new JsonObject();
        text.add("text", match);
        JsonObject matchClause = new JsonObject();
        matchClause.add("match", text);

        JsonArray must = new JsonArray();
        must.add(matchClause);

        JsonObject bool = new JsonObject();
        bool.add("must", must);
        bool.add("filter", filters);

        JsonObject clause = new JsonObject();
        clause.add("bool", bool);
        return clause;
    }

    private JsonObject vectorClause(String query, int topK, JsonArray filters) {
        JsonArray vector = new JsonArray();
        for (float value : embedder.embedQuery(query)) {
            vector.add(value);
        }

        JsonObject filter = new JsonObject();
        JsonObject filterBool = new JsonObject();
        filterBool.add("filter", filters);
        filter.add("bool", filterBool);

        JsonObject embedding = new JsonObject();
        embedding.add("vector", vector);
        embedding.addProperty("k", Math.max(topK, MIN_VECTOR_CANDIDATES));
        embedding.add("filter", filter);

        JsonObject field = new JsonObject();
        field.add("embedding", embedding);
        JsonObject clause = new JsonObject();
        clause.add("knn", field);
        return clause;
    }

    /**
     * {@code project = X AND (version = Y OR version is missing) [AND type = Z]}.
     * <p>
     * The second half is what lets documentation under {@code <project>/doc/} be found for every
     * version without being duplicated per version. Such chunks carry no version field at all, so
     * they have to be matched as missing rather than as a value.
     */
    private static JsonArray filters(String project, String version, ChunkType type) {
        JsonArray filters = new JsonArray();
        filters.add(term("project", project));

        JsonArray versionOptions = new JsonArray();
        versionOptions.add(term("version", version));
        JsonObject missingVersion = new JsonObject();
        JsonObject mustNot = new JsonObject();
        JsonObject exists = new JsonObject();
        JsonObject field = new JsonObject();
        field.addProperty("field", "version");
        exists.add("exists", field);
        mustNot.add("must_not", exists);
        missingVersion.add("bool", mustNot);
        versionOptions.add(missingVersion);

        JsonObject versionBool = new JsonObject();
        versionBool.add("should", versionOptions);
        versionBool.addProperty("minimum_should_match", 1);
        JsonObject versionFilter = new JsonObject();
        versionFilter.add("bool", versionBool);
        filters.add(versionFilter);

        if (type != null) {
            filters.add(term("type", type.wireName()));
        }
        return filters;
    }

    private static JsonObject term(String field, String value) {
        JsonObject value0 = new JsonObject();
        value0.addProperty(field, value);
        JsonObject term = new JsonObject();
        term.add("term", value0);
        return term;
    }

    private static SearchHit toHit(List<?> fields) {
        return new SearchHit((String) fields.get(0), (String) fields.get(1),
                (ChunkType) fields.get(2), (String) fields.get(3), (String) fields.get(4),
                (String) fields.get(5));
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
