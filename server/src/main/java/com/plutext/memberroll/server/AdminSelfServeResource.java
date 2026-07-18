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

import jakarta.annotation.security.RolesAllowed;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jdbi.v3.core.Jdbi;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * CR-006 provisioning: the register pushes identity into Keycloak, never
 * the other way round. An admin-triggered, idempotent batch — safe after a
 * CR-002 import, a CR-010 walk-in, or on a whim; re-running heals any
 * partial failure. Deliberately NOT wired into the import/new-member
 * transactions: a Keycloak REST call cannot roll back, so provisioning is a
 * follow-up step, and the ordering inside each candidate is Keycloak first,
 * DB second — if the run dies between, the next run's ADOPT branch heals it
 * (do not "optimize" this into DB-first).
 *
 * The report is the admin's data-quality tool: CONFLICT_HOUSEHOLDS,
 * SHARED_ADDRESS, SKIPPED_UNVERIFIED and CONFLICT_SUBJECT each name a
 * different fix — they are deliberately not collapsed into "skipped".
 */
@Path("admin/self-serve")
@RolesAllowed("admin")
public class AdminSelfServeResource {

    private static final Logger LOG = Logger.getLogger(AdminSelfServeResource.class.getName());

    private final Jdbi jdbi = Db.jdbi();

    @POST
    @Path("preview")
    @Produces(MediaType.APPLICATION_JSON)
    public Response preview() {
        return run(false);
    }

    @POST
    @Path("provision")
    @Produces(MediaType.APPLICATION_JSON)
    public Response provision() {
        return run(true);
    }

    /** One resolved candidate: the planned (or applied) action, per the CR-006 table. */
    private record Row(long personId, String name, String email, String action, String detail,
                       String adoptSubject) {
        Row(long personId, String name, String email, String action, String detail) {
            this(personId, name, email, action, detail, null);
        }
    }

    private Response run(boolean apply) {
        List<SelfServeStore.Candidate> candidates =
                jdbi.withHandle(SelfServeStore::candidates);

        // group carriers by address for the conflict/attribution rules; a person
        // in two current MEMBER households appears twice and their own address
        // then spans two households — a CONFLICT_HOUSEHOLDS by principle 4
        Map<String, List<SelfServeStore.Candidate>> byEmail = new HashMap<>();
        for (SelfServeStore.Candidate c : candidates) {
            byEmail.computeIfAbsent(c.email(), k -> new ArrayList<>()).add(c);
        }

        KeycloakAdmin keycloak = KeycloakAdmin.instance();
        List<Row> rows = new ArrayList<>();
        java.util.Set<Long> seenPeople = new java.util.HashSet<>();
        for (SelfServeStore.Candidate c : candidates) {
            if (!seenPeople.add(c.personId())) continue; // one row per person
            String name = (c.givenName() + " " + c.familyName()).trim();
            try {
                rows.add(resolve(keycloak, c, name, byEmail.get(c.email())));
            } catch (IOException e) {
                // one candidate's Keycloak failure must not sink the batch —
                // report it and let a re-run heal it (nothing was half-linked:
                // the DB write only happens after every Keycloak step)
                LOG.log(Level.WARNING, "provisioning resolution failed for person #" + c.personId(), e);
                rows.add(new Row(c.personId(), name, c.email(), "ERROR", e.getMessage()));
            }
        }

        if (apply) {
            List<Row> applied = new ArrayList<>(rows.size());
            for (Row row : rows) {
                applied.add(switch (row.action()) {
                    case "CREATE", "ADOPT" -> execute(keycloak, row);
                    default -> row;
                });
            }
            rows = applied;
        }
        return Response.ok(reportJson(rows).toString()).build();
    }

    /** The CR-006 resolution table, first match wins. */
    private Row resolve(KeycloakAdmin keycloak, SelfServeStore.Candidate c, String name,
                        List<SelfServeStore.Candidate> carriers) throws IOException {
        if (c.linkedSubject() != null) {
            return new Row(c.personId(), name, c.email(), "ALREADY_LINKED", null);
        }
        long distinctHouseholds = carriers.stream()
                .map(SelfServeStore.Candidate::householdId).distinct().count();
        if (distinctHouseholds > 1) {
            return new Row(c.personId(), name, c.email(), "CONFLICT_HOUSEHOLDS",
                    "address in use in " + distinctHouseholds + " households — fix the data, then re-run");
        }
        long distinctPeople = carriers.stream()
                .map(SelfServeStore.Candidate::personId).distinct().count();
        if (distinctPeople > 1) {
            // shared within the one household: primary contact wins, else lower person id
            long attributed = carriers.stream()
                    .filter(SelfServeStore.Candidate::primaryContact)
                    .map(SelfServeStore.Candidate::personId)
                    .findFirst()
                    .orElseGet(() -> carriers.stream()
                            .mapToLong(SelfServeStore.Candidate::personId).min().orElseThrow());
            if (c.personId() != attributed) {
                return new Row(c.personId(), name, c.email(), "SHARED_ADDRESS",
                        "the address's account belongs to person #" + attributed);
            }
        }
        JsonArray existing = keycloak.findUsersByEmail(c.email());
        if (!existing.isEmpty()) {
            JsonObject user = existing.getJsonObject(0);
            if (!user.getBoolean("emailVerified", false)) {
                // never link an unconfirmed mailbox claim — someone may have
                // self-registered with an address they don't control
                return new Row(c.personId(), name, c.email(), "SKIPPED_UNVERIFIED",
                        "a Keycloak account with this address exists but has not verified it");
            }
            String subject = user.getString("id");
            Optional<Long> owner = jdbi.withHandle(h -> SelfServeStore.personIdLinkedTo(h, subject));
            if (owner.isPresent() && owner.get() != c.personId()) {
                return new Row(c.personId(), name, c.email(), "CONFLICT_SUBJECT",
                        "that account is already linked to person #" + owner.get());
            }
            return new Row(c.personId(), name, c.email(), "ADOPT", null, subject);
        }
        return new Row(c.personId(), name, c.email(), "CREATE", null);
    }

    /** Apply one CREATE/ADOPT: Keycloak first (account + claim), DB link second. */
    private Row execute(KeycloakAdmin keycloak, Row row) {
        try {
            String subject;
            if (row.adoptSubject() != null) {
                subject = row.adoptSubject();
            } else {
                record Names(String given, String family) {}
                Names n = jdbi.withHandle(h -> h.createQuery(
                        "SELECT given_name, family_name FROM person WHERE person_id = :id")
                        .bind("id", row.personId())
                        .map((rs, ctx) -> new Names(rs.getString("given_name"), rs.getString("family_name")))
                        .one());
                subject = keycloak.createUser(row.email(), row.email(), n.given(), n.family(), true);
            }
            // claimed_role via the claim mechanism — exactly the MeResource path,
            // never a bare role grant (reconciliation would revert it); the sync
            // resets role_verified, so the verified=true write comes after it:
            // the register vouching for the person IS verification
            keycloak.updateAttributes(subject,
                    java.util.Collections.singletonMap(KeycloakAdmin.CLAIM_ATTRIBUTE, "member"));
            keycloak.syncClaim(subject, "member");
            keycloak.updateAttributes(subject, Map.of(KeycloakAdmin.VERIFIED_ATTRIBUTE, "true"));
            jdbi.useHandle(h -> SelfServeStore.link(h, row.personId(), subject));
            return row;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "provisioning failed for person #" + row.personId(), e);
            return new Row(row.personId(), row.name(), row.email(), "ERROR", e.getMessage());
        }
    }

    private static JsonObject reportJson(List<Row> rows) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        JsonArrayBuilder out = Json.createArrayBuilder();
        for (Row r : rows) {
            counts.merge(r.action(), 1, Integer::sum);
            JsonObjectBuilder b = Json.createObjectBuilder()
                    .add("personId", r.personId())
                    .add("name", r.name())
                    .add("email", r.email())
                    .add("action", r.action());
            AdminPeopleResource.addNullable(b, "detail", r.detail());
            out.add(b);
        }
        JsonObjectBuilder countsJson = Json.createObjectBuilder();
        counts.forEach(countsJson::add);
        return Json.createObjectBuilder().add("counts", countsJson).add("rows", out).build();
    }
}
