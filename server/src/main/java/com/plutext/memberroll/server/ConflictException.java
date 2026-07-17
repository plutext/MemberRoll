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

/**
 * A uniqueness/state conflict that a resource maps to HTTP 409 — the store
 * counterpart to {@link IllegalArgumentException} (which resources map to
 * 400). Thrown inside a transaction so the enclosing write rolls back:
 * duplicate period name, or a household that already has a membership in a
 * period (the CR-001 {@code UNIQUE (household_id, membership_period_id)} is
 * the backstop; this is the friendly message ahead of it).
 */
class ConflictException extends RuntimeException {
    ConflictException(String message) {
        super(message);
    }
}
