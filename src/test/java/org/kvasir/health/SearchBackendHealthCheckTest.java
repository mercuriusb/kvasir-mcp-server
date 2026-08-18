package org.kvasir.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.hibernate.search.mapper.pojo.standalone.mapping.SearchMapping;
import org.hibernate.search.util.common.SearchException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The failure path of the readiness check, which cannot be produced against the real cluster: the
 * application does not even start when the backend is unreachable, so the only way into this branch
 * is a backend that disappears while running — during a rolling restart of OpenSearch, say.
 * <p>
 * A dynamic proxy stands in for the mapping rather than a mock framework, which this build does not
 * carry. It throws on every call, which is exactly what an unreachable backend does.
 */
class SearchBackendHealthCheckTest {

    private static SearchMapping failingWith(RuntimeException failure) {
        return (SearchMapping) Proxy.newProxyInstance(
                SearchMapping.class.getClassLoader(),
                new Class<?>[] { SearchMapping.class },
                (proxy, method, args) -> {
                    throw failure;
                });
    }

    @Test
    @DisplayName("reports DOWN when the search backend does not answer")
    void reportsDownWhenBackendIsUnreachable() {
        SearchException failure = new SearchException("HSEARCH400007: Elasticsearch request failed");
        HealthCheckResponse response = new SearchBackendHealthCheck(failingWith(failure)).call();

        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus(),
                "an unreachable backend must not be reported as ready");
    }

    @Test
    @DisplayName("names the cause, so the probe response is worth reading")
    void reportsTheCause() {
        SearchException failure = new SearchException("connection refused");
        HealthCheckResponse response = new SearchBackendHealthCheck(failingWith(failure)).call();

        String data = String.valueOf(response.getData().orElseThrow().get("error"));
        assertTrue(data.contains("connection refused"),
                "expected the failure message in the response data, was: " + data);
    }

    @Test
    @DisplayName("does not let the failure escape into the probe")
    void swallowsTheFailure() {
        // a health check that throws is reported by the runtime as a 500 without any detail, which
        // is strictly less useful than a DOWN carrying the reason.
        HealthCheckResponse response =
                new SearchBackendHealthCheck(failingWith(new IllegalStateException("boom"))).call();

        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
    }
}
