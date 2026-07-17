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

import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;

import java.util.stream.Collectors;

/**
 * "Any valid token" endpoint - answers who the server thinks the caller
 * is. The 401 challenge for guests is the pattern own-data resources
 * reuse (see NotesResource).
 */
@Path("whoami")
public class WhoAmIResource {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public String whoami(@Context SecurityContext security) {
        if (!(security.getUserPrincipal() instanceof UserPrincipal user)) {
            throw new NotAuthorizedException("Bearer realm=\"memberroll\"");
        }
        // verified is only reported true when the claim it verified is
        // actually among the granted roles — a just-changed, not-yet-synced
        // claim reads as unverified rather than stale-true.
        boolean verified = user.isRoleVerified() && user.getClaimedRole() != null
                && user.getRoles().contains(user.getClaimedRole());
        return "{\"sub\":\"" + escape(user.getName())
                + "\",\"username\":\"" + escape(user.getUsername())
                + "\",\"roles\":[" + user.getRoles().stream().sorted()
                        .map(r -> "\"" + escape(r) + "\"")
                        .collect(Collectors.joining(","))
                + "]"
                + (user.getClaimedRole() == null ? ""
                        : ",\"claimed_role\":\"" + escape(user.getClaimedRole()) + "\"")
                + ",\"verified\":" + verified + "}";
    }

    /** Minimal JSON string escaping for values we already control loosely. */
    static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
