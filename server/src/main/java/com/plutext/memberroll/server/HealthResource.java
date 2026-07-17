package com.plutext.memberroll.server;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Smoke endpoint: proves Jersey dispatch. The first thing to curl after
 * any deployment: {@code /server/api/health}.
 */
@Path("health")
public class HealthResource {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public String health() {
        return "{\"status\":\"ok\"}";
    }
}
