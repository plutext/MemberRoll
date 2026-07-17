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

import java.security.Principal;
import java.util.Set;

/**
 * The authenticated caller as read from a validated Keycloak access token.
 * The principal name is the stable subject id; the username is
 * display-only. Roles are the realm roles the token carried.
 */
public final class UserPrincipal implements Principal {

    private final String subject;
    private final String username;
    private final Set<String> roles;
    private final String claimedRole;   // claimed_role token claim, null when unclaimed
    private final boolean roleVerified; // role_verified token claim

    UserPrincipal(String subject, String username, Set<String> roles,
                  String claimedRole, boolean roleVerified) {
        this.subject = subject;
        this.username = username;
        this.roles = roles;
        this.claimedRole = claimedRole;
        this.roleVerified = roleVerified;
    }

    /** The token's {@code sub}: stable, unique, the key for "own data". */
    @Override
    public String getName() {
        return subject;
    }

    public String getUsername() {
        return username;
    }

    public Set<String> getRoles() {
        return roles;
    }

    /** The role the user claims; null when they never picked one. */
    public String getClaimedRole() {
        return claimedRole;
    }

    /**
     * The raw role_verified attribute as the token carried it. Report it as
     * true only alongside a claim the granted roles actually reflect —
     * {@link WhoAmIResource} composes that.
     */
    public boolean isRoleVerified() {
        return roleVerified;
    }
}
