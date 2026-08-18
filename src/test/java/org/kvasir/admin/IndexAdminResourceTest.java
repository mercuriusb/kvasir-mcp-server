package org.kvasir.admin;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.TimeUnit;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

/**
 * Drives the admin endpoint over HTTP. Indexing is deliberately reachable only this way and not
 * through MCP, so that an agent cannot trigger a reindex.
 */
@QuarkusTest
class IndexAdminResourceTest {

    private static final String INDEX = "/admin/index";
    private static final String STATUS = "/admin/index/status";

    private void awaitFinished(String scanId) {
        Awaitility.await().atMost(2, TimeUnit.MINUTES).until(() -> !"RUNNING".equals(
                given().queryParam("scan-id", scanId).when().get(STATUS)
                        .then().statusCode(200).extract().path("status")));
    }

    /** Triggers a scan and returns its id once it is no longer running. */
    private String scanAndWait(String query) {
        String scanId = given()
                .when().post(INDEX + query)
                .then().statusCode(202)
                .body("scanId", notNullValue())
                .body("status", equalTo("RUNNING"))
                .extract().path("scanId");

        awaitFinished(scanId);
        return scanId;
    }

    @Test
    void aScanIsAcceptedImmediatelyAndRunsInTheBackground() {
        String scanId = scanAndWait("");

        given().queryParam("scan-id", scanId)
                .when().get(STATUS)
                .then().statusCode(200)
                .body("scanId", equalTo(scanId))
                .body("status", equalTo("COMPLETED"))
                .body("project", nullValue())
                .body("version", nullValue())
                .body("startedAt", notNullValue())
                .body("finishedAt", notNullValue())
                .body("failedFiles", equalTo(0));
    }

    @Test
    void narrowingToAProjectVersionIsReflectedInTheStatus() {
        String scanId = scanAndWait("?project=demo&version=1.0.0");

        given().queryParam("scan-id", scanId)
                .when().get(STATUS)
                .then().statusCode(200)
                .body("project", equalTo("demo"))
                .body("version", equalTo("1.0.0"))
                .body("scannedFiles", equalTo(2));
    }

    @Test
    void theStatusEndpointWithoutParametersListsEveryScan() {
        String scanId = scanAndWait("?project=other");

        given().when().get(STATUS)
                .then().statusCode(200)
                .body("scanId", hasItem(scanId));
    }

    @Test
    void anUnknownScanIdIsNotFound() {
        given().queryParam("scan-id", "does-not-exist")
                .when().get(STATUS)
                .then().statusCode(404)
                .body("message", equalTo("unknown scan-id: does-not-exist"));
    }

    @Test
    void aVersionWithoutAProjectIsRejected() {
        given().queryParam("version", "1.0.0")
                .when().post(INDEX)
                .then().statusCode(400)
                .body("message", equalTo("version requires project"));
    }

    /** Re-running an unchanged scan must skip everything rather than write it again. */
    @Test
    void aSecondScanSkipsEverything() {
        scanAndWait("?project=demo");
        String scanId = scanAndWait("?project=demo");

        given().queryParam("scan-id", scanId)
                .when().get(STATUS)
                .then().statusCode(200)
                .body("indexedFiles", equalTo(0))
                .body("skippedFiles", equalTo(5))
                .body("indexedChunks", equalTo(0));
    }

    /**
     * A second scan for a scope that is already being worked on is refused, and the answer names
     * the scan to watch instead of leaving the caller to guess.
     */
    @Test
    void aScanThatCollidesWithARunningOneIsRefusedWithAPointerToIt() {
        String runningId = given()
                .when().post(INDEX)
                .then().statusCode(202)
                .extract().path("scanId");

        // the full scan covers everything, so any narrower request has to bounce off it
        given().queryParam("project", "demo")
                .when().post(INDEX)
                .then().statusCode(409)
                .body("message", containsString("the whole data directory"))
                .body("message", containsString(runningId))
                .body("runningScan.scanId", equalTo(runningId))
                .body("runningScan.status", equalTo("RUNNING"));

        awaitFinished(runningId);

        // once it is done the same request goes through; waited for so the test leaves nothing
        // running that the next one would collide with
        awaitFinished(given().queryParam("project", "demo")
                .when().post(INDEX)
                .then().statusCode(202)
                .extract().path("scanId"));
    }

    /**
     * The counterpart to the MCP side: no tool may exist that starts indexing. The tool list is
     * checked in DocsMcpToolsTest; here we make sure the admin path is not served under /mcp.
     */
    @Test
    void theAdminPathIsSeparateFromTheMcpTransport() {
        int status = given().contentType(ContentType.JSON)
                .when().post("/mcp/admin/index")
                .then().extract().statusCode();

        assertEquals(404, status, "the admin endpoint must not hang below the MCP path");

        given().when().get(STATUS).then().statusCode(200).body("$", not(nullValue()));
    }
}
