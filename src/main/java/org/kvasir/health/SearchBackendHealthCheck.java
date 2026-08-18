package org.kvasir.health;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.hibernate.search.mapper.pojo.standalone.mapping.SearchMapping;
import org.hibernate.search.mapper.pojo.standalone.session.SearchSession;
import org.jboss.logging.Logger;
import org.kvasir.entity.DocChunk;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Readiness: can this instance answer a search at all?
 *
 * <h2>Why this exists</h2>
 * Hibernate Search Standalone contributes no health check of its own — with the health extension
 * added and nothing else done, {@code /q/health} reports {@code UP} with an empty check list. That
 * reading is worthless to Kubernetes: it says the process is alive, not that it can serve a single
 * request. Without this check a pod whose search backend is unreachable would receive traffic and
 * fail every call.
 *
 * <h2>Why a count and not a ping</h2>
 * The query goes through the same path a real search takes: session, index, backend. Asking the
 * cluster whether it is alive would prove less — the cluster can be perfectly healthy while this
 * application's index is missing, which is exactly the state after a restore that brought back
 * only the fingerprint index. A count over the chunk index fails in that case, as it should.
 * <p>
 * Total hit count only, no documents fetched. That is one cheap request per probe interval.
 *
 * <h2>Why readiness and not liveness</h2>
 * Deliberately not {@code @Liveness}: an unreachable OpenSearch is not something restarting this
 * pod repairs. As a liveness check it would produce a crash loop that hides the real fault and adds
 * a second one. Readiness is the honest signal — take this instance out of the load balancer, leave
 * it running, let it recover when the backend does.
 * <p>
 * The data directory is deliberately not part of this either, for the same reason it does not fail
 * the startup: searching never touches it. Only indexing does, and an unreachable object store must
 * not take retrieval down with it.
 */
@Readiness
@ApplicationScoped
public class SearchBackendHealthCheck implements HealthCheck {

    private static final Logger LOG = Logger.getLogger(SearchBackendHealthCheck.class);

    private static final String NAME = "search backend";

    private final SearchMapping searchMapping;

    public SearchBackendHealthCheck(SearchMapping searchMapping) {
        this.searchMapping = searchMapping;
    }

    @Override
    public HealthCheckResponse call() {
        try (SearchSession session = searchMapping.createSession()) {
            long chunks = session.search(DocChunk.class)
                    .where(f -> f.matchAll())
                    .fetchTotalHitCount();
            return HealthCheckResponse.builder()
                    .name(NAME)
                    .up()
                    .withData("indexedChunks", chunks)
                    .build();
        } catch (RuntimeException e) {
            // logged as well as reported: the probe response is read by Kubernetes, not by a
            // person, and without a log line the reason is gone by the time anyone looks.
            LOG.warnf(e, "Readiness check failed: the search backend did not answer");
            return HealthCheckResponse.builder()
                    .name(NAME)
                    .down()
                    .withData("error", String.valueOf(e.getMessage()))
                    .build();
        }
    }
}
