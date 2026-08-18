package org.kvasir.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import org.hibernate.search.engine.search.predicate.dsl.PredicateFinalStep;
import org.hibernate.search.engine.search.predicate.dsl.SearchPredicateFactory;
import org.hibernate.search.mapper.pojo.standalone.mapping.SearchMapping;
import org.hibernate.search.mapper.pojo.standalone.session.SearchSession;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * Verifies the acceptance criteria of task 3 against the real OpenSearch instance: the
 * application connects on startup, the index exists with the expected field types, and a manually
 * indexed {@link DocChunk} can be found again.
 * <p>
 * Test runs write into their own prefixed index, which is dropped again at shutdown, so nothing
 * here can reach the index the application uses.
 */
@QuarkusTest
class DocChunkIndexTest {

    private static final String TEST_PROJECT = "__connection-test__";

    @Inject
    SearchMapping searchMapping;

    @Test
    void manuallyIndexedChunkIsFoundAgain() {
        String id = "connection-test-" + UUID.randomUUID();
        DocChunk chunk = new DocChunk(id, TEST_PROJECT, "1.0.0", ChunkType.MARKDOWN,
                "doc/connection-test.md", "Connection test",
                "Hibernate Search reaches the OpenSearch cluster and indexes this chunk.");

        try (SearchSession session = searchMapping.createSession()) {
            session.indexingPlan().add(chunk);
        }

        try {
            // keyword filter on project plus a full text match on text: proves that both the
            // @KeywordField and the @FullTextField side of the mapping actually work
            List<String> hits = search(f -> f.bool()
                    .must(f.match().field("project").matching(TEST_PROJECT))
                    .must(f.match().field("text").matching("opensearch cluster")));
            assertEquals(List.of(id), hits);

            // the keyword filter really discriminates instead of matching everything
            assertTrue(search(f -> f.match().field("project").matching("some-other-project"))
                    .stream().noneMatch(id::equals));
        } finally {
            try (SearchSession session = searchMapping.createSession()) {
                session.indexingPlan().purge(DocChunk.class, id, null);
            }
        }
    }

    /** Runs a query and projects the document ids of its hits. */
    private List<String> search(Function<SearchPredicateFactory, PredicateFinalStep> predicate) {
        try (SearchSession session = searchMapping.createSession()) {
            return session.search(DocChunk.class)
                    .select(f -> f.id(String.class))
                    .where(predicate::apply)
                    .fetchHits(20);
        }
    }
}
