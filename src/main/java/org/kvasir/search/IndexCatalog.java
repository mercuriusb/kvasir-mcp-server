package org.kvasir.search;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.hibernate.search.engine.search.aggregation.AggregationKey;
import org.hibernate.search.engine.search.predicate.dsl.PredicateFinalStep;
import org.hibernate.search.engine.search.predicate.dsl.SearchPredicateFactory;
import org.hibernate.search.mapper.pojo.standalone.mapping.SearchMapping;
import org.hibernate.search.mapper.pojo.standalone.session.SearchSession;
import org.jboss.logging.Logger;
import org.kvasir.dto.Page;
import org.kvasir.entity.DocChunk;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Answers what is actually in the index: which projects, and which versions of them.
 * <p>
 * Deliberately read from the index rather than from the {@code data} directory. These lists are the
 * agent's source of truth for what it can search next, so a directory that exists but has never been
 * indexed must not appear — listing it would send the agent into a search that is guaranteed to come
 * back empty. The flip side is intended too: something only shows up here once
 * {@code POST /admin/index} has run.
 * <p>
 * The distinct values come from a terms aggregation, which is why {@code project} and {@code version}
 * are mapped as aggregable.
 */
@ApplicationScoped
public class IndexCatalog {

    private static final Logger LOG = Logger.getLogger(IndexCatalog.class);

    /** Largest page a caller may ask for when it does ask for one. */
    public static final int MAX_PAGE_SIZE = 500;

    /**
     * Ceiling on the distinct values the aggregation reports.
     * <p>
     * Terms aggregations cannot be paged — Elasticsearch and OpenSearch offer no offset for them, a
     * composite aggregation would be needed for that. The distinct values are therefore determined
     * in full and the page is cut from that list. Fine for a catalogue of projects or versions,
     * which is dozens to hundreds of entries; beyond this ceiling the listing would be incomplete,
     * so that case is logged rather than passed off as complete.
     */
    static final int MAX_DISTINCT_VALUES = 10_000;

    private final SearchMapping searchMapping;

    public IndexCatalog(SearchMapping searchMapping) {
        this.searchMapping = searchMapping;
    }

    /** Every indexed project, without paging. */
    public Page<String> allProjects() {
        return projects(0, null);
    }

    /**
     * Every project that has at least one indexed chunk, alphabetically.
     *
     * @param offset how many projects to skip; an offset past the end yields an empty page rather
     *               than an error, so a caller can walk to the end without special casing
     * @param limit  page size, clamped to {@link #MAX_PAGE_SIZE}, or {@code null} for no upper
     *               bound at all — a catalogue of a few dozen entries is not worth paging
     * @throws IllegalArgumentException if {@code offset} is negative or {@code limit} is not positive
     */
    public Page<String> projects(int offset, Integer limit) {
        return pageOf(distinctValuesOf("project", SearchPredicateFactory::matchAll), offset, limit);
    }

    /**
     * The versions of one project, oldest first, one page at a time.
     * <p>
     * Sorted semantically rather than alphabetically — see {@link VersionComparator} — and sorted
     * <em>before</em> the page is cut, otherwise the page boundaries would sit in the wrong place.
     * <p>
     * Chunks from {@code <project>/doc/} apply to every version and carry no {@code version} field
     * at all. A terms aggregation ignores missing values, so they produce no bucket and the list
     * never contains a {@code null} entry — which is right, because {@code null} is not a version
     * but "applies to all of them".
     *
     * @param project the project whose versions are wanted; an unknown one yields an empty page
     * @param limit   {@code null} for no upper bound; a project rarely has enough versions to be
     *                worth paging, so that is the expected case here
     * @throws IllegalArgumentException if {@code project} is blank, or the paging arguments are
     *                                  out of range
     */
    public Page<String> versions(String project, int offset, Integer limit) {
        if (project == null || project.isBlank()) {
            throw new IllegalArgumentException("project must not be blank");
        }
        List<String> versions = distinctValuesOf("version",
                f -> f.match().field("project").matching(project))
                .stream()
                .sorted(VersionComparator.INSTANCE)
                .toList();
        return pageOf(versions, offset, limit);
    }

    /** Every indexed version of one project, without paging. */
    public Page<String> allVersions(String project) {
        return versions(project, 0, null);
    }

    /** All distinct values of an aggregable keyword field among the documents the filter selects. */
    private List<String> distinctValuesOf(String field,
            Function<SearchPredicateFactory, ? extends PredicateFinalStep> filter) {
        AggregationKey<Map<String, Long>> key = AggregationKey.of(field);
        List<String> values;
        try (SearchSession session = searchMapping.createSession()) {
            values = List.copyOf(session.search(DocChunk.class)
                    .where(filter::apply)
                    .aggregation(key, f -> f.terms()
                            .field(field, String.class)
                            .orderByTermAscending()
                            .maxTermCount(MAX_DISTINCT_VALUES))
                    .fetch(0)
                    .aggregation(key)
                    .keySet());
        }
        if (values.size() >= MAX_DISTINCT_VALUES) {
            LOG.warnf("Distinct values of '%s' hit the ceiling of %d; the listing is incomplete",
                    field, MAX_DISTINCT_VALUES);
        }
        return values;
    }

    /**
     * Cuts the requested window out of the sorted values.
     * <p>
     * A {@code null} limit means "everything from the offset on". Paging stays available for the
     * case where a catalogue really has grown, but it is not forced on a caller who just wants the
     * three versions of a project.
     */
    private static Page<String> pageOf(List<String> values, int offset, Integer limit) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative, was " + offset);
        }
        if (limit != null && limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, was " + limit);
        }
        int from = Math.min(offset, values.size());
        int pageSize = limit == null ? values.size() - from : Math.min(limit, MAX_PAGE_SIZE);
        int to = Math.min(from + pageSize, values.size());
        return Page.of(values.subList(from, to), offset, pageSize, values.size());
    }
}
