package org.kvasir.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.Test;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Makes sure every MCP tool invocation ends up in the log with its tool name and arguments —
 * without that, there is no way to reconstruct afterwards what an agent asked for.
 */
@QuarkusTest
class ToolCallLoggingTest {

    @Test
    void toolCallsAreLoggedWithNameAndArguments() {
        List<String> logged = new CopyOnWriteArrayList<>();
        Logger logger = Logger.getLogger(ToolCallLogger.class.getName());
        Handler handler = collectInto(logged);
        logger.addHandler(handler);
        try {
            McpAssured.newConnectedStreamableClient()
                    .when()
                    .toolsCall("list_projects", response -> assertFalse(response.isError()))
                    .toolsCall("list_versions", Map.of("project", "jackson"),
                            response -> assertFalse(response.isError()))
                    .toolsCall("search_docs",
                            Map.of("project", "jackson", "version", "2.22.1", "query", "polymorphism"),
                            response -> assertFalse(response.isError()))
                    .thenAssertResults();
        } finally {
            logger.removeHandler(handler);
        }

        // an omitted limit means "no upper bound" and is visible as such in the log
        assertLogged(logged, "list_projects(offset=0, limit=null)");
        assertLogged(logged, "list_versions(project=jackson, offset=0, limit=null)");
        // topK is absent from the call and shows up in the log with its default of 5
        assertLogged(logged,
                "search_docs(project=jackson, version=2.22.1, query=polymorphism, topK=5, type=null)");
    }

    private static void assertLogged(List<String> logged, String expectedCall) {
        assertTrue(logged.stream().anyMatch(message -> message.contains(expectedCall)),
                () -> "no log entry for '" + expectedCall + "', logged instead: " + logged);
    }

    private static Handler collectInto(List<String> target) {
        return new Handler() {
            @Override
            public void publish(LogRecord record) {
                target.add(record instanceof ExtLogRecord ext ? ext.getFormattedMessage()
                        : record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
    }
}
