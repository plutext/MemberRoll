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

import java.io.StringReader;
import java.time.LocalDate;

/**
 * Leaf helpers for reading JSON request bodies — the CR-003 resources parse
 * richer payloads (periods with price lists, payments with allocation lines)
 * than the CR-001 resources, so the small get/require primitives live here
 * once rather than copied three ways. Missing-required and malformed-value
 * both throw {@link IllegalArgumentException} with a caller-facing message the
 * resource maps to HTTP 400 (mirroring AdminPeopleResource.parse).
 */
final class Payloads {

    private Payloads() {}

    /** Parse a body to an object, or null if it is not a JSON object. */
    static JsonObject read(String body) {
        try (JsonReader reader = Json.createReader(new StringReader(body == null ? "" : body))) {
            return reader.readObject();
        } catch (Exception e) {
            return null;
        }
    }

    /** Trimmed non-blank string, or null if absent/null/blank. */
    static String optString(JsonObject o, String key) {
        if (!o.containsKey(key) || o.isNull(key)) return null;
        try {
            String v = o.getString(key).trim();
            return v.isEmpty() ? null : v;
        } catch (Exception e) {
            return null;
        }
    }

    static Long optLong(JsonObject o, String key) {
        if (!o.containsKey(key) || o.isNull(key)) return null;
        try {
            return o.getJsonNumber(key).longValue();
        } catch (Exception e) {
            throw new IllegalArgumentException(key + " must be a number");
        }
    }

    static long reqLong(JsonObject o, String key) {
        Long v = optLong(o, key);
        if (v == null) throw new IllegalArgumentException(key + " is required");
        return v;
    }

    static Integer optInt(JsonObject o, String key) {
        if (!o.containsKey(key) || o.isNull(key)) return null;
        try {
            return o.getJsonNumber(key).intValueExact();
        } catch (Exception e) {
            throw new IllegalArgumentException(key + " must be a whole number");
        }
    }

    static int reqInt(JsonObject o, String key) {
        Integer v = optInt(o, key);
        if (v == null) throw new IllegalArgumentException(key + " is required");
        return v;
    }

    static LocalDate optDate(JsonObject o, String key) {
        String v = optString(o, key);
        if (v == null) return null;
        try {
            return LocalDate.parse(v);
        } catch (Exception e) {
            throw new IllegalArgumentException(key + " must be an ISO date (YYYY-MM-DD)");
        }
    }

    static LocalDate reqDate(JsonObject o, String key) {
        LocalDate v = optDate(o, key);
        if (v == null) throw new IllegalArgumentException(key + " is required");
        return v;
    }

    static boolean optBool(JsonObject o, String key, boolean fallback) {
        if (!o.containsKey(key) || o.isNull(key)) return fallback;
        try {
            return o.getBoolean(key);
        } catch (Exception e) {
            return fallback;
        }
    }
}
