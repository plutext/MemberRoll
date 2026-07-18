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

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Minimal SMTP sender (CR-004) — deliberately less than CR-005 will bring
 * (no templates-as-data, no send log, no preferences): just what a payment
 * receipt and a lost-link reply need. Env-configured; SMTP_HOST unset means
 * mail is disabled and {@link #send} is a logged no-op — callers treat every
 * send as best-effort (a receipt failure must never fail the webhook that
 * recorded the payment). {@link #sendAsync} is the normal entry point from
 * request threads: SMTP must never hold a Tomcat thread past the response
 * (Stripe times webhooks out in seconds, and lost-link response time must
 * not reveal whether a mail is being sent).
 */
final class Mail {

    private static final Logger LOG = Logger.getLogger(Mail.class.getName());

    // one daemon thread: sends are rare (a payment, a lost-link click) and
    // strictly best-effort, so a queue that dies with the JVM is fine
    private static final ExecutorService SENDER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mail-sender");
        t.setDaemon(true);
        return t;
    });

    private Mail() {}

    static boolean enabled() {
        return env("SMTP_HOST") != null;
    }

    /** The society name for email + pay-page branding (single-tenant rule: no "Yass" in code). */
    static String societyName() {
        String name = env("MEMBERROLL_SOCIETY_NAME");
        return name != null ? name : "memberroll dev";
    }

    /** Queue a plain-text mail off the calling thread; always returns immediately. */
    static void sendAsync(String to, String subject, String body) {
        SENDER.submit(() -> send(to, subject, body));
    }

    /** Run mail-adjacent work (lookup + compose + send) on the mail thread. */
    static void async(Runnable task) {
        SENDER.submit(task);
    }

    /**
     * Send a plain-text mail; false (logged) on any failure or when mail is
     * disabled. Never throws.
     */
    static boolean send(String to, String subject, String body) {
        String host = env("SMTP_HOST");
        if (host == null) {
            LOG.warning("mail disabled (SMTP_HOST unset) — not sending \"" + subject + "\" to " + to);
            return false;
        }
        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", host);
            props.put("mail.smtp.port", env("SMTP_PORT") != null ? env("SMTP_PORT") : "25");
            // jakarta.mail's default timeouts are INFINITE — a half-open SMTP
            // host would otherwise pin the sender thread forever
            props.put("mail.smtp.connectiontimeout", "10000");
            props.put("mail.smtp.timeout", "10000");
            props.put("mail.smtp.writetimeout", "10000");
            String username = env("SMTP_USERNAME");
            Authenticator auth = null;
            if (username != null) {
                props.put("mail.smtp.auth", "true");
                auth = new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(username, env("SMTP_PASSWORD"));
                    }
                };
            }
            if (Boolean.parseBoolean(env("SMTP_STARTTLS"))) props.put("mail.smtp.starttls.enable", "true");
            Session session = Session.getInstance(props, auth);
            MimeMessage message = new MimeMessage(session);
            String from = env("MAIL_FROM");
            message.setFrom(new InternetAddress(from != null ? from : "noreply@localhost"));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            message.setSubject(subject, "UTF-8");
            message.setText(body, "UTF-8");
            Transport.send(message);
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "mail send failed: \"" + subject + "\" to " + to, e);
            return false;
        }
    }

    /** Blank-is-unset env lookup — the one config idiom for CR-004's optional subsystems. */
    static String env(String name) {
        String value = System.getenv(name);
        return value != null && !value.isBlank() ? value : null;
    }
}
