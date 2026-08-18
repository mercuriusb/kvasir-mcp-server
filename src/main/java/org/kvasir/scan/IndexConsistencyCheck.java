package org.kvasir.scan;

import java.util.List;
import java.util.Map;

import org.hibernate.search.engine.search.aggregation.AggregationKey;
import org.hibernate.search.mapper.pojo.standalone.mapping.SearchMapping;
import org.hibernate.search.mapper.pojo.standalone.session.SearchSession;
import org.jboss.logging.Logger;
import org.kvasir.entity.DocChunk;
import org.kvasir.entity.IndexedFile;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Checks at startup that the two indexes still tell the same story.
 * <p>
 * They are written together but restored separately, and one of the two possible mismatches is
 * silent and harmful:
 * <ul>
 * <li>Only the chunks came back: every fingerprint is missing, so the next scan rebuilds
 * everything. Slow, harmless, self-healing.</li>
 * <li>Only the fingerprints came back: the scan skips every file because they all look unchanged,
 * and the chunk index stays empty. Searches then return nothing, and nothing anywhere says
 * why.</li>
 * </ul>
 * The second case is what this looks for: a project with recorded files but no chunks.
 * <p>
 * Reported rather than repaired. Deleting fingerprints on a hunch during startup would be a
 * destructive guess; naming the problem and the fix leaves the decision where it belongs.
 */
@ApplicationScoped
public class IndexConsistencyCheck {

    private static final Logger LOG = Logger.getLogger(IndexConsistencyCheck.class);

    private final SearchMapping searchMapping;

    public IndexConsistencyCheck(SearchMapping searchMapping) {
        this.searchMapping = searchMapping;
    }

    void checkOnStartup(@Observes StartupEvent event) {
        List<String> broken;
        try {
            broken = projectsWithoutChunks();
        } catch (RuntimeException e) {
            // a diagnostic that stops the application is worse than no diagnostic
            LOG.warnf(e, "Could not check index consistency");
            return;
        }
        if (broken.isEmpty()) {
            return;
        }
        LOG.warnf("""
                Index inconsistency: %s recorded as indexed but hold no chunks. A scan will skip \
                their files because the fingerprints say they are unchanged, so the gap will not \
                close by itself. Reindex them with \
                POST /admin/index?project=<name>&force=true, or restore both indexes together \
                next time - '%s' and '%s' belong to each other.""",
                broken, DocChunk.INDEX_NAME, IndexedFile.INDEX_NAME);
    }

    /**
     * @return projects that have recorded files but not a single chunk
     */
    public List<String> projectsWithoutChunks() {
        return withFingerprints().stream()
                .filter(project -> !withChunks().contains(project))
                .sorted()
                .toList();
    }

    private List<String> withFingerprints() {
        AggregationKey<Map<String, Long>> key = AggregationKey.of("projects");
        try (SearchSession session = searchMapping.createSession()) {
            return List.copyOf(session.search(IndexedFile.class)
                    .where(f -> f.matchAll())
                    .aggregation(key, f -> f.terms().field("project", String.class))
                    .fetch(0)
                    .aggregation(key)
                    .keySet());
        }
    }

    private List<String> withChunks() {
        AggregationKey<Map<String, Long>> key = AggregationKey.of("projects");
        try (SearchSession session = searchMapping.createSession()) {
            return List.copyOf(session.search(DocChunk.class)
                    .where(f -> f.matchAll())
                    .aggregation(key, f -> f.terms().field("project", String.class))
                    .fetch(0)
                    .aggregation(key)
                    .keySet());
        }
    }
}
