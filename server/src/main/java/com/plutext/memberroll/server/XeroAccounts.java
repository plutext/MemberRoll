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

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;

import org.jdbi.v3.core.Handle;

import java.io.StringReader;
import java.util.Optional;

/**
 * The society's Xero account codes (CR-015), stored as one {@code app_setting}
 * JSON blob exactly like CR-014's {@code smtp_settings} — settings change as a
 * unit, one atomic value, no migration. The codes are <b>opaque strings</b>:
 * the server validates presence, never chart-of-accounts sense (a wrong code
 * fails loudly at Xero's import, the right place). With no usable mapping the
 * journal-CSV feature is dormant ({@link #read} returns empty → the endpoint
 * answers 409) and the plain CSV workflow stands alone. This is the one place
 * CR-014's "the server knows no vendors" bends: generating an importable
 * journal needs the society's codes. It still knows no Xero semantics.
 */
final class XeroAccounts {

    private XeroAccounts() {}

    /** The single {@code app_setting} row holding the account mapping, as a JSON blob. */
    static final String SETTINGS_KEY = "xero_accounts";

    /** The default tax rate for a society not registered for GST (treasurer-confirmed, still editable). */
    static final String DEFAULT_TAX_RATE = "BAS Excluded";

    record Mapping(String membershipCode, String journalCode, String donationCode,
                   String otherCode, String clearingCode, String taxRate) {}

    /**
     * The saved mapping, or empty when no row exists or the row is missing any
     * of the five required codes (feature dormant). {@code taxRate} defaults to
     * {@link #DEFAULT_TAX_RATE} if absent, matching the PUT default.
     */
    static Optional<Mapping> read(Handle handle) {
        Optional<String> json = handle.createQuery("SELECT value FROM app_setting WHERE key = :k")
                .bind("k", SETTINGS_KEY).mapTo(String.class).findOne();
        if (json.isEmpty()) return Optional.empty();
        try (JsonReader reader = Json.createReader(new StringReader(json.get()))) {
            JsonObject o = reader.readObject();
            String membership = str(o, "membershipCode");
            String journal = str(o, "journalCode");
            String donation = str(o, "donationCode");
            String other = str(o, "otherCode");
            String clearing = str(o, "clearingCode");
            if (membership == null || journal == null || donation == null
                    || other == null || clearing == null) {
                return Optional.empty();
            }
            String taxRate = str(o, "taxRate");
            return Optional.of(new Mapping(membership, journal, donation, other, clearing,
                    taxRate != null ? taxRate : DEFAULT_TAX_RATE));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String str(JsonObject o, String key) {
        if (!o.containsKey(key) || o.isNull(key)) return null;
        try {
            String v = o.getString(key).trim();
            return v.isEmpty() ? null : v;
        } catch (Exception e) {
            return null;
        }
    }
}
