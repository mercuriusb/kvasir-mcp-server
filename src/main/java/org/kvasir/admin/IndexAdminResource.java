package org.kvasir.admin;

import java.util.List;

import org.kvasir.scan.ScanRegistry;
import org.kvasir.scan.ScanService;
import org.kvasir.scan.ScanStatus;

import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Triggers indexing and reports on it.
 * <p>
 * Deliberately REST and deliberately <em>not</em> an MCP tool: indexing writes, can run for a long
 * time and is an administrative act. Leaving it out of the MCP surface means an agent cannot start
 * a reindex — neither by accident nor because something in its context talked it into one.
 * <p>
 * The path prefix {@code /admin} keeps it clearly apart from the MCP transport at {@code /mcp}, so
 * that it can later be put behind authentication on its own without touching MCP access.
 */
@Path("/admin/index")
@Produces(MediaType.APPLICATION_JSON)
public class IndexAdminResource {

    private final ScanService scanService;
    private final ScanRegistry registry;

    public IndexAdminResource(ScanService scanService, ScanRegistry registry) {
        this.scanService = scanService;
        this.registry = registry;
    }

    /**
     * Starts a scan and returns immediately; the work happens in the background.
     * <p>
     * Scans whose scopes cannot overlap run side by side — two projects, or two versions of one
     * project. A request that would collide with a running scan is refused with {@code 409} rather
     * than queued, and the answer carries the running scan so the caller can follow that one
     * instead of guessing.
     *
     * @param project optional, limits the scan to one project
     * @param version optional, limits it further to one version of that project
     * @param force   rebuild everything in scope, ignoring the recorded fingerprints. For the case
     *                where the index is known to be wrong but the fingerprints still say otherwise
     * @return {@code 202 Accepted} with the new scan's status, or {@code 409 Conflict} with the
     *         scan already covering that scope
     */
    @POST
    public Response startScan(@QueryParam("project") String project,
            @QueryParam("version") String version,
            @QueryParam("force") @DefaultValue("false") boolean force) {
        if (version != null && project == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("version requires project"))
                    .build();
        }

        ScanRegistry.Started started = scanService.startScan(project, version, force);
        ScanStatusResponse status = ScanStatusResponse.of(started.scan());
        if (started.isNew()) {
            return Response.accepted(status).build();
        }
        return Response.status(Response.Status.CONFLICT)
                .entity(new ScanConflictResponse(
                        "a scan covering %s is already running; follow it under /admin/index/status?scan-id=%s"
                                .formatted(scopeOf(started.scan()), started.scan().getScanId()),
                        status))
                .build();
    }

    private static String scopeOf(ScanStatus scan) {
        if (scan.getProject() == null) {
            return "the whole data directory";
        }
        return scan.getVersion() == null
                ? "project " + scan.getProject()
                : "project %s version %s".formatted(scan.getProject(), scan.getVersion());
    }

    /**
     * @param scanId optional; without it every known scan is returned, running and finished alike
     * @return the matching scans, or {@code 404} if the given id is unknown
     */
    @GET
    @Path("/status")
    public Response status(@QueryParam("scan-id") String scanId) {
        if (scanId == null) {
            List<ScanStatusResponse> all = registry.all().stream()
                    .map(ScanStatusResponse::of)
                    .toList();
            return Response.ok(all).build();
        }
        return registry.find(scanId)
                .map(status -> Response.ok(ScanStatusResponse.of(status)).build())
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND)
                        .entity(new ErrorResponse("unknown scan-id: " + scanId))
                        .build());
    }

    public record ErrorResponse(String message) {
    }

    /**
     * Answer to a scan request that collided with a running one: says what is wrong and hands over
     * the scan to watch instead.
     */
    public record ScanConflictResponse(String message, ScanStatusResponse runningScan) {
    }
}
