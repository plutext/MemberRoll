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
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jdbi.v3.core.Jdbi;

import java.util.Set;

/**
 * CR-010: the admin "new member" fast path — person, household and
 * membership created in one composite call instead of the four separate
 * register steps. One {@code Handle} transaction (the CR-003 rule — stores
 * compose, the resource owns the transaction): every failure inside the
 * transaction is a thrown {@link IllegalArgumentException} (400) or {@link
 * ConflictException} (409), never an early-return {@code Response} — that
 * would let JDBI commit a half-created member instead of rolling it back.
 */
@Path("admin/new-member")
@RolesAllowed("admin")
public class AdminNewMemberResource {

    private static final Set<String> RELATIONSHIP_TYPES =
            Set.of("MEMBER", "PARTNER", "DEPENDANT", "OTHER");

    private final Jdbi jdbi = Db.jdbi();
    private final PersonStore people = new PersonStore(jdbi);
    private final HouseholdStore households = new HouseholdStore(jdbi);
    private final MembershipStore memberships = new MembershipStore(jdbi);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(String body) {
        JsonObject request = Payloads.read(body);
        if (request == null) return badRequest("body must be a JSON object");

        PersonStore.Person personDraft;
        PersonStore.Person secondDraft = null;
        String secondRelationship = "PARTNER";
        long periodId;
        long typeId;
        String householdName;
        try {
            if (!request.containsKey("person") || request.isNull("person")) {
                throw new IllegalArgumentException("person is required");
            }
            personDraft = AdminPeopleResource.parseObject(request.getJsonObject("person"));
            periodId = Payloads.reqLong(request, "membershipPeriodId");
            typeId = Payloads.reqLong(request, "membershipTypeId");
            String name = Payloads.optString(request, "householdName");
            householdName = name != null ? name : personDraft.familyName() + " household";
            if (request.containsKey("secondPerson") && !request.isNull("secondPerson")) {
                JsonObject sp = request.getJsonObject("secondPerson");
                secondDraft = AdminPeopleResource.parseObject(sp);
                String rel = Payloads.optString(sp, "relationship");
                if (rel != null) {
                    if (!RELATIONSHIP_TYPES.contains(rel)) {
                        throw new IllegalArgumentException("secondPerson.relationship must be one of " + RELATIONSHIP_TYPES);
                    }
                    secondRelationship = rel;
                }
            }
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }

        final PersonStore.Person second = secondDraft;
        final String relationship = secondRelationship;
        final long finalPeriodId = periodId;
        final long finalTypeId = typeId;
        final String finalHouseholdName = householdName;
        try {
            JsonObject created = jdbi.inTransaction(handle -> {
                MembershipStore.TypeBounds bounds = MembershipStore.typeBounds(handle, finalTypeId)
                        .orElseThrow(() -> new IllegalArgumentException("no such membershipTypeId"));
                if (!PeriodStore.exists(handle, finalPeriodId)) {
                    throw new IllegalArgumentException("no such membershipPeriodId");
                }
                // maximumPeople caps formal (relationship=MEMBER) members only — a
                // PARTNER/DEPENDANT/OTHER second person receives membership benefits
                // without being a second formal member (2026-07-18 correction; see
                // MembershipStore.insertMembershipPerson), so e.g. a SINGLE
                // membership (max 1) may still record a non-voting partner.
                // minimumPeople stays headcount-based — it warns about the
                // household being under-occupied for the type, not under-voted.
                int peopleCount = second != null ? 2 : 1;
                int memberCount = 1 + (second != null && "MEMBER".equals(relationship) ? 1 : 0);
                if (bounds.maximumPeople() != null && memberCount > bounds.maximumPeople()) {
                    throw new IllegalArgumentException(bounds.name() + " allows at most "
                            + bounds.maximumPeople() + (bounds.maximumPeople() == 1 ? " formal member" : " formal members")
                            + " (" + memberCount + " given)");
                }
                boolean underMinimum = bounds.minimumPeople() != null && peopleCount < bounds.minimumPeople();

                PersonStore.Person p1 = people.create(handle, personDraft);
                HouseholdStore.Household household = households.create(handle, finalHouseholdName, p1.id())
                        .orElseThrow(() -> new IllegalStateException("primary contact just created"));
                Long secondPersonId = null;
                if (second != null) {
                    PersonStore.Person p2 = people.create(handle, second);
                    households.addPerson(handle, household.id(), p2.id(), relationship);
                    secondPersonId = p2.id();
                }

                MembershipStore.CreateOutcome outcome =
                        memberships.createForHousehold(handle, household.id(), finalPeriodId, finalTypeId, null);

                JsonArrayBuilder warnings = Json.createArrayBuilder();
                if (underMinimum) {
                    warnings.add(bounds.name() + " normally has at least " + bounds.minimumPeople()
                            + " people — add the second person now, or later via household detail"
                            + " (composition is not retroactively copied onto this membership).");
                }
                if (outcome.lateJoiningWarning()) {
                    warnings.add("today is past the period's late-joining cutoff — consider the next period");
                }
                JsonArrayBuilder personIds = Json.createArrayBuilder().add(p1.id());
                if (secondPersonId != null) personIds.add(secondPersonId);
                return Json.createObjectBuilder()
                        .add("householdId", household.id())
                        .add("membershipId", outcome.membershipId())
                        .add("personIds", personIds)
                        .add("status", outcome.status())
                        .add("amountDueCents", outcome.amountDueCents())
                        .add("warnings", warnings)
                        .build();
            });
            return Response.status(Response.Status.CREATED).entity(created.toString()).build();
        } catch (ConflictException e) {
            return conflict(e.getMessage());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Json.createObjectBuilder().add("error", message).build().toString()).build();
    }

    private static Response conflict(String message) {
        return Response.status(Response.Status.CONFLICT)
                .entity(Json.createObjectBuilder().add("error", message).build().toString()).build();
    }
}
