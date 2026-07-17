/*
 * Copyright 2026 Jason Harrop
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.plutext.memberroll.server;

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
                AdminPeopleResource.class, AdminHouseholdsResource.class,
                AuthFilter.class, RolesAllowedDynamicFeature.class);
    }
}
