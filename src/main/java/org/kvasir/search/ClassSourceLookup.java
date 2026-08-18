package org.kvasir.search;

import java.util.List;
import java.util.Map;

import org.hibernate.search.engine.search.aggregation.AggregationKey;
import org.hibernate.search.mapper.pojo.standalone.mapping.SearchMapping;
import org.hibernate.search.mapper.pojo.standalone.session.SearchSession;
import org.kvasir.entity.ChunkType;
import org.kvasir.entity.DocChunk;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Hands back the complete source of one class.
 * <p>
 * The source lives in the index as a chunk of type {@link ChunkType#SOURCE}, written during
 * ingestion. That is the only place it survives: the sources archive is read through a temporary
 * view and a separate store for raw data is an explicit non-goal.
 * <p>
 * The lookup is pinned to exactly one project and one version. Falling back to another version
 * would be the worst possible answer here — the caller asked for the source of a specific release,
 * and code that merely looks plausible is harder to catch than an error.
 */
@ApplicationScoped
public class ClassSourceLookup {

    private final SearchMapping searchMapping;

    public ClassSourceLookup(SearchMapping searchMapping) {
        this.searchMapping = searchMapping;
    }

    /**
     * @return the complete source file of the class
     * @throws IllegalArgumentException if an argument is blank, or if that class is not indexed for
     *                                  that project and version. The message names the versions the
     *                                  class does exist in, so a caller that guessed the version
     *                                  wrong learns what to ask for instead.
     */
    public String sourceOf(String project, String version, String fullyQualifiedClassName) {
        require(project, "project");
        require(version, "version");
        require(fullyQualifiedClassName, "fullyQualifiedClassName");

        try (SearchSession session = searchMapping.createSession()) {
            List<String> sources = session.search(DocChunk.class)
                    .select(f -> f.field("text", String.class))
                    .where(f -> f.bool()
                            .must(f.match().field("type").matching(ChunkType.SOURCE))
                            .must(f.match().field("project").matching(project))
                            .must(f.match().field("version").matching(version))
                            .must(f.match().field("fullyQualifiedClassName")
                                    .matching(fullyQualifiedClassName)))
                    .fetchHits(1);

            if (sources.isEmpty()) {
                throw new IllegalArgumentException(notFoundMessage(project, version,
                        fullyQualifiedClassName));
            }
            return sources.getFirst();
        }
    }

    /**
     * Turns a dead end into a next step: if the class exists in other versions of the project, they
     * are named, because guessing the version is the most likely way to get here.
     */
    private String notFoundMessage(String project, String version, String className) {
        List<String> otherVersions = versionsContaining(project, className).stream()
                .filter(candidate -> !candidate.equals(version))
                .sorted(VersionComparator.INSTANCE)
                .toList();

        String message = "No source indexed for class '%s' in project '%s' version '%s'"
                .formatted(className, project, version);
        if (otherVersions.isEmpty()) {
            return message + ". Use list_projects and list_versions to check the project and version,"
                    + " and note that only classes from a sources archive have their source indexed.";
        }
        return message + ", but it exists in these versions: " + String.join(", ", otherVersions);
    }

    private List<String> versionsContaining(String project, String className) {
        AggregationKey<Map<String, Long>> key = AggregationKey.of("versions");
        try (SearchSession session = searchMapping.createSession()) {
            return List.copyOf(session.search(DocChunk.class)
                    .where(f -> f.bool()
                            .must(f.match().field("type").matching(ChunkType.SOURCE))
                            .must(f.match().field("project").matching(project))
                            .must(f.match().field("fullyQualifiedClassName").matching(className)))
                    .aggregation(key, f -> f.terms().field("version", String.class))
                    .fetch(0)
                    .aggregation(key)
                    .keySet());
        }
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
