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

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Segment email (CR-005): templates, the saved footer, the send log, and the
 * sequential sender that runs on {@link Mail}'s single mail thread. A "segment"
 * is not a new concept — it is exactly the CR-003 financial-status view (a
 * period plus the same optional status/type filters), so what the admin sees
 * in the Renewals table is literally what a send targets.
 *
 * The send-time contract mirrors the "no queue at this scale" decision
 * (2026-07-17): {@link #createSend} writes the send row and ALL recipient rows
 * (PENDING plus the SKIPPED_* and NO_EMAIL bookkeeping) in the caller's
 * transaction; {@link #startSending} then walks the PENDING rows one at a time
 * on the mail thread, minting a fresh pay link and rendering per recipient.
 * There is no retry machinery — a dead relay flips the send to ABORTED after 5
 * consecutive failures and the treasurer presses Resume.
 *
 * Reads/writes take the caller's {@link Handle} (CR-003 pattern); the sender
 * owns short transactions of its own off the request thread.
 */
final class EmailStore {

    static final String FOOTER_KEY = "email_footer";
    private static final int ABORT_AFTER_CONSECUTIVE_FAILURES = 5;
    private static final Logger LOG = Logger.getLogger(EmailStore.class.getName());

    private EmailStore() {}

    // ---- records ------------------------------------------------------------

    record Template(long id, String name, String subject, String body,
                    String updatedBy, OffsetDateTime updatedAt) {}

    /** One planned recipient row before it is written — status is PENDING or a SKIPPED_* and NO_EMAIL marker. */
    record PlannedRecipient(Long personId, String givenName, String familyName, String email, String status) {}

    record SegmentMembership(long membershipId, long householdId, String displayName,
                             String periodName, String typeName,
                             int amountDueCents, int amountPaidCents, List<PlannedRecipient> recipients) {}

    /** The full resolution of a segment: every membership with its planned rows, plus an SMS-seen flag. */
    record Segment(List<SegmentMembership> memberships, boolean smsSeen) {}

    record SendSummary(long id, Long templateId, String templateName, String subject, String body,
                       long periodId, String periodName, String statusFilter, Long typeFilter, String typeName,
                       String communicationType, String status, String createdBy,
                       OffsetDateTime createdAt, OffsetDateTime finishedAt, Map<String, Integer> counts) {}

    record RecipientRow(long id, long membershipId, String displayName, Long personId, String personName,
                        String email, String status, String error, Long renewalTokenId, OffsetDateTime sentAt) {}

    // ---- templates ----------------------------------------------------------

    static List<Template> listTemplates(Handle handle) {
        return handle.createQuery("SELECT email_template_id, name, subject, body, updated_by, updated_at"
                + " FROM email_template ORDER BY name")
                .map((rs, ctx) -> mapTemplate(rs)).list();
    }

    static Optional<Template> getTemplate(Handle handle, long id) {
        return handle.createQuery("SELECT email_template_id, name, subject, body, updated_by, updated_at"
                + " FROM email_template WHERE email_template_id = :id")
                .bind("id", id).map((rs, ctx) -> mapTemplate(rs)).findOne();
    }

    /** 409 (ConflictException) on a duplicate name; caller validates merge fields first. */
    static Template createTemplate(Handle handle, String name, String subject, String body, String updatedBy) {
        if (handle.createQuery("SELECT count(*) FROM email_template WHERE name = :name")
                .bind("name", name).mapTo(Integer.class).one() > 0) {
            throw new ConflictException("a template named '" + name + "' already exists");
        }
        long id = handle.createUpdate("INSERT INTO email_template (name, subject, body, updated_by)"
                + " VALUES (:name, :subject, :body, :by)")
                .bind("name", name).bind("subject", subject).bind("body", body).bind("by", updatedBy)
                .executeAndReturnGeneratedKeys("email_template_id").mapTo(Long.class).one();
        return getTemplate(handle, id).orElseThrow();
    }

    /** Update name/subject/body; empty if no such template. 409 if the new name collides with another. */
    static Optional<Template> updateTemplate(Handle handle, long id, String name, String subject,
                                             String body, String updatedBy) {
        if (getTemplate(handle, id).isEmpty()) return Optional.empty();
        if (handle.createQuery("SELECT count(*) FROM email_template WHERE name = :name AND email_template_id <> :id")
                .bind("name", name).bind("id", id).mapTo(Integer.class).one() > 0) {
            throw new ConflictException("a template named '" + name + "' already exists");
        }
        handle.createUpdate("UPDATE email_template SET name = :name, subject = :subject, body = :body,"
                + " updated_by = :by, updated_at = now() WHERE email_template_id = :id")
                .bind("name", name).bind("subject", subject).bind("body", body)
                .bind("by", updatedBy).bind("id", id).execute();
        return getTemplate(handle, id);
    }

    /** Delete; past sends keep their subject/body snapshot (email_send FK is SET NULL). */
    static boolean deleteTemplate(Handle handle, long id) {
        return handle.createUpdate("DELETE FROM email_template WHERE email_template_id = :id")
                .bind("id", id).execute() > 0;
    }

    // ---- footer (app_setting) ----------------------------------------------

    /** The saved global footer, or "" when none has been set. */
    static String getFooter(Handle handle) {
        return handle.createQuery("SELECT value FROM app_setting WHERE key = :k")
                .bind("k", FOOTER_KEY).mapTo(String.class).findOne().orElse("");
    }

    static void putFooter(Handle handle, String text, String updatedBy) {
        handle.createUpdate("INSERT INTO app_setting (key, value, updated_by) VALUES (:k, :v, :by)"
                + " ON CONFLICT (key) DO UPDATE SET value = :v, updated_by = :by, updated_at = now()")
                .bind("k", FOOTER_KEY).bind("v", text).bind("by", updatedBy).execute();
    }

    // ---- segment resolution -------------------------------------------------

    /**
     * Resolve a segment to planned recipient rows without writing anything.
     * Per membership: MEMBER-relationship current people only (PARTNER/
     * DEPENDANT/OTHER never receive segment mail — the voting-rights
     * correction), each resolved to a delivery method (person → household →
     * EMAIL default); EMAIL addresses are deduped within the membership
     * (couples share one — primary contact wins attribution, else lower person
     * id); a membership that yields no address and no skip is one NO_EMAIL row.
     */
    static Segment resolveSegment(Handle handle, long periodId, String statusFilter,
                                  Long typeFilterId, String communicationType) {
        StringBuilder where = new StringBuilder(" WHERE m.membership_period_id = :period");
        if (statusFilter != null) where.append(" AND m.status = :status");
        if (typeFilterId != null) where.append(" AND m.membership_type_id = :type");
        var q = handle.createQuery(
                "SELECT m.membership_id, m.household_id, h.primary_contact_person_id,"
                + " COALESCE(NULLIF(trim(h.household_name), ''),"
                + "          trim(pc.given_name || ' ' || pc.family_name)) AS display_name,"
                + " per.name AS period_name, mt.name AS type_name, m.amount_due_cents,"
                + " " + MembershipStore.PAID_SQL + " AS paid"
                + " FROM membership m"
                + " JOIN membership_period per ON per.membership_period_id = m.membership_period_id"
                + " JOIN membership_type mt ON mt.membership_type_id = m.membership_type_id"
                + " JOIN household h ON h.household_id = m.household_id"
                + " JOIN person pc ON pc.person_id = h.primary_contact_person_id"
                + where + " ORDER BY display_name NULLS LAST, m.membership_id")
                .bind("period", periodId);
        if (statusFilter != null) q.bind("status", statusFilter);
        if (typeFilterId != null) q.bind("type", typeFilterId);

        record Base(long membershipId, long householdId, long primaryContactPersonId, String displayName,
                    String periodName, String typeName, int amountDueCents, int amountPaidCents) {}
        List<Base> bases = q.map((rs, ctx) -> new Base(rs.getLong("membership_id"), rs.getLong("household_id"),
                rs.getLong("primary_contact_person_id"), rs.getString("display_name"),
                rs.getString("period_name"), rs.getString("type_name"),
                rs.getInt("amount_due_cents"), rs.getInt("paid"))).list();

        record Cand(long personId, String givenName, String familyName, String email) {}
        boolean[] smsSeen = {false};
        List<SegmentMembership> out = new ArrayList<>();
        for (Base b : bases) {
            List<Cand> cands = handle.createQuery(
                    "SELECT hp.person_id, pe.given_name, pe.family_name,"
                    + " (SELECT e.email FROM email_address e WHERE e.person_id = hp.person_id"
                    + "    ORDER BY e.is_primary DESC, e.email_id LIMIT 1) AS email"
                    + " FROM household_person hp JOIN person pe ON pe.person_id = hp.person_id"
                    + " WHERE hp.household_id = :hh AND hp.left_household_date IS NULL"
                    + "   AND hp.relationship_type = 'MEMBER'"
                    + " ORDER BY hp.person_id")
                    .bind("hh", b.householdId())
                    .map((rs, ctx) -> new Cand(rs.getLong("person_id"), rs.getString("given_name"),
                            rs.getString("family_name"), rs.getString("email"))).list();

            // dedup EMAIL recipients by address; skipped rows stay per-person
            LinkedHashMap<String, PlannedRecipient> byAddress = new LinkedHashMap<>();
            List<PlannedRecipient> skipped = new ArrayList<>();
            for (Cand c : cands) {
                String method = CommunicationPreferenceStore.resolve(
                        handle, c.personId(), b.householdId(), communicationType);
                switch (method) {
                    case "EMAIL" -> {
                        if (c.email() != null) {
                            PlannedRecipient existing = byAddress.get(c.email());
                            // primary contact wins attribution; otherwise the first
                            // seen wins, and candidates are ordered by ascending
                            // person id, so that is the lower id (schema-doc rule)
                            if (existing == null || c.personId() == b.primaryContactPersonId()) {
                                byAddress.put(c.email(), new PlannedRecipient(
                                        c.personId(), c.givenName(), c.familyName(), c.email(), "PENDING"));
                            }
                        }
                        // EMAIL preference with no address contributes nothing; it
                        // folds into the membership's NO_EMAIL only if nobody else sends/skips
                    }
                    case "POST" -> skipped.add(new PlannedRecipient(
                            c.personId(), c.givenName(), c.familyName(), orEmpty(c.email()), "SKIPPED_POST"));
                    case "SMS" -> {
                        smsSeen[0] = true; // not implemented (roadmap) — treated as NONE, warned in preview
                        skipped.add(new PlannedRecipient(
                                c.personId(), c.givenName(), c.familyName(), orEmpty(c.email()), "SKIPPED_NONE"));
                    }
                    default -> skipped.add(new PlannedRecipient( // NONE
                            c.personId(), c.givenName(), c.familyName(), orEmpty(c.email()), "SKIPPED_NONE"));
                }
            }
            List<PlannedRecipient> recipients = new ArrayList<>(byAddress.values());
            recipients.addAll(skipped);
            if (byAddress.isEmpty() && skipped.isEmpty()) {
                recipients.add(new PlannedRecipient(null, null, null, null, "NO_EMAIL"));
            }
            out.add(new SegmentMembership(b.membershipId(), b.householdId(), b.displayName(),
                    b.periodName(), b.typeName(), b.amountDueCents(), b.amountPaidCents(), recipients));
        }
        return new Segment(out, smsSeen[0]);
    }

    // ---- send creation ------------------------------------------------------

    /** True if any send is currently RUNNING — the one-at-a-time guard (POST answers 409). */
    static boolean anyRunning(Handle handle) {
        return handle.createQuery("SELECT count(*) FROM email_send WHERE status = 'RUNNING'")
                .mapTo(Integer.class).one() > 0;
    }

    /**
     * Insert the send (subject/body SNAPSHOT) and every recipient row in the
     * caller's transaction; returns the new send id. The snapshot is the
     * composed template+footer WITH its merge tokens intact — rendering is per
     * recipient at send time, and the log shows what was composed, not any
     * later template edit.
     */
    static long createSend(Handle handle, Long templateId, String subjectSnapshot, String bodySnapshot,
                           long periodId, String statusFilter, Long typeFilterId,
                           String communicationType, String createdBy, Segment segment) {
        long sendId = handle.createUpdate(
                "INSERT INTO email_send (email_template_id, subject, body, membership_period_id,"
                + " status_filter, type_filter, communication_type, created_by)"
                + " VALUES (:tpl, :subject, :body, :period, :status, :type, :ct, :by)")
                .bind("tpl", templateId).bind("subject", subjectSnapshot).bind("body", bodySnapshot)
                .bind("period", periodId).bind("status", statusFilter).bind("type", typeFilterId)
                .bind("ct", communicationType).bind("by", createdBy)
                .executeAndReturnGeneratedKeys("email_send_id").mapTo(Long.class).one();
        var insert = handle.prepareBatch(
                "INSERT INTO email_send_recipient (email_send_id, membership_id, person_id, email, status)"
                + " VALUES (:send, :membership, :person, :email, :status)");
        for (SegmentMembership sm : segment.memberships()) {
            for (PlannedRecipient r : sm.recipients()) {
                insert.bind("send", sendId).bind("membership", sm.membershipId())
                        .bind("person", r.personId()).bind("email", r.email()).bind("status", r.status())
                        .add();
            }
        }
        insert.execute();
        return sendId;
    }

    // ---- send log reads -----------------------------------------------------

    static List<SendSummary> listSends(Handle handle) {
        List<SendSummary> sends = handle.createQuery(
                "SELECT s.email_send_id, s.email_template_id, t.name AS template_name, s.subject, s.body,"
                + " s.membership_period_id, per.name AS period_name, s.status_filter, s.type_filter,"
                + " mt.name AS type_name, s.communication_type, s.status, s.created_by, s.created_at, s.finished_at"
                + " FROM email_send s"
                + " JOIN membership_period per ON per.membership_period_id = s.membership_period_id"
                + " LEFT JOIN email_template t ON t.email_template_id = s.email_template_id"
                + " LEFT JOIN membership_type mt ON mt.membership_type_id = s.type_filter"
                + " ORDER BY s.email_send_id DESC")
                .map((rs, ctx) -> mapSend(rs, Map.of())).list();
        List<SendSummary> withCounts = new ArrayList<>(sends.size());
        for (SendSummary s : sends) {
            withCounts.add(new SendSummary(s.id(), s.templateId(), s.templateName(), s.subject(), s.body(),
                    s.periodId(), s.periodName(), s.statusFilter(), s.typeFilter(), s.typeName(),
                    s.communicationType(), s.status(), s.createdBy(), s.createdAt(), s.finishedAt(),
                    countsFor(handle, s.id())));
        }
        return withCounts;
    }

    static Optional<SendSummary> getSend(Handle handle, long id) {
        return handle.createQuery(
                "SELECT s.email_send_id, s.email_template_id, t.name AS template_name, s.subject, s.body,"
                + " s.membership_period_id, per.name AS period_name, s.status_filter, s.type_filter,"
                + " mt.name AS type_name, s.communication_type, s.status, s.created_by, s.created_at, s.finished_at"
                + " FROM email_send s"
                + " JOIN membership_period per ON per.membership_period_id = s.membership_period_id"
                + " LEFT JOIN email_template t ON t.email_template_id = s.email_template_id"
                + " LEFT JOIN membership_type mt ON mt.membership_type_id = s.type_filter"
                + " WHERE s.email_send_id = :id")
                .bind("id", id)
                .map((rs, ctx) -> mapSend(rs, Map.of())).findOne()
                .map(s -> new SendSummary(s.id(), s.templateId(), s.templateName(), s.subject(), s.body(),
                        s.periodId(), s.periodName(), s.statusFilter(), s.typeFilter(), s.typeName(),
                        s.communicationType(), s.status(), s.createdBy(), s.createdAt(), s.finishedAt(),
                        countsFor(handle, id)));
    }

    static List<RecipientRow> recipients(Handle handle, long sendId) {
        return handle.createQuery(
                "SELECT r.email_send_recipient_id, r.membership_id,"
                + " COALESCE(NULLIF(trim(h.household_name), ''),"
                + "          trim(pc.given_name || ' ' || pc.family_name)) AS display_name,"
                + " r.person_id, trim(pe.given_name || ' ' || pe.family_name) AS person_name,"
                + " r.email, r.status, r.error, r.renewal_token_id, r.sent_at"
                + " FROM email_send_recipient r"
                + " JOIN membership m ON m.membership_id = r.membership_id"
                + " JOIN household h ON h.household_id = m.household_id"
                + " JOIN person pc ON pc.person_id = h.primary_contact_person_id"
                + " LEFT JOIN person pe ON pe.person_id = r.person_id"
                + " WHERE r.email_send_id = :id ORDER BY r.email_send_recipient_id")
                .bind("id", sendId)
                .map((rs, ctx) -> new RecipientRow(rs.getLong("email_send_recipient_id"),
                        rs.getLong("membership_id"), rs.getString("display_name"),
                        (Long) rs.getObject("person_id"), rs.getString("person_name"),
                        rs.getString("email"), rs.getString("status"), rs.getString("error"),
                        (Long) rs.getObject("renewal_token_id"),
                        rs.getObject("sent_at", OffsetDateTime.class))).list();
    }

    static Map<String, Integer> countsFor(Handle handle, long sendId) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        handle.createQuery("SELECT status, count(*) AS n FROM email_send_recipient"
                + " WHERE email_send_id = :id GROUP BY status ORDER BY status")
                .bind("id", sendId)
                .map((rs, ctx) -> Map.entry(rs.getString("status"), rs.getInt("n")))
                .forEach(e -> counts.put(e.getKey(), e.getValue()));
        return counts;
    }

    // ---- resume -------------------------------------------------------------

    /**
     * Re-enqueue a send's PENDING and FAILED rows (SENT rows are never
     * re-sent) and flip the send back to RUNNING. This is also the recovery
     * for a JVM restart mid-send (rows stranded PENDING under a dead RUNNING
     * thread). Caller has already checked it exists and is not RUNNING.
     */
    static void reenqueue(Handle handle, long sendId) {
        handle.createUpdate("UPDATE email_send_recipient SET status = 'PENDING', error = NULL"
                + " WHERE email_send_id = :id AND status = 'FAILED'").bind("id", sendId).execute();
        handle.createUpdate("UPDATE email_send SET status = 'RUNNING', finished_at = NULL"
                + " WHERE email_send_id = :id").bind("id", sendId).execute();
    }

    // ---- the sender (runs on Mail's single thread) --------------------------

    /** Kick off (or resume) sending on the mail thread; returns immediately. */
    static void startSending(Jdbi jdbi, long sendId) {
        Mail.async(() -> {
            try {
                process(jdbi, sendId);
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "email send #" + sendId + " aborted on an unexpected error", e);
                jdbi.useHandle(h -> setFinished(h, sendId, "ABORTED"));
            }
        });
    }

    private record Snapshot(String subject, String body) {}
    private record Pending(long recipientId, long membershipId, Long personId, String email) {}
    private record Prepared(String subject, String body) {}

    private static void process(Jdbi jdbi, long sendId) {
        Snapshot snap = jdbi.withHandle(h -> h.createQuery(
                "SELECT subject, body FROM email_send WHERE email_send_id = :id")
                .bind("id", sendId).map((rs, ctx) -> new Snapshot(rs.getString("subject"), rs.getString("body")))
                .one());
        int consecutiveFailures = 0;
        while (true) {
            Pending next = jdbi.withHandle(h -> h.createQuery(
                    "SELECT email_send_recipient_id, membership_id, person_id, email"
                    + " FROM email_send_recipient WHERE email_send_id = :id AND status = 'PENDING'"
                    + " ORDER BY email_send_recipient_id LIMIT 1")
                    .bind("id", sendId)
                    .map((rs, ctx) -> new Pending(rs.getLong("email_send_recipient_id"),
                            rs.getLong("membership_id"), (Long) rs.getObject("person_id"), rs.getString("email")))
                    .findOne().orElse(null));
            if (next == null) {
                jdbi.useHandle(h -> setFinished(h, sendId, "COMPLETE"));
                return;
            }
            Prepared prepared;
            try {
                prepared = jdbi.inTransaction(h -> {
                    // fresh mint per email is fine (CR-004 amendment); the token id
                    // lands on the recipient row so the log can answer "which token"
                    RenewalTokenStore.Minted minted =
                            RenewalTokenStore.mint(h, next.membershipId()).orElse(null);
                    String payLink = minted == null ? "" : PayResource.payUrl(minted.token());
                    Map<String, String> values = mergeValues(h, next.membershipId(), next.personId(), payLink);
                    if (minted != null) {
                        h.createUpdate("UPDATE email_send_recipient SET renewal_token_id = :tok"
                                + " WHERE email_send_recipient_id = :id")
                                .bind("tok", minted.tokenId()).bind("id", next.recipientId()).execute();
                    }
                    return new Prepared(MergeFields.render(snap.subject(), values),
                            MergeFields.render(snap.body(), values));
                });
            } catch (Exception e) {
                LOG.log(Level.WARNING, "render/mint failed for recipient #" + next.recipientId(), e);
                jdbi.useHandle(h -> markResult(h, next.recipientId(), false, "render failed: " + e.getMessage()));
                if (++consecutiveFailures >= ABORT_AFTER_CONSECUTIVE_FAILURES) {
                    jdbi.useHandle(h -> setFinished(h, sendId, "ABORTED"));
                    return;
                }
                continue;
            }
            boolean ok = Mail.send(next.email(), prepared.subject(), prepared.body());
            jdbi.useHandle(h -> markResult(h, next.recipientId(), ok, ok ? null : "mail send failed"));
            consecutiveFailures = ok ? 0 : consecutiveFailures + 1;
            if (consecutiveFailures >= ABORT_AFTER_CONSECUTIVE_FAILURES) {
                // a dead relay must not grind through the rest; remaining rows stay PENDING for Resume
                jdbi.useHandle(h -> setFinished(h, sendId, "ABORTED"));
                return;
            }
        }
    }

    /** The merge-field values for one recipient of one membership (never called for a NO_EMAIL row). */
    static Map<String, String> mergeValues(Handle handle, long membershipId, Long personId, String payLink) {
        record Ctx(String displayName, String periodName, String typeName, int due, int paid) {}
        Ctx c = handle.createQuery(
                "SELECT COALESCE(NULLIF(trim(h.household_name), ''),"
                + "          trim(pc.given_name || ' ' || pc.family_name)) AS display_name,"
                + " per.name AS period_name, mt.name AS type_name, m.amount_due_cents,"
                + " " + MembershipStore.PAID_SQL + " AS paid"
                + " FROM membership m"
                + " JOIN membership_period per ON per.membership_period_id = m.membership_period_id"
                + " JOIN membership_type mt ON mt.membership_type_id = m.membership_type_id"
                + " JOIN household h ON h.household_id = m.household_id"
                + " JOIN person pc ON pc.person_id = h.primary_contact_person_id"
                + " WHERE m.membership_id = :id")
                .bind("id", membershipId)
                .map((rs, ctx) -> new Ctx(rs.getString("display_name"), rs.getString("period_name"),
                        rs.getString("type_name"), rs.getInt("amount_due_cents"), rs.getInt("paid")))
                .one();
        String given = "";
        String family = "";
        if (personId != null) {
            record Name(String given, String family) {}
            Name n = handle.createQuery("SELECT given_name, family_name FROM person WHERE person_id = :id")
                    .bind("id", personId)
                    .map((rs, ctx) -> new Name(rs.getString("given_name"), rs.getString("family_name")))
                    .one();
            given = n.given();
            family = n.family();
        }
        int balance = Math.max(0, c.due() - c.paid());
        Map<String, String> values = new LinkedHashMap<>();
        values.put("givenName", given);
        values.put("familyName", family);
        values.put("displayName", c.displayName());
        values.put("periodName", c.periodName());
        values.put("typeName", c.typeName());
        values.put("amountDue", PayResource.dollars(c.due()));
        values.put("amountPaid", PayResource.dollars(c.paid()));
        values.put("balance", PayResource.dollars(balance));
        values.put("payLink", payLink);
        values.put("societyName", Mail.societyName());
        return values;
    }

    // ---- internals ----------------------------------------------------------

    private static void markResult(Handle handle, long recipientId, boolean ok, String error) {
        handle.createUpdate("UPDATE email_send_recipient SET status = :status, error = :error,"
                + " sent_at = CASE WHEN :ok THEN now() ELSE sent_at END"
                + " WHERE email_send_recipient_id = :id")
                .bind("status", ok ? "SENT" : "FAILED").bind("error", error).bind("ok", ok)
                .bind("id", recipientId).execute();
    }

    private static void setFinished(Handle handle, long sendId, String status) {
        handle.createUpdate("UPDATE email_send SET status = :status, finished_at = now()"
                + " WHERE email_send_id = :id").bind("status", status).bind("id", sendId).execute();
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static Template mapTemplate(java.sql.ResultSet rs) {
        try {
            return new Template(rs.getLong("email_template_id"), rs.getString("name"),
                    rs.getString("subject"), rs.getString("body"), rs.getString("updated_by"),
                    rs.getObject("updated_at", OffsetDateTime.class));
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static SendSummary mapSend(java.sql.ResultSet rs, Map<String, Integer> counts) {
        try {
            return new SendSummary(rs.getLong("email_send_id"), (Long) rs.getObject("email_template_id"),
                    rs.getString("template_name"), rs.getString("subject"), rs.getString("body"),
                    rs.getLong("membership_period_id"), rs.getString("period_name"),
                    rs.getString("status_filter"), (Long) rs.getObject("type_filter"), rs.getString("type_name"),
                    rs.getString("communication_type"), rs.getString("status"), rs.getString("created_by"),
                    rs.getObject("created_at", OffsetDateTime.class),
                    rs.getObject("finished_at", OffsetDateTime.class), counts);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
