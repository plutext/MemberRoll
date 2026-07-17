/* The shared login for every page in the war. A hand-rolled OAuth2
 * Authorization Code + PKCE flow against the Keycloak `web` public
 * client — deliberately no keycloak-js: these ~80 lines are
 * self-contained, and they are exactly the flow a native mobile app
 * would run (e.g. via AppAuth).
 *
 * Pages call Auth.completeLoginIfReturning() on boot, Auth.login() /
 * Auth.logout() from their buttons, and Auth.api(path, options) for
 * bearer-attached fetches. Assign Auth.onFresh401 to surface the
 * issuer-mismatch explanation in the page's own status area.
 *
 * Tokens live in localStorage so a cold start (new tab, installed PWA)
 * resumes the session; per-flight state (the PKCE verifier, the 401
 * loop-breaker) stays tab-scoped in sessionStorage.
 */
"use strict";

const Auth = (() => {

    // The scheme is the deployment discriminator. Production is https and
    // serves Keycloak on the page's own origin under /auth — one origin,
    // one issuer. The http dev loop keeps "same host as the page, port
    // 8081": visiting the page via localhost or via the machine's LAN IP
    // then automatically talks to a Keycloak whose issuer matches
    // (Keycloak 26 stamps the issuer as the client saw it, and the war
    // validates against its KEYCLOAK_ISSUER allowlist)
    const KEYCLOAK_BASE = location.protocol === "https:"
        ? `${location.origin}/auth`
        : `${location.protocol}//${location.hostname}:8081`;
    const REALM = "memberroll";
    const CLIENT_ID = "web";
    const ISSUER = `${KEYCLOAK_BASE}/realms/${REALM}`;
    const REDIRECT = location.origin + location.pathname;
    const API = "/server/api";

    // ---- PKCE -------------------------------------------------------------

    function base64url(bytes) {
        return btoa(String.fromCharCode(...new Uint8Array(bytes)))
            .replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
    }

    async function login() {
        // Login is a full-page navigation to Keycloak: probe reachability
        // first so an offline tap surfaces a message instead of stranding
        // the page on a browser error with the auth URL in the address bar
        // (navigator.onLine can claim online while the network is down, so
        // ask the server, not the flag). A no-cors probe resolves on any
        // reachable server — CORS-blind.
        try {
            await fetch(ISSUER, {mode: "no-cors", cache: "no-store"});
        } catch {
            throw new Error("signing in needs the network, and the sign-in "
                + "server is unreachable right now — anything saved stays in "
                + "this browser and uploads once you sign in when back online");
        }
        const verifier = base64url(crypto.getRandomValues(new Uint8Array(32)));
        sessionStorage.setItem("pkce_verifier", verifier);
        // crypto.subtle is secure-context-gated: present on https/localhost,
        // absent on http://<LAN-IP> (the phone dev loop). Fall back to the
        // plain-JS SHA-256 (../shared/sha256.js, loaded before this file)
        // so the challenge stays S256 everywhere.
        const data = new TextEncoder().encode(verifier);
        const digest = crypto.subtle
            ? await crypto.subtle.digest("SHA-256", data)
            : Sha256.sha256(data);
        const challenge = base64url(digest);
        const params = new URLSearchParams({
            client_id: CLIENT_ID, redirect_uri: REDIRECT, response_type: "code",
            scope: "openid", code_challenge: challenge, code_challenge_method: "S256",
        });
        location.href = `${ISSUER}/protocol/openid-connect/auth?${params}`;
    }

    async function tokenRequest(body) {
        const response = await fetch(`${ISSUER}/protocol/openid-connect/token`, {
            method: "POST",
            headers: {"Content-Type": "application/x-www-form-urlencoded"},
            body: new URLSearchParams(body),
        });
        if (!response.ok) throw new Error(`token endpoint: HTTP ${response.status}`);
        const tokens = await response.json();
        localStorage.setItem("access_token", tokens.access_token);
        if (tokens.refresh_token) localStorage.setItem("refresh_token", tokens.refresh_token);
        if (tokens.id_token) localStorage.setItem("id_token", tokens.id_token); // for logout
    }

    /** RP-initiated logout: end the Keycloak SSO session, land back here. */
    function logout() {
        const idToken = localStorage.getItem("id_token");
        for (const key of ["access_token", "refresh_token", "id_token"]) {
            localStorage.removeItem(key);
        }
        sessionStorage.clear();
        const params = new URLSearchParams({post_logout_redirect_uri: REDIRECT});
        if (idToken) params.set("id_token_hint", idToken);
        else params.set("client_id", CLIENT_ID);
        location.href = `${ISSUER}/protocol/openid-connect/logout?${params}`;
    }

    async function completeLoginIfReturning() {
        const code = new URLSearchParams(location.search).get("code");
        if (!code) return;
        await tokenRequest({
            grant_type: "authorization_code", client_id: CLIENT_ID, code,
            redirect_uri: REDIRECT,
            code_verifier: sessionStorage.getItem("pkce_verifier"),
        });
        sessionStorage.setItem("just_logged_in", "1"); // arms the 401 loop-breaker
        history.replaceState(null, "", REDIRECT); // drop ?code=... from the URL
    }

    async function refresh() {
        const token = localStorage.getItem("refresh_token");
        if (!token) return false;
        try {
            await tokenRequest({grant_type: "refresh_token", client_id: CLIENT_ID,
                refresh_token: token});
            return true;
        } catch {
            return false;
        }
    }

    /**
     * fetch with the bearer attached; one refresh-and-retry on 401. If even a
     * freshly-exchanged token is rejected, DON'T bounce back to login: with an
     * SSO session that redirect loops silently forever (seen in the wild when
     * the war validated a different issuer than this page's Keycloak host) —
     * surface the error via onFresh401 instead. Returns null in both
     * went-to-login and mismatch-shown cases.
     */
    async function api(path, options = {}, retried = false) {
        options.headers = Object.assign({}, options.headers, {
            Authorization: `Bearer ${localStorage.getItem("access_token")}`,
        });
        const response = await fetch(API + path, options);
        if (response.status === 401 && !retried && await refresh()) {
            return api(path, options, true);
        }
        if (response.status === 401) {
            if (sessionStorage.getItem("just_logged_in") === "1") {
                sessionStorage.removeItem("just_logged_in");
                Auth.onFresh401("Logged in at Keycloak, but the server rejects the token "
                    + "(HTTP 401). Likely an issuer mismatch: this page uses "
                    + `${KEYCLOAK_BASE} — the server's KEYCLOAK_ISSUER allowlist must include `
                    + "that issuer (see server/README.md, LAN section).");
                return null;
            }
            return login();
        }
        sessionStorage.removeItem("just_logged_in");
        return response;
    }

    function hasToken() {
        return localStorage.getItem("access_token") !== null;
    }

    return {
        API,
        login,
        logout,
        completeLoginIfReturning,
        api,
        hasToken,
        /** Force-mint a fresh token now (makes a just-claimed role live). */
        refresh,
        /** Page hook: a freshly-exchanged token was rejected (issuer mismatch). */
        onFresh401: (message) => alert(message),
    };
})();
