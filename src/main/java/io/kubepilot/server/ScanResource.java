package io.kubepilot.server;

import io.kubepilot.analysis.ScanOrchestrator;
import io.kubepilot.common.ScanReport;
import io.smallrye.common.annotation.RunOnVirtualThread;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@ApplicationScoped
@Path("/api/v1")
public class ScanResource {

    @Inject
    ScanOrchestrator orchestrator;

    @GET
    @Path("/scan")
    @Produces(MediaType.APPLICATION_JSON)
    @RunOnVirtualThread
    public ScanReport scan() {
        return orchestrator.scan();
    }
}