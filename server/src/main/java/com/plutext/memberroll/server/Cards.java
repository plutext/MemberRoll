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
import jakarta.json.JsonObjectBuilder;

import org.jdbi.v3.core.Handle;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.imageio.ImageIO;

/**
 * The one membership-card renderer (CR-017). The CR-012 lesson applied again:
 * one renderer, several delivery surfaces (the on-screen copy, the download,
 * the print pop-up and the emailed attachment), so they are the same document.
 * It composes from CURRENT register state at request time — never from anything
 * stored — and only for a membership whose status is ACTIVE: a card asserts
 * financial standing, so an unpaid or lapsed membership simply has no card.
 * Staleness needs no revocation machinery — the card carries "Valid to
 * &lt;period end_date&gt;", so a saved image expires visibly on its own.
 *
 * <p>Cards are for MEMBER-relationship people only (the voting-rights
 * composition rule's sibling): {@link #compose} returns a card only when the
 * person is a CURRENT MEMBER-relationship member of the ACTIVE membership's
 * household. Empty otherwise — the caller renders that as an indistinguishable
 * 404, exactly like CR-006 pay-link, so nothing enumerates.
 *
 * <p>Rendering is a PNG via Java2D (JDK-only, no new dependency), at the
 * credit-card ratio 85.6 × 54 mm and 300 dpi (1012 × 638 px) so a printed card
 * is crisp at true size. A headless server's font set is unreliable, so the
 * bundled DejaVu Sans TTFs are loaded with {@link Font#createFont} — never
 * {@code new Font("SansSerif")}. Like {@link Receipts}, there is a JSON
 * companion ({@link #toJson}) — the assertable surface for the matrix and the
 * dialogs, so the PNG itself only ever needs a magic-bytes/size check.
 */
final class Cards {

    private static final Logger LOG = Logger.getLogger(Cards.class.getName());

    /** Credit-card 85.6 × 54 mm at 300 dpi — a printed card is crisp at true size. */
    static final int WIDTH = 1012;
    static final int HEIGHT = 638;

    // "31 August 2027" — the Sep–Aug year's human-facing validity line
    private static final DateTimeFormatter LONG_DATE =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    private Cards() {}

    /**
     * A composed card. {@code personName} is how the person is actually known
     * (preferred name when set, else given name, plus family name).
     * {@code memberNo} is the display number (CR-020): the assigned
     * {@code person.member_no} when set, else the person id — resolved by
     * COALESCE in {@link #compose}, so every surface inherits it from the one
     * spot. {@code personId} stays the compose key (pay-link, primary-email
     * lookups), never the printed number.
     */
    record Card(long personId, long memberNo, String personName, String societyName,
                String typeName, String periodName, LocalDate validTo) {

        String validToText() {
            return validTo.format(LONG_DATE);
        }

        String memberNoText() {
            return "Member no. " + memberNo;
        }

        /** The download / attachment filename — period-stamped, filesystem-safe. */
        String filename() {
            String period = periodName.replaceAll("[^A-Za-z0-9._-]+", "-");
            return "membership-card-" + period + ".png";
        }
    }

    /**
     * Compose the card for a MEMBER-relationship person of an ACTIVE
     * membership; empty otherwise (unknown membership, unknown person, a
     * non-MEMBER covered person, or a membership that is not ACTIVE). The one
     * SQL is the gate — it re-derives entitlement from current register state,
     * so a person removed from the household loses their card with no other
     * action.
     */
    static Optional<Card> compose(Handle handle, long membershipId, long personId) {
        return handle.createQuery(
                "SELECT p.person_id, COALESCE(p.member_no, p.person_id) AS member_no,"
                + " p.given_name, p.family_name, p.preferred_name,"
                + " mt.name AS type_name, per.name AS period_name, per.end_date"
                + " FROM membership m"
                + " JOIN membership_period per ON per.membership_period_id = m.membership_period_id"
                + " JOIN membership_type mt ON mt.membership_type_id = m.membership_type_id"
                + " JOIN household_person hp ON hp.household_id = m.household_id"
                + "   AND hp.person_id = :pid AND hp.left_household_date IS NULL"
                + "   AND hp.relationship_type = 'MEMBER'"
                + " JOIN person p ON p.person_id = hp.person_id"
                + " WHERE m.membership_id = :mid AND m.status = 'ACTIVE'")
                .bind("mid", membershipId).bind("pid", personId)
                .map((rs, ctx) -> {
                    String preferred = rs.getString("preferred_name");
                    String given = rs.getString("given_name");
                    String first = preferred != null && !preferred.isBlank() ? preferred : given;
                    String name = (first + " " + rs.getString("family_name")).trim();
                    return new Card(rs.getLong("person_id"), rs.getLong("member_no"),
                            name, Mail.societyName(),
                            rs.getString("type_name"), rs.getString("period_name"),
                            rs.getDate("end_date").toLocalDate());
                })
                .findOne();
    }

    /**
     * A person's primary register email (primary flag wins, then lowest email
     * id), or empty. The default card recipient for both surfaces — the
     * member's own address, and the admin dialog's prefill.
     */
    static Optional<String> primaryEmail(Handle handle, long personId) {
        return handle.createQuery(
                "SELECT email FROM email_address WHERE person_id = :pid"
                + " ORDER BY is_primary DESC, email_id LIMIT 1")
                .bind("pid", personId).mapTo(String.class).findOne();
    }

    // ---- the assertable JSON companion --------------------------------------

    /**
     * The card's fields as JSON — the surface the dialogs read and the matrix
     * asserts, so the opaque PNG only ever needs a magic-bytes/size check. The
     * caller adds its own recipient field ({@code emailTo} for the member,
     * {@code defaultTo} for the admin) onto this builder.
     */
    static JsonObjectBuilder toJson(Card c, boolean mailEnabled) {
        return Json.createObjectBuilder()
                .add("membershipCard", true)
                .add("name", c.personName())
                .add("societyName", c.societyName())
                .add("typeName", c.typeName())
                .add("periodName", c.periodName())
                .add("validTo", c.validTo().toString())
                .add("validToText", c.validToText())
                .add("memberNo", c.memberNo())
                .add("filename", c.filename())
                // lets the page/dialog disable Email upfront, the CR-005 mail banner
                .add("mailEnabled", mailEnabled);
    }

    /** The email subject line for a card send (per-tenant branded). */
    static String subject(Card c) {
        return c.societyName() + " — membership card";
    }

    /** The short plain-text body that accompanies the attached card. */
    static String emailBody(Card c) {
        return c.societyName() + "\n\n"
                + "Your membership card is attached as a PNG — save it to your phone,"
                + " or print it and cut it out.\n\n"
                + "Member: " + c.personName() + "\n"
                + c.typeName() + " membership, " + c.periodName() + "\n"
                + "Valid to " + c.validToText() + "\n"
                + c.memberNoText() + "\n\n"
                + c.societyName() + "\n";
    }

    /** The card as a Mail attachment, for the one-shot transactional send. */
    static Mail.Attachment attachment(Card c) {
        return new Mail.Attachment(c.filename(), "image/png", png(c));
    }

    // ---- the PNG ------------------------------------------------------------

    // palette: deep-blue header band matching the admin panel's blue badge,
    // cream card face, dark body text — legible at true print size
    private static final Color BAND = new Color(0x1c, 0x4f, 0x8a);
    private static final Color BAND_TEXT = Color.WHITE;
    private static final Color FACE = new Color(0xfb, 0xfa, 0xf7);
    private static final Color BORDER = new Color(0xcf, 0xcb, 0xc2);
    private static final Color INK = new Color(0x22, 0x22, 0x22);
    private static final Color MUTED = new Color(0x5a, 0x5a, 0x5a);

    /**
     * Render the card to PNG bytes. All layout is in device pixels at 300 dpi.
     * Never throws for a well-formed card: an unreadable optional logo is
     * logged and skipped (text-only), not fatal.
     */
    static byte[] png(Card c) {
        BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                    RenderingHints.VALUE_FRACTIONALMETRICS_ON);

            // face + border
            g.setColor(FACE);
            g.fillRect(0, 0, WIDTH, HEIGHT);
            g.setColor(BORDER);
            g.setStroke(new java.awt.BasicStroke(3f));
            g.drawRect(1, 1, WIDTH - 3, HEIGHT - 3);

            // header band
            int bandH = 168;
            g.setColor(BAND);
            g.fillRect(0, 0, WIDTH, bandH);

            int pad = 56;
            int textLeft = pad;

            // optional logo, top-left of the band; text shifts right when present
            BufferedImage logo = logo();
            if (logo != null) {
                int box = bandH - 48; // 24px inset top/bottom
                int y = (bandH - box) / 2;
                double scale = Math.min((double) box / logo.getWidth(), (double) box / logo.getHeight());
                int w = (int) Math.round(logo.getWidth() * scale);
                int h = (int) Math.round(logo.getHeight() * scale);
                g.drawImage(logo, pad, y + (box - h) / 2, w, h, null);
                textLeft = pad + box + 32;
            }

            // society name in the band (wraps to two lines if long)
            g.setColor(BAND_TEXT);
            drawFitted(g, c.societyName(), bold(), textLeft, bandH, WIDTH - textLeft - pad, 54f, 40f);

            // body
            int x = pad;
            int y = bandH + 96;
            g.setColor(INK);
            g.setFont(bold().deriveFont(66f));
            g.drawString(c.personName(), x, y);

            y += 78;
            g.setColor(MUTED);
            g.setFont(regular().deriveFont(38f));
            g.drawString(c.typeName() + " membership · " + c.periodName(), x, y);

            y += 66;
            g.setColor(INK);
            g.setFont(regular().deriveFont(40f));
            g.drawString("Valid to " + c.validToText(), x, y);

            // member number, bottom-left; larger and slightly emphasised
            g.setColor(MUTED);
            g.setFont(regular().deriveFont(36f));
            g.drawString(c.memberNoText(), x, HEIGHT - pad + 6);

            g.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            // ImageIO writing to memory does not do real IO; treat as fatal-but-typed
            throw new IllegalStateException("card render failed", e);
        }
    }

    /**
     * Draw {@code text} at {@code (left, bandBottom)} baselined so it sits
     * vertically centred in the band, shrinking one step if it would overflow
     * {@code maxWidth} at the preferred size. A long society name is the only
     * variable-length band string, so one shrink step is enough in practice.
     */
    private static void drawFitted(Graphics2D g, String text, Font base, int left, int bandH,
                                   int maxWidth, float preferred, float fallback) {
        Font f = base.deriveFont(preferred);
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(text) > maxWidth) {
            f = base.deriveFont(fallback);
            g.setFont(f);
            fm = g.getFontMetrics();
        }
        int baseline = (bandH - fm.getHeight()) / 2 + fm.getAscent();
        g.drawString(text, left, baseline);
    }

    // ---- fonts + logo (cached, classpath-loaded) ----------------------------

    private static volatile Font regularFont;
    private static volatile Font boldFont;

    private static Font regular() {
        if (regularFont == null) regularFont = load("/card/DejaVuSans.ttf");
        return regularFont;
    }

    private static Font bold() {
        if (boldFont == null) boldFont = load("/card/DejaVuSans-Bold.ttf");
        return boldFont;
    }

    /**
     * Load a bundled TTF. On a headless server {@code new Font("SansSerif")}
     * may resolve to a font with no glyphs, so the card fonts are always the
     * bundled files. If even that fails (should not — they ship in the war),
     * fall back to the logical sans so a card still renders rather than 500s.
     */
    private static Font load(String resource) {
        try (InputStream in = Cards.class.getResourceAsStream(resource)) {
            if (in == null) {
                LOG.warning("card font resource missing: " + resource + " — using logical sans");
                return new Font(Font.SANS_SERIF, Font.PLAIN, 1);
            }
            return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "loading card font " + resource + " failed — using logical sans", e);
            return new Font(Font.SANS_SERIF, Font.PLAIN, 1);
        }
    }

    // the logo slot: an optional repo asset (the fork drops in its own file).
    // Cached as present/absent so a missing file is read at most once.
    private static volatile BufferedImage logoImage;
    private static volatile boolean logoChecked;

    private static BufferedImage logo() {
        if (!logoChecked) {
            synchronized (Cards.class) {
                if (!logoChecked) {
                    try (InputStream in = Cards.class.getResourceAsStream("/card/logo.png")) {
                        logoImage = in == null ? null : ImageIO.read(in);
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, "reading card/logo.png failed — rendering text-only", e);
                        logoImage = null;
                    }
                    logoChecked = true;
                }
            }
        }
        return logoImage;
    }
}
