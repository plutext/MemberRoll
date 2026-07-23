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
 * The public application form's settings (CR-007), one {@code app_setting}
 * JSON blob in the CR-014/CR-015 shape. {@code formEnabled} defaults FALSE —
 * the form ships dark, and turning it on is the go-live act that follows the
 * committee's clause-3 minute (electronic lodgement must be resolved before
 * the form is the society's approved application form). {@code alertMailbox}
 * is where new-application notices go (the committee's page-settable society
 * mailbox); when unset, the current secretary's email (CR-013 contacts seam)
 * is the fallback. Read per use, never cached — a save applies immediately.
 */
final class ApplicationSettings {

    private ApplicationSettings() {}

    /** The single {@code app_setting} row holding the form settings, as a JSON blob. */
    static final String SETTINGS_KEY = "application_settings";

    record Settings(String alertMailbox, boolean formEnabled) {

        /** No row saved: form disabled, no alert mailbox (secretary fallback applies). */
        static Settings absent() {
            return new Settings(null, false);
        }
    }

    static Settings read(Handle handle) {
        Optional<String> json = handle.createQuery("SELECT value FROM app_setting WHERE key = :k")
                .bind("k", SETTINGS_KEY).mapTo(String.class).findOne();
        if (json.isEmpty()) return Settings.absent();
        try (JsonReader reader = Json.createReader(new StringReader(json.get()))) {
            JsonObject o = reader.readObject();
            String mailbox = null;
            if (o.containsKey("alertMailbox") && !o.isNull("alertMailbox")) {
                String v = o.getString("alertMailbox", "").trim();
                if (!v.isEmpty()) mailbox = v;
            }
            return new Settings(mailbox, o.getBoolean("formEnabled", false));
        } catch (Exception e) {
            return Settings.absent();
        }
    }
}
