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

import jakarta.activation.DataHandler;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;

import java.io.StringReader;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SMTP sender. Its configuration is resolved per send, uncached, in the order
 * PAGE → ENV → NONE (CR-014): the admin "mail settings" page writes one
 * {@code app_setting} row ({@link #SETTINGS_KEY}), and when that row is absent
 * the CR-004 environment variables ({@code SMTP_*}/{@code MAIL_*}) remain the
 * fallback — so the dev stack and a fresh install still send before anyone
 * opens the page. Neither configured means mail is disabled and {@link #send}
 * is a logged no-op — callers treat every send as best-effort (a receipt
 * failure must never fail the webhook that recorded the payment).
 *
 * <p>Per-use resolution is deliberate: a saved change applies to the very next
 * message with no restart (notably a CR-005 segment send that ABORTED on a
 * dead relay, resumed after the page is fixed). If the row read itself fails,
 * {@link #resolve} falls back to ENV (logged once) — a DB hiccup must not
 * change mail's best-effort contract.
 *
 * <p>{@link #sendAsync} is the normal entry point from request threads: SMTP
 * must never hold a Tomcat thread past the response (Stripe times webhooks out
 * in seconds, and lost-link response time must not reveal whether a mail is
 * being sent). The password is never logged, never returned to any client, and
 * scrubbed even from the one place a send error reaches a human — the page's
 * test button ({@link #test}).
 */
final class Mail {

    private static final Logger LOG = Logger.getLogger(Mail.class.getName());

    /** The single {@code app_setting} row holding the page-configured SMTP settings, as a JSON blob. */
    static final String SETTINGS_KEY = "smtp_settings";

    // one daemon thread: sends are rare (a payment, a lost-link click) and
    // strictly best-effort, so a queue that dies with the JVM is fine
    private static final ExecutorService SENDER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mail-sender");
        t.setDaemon(true);
        return t;
    });

    // last DB-read failure logged, to keep a down database from spamming the log
    // on every send (once per distinct failure is the design's "fine")
    private static final AtomicReference<String> LAST_READ_FAILURE = new AtomicReference<>();

    private Mail() {}

    /** Where {@link #resolve} found the effective settings. */
    enum Source { PAGE, ENV, NONE }

    /** Transport security — the three cases real relays present; replaces the env boolean. */
    enum Security { NONE, STARTTLS, SSL }

    /**
     * The effective SMTP settings and where they came from. The password is
     * carried here for the sender and the keep-on-resave contract, and is never
     * serialised to a client (only {@code passwordSet}).
     */
    record Settings(Source source, String host, int port, Security security,
                    String username, String password, String from, String replyTo) {

        boolean passwordSet() {
            return password != null && !password.isBlank();
        }

        /** A disabled configuration — nothing to send with. */
        static Settings none() {
            return new Settings(Source.NONE, null, 0, Security.NONE, null, null, null, null);
        }
    }

    /**
     * A single file attachment for a transactional send (CR-017 membership
     * card). Present {@code attachment} switches the message to multipart/mixed
     * (text part + file part); the no-attachment path stays byte-for-byte the
     * single-part message CR-004/005/012 rely on.
     */
    record Attachment(String filename, String contentType, byte[] bytes) {}

    static boolean enabled() {
        return resolve().source() != Source.NONE;
    }

    /**
     * The effective settings: the page row if present and parseable, else the
     * environment, else disabled. A DB read failure falls back to ENV (logged
     * once). Read per call — never cache the result.
     */
    static Settings resolve() {
        try {
            Optional<String> json = Db.jdbi().withHandle(h ->
                    h.createQuery("SELECT value FROM app_setting WHERE key = :k")
                            .bind("k", SETTINGS_KEY).mapTo(String.class).findOne());
            LAST_READ_FAILURE.set(null); // read succeeded; re-arm the log-once guard
            if (json.isPresent()) {
                Settings page = parsePage(json.get());
                if (page != null) return page;
                LOG.warning("smtp_settings row is unparseable — falling back to environment");
            }
        } catch (Exception e) {
            String key = e.getClass().getName() + ":" + e.getMessage();
            if (!key.equals(LAST_READ_FAILURE.getAndSet(key))) {
                LOG.log(Level.WARNING, "reading smtp_settings failed — falling back to environment", e);
            }
        }
        return envSettings();
    }

    /** Parse the stored JSON blob into PAGE settings, or null if it lacks a usable host/port. */
    private static Settings parsePage(String json) {
        try (JsonReader reader = Json.createReader(new StringReader(json))) {
            JsonObject o = reader.readObject();
            String host = str(o, "host");
            if (host == null) return null;
            int port = o.containsKey("port") && !o.isNull("port") ? o.getInt("port") : 0;
            if (port < 1 || port > 65535) return null;
            String from = str(o, "from");
            if (from == null) return null;
            Security security = parseSecurity(str(o, "security"));
            return new Settings(Source.PAGE, host, port, security,
                    str(o, "username"), str(o, "password"), from, str(o, "replyTo"));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Today's environment variables, verbatim CR-004 behaviour: no SMTP_HOST is
     * NONE (mail disabled), port default 25, STARTTLS boolean, MAIL_FROM default
     * {@code noreply@localhost}. This path must stay byte-for-byte compatible —
     * the send() branch keeps {@code starttls.enable} only for it (no
     * {@code .required}: dev Mailpit offers no STARTTLS).
     */
    private static Settings envSettings() {
        String host = env("SMTP_HOST");
        if (host == null) return Settings.none();
        int port = env("SMTP_PORT") != null ? parsePort(env("SMTP_PORT")) : 25;
        Security security = Boolean.parseBoolean(env("SMTP_STARTTLS")) ? Security.STARTTLS : Security.NONE;
        String from = env("MAIL_FROM");
        return new Settings(Source.ENV, host, port, security,
                env("SMTP_USERNAME"), env("SMTP_PASSWORD"),
                from != null ? from : "noreply@localhost", env("MAIL_REPLY_TO"));
    }

    private static int parsePort(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 25;
        }
    }

    private static Security parseSecurity(String s) {
        if (s == null) return Security.NONE;
        try {
            return Security.valueOf(s.toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            return Security.NONE;
        }
    }

    private static String str(JsonObject o, String key) {
        if (!o.containsKey(key) || o.isNull(key)) return null;
        try {
            String v = o.getString(key);
            return v.isBlank() ? null : v;
        } catch (Exception e) {
            return null;
        }
    }

    /** The society name for email + pay-page branding (single-tenant rule: no "Yass" in code). */
    static String societyName() {
        String name = env("MEMBERROLL_SOCIETY_NAME");
        return name != null ? name : "memberroll dev";
    }

    /** Queue a plain-text mail off the calling thread; always returns immediately. */
    static void sendAsync(String to, String subject, String body) {
        SENDER.submit(() -> send(to, subject, body, null));
    }

    /** Queue a mail with a single attachment off the calling thread (CR-017). */
    static void sendAsync(String to, String subject, String body, Attachment attachment) {
        SENDER.submit(() -> send(to, subject, body, attachment));
    }

    /** Run mail-adjacent work (lookup + compose + send) on the mail thread. */
    static void async(Runnable task) {
        SENDER.submit(task);
    }

    /**
     * Send a plain-text mail with the effective settings; false (logged) on any
     * failure or when mail is disabled. Never throws. The failure is logged with
     * the password scrubbed out (best-effort, and the PAGE path stores one).
     */
    static boolean send(String to, String subject, String body) {
        return send(to, subject, body, null);
    }

    /** As {@link #send(String, String, String)} but with a single attachment (CR-017). */
    static boolean send(String to, String subject, String body, Attachment attachment) {
        Settings settings = resolve();
        if (settings.source() == Source.NONE) {
            LOG.warning("mail disabled (no page settings, SMTP_HOST unset) — not sending \""
                    + subject + "\" to " + to);
            return false;
        }
        String error = doSend(settings, to, subject, body, attachment);
        if (error != null) {
            LOG.warning("mail send failed: \"" + subject + "\" to " + to + " — " + scrub(error, settings));
        }
        return error == null;
    }

    /**
     * Send synchronously with the given (candidate) settings and return null on
     * success or the SMTP error string, password scrubbed. This is the ONE place
     * send-failure detail reaches a human — the settings page's test button —
     * so "535 5.7.139 Authentication unsuccessful" is the product; the scrub
     * guards against a chained exception leaking the secret alongside it.
     */
    static String test(Settings settings, String to, String subject, String body) {
        if (settings.source() == Source.NONE) return "mail is not configured";
        return scrub(doSend(settings, to, subject, body, null), settings);
    }

    /** The actual send; returns null on success or a human-readable error string (unscrubbed). */
    private static String doSend(Settings settings, String to, String subject, String body,
                                 Attachment attachment) {
        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", settings.host());
            props.put("mail.smtp.port", String.valueOf(settings.port()));
            // jakarta.mail's default timeouts are INFINITE — a half-open SMTP
            // host would otherwise pin the sender thread forever
            props.put("mail.smtp.connectiontimeout", "10000");
            props.put("mail.smtp.timeout", "10000");
            props.put("mail.smtp.writetimeout", "10000");
            Authenticator auth = null;
            if (settings.username() != null && !settings.username().isBlank()) {
                props.put("mail.smtp.auth", "true");
                String user = settings.username();
                String pass = settings.password() != null ? settings.password() : "";
                auth = new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(user, pass);
                    }
                };
            }
            switch (settings.security()) {
                case STARTTLS -> {
                    props.put("mail.smtp.starttls.enable", "true");
                    // a page-configured relay must not silently downgrade to
                    // cleartext when a MITM strips STARTTLS; the ENV path keeps
                    // enable-only (dev Mailpit offers no STARTTLS)
                    if (settings.source() == Source.PAGE) props.put("mail.smtp.starttls.required", "true");
                }
                case SSL -> props.put("mail.smtp.ssl.enable", "true"); // implicit TLS (port 465)
                case NONE -> { }
            }
            Session session = Session.getInstance(props, auth);
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(settings.from()));
            // renewal replies should reach the treasurer, not noreply@ (CR-005);
            // unset → no Reply-To header, exactly the prior behaviour
            if (settings.replyTo() != null && !settings.replyTo().isBlank()) {
                message.setReplyTo(InternetAddress.parse(settings.replyTo()));
            }
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            message.setSubject(subject, "UTF-8");
            if (attachment == null) {
                // the no-attachment path stays byte-for-byte the CR-004/005/012
                // single-part message — do NOT route it through multipart
                message.setText(body, "UTF-8");
            } else {
                MimeBodyPart textPart = new MimeBodyPart();
                textPart.setText(body, "UTF-8");
                MimeBodyPart filePart = new MimeBodyPart();
                filePart.setDataHandler(new DataHandler(
                        new ByteArrayDataSource(attachment.bytes(), attachment.contentType())));
                filePart.setFileName(attachment.filename());
                MimeMultipart multipart = new MimeMultipart();
                multipart.addBodyPart(textPart);
                multipart.addBodyPart(filePart);
                message.setContent(multipart);
            }
            Transport.send(message);
            return null;
        } catch (Exception e) {
            return errorText(e);
        }
    }

    /** Flatten an exception and its causes into one message string for the test surface / log. */
    private static String errorText(Throwable e) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = e; t != null && t != t.getCause(); t = t.getCause()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
        }
        return sb.toString();
    }

    /**
     * Remove the settings' password from a string, so it can never leak through
     * the test error or a log line. Belt-and-braces: jakarta.mail's own messages
     * don't include the password, but a chained provider exception might.
     */
    private static String scrub(String text, Settings settings) {
        if (text == null) return null;
        String pw = settings.password();
        if (pw == null || pw.isEmpty()) return text;
        return text.contains(pw) ? text.replace(pw, "***") : text;
    }

    /** Blank-is-unset env lookup — the one config idiom for CR-004's optional subsystems. */
    static String env(String name) {
        String value = System.getenv(name);
        return value != null && !value.isBlank() ? value : null;
    }
}
