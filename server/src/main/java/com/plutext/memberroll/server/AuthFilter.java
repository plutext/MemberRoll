package com.plutext.memberroll.server;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.net.URL;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Bearer-token authentication. No Authorization header means the request
 * proceeds as a guest (public endpoints keep working); a present but
 * invalid token is rejected with 401 immediately. A valid RS256 token
 * - signature against Keycloak's JWKS, exact issuer, required audience,
 * unexpired - yields a SecurityContext whose principal is the token's
 * subject and whose roles are the realm roles ({@code realm_access.roles}).
 *
 * The JWKS source caches keys and rate-limits refreshes, so steady-state
 * validation is local computation only, and a Keycloak key rotation heals
 * without a server restart.
 */
@Priority(Priorities.AUTHENTICATION)
public class AuthFilter implements ContainerRequestFilter {

    /**
     * Comma-separated allowlist. Keycloak 26 stamps tokens with the issuer
     * AS THE CLIENT SAW IT, so a dev session where the admin panel runs in
     * a localhost browser while phones use the LAN IP needs both, e.g.
     * {@code http://localhost:8081/realms/memberroll,http://192.168.1.50:8081/realms/memberroll}.
     * Each issuer gets its own JWKS-backed processor; a token is validated
     * strictly against the (single) issuer it names.
     */
    static final List<String> ISSUERS = List.of(
            env("KEYCLOAK_ISSUER", "http://localhost:8081/realms/memberroll")
                    .split("\\s*,\\s*"));
    static final String AUDIENCE = env("KEYCLOAK_AUDIENCE", "memberroll-server");

    private static final Map<String, DefaultJWTProcessor<com.nimbusds.jose.proc.SecurityContext>>
            processors = new java.util.concurrent.ConcurrentHashMap<>();

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** Lazily built per issuer so a slow/absent Keycloak never blocks deployment. */
    private static DefaultJWTProcessor<com.nimbusds.jose.proc.SecurityContext> processor(
            String issuer) {
        return processors.computeIfAbsent(issuer, iss -> {
            try {
                JWKSource<com.nimbusds.jose.proc.SecurityContext> keys = JWKSourceBuilder
                        .create(new URL(iss + "/protocol/openid-connect/certs"))
                        .build();
                DefaultJWTProcessor<com.nimbusds.jose.proc.SecurityContext> built =
                        new DefaultJWTProcessor<>();
                built.setJWSKeySelector(
                        new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
                built.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
                        AUDIENCE,
                        new JWTClaimsSet.Builder().issuer(iss).build(),
                        Set.of("exp", "sub")));
                return built;
            } catch (Exception e) {
                throw new IllegalStateException("bad issuer " + iss, e);
            }
        });
    }

    @Override
    public void filter(ContainerRequestContext request) {
        String header = request.getHeaderString(HttpHeaders.AUTHORIZATION);
        if (header == null) {
            return; // guest: role-gated resources will refuse on their own
        }
        if (!header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            abort(request, "only Bearer authorization is supported");
            return;
        }
        String token = header.substring(7).trim();
        JWTClaimsSet claims;
        try {
            // route by the (as yet unverified) iss claim to the matching
            // allowlisted issuer; the processor then verifies it strictly
            String issuer = com.nimbusds.jwt.JWTParser.parse(token)
                    .getJWTClaimsSet().getIssuer();
            if (issuer == null || !ISSUERS.contains(issuer)) {
                abort(request, "invalid token");
                return;
            }
            claims = processor(issuer).process(token, null);
        } catch (Exception e) {
            abort(request, "invalid token");
            return;
        }
        Set<String> roles = realmRoles(claims);
        String username = String.valueOf(
                claims.getClaims().getOrDefault("preferred_username", ""));
        UserPrincipal principal = new UserPrincipal(claims.getSubject(), username, roles,
                stringClaim(claims, "claimed_role"), booleanClaim(claims, "role_verified"));
        // a token whose claimed role isn't reflected in its granted roles
        // triggers a (rate-limited, non-fatal) sync against Keycloak; this
        // request still runs with the roles the token actually carried.
        KeycloakAdmin.maybeReconcile(principal);
        boolean secure = request.getSecurityContext() != null
                && request.getSecurityContext().isSecure();
        request.setSecurityContext(new SecurityContext() {
            @Override
            public Principal getUserPrincipal() {
                return principal;
            }

            @Override
            public boolean isUserInRole(String role) {
                return roles.contains(role);
            }

            @Override
            public boolean isSecure() {
                return secure;
            }

            @Override
            public String getAuthenticationScheme() {
                return "Bearer";
            }
        });
    }

    private static String stringClaim(JWTClaimsSet claims, String name) {
        Object value = claims.getClaim(name);
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private static boolean booleanClaim(JWTClaimsSet claims, String name) {
        return Boolean.TRUE.equals(claims.getClaim(name));
    }

    private static Set<String> realmRoles(JWTClaimsSet claims) {
        Object realmAccess = claims.getClaim("realm_access");
        if (!(realmAccess instanceof Map)) return Set.of();
        Object roles = ((Map<?, ?>) realmAccess).get("roles");
        if (!(roles instanceof List)) return Set.of();
        return ((List<?>) roles).stream().map(String::valueOf).collect(Collectors.toSet());
    }

    private static void abort(ContainerRequestContext request, String reason) {
        request.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"memberroll\"")
                .type("application/json")
                .entity("{\"error\":\"" + reason + "\"}")
                .build());
    }
}
