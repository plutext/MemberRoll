package com.example.myapp.server;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The @RolesAllowed pattern demonstrated on the smallest possible
 * resource: guests and non-admins get 403 from
 * RolesAllowedDynamicFeature before the method runs.
 */
@Path("admin/ping")
public class AdminPingResource {

    @GET
    @RolesAllowed("admin")
    @Produces(MediaType.APPLICATION_JSON)
    public String ping() {
        return "{\"admin\":true}";
    }
}
