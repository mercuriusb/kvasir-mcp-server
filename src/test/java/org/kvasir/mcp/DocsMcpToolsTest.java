package org.kvasir.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.kvasir.scan.ScanService;
import org.kvasir.scan.ScanStatus;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkiverse.mcp.server.test.McpAssured.ToolInfo;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import io.vertx.core.json.JsonObject;

/**
 * Verifies the acceptance criteria of task 2 over the real MCP protocol (streamable HTTP) — the
 * same route the MCP Inspector takes.
 */
@QuarkusTest
class DocsMcpToolsTest {

    @Inject
    ScanService scanService;

    private void indexTheFixture() {
        ScanStatus status = scanService.startScan(null, null).scan();
        Awaitility.await().atMost(2, TimeUnit.MINUTES)
                .until(() -> status.getState() != ScanStatus.State.RUNNING);
    }

    /** Acceptance criterion: all four tools are visible via {@code tools/list}. */
    @Test
    void allFourToolsAreListed() {
        McpAssured.newConnectedStreamableClient()
                .when()
                .toolsList(page -> {
                    assertEquals(4, page.size());
                    for (String name : List.of("list_projects", "list_versions", "get_class_source",
                            "search_docs")) {
                        ToolInfo tool = page.findByName(name);
                        assertNotNull(tool, "tool missing from tools/list: " + name);
                        assertFalse(tool.description().isBlank(), "description missing: " + name);
                    }
                })
                .thenAssertResults();
    }

    /**
     * Acceptance criterion: {@code list_versions} requires {@code project}, {@code list_projects}
     * takes no parameters.
     */
    @Test
    void requiredParametersMatchTheSpec() {
        McpAssured.newConnectedStreamableClient()
                .when()
                .toolsList(page -> {
                    // list_projects is paged, but both paging arguments are optional, so calling
                    // it without any argument at all still has to work
                    assertEquals(List.of(), requiredArgs(page.findByName("list_projects")));
                    assertEquals(List.of("offset", "limit"),
                            properties(page.findByName("list_projects")).fieldNames().stream().toList());

                    assertEquals(List.of("project"), requiredArgs(page.findByName("list_versions")));

                    assertEquals(List.of("project", "version", "fullyQualifiedClassName"),
                            requiredArgs(page.findByName("get_class_source")));

                    // per CLAUDE.md topK and type are optional, and topK defaults to 5
                    ToolInfo searchDocs = page.findByName("search_docs");
                    assertEquals(List.of("project", "version", "query"), requiredArgs(searchDocs));
                    assertEquals(5, properties(searchDocs).getJsonObject("topK").getInteger("default"));
                    assertNotNull(properties(searchDocs).getJsonObject("type"));
                })
                .thenAssertResults();
    }

    /**
     * Acceptance criterion from task 2: every tool can be called individually and answers without
     * throwing. The ones still unimplemented return their placeholder; list_projects has since been
     * implemented and answers from the index, so only its well-formedness is checked here.
     */
    @Test
    void everyToolIsCallableAndReturnsAPlaceholder() {
        McpStreamableTestClient client = McpAssured.newConnectedStreamableClient();
        client.when()
                .toolsCall("list_projects", response -> assertFalse(response.isError()))
                .toolsCall("list_versions", Map.of("project", "jackson"),
                        response -> assertFalse(response.isError()))
                // no sources archive in the fixture, so this is the not-found path - which still
                // has to be a readable tool error rather than a protocol failure
                .toolsCall("get_class_source",
                        Map.of("project", "demo", "version", "1.0.0",
                                "fullyQualifiedClassName", "com.example.Nope"),
                        response -> {
                            assertTrue(response.isError());
                            assertTrue(response.content().getFirst().asText().text()
                                    .contains("com.example.Nope"));
                        })
                .toolsCall("search_docs",
                        Map.of("project", "jackson", "version", "2.22.1", "query", "polymorphism"),
                        response -> {
                            assertFalse(response.isError());
                            assertTrue(response.content().isEmpty());
                        })
                // the same tool, now with the optional parameters supplied
                .toolsCall("search_docs",
                        Map.of("project", "jackson", "version", "2.22.1", "query", "polymorphism",
                                "topK", 3, "type", "javadoc"),
                        response -> assertFalse(response.isError()))
                .thenAssertResults();
    }

    /**
     * list_projects now answers from the index, so its result depends on what has been indexed. The
     * fixture holds two projects; both have to come back, and nothing internal with them.
     */
    @Test
    void listProjectsReturnsTheIndexedProjects() {
        indexTheFixture();

        McpAssured.newConnectedStreamableClient()
                .when()
                .toolsCall("list_projects", response -> {
                    assertFalse(response.isError());
                    JsonObject page = pageOf(response);
                    List<String> projects = page.getJsonArray("items").stream()
                            .map(String.class::cast)
                            .toList();
                    assertTrue(projects.containsAll(List.of("demo", "other")), projects.toString());
                    assertEquals(projects.size(), projects.stream().distinct().count(),
                            "no duplicates: " + projects);
                    assertEquals(projects.size(), page.getInteger("total"));
                    assertFalse(page.getBoolean("hasMore"));
                })
                .thenAssertResults();
    }

    /**
     * The list can grow, so it is paged. The agent has to be able to tell a short complete list
     * from a truncated one, which is what total and hasMore are for.
     */
    @Test
    void listProjectsIsPagedAndSaysWhetherMoreFollows() {
        indexTheFixture();

        McpAssured.newConnectedStreamableClient()
                .when()
                .toolsCall("list_projects", Map.of("offset", 0, "limit", 1), response -> {
                    JsonObject page = pageOf(response);
                    assertEquals(1, page.getJsonArray("items").size());
                    assertEquals(2, page.getInteger("total"));
                    assertTrue(page.getBoolean("hasMore"));
                })
                .toolsCall("list_projects", Map.of("offset", 1, "limit", 1), response -> {
                    JsonObject page = pageOf(response);
                    assertEquals(2, page.getInteger("total"));
                    assertFalse(page.getBoolean("hasMore"), "nothing follows the last page");
                })
                .thenAssertResults();
    }

    /**
     * list_versions answers from the index too, and its order is the semantic one — alphabetically
     * 1.10.0 would land before 1.9.0.
     */
    @Test
    void listVersionsReturnsTheVersionsOfOneProjectInSemanticOrder() {
        indexTheFixture();

        McpAssured.newConnectedStreamableClient()
                .when()
                // no limit given, so all three come back in one go
                .toolsCall("list_versions", Map.of("project", "demo"), response -> {
                    assertFalse(response.isError());
                    JsonObject page = pageOf(response);
                    assertEquals(List.of("1.0.0", "1.9.0", "1.10.0"),
                            page.getJsonArray("items").getList());
                    assertEquals(3, page.getInteger("total"));
                    assertFalse(page.getBoolean("hasMore"));
                })
                .toolsCall("list_versions", Map.of("project", "does-not-exist"), response -> {
                    assertFalse(response.isError(), "an unknown project is empty, not an error");
                    assertEquals(0, pageOf(response).getInteger("total"));
                })
                .thenAssertResults();
    }

    /** Nonsense paging arguments come back as an error response, not as a broken page. */
    @Test
    void anInvalidPageRequestIsReportedAsAnError() {
        McpAssured.newConnectedStreamableClient()
                .when()
                .toolsCall("list_projects", Map.of("limit", 0), response -> {
                    assertTrue(response.isError());
                    assertTrue(response.content().getFirst().asText().text().contains("limit"),
                            response.content().getFirst().asText().text());
                })
                .thenAssertResults();
    }

    private static JsonObject pageOf(io.quarkiverse.mcp.server.ToolResponse response) {
        return new JsonObject(response.content().getFirst().asText().text());
    }

    /** A missing required argument is reported as an error response, not as an exception. */
    @Test
    void missingRequiredArgumentYieldsErrorResponse() {
        McpAssured.newConnectedStreamableClient()
                .when()
                .toolsCall("list_versions", Map.of(), response -> {
                    assertTrue(response.isError());
                    assertTrue(response.content().getFirst().asText().text().contains("project"));
                })
                .thenAssertResults();
    }

    private static List<String> requiredArgs(ToolInfo tool) {
        return tool.inputSchema().getJsonArray("required").stream().map(String.class::cast).toList();
    }

    private static JsonObject properties(ToolInfo tool) {
        return tool.inputSchema().getJsonObject("properties");
    }
}
