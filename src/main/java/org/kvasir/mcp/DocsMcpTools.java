package org.kvasir.mcp;

import java.util.List;

import org.kvasir.dto.Page;
import org.kvasir.entity.ChunkType;
import org.kvasir.dto.SearchHit;
import org.kvasir.search.ClassSourceLookup;
import org.kvasir.search.DocSearchService;
import org.kvasir.search.IndexCatalog;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.WrapBusinessError;
import jakarta.inject.Singleton;

/**
 * The four MCP tools that expose indexed documentation, Javadoc and Java source code.
 * <p>
 * There is deliberately <em>no</em> tool for triggering indexing: that is an administrative
 * operation and runs via {@code POST /admin/index}, so that an agent cannot start a reindex.
 * <p>
 * Task 2 provides signatures and descriptions only; the actual logic follows in tasks 7 to 10.
 * <p>
 * An argument the caller got wrong is answered as a tool error carrying the reason, not as a
 * protocol level "internal error" — an agent can correct the former and only guess at the latter.
 * That is what {@link WrapBusinessError} does with the {@link IllegalArgumentException}s the
 * services below raise.
 */
@Singleton
@LoggedToolCall
@WrapBusinessError(IllegalArgumentException.class)
public class DocsMcpTools {

    private final IndexCatalog catalog;
    private final ClassSourceLookup classSources;
    private final DocSearchService search;

    public DocsMcpTools(IndexCatalog catalog, ClassSourceLookup classSources,
            DocSearchService search) {
        this.catalog = catalog;
        this.classSources = classSources;
        this.search = search;
    }

    @Tool(name = "list_projects", description = """
            List the indexed projects that are available. Source of truth for valid project \
            values - never guess a project name. Returns all of them unless limit is given; the \
            answer always reports the total and whether another page follows.""")
    public Page<String> listProjects(
            @ToolArg(required = false, defaultValue = "0",
                    description = "How many projects to skip") int offset,
            @ToolArg(required = false, description = """
                    Maximum number of projects to return, at most 500. Leave it out to get all of \
                    them, which is usually what you want.""") Integer limit) {
        return catalog.projects(offset, limit);
    }

    @Tool(name = "list_versions", description = """
            List the indexed versions available for one project, oldest first. Source of truth for \
            valid version values - never guess a version. Returns all of them unless limit is \
            given; a project rarely has enough versions to be worth paging. Documentation that \
            applies to every version is not a version and does not appear here; it shows up in \
            search results with version = null.""")
    public Page<String> listVersions(
            @ToolArg(description = "Project whose versions should be listed") String project,
            @ToolArg(required = false, defaultValue = "0",
                    description = "How many versions to skip") int offset,
            @ToolArg(required = false, description = """
                    Maximum number of versions to return, at most 500. Leave it out to get all of \
                    them, which is usually what you want.""") Integer limit) {
        return catalog.versions(project, offset, limit);
    }

    @Tool(name = "get_class_source", description = """
            Return the complete source code of a class. Pinned to exactly the given project and \
            version - it never falls back to another version, because source that merely looks \
            plausible is worse than an error. Only classes that came from a sources archive have \
            their source indexed.""")
    public String getClassSource(
            @ToolArg(description = "Project the class belongs to") String project,
            @ToolArg(description = "Version of the project") String version,
            @ToolArg(description = "Fully qualified class name, e.g. com.fasterxml.jackson.core.JsonParser") //
            String fullyQualifiedClassName) {
        return classSources.sourceOf(project, version, fullyQualifiedClassName);
    }

    @Tool(name = "search_docs", description = """
            Hybrid search (BM25 + kNN) across the Markdown, TXT, HTML, Javadoc, package doc and \
            Java source of one project. Results are always restricted to the given project and \
            version; cross-version hits have version = null.""")
    public List<SearchHit> searchDocs(
            @ToolArg(description = "Project to search in - obtain valid values via list_projects") //
            String project,
            @ToolArg(description = "Version of the project - obtain valid values via list_versions") //
            String version,
            @ToolArg(description = "Search query") String query,
            @ToolArg(required = false, defaultValue = "5",
                    description = "Maximum number of hits") int topK,
            @ToolArg(required = false, description = """
                    Optional filter on the chunk type: markdown, txt, html, javadoc, package-doc \
                    or source""") String type) {
        return search.search(project, version, query, topK,
                type == null || type.isBlank() ? null : ChunkType.fromWireName(type));
    }
}
