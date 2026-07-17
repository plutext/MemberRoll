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
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Smoke endpoint: proves Jersey dispatch and (since CR-001) that the
 * register database is still reachable. The first thing to curl after
 * any deployment: {@code /server/api/health}. Public, so no detail
 * beyond ok/degraded — a lost database is a 503 so monitors notice.
 */
@Path("health")
public class HealthResource {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response health() {
        boolean db = Db.probe();
        return Response.status(db ? 200 : 503)
                .entity(db ? "{\"status\":\"ok\",\"db\":\"ok\"}"
                           : "{\"status\":\"degraded\",\"db\":\"down\"}")
                .build();
    }
}
