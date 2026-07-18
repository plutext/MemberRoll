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

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code {{field}}} merge-field vocabulary and its strict renderer (CR-005).
 * The list is deliberately closed and validation is deliberately strict:
 * {@link #validate} rejects any unknown field at BOTH template save and send
 * creation (belt and braces — a template written before a rename must not leak
 * {@code {{payLnk}}} into a hundred mailboxes). There is no conditional/loop
 * DSL: the one variable-shape case (household vs single) is covered by
 * {@code {{displayName}}}/{@code {{typeName}}}, and anything smarter would be a
 * template language this war does not need.
 */
final class MergeFields {

    /** The whole vocabulary. Adding one here is the only place a new field is enabled. */
    static final List<String> FIELDS = List.of(
            "givenName", "familyName", "displayName",
            "periodName", "typeName",
            "amountDue", "amountPaid", "balance",
            "payLink", "societyName");

    // {{ field }} — inner whitespace tolerated; a bare identifier only (no dots,
    // no args), so a stray "{{" in prose without a closing "}}" simply doesn't match
    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*\\}\\}");

    private MergeFields() {}

    /**
     * @throws IllegalArgumentException naming the first unknown field, if the
     *     text references a {@code {{token}}} that is not in {@link #FIELDS}.
     */
    static void validate(String text) {
        if (text == null) return;
        Matcher m = TOKEN.matcher(text);
        while (m.find()) {
            if (!FIELDS.contains(m.group(1))) {
                throw new IllegalArgumentException("unknown merge field {{" + m.group(1) + "}}"
                        + " — allowed fields are " + FIELDS);
            }
        }
    }

    /**
     * Substitute every {@code {{field}}} from {@code values}. Assumes {@link
     * #validate} already passed, so every token has a value; a token absent
     * from the map (should not happen) renders empty rather than throwing at
     * send time.
     */
    static String render(String text, Map<String, String> values) {
        if (text == null) return "";
        Matcher m = TOKEN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String v = values.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(v == null ? "" : v));
        }
        m.appendTail(out);
        return out.toString();
    }
}
