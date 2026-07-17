package com.example.myapp.server;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

import org.glassfish.jersey.server.filter.RolesAllowedDynamicFeature;

import java.util.Set;

/**
 * Jersey bootstrap: resources are registered explicitly (no classpath
 * scanning), and the servlet container picks this class up via Jersey's
 * ServletContainerInitializer - no web.xml. AuthFilter provides bearer
 * authentication; RolesAllowedDynamicFeature enforces @RolesAllowed.
 */
@ApplicationPath("/api")
public class ApiApplication extends Application {

    @Override
    public Set<Class<?>> getClasses() {
        return Set.of(HealthResource.class, WhoAmIResource.class,
                AdminPingResource.class, MeResource.class,
                AdminUsersResource.class, NotesResource.class,
                AuthFilter.class, RolesAllowedDynamicFeature.class);
    }
}
