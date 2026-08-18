package org.kvasir.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.hibernate.search.mapper.pojo.standalone.mapping.SearchMapping;
import org.hibernate.search.mapper.pojo.standalone.session.SearchSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kvasir.entity.ChunkType;
import org.kvasir.entity.DocChunk;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * Indexes source chunks directly instead of scanning an archive: what is under test is the lookup,
 * and building a JAR would only add moving parts between the assertion and what it checks. The
 * chunks are removed again afterwards, since the test index is shared.
 */
@QuarkusTest
class ClassSourceLookupTest {

    private static final String PROJECT = "__source-lookup__";
    private static final String CLASS_NAME = "com.example.demo.Widget";
    private static final String SOURCE_IN_1_0_0 = """
            package com.example.demo;

            /** A widget, as of 1.0.0. */
            public class Widget {
                public void doSomething() {
                }
            }
            """;
    private static final String SOURCE_IN_2_0_0 = """
            package com.example.demo;

            /** A widget, as of 2.0.0 - the method was renamed. */
            public class Widget {
                public void doSomethingElse() {
                }
            }
            """;

    @Inject
    ClassSourceLookup lookup;

    @Inject
    SearchMapping searchMapping;

    @BeforeEach
    void indexTwoVersionsOfTheSameClass() {
        index("1.0.0", CLASS_NAME, SOURCE_IN_1_0_0);
        index("2.0.0", CLASS_NAME, SOURCE_IN_2_0_0);
        // a javadoc chunk for the same class: the lookup must not mistake it for the source
        DocChunk javadoc = new DocChunk(PROJECT + "/2.0.0/javadoc", PROJECT, "2.0.0",
                ChunkType.JAVADOC, "Widget.java", CLASS_NAME, "A widget, as of 2.0.0.");
        javadoc.setFullyQualifiedClassName(CLASS_NAME);
        write(javadoc);
    }

    @AfterEach
    void removeTheTestChunks() {
        List<String> ids;
        try (SearchSession session = searchMapping.createSession()) {
            ids = session.search(DocChunk.class)
                    .select(f -> f.id(String.class))
                    .where(f -> f.match().field("project").matching(PROJECT))
                    .fetchHits(100);
        }
        try (SearchSession session = searchMapping.createSession()) {
            ids.forEach(id -> session.indexingPlan().purge(DocChunk.class, id, null));
        }
    }

    @Test
    void theCompleteSourceOfTheRequestedVersionComesBack() {
        assertEquals(SOURCE_IN_1_0_0, lookup.sourceOf(PROJECT, "1.0.0", CLASS_NAME));
        assertEquals(SOURCE_IN_2_0_0, lookup.sourceOf(PROJECT, "2.0.0", CLASS_NAME));
    }

    /**
     * The important half of the contract: asking for a version that does not carry the class must
     * not quietly hand over the other one. Source that merely looks plausible is harder to catch
     * than an error.
     */
    @Test
    void aVersionWithoutThatClassIsAnErrorRatherThanTheOtherVersion() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> lookup.sourceOf(PROJECT, "3.0.0", CLASS_NAME));

        assertTrue(failure.getMessage().contains("3.0.0"), failure.getMessage());
        assertTrue(failure.getMessage().contains(CLASS_NAME), failure.getMessage());
    }

    /** A wrong version is the likeliest mistake, so the answer names the ones that would work. */
    @Test
    void theErrorNamesTheVersionsThatDoHaveTheClass() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> lookup.sourceOf(PROJECT, "3.0.0", CLASS_NAME));

        assertTrue(failure.getMessage().contains("1.0.0, 2.0.0"),
                "expected the available versions in semantic order: " + failure.getMessage());
    }

    @Test
    void anotherProjectNeverContributesAHit() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> lookup.sourceOf("some-other-project", "1.0.0", CLASS_NAME));

        assertTrue(failure.getMessage().contains("some-other-project"), failure.getMessage());
        assertTrue(failure.getMessage().contains("list_projects"),
                "without other versions to offer, the message points at the listing tools");
    }

    @Test
    void anUnknownClassIsReported() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> lookup.sourceOf(PROJECT, "1.0.0", "com.example.demo.NoSuchThing"));

        assertTrue(failure.getMessage().contains("NoSuchThing"), failure.getMessage());
    }

    @Test
    void blankArgumentsAreRejectedByName() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> lookup.sourceOf(" ", "1.0.0", CLASS_NAME)).getMessage().contains("project"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> lookup.sourceOf(PROJECT, null, CLASS_NAME)).getMessage().contains("version"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> lookup.sourceOf(PROJECT, "1.0.0", "")).getMessage()
                .contains("fullyQualifiedClassName"));
    }

    private void index(String version, String className, String source) {
        DocChunk chunk = new DocChunk(PROJECT + "/" + version + "/source", PROJECT, version,
                ChunkType.SOURCE, "demo-sources.jar!/com/example/demo/Widget.java", className,
                source);
        chunk.setFullyQualifiedClassName(className);
        write(chunk);
    }

    private void write(DocChunk chunk) {
        try (SearchSession session = searchMapping.createSession()) {
            session.indexingPlan().addOrUpdate(chunk);
        }
    }
}
