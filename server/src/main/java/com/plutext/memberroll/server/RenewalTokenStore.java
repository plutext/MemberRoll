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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Magic-link pay tokens (CR-004). A token is 256 random bits, base64url in
 * the link; only its sha256 hex ever lands in the database, so a DB leak
 * leaks no usable pay links. The flip side: a mint can never re-present an
 * earlier token, so each mint issues a fresh one and older unexpired tokens
 * simply stay valid — a re-clicked email link must not dead-end a member.
 * expires_at (the membership's end_date, inclusive) is the gate; used_at is
 * bookkeeping only. All methods take the caller's {@link Handle} — minting
 * rides inside whatever transaction the resource owns.
 */
final class RenewalTokenStore {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** A freshly minted token: the raw value exists only in this record and the link built from it. */
    record Minted(long tokenId, String token, OffsetDateTime expiresAt) {}

    /** Everything the public pay page shows, resolved from a live token in one query. */
    record PayView(long tokenId, long membershipId, String status, String displayName,
                   String periodName, String typeName, int amountDueCents, int amountPaidCents,
                   Integer journalPriceCents, boolean journalBought) {}

    /** A membership the lost-link email should carry a link for. */
    record LostLinkRow(long membershipId, String displayName, String periodName,
                       int amountDueCents, int amountPaidCents) {}

    private RenewalTokenStore() {}

    /**
     * Mint a fresh token for a membership, expiring the day after the
     * membership's end_date (so paying on the last day works) — but never
     * less than 30 days out: a treasurer chasing a lapsed member just after
     * the period ends must not be handed a link that is already dead. Empty
     * if no such membership.
     */
    static Optional<Minted> mint(Handle handle, long membershipId) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        return handle.createQuery(
                "INSERT INTO renewal_token (membership_id, token_hash, expires_at)"
                + " SELECT membership_id, :hash,"
                + "   GREATEST((end_date + 1)::timestamptz, now() + interval '30 days')"
                + " FROM membership WHERE membership_id = :id"
                + " RETURNING renewal_token_id, expires_at")
                .bind("hash", sha256Hex(token)).bind("id", membershipId)
                .map((rs, ctx) -> new Minted(rs.getLong("renewal_token_id"), token,
                        rs.getObject("expires_at", OffsetDateTime.class)))
                .findOne();
    }

    /** Resolve a presented token to its pay view; empty for unknown AND expired alike (both 404). */
    static Optional<PayView> resolve(Handle handle, String token) {
        return handle.createQuery(
                "SELECT rt.renewal_token_id, m.membership_id, m.status,"
                + " COALESCE(NULLIF(trim(h.household_name), ''),"
                + "          trim(pc.given_name || ' ' || pc.family_name)) AS display_name,"
                + " per.name AS period_name, per.journal_price_cents, mt.name AS type_name,"
                + " m.amount_due_cents,"
                + " " + MembershipStore.PAID_SQL + " AS paid,"
                // SUM, not EXISTS: a refunded journal (negative correction, the
                // system's only fix) must make the add-on purchasable again
                + " COALESCE((SELECT SUM(pa.amount_cents) FROM payment_allocation pa"
                + "   WHERE pa.membership_id = m.membership_id AND pa.allocation_type = 'JOURNAL'), 0) > 0"
                + "   AS journal_bought"
                + " FROM renewal_token rt"
                + " JOIN membership m ON m.membership_id = rt.membership_id"
                + " JOIN membership_period per ON per.membership_period_id = m.membership_period_id"
                + " JOIN membership_type mt ON mt.membership_type_id = m.membership_type_id"
                + " JOIN household h ON h.household_id = m.household_id"
                + " JOIN person pc ON pc.person_id = h.primary_contact_person_id"
                + " WHERE rt.token_hash = :hash AND rt.expires_at > now()")
                .bind("hash", sha256Hex(token))
                .map((rs, ctx) -> new PayView(rs.getLong("renewal_token_id"), rs.getLong("membership_id"),
                        rs.getString("status"), rs.getString("display_name"), rs.getString("period_name"),
                        rs.getString("type_name"), rs.getInt("amount_due_cents"), rs.getInt("paid"),
                        (Integer) rs.getObject("journal_price_cents"), rs.getBoolean("journal_bought")))
                .findOne();
    }

    /**
     * The memberships a lost-link request for an email address should offer:
     * every matching person's current households' non-ceased memberships in
     * a period that is current OR whose renewal window is open — during
     * renewal season the next period's rolled-over membership exists before
     * its start_date, and that is exactly the one the member needs to pay.
     */
    static List<LostLinkRow> lostLinkRows(Handle handle, String email) {
        return handle.createQuery(
                "SELECT DISTINCT m.membership_id,"
                + " COALESCE(NULLIF(trim(h.household_name), ''),"
                + "          trim(pc.given_name || ' ' || pc.family_name)) AS display_name,"
                + " per.name AS period_name, m.amount_due_cents,"
                + " " + MembershipStore.PAID_SQL + " AS paid"
                + " FROM email_address e"
                + " JOIN household_person hp ON hp.person_id = e.person_id"
                + "   AND hp.left_household_date IS NULL"
                + " JOIN membership m ON m.household_id = hp.household_id AND m.status <> 'CEASED'"
                + " JOIN membership_period per ON per.membership_period_id = m.membership_period_id"
                + "   AND current_date <= per.end_date"
                + "   AND (current_date >= per.start_date"
                + "        OR (per.renewal_open_date IS NOT NULL AND current_date >= per.renewal_open_date))"
                + " JOIN household h ON h.household_id = m.household_id"
                + " JOIN person pc ON pc.person_id = h.primary_contact_person_id"
                + " WHERE e.email = :email ORDER BY m.membership_id")
                .bind("email", email)
                .map((rs, ctx) -> new LostLinkRow(rs.getLong("membership_id"),
                        rs.getString("display_name"), rs.getString("period_name"),
                        rs.getInt("amount_due_cents"), rs.getInt("paid")))
                .list();
    }

    /** Stamp the first successful payment through a token; later payments leave it alone. */
    static void markUsed(Handle handle, long tokenId) {
        handle.createUpdate("UPDATE renewal_token SET used_at = now()"
                + " WHERE renewal_token_id = :id AND used_at IS NULL")
                .bind("id", tokenId).execute();
    }

    static String sha256Hex(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 is a mandatory JDK algorithm
        }
    }
}
