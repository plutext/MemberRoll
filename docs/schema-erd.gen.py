#!/usr/bin/env python3
"""Generate a Graphviz ERD for the MemberRoll schema (Flyway V1..V10).

The diagram is DERIVED from a hand-maintained model of the schema (the
TABLES and FKS structures below), NOT parsed from the SQL — so when a new
Flyway migration lands under server/src/main/resources/db/migration/, add
the new table/columns/foreign keys here and regenerate. Keeping it a model
(rather than a live parse) is deliberate: it lets the diagram carry the
[Vn] "added-in" badges and the domain colour-grouping the raw DDL can't.

Outputs (committed to docs/): schema-erd.svg, schema-erd.pdf (A3 landscape),
schema-erd.dot. Requires graphviz (dot) and librsvg (rsvg-convert):
    sudo pacman -S graphviz librsvg      # Arch/Manjaro
    sudo apt install graphviz librsvg2-bin   # Debian/Ubuntu

Regenerate (run from docs/):
    python3 schema-erd.gen.py                       # writes schema-erd.dot
    dot -Tsvg schema-erd.dot -o schema-erd.svg
    rsvg-convert -f pdf --page-width 420mm --page-height 297mm \
        --width 404mm --height 281mm --keep-aspect-ratio --left 8mm --top 8mm \
        -o schema-erd.pdf schema-erd.svg

The A3-fit numbers: A3 = 420x297mm; --width/--height leave an 8mm margin
(404 = 420-2*8, 281 = 297-2*8); --keep-aspect-ratio scales the near-square
drawing to fit, so it centres within the page height.
"""
import html

# domain -> header colour
GROUPS = {
    "people":     ("#1f6feb", "#dbeaff"),  # blue
    "membership": ("#8250df", "#efe3ff"),  # purple
    "payment":    ("#1a7f37", "#dcffe4"),  # green
    "email":      ("#bc4c00", "#ffe7d6"),  # orange
    "committee":  ("#9a6700", "#fff3cf"),  # amber
    "application":("#cf222e", "#ffd9dc"),  # red
    "misc":       ("#57606a", "#eaeef2"),  # grey
}

# table: (group, added-in, [ (col, kind) ... ])
# kind: "pk", "fk", "pkfk", "" ; port name == col name
TABLES = {
    "person": ("people", "V1", [
        ("person_id", "pk"), ("title", ""), ("given_name", ""), ("family_name", ""),
        ("preferred_name", ""), ("date_of_birth", ""), ("deceased_date", ""),
        ("notes", ""), ("keycloak_subject", "u:V6"), ("member_no", "u:V9"),
    ]),
    "household": ("people", "V1", [
        ("household_id", "pk"), ("household_name", ""),
        ("primary_contact_person_id", "fk"), ("status", ""),
    ]),
    "household_person": ("people", "V1", [
        ("household_person_id", "pk"), ("household_id", "fk"), ("person_id", "fk"),
        ("relationship_type", ""), ("joined_household_date", ""), ("left_household_date", ""),
    ]),
    "household_address": ("people", "V1", [
        ("household_address_id", "pk"), ("household_id", "fk"), ("address_type", ""),
        ("line_1", ""), ("line_2", ""), ("locality", ""), ("state", ""),
        ("postcode", ""), ("country", ""), ("valid_from", ""), ("valid_to", ""),
        ("is_preferred", ""),
    ]),
    "email_address": ("people", "V1", [
        ("email_id", "pk"), ("person_id", "fk"), ("email", ""),
        ("is_primary", ""), ("valid_from", ""), ("valid_to", ""),
    ]),
    "phone_number": ("people", "V1", [
        ("phone_number_id", "pk"), ("person_id", "fk"), ("number", ""),
        ("phone_type", ""), ("is_primary", ""), ("valid_from", ""), ("valid_to", ""),
    ]),
    "communication_preference": ("people", "V1", [
        ("communication_preference_id", "pk"), ("person_id", "fk (xor)"),
        ("household_id", "fk (xor)"), ("communication_type", ""),
        ("delivery_method", ""), ("consent_status", ""),
        ("effective_from", ""), ("effective_to", ""),
    ]),
    "membership_type": ("membership", "V1", [
        ("membership_type_id", "pk"), ("name", "u"), ("description", ""),
        ("minimum_people", ""), ("maximum_people", ""),
        ("active_from", ""), ("active_to", ""),
    ]),
    "membership_period": ("membership", "V1", [
        ("membership_period_id", "pk"), ("name", "u"), ("start_date", ""),
        ("end_date", ""), ("renewal_open_date", ""), ("late_joining_cutoff", ""),
        ("journal_price_cents", "V3"),
    ]),
    "membership_type_price": ("membership", "V1", [
        ("membership_type_id", "pkfk"), ("membership_period_id", "pkfk"),
        ("amount_cents", ""),
    ]),
    "membership": ("membership", "V1", [
        ("membership_id", "pk"), ("membership_period_id", "fk"),
        ("membership_type_id", "fk"), ("household_id", "fk"), ("status", ""),
        ("application_date", ""), ("approved_date", ""), ("start_date", ""),
        ("end_date", ""), ("amount_due_cents", ""), ("ceased_date", ""),
        ("cessation_reason", ""),
    ]),
    "membership_person": ("membership", "V1", [
        ("membership_person_id", "pk"), ("membership_id", "fk"), ("person_id", "fk"),
        ("membership_role", ""), ("is_statutory_member", ""), ("has_voting_rights", ""),
        ("eligible_for_committee", ""), ("start_date", ""), ("end_date", ""),
    ]),
    "payment": ("payment", "V1", [
        ("payment_id", "pk"), ("received_date", ""), ("amount_cents", ""),
        ("payment_method", ""), ("payer_person_id", "fk"), ("bank_reference", ""),
        ("external_transaction_id", "u"), ("reconciliation_status", ""),
        ("recorded_by", ""), ("recorded_at", ""), ("notes", ""),
    ]),
    "payment_allocation": ("payment", "V1", [
        ("payment_allocation_id", "pk"), ("payment_id", "fk"),
        ("allocation_type", ""), ("membership_id", "fk"), ("amount_cents", ""),
    ]),
    "renewal_token": ("payment", "V3", [
        ("renewal_token_id", "pk"), ("membership_id", "fk"), ("token_hash", "u"),
        ("created_at", ""), ("expires_at", ""), ("used_at", ""),
    ]),
    "email_template": ("email", "V5", [
        ("email_template_id", "pk"), ("name", "u"), ("subject", ""),
        ("body", ""), ("updated_by", ""), ("updated_at", ""),
    ]),
    "email_send": ("email", "V5", [
        ("email_send_id", "pk"), ("email_template_id", "fk"), ("subject", ""),
        ("body", ""), ("membership_period_id", "fk"), ("status_filter", ""),
        ("type_filter", "fk"), ("communication_type", ""), ("status", ""),
        ("created_by", ""), ("created_at", ""), ("finished_at", ""),
    ]),
    "email_send_recipient": ("email", "V5", [
        ("email_send_recipient_id", "pk"), ("email_send_id", "fk"),
        ("membership_id", "fk"), ("person_id", "fk"), ("email", ""),
        ("status", ""), ("error", ""), ("renewal_token_id", "fk"), ("sent_at", ""),
    ]),
    "app_setting": ("misc", "V5", [
        ("key", "pk"), ("value", ""), ("updated_by", ""), ("updated_at", ""),
    ]),
    "committee_appointment": ("committee", "V7", [
        ("committee_appointment_id", "pk"), ("person_id", "fk"), ("office", ""),
        ("started_date", ""), ("ended_date", ""), ("elected_date", ""),
        ("minute_ref", ""), ("notes", ""), ("recorded_by", ""), ("recorded_at", ""),
    ]),
    "membership_application": ("application", "V10", [
        ("application_id", "pk"), ("status", ""), ("submitted_at", ""),
        ("submitted_ip", ""), ("confirm_token_hash", ""), ("confirm_expires_at", ""),
        ("confirmed_at", ""), ("membership_type_id", "fk"), ("address_line_1", ""),
        ("address_line_2", ""), ("locality", ""), ("state", ""), ("postcode", ""),
        ("applicant_message", ""), ("decision_date", ""), ("minute_reference", ""),
        ("rejection_reason", ""), ("decided_by", ""),
        ("created_household_id", "fk"), ("created_membership_id", "fk"),
    ]),
    "membership_application_person": ("application", "V10", [
        ("application_person_id", "pk"), ("application_id", "fk"), ("position", ""),
        ("given_name", ""), ("family_name", ""), ("email", ""), ("phone", ""),
        ("relationship", ""),
    ]),
}

# (from_table, from_col) -> (to_table, to_col, style)   style: solid / dashed(nullable)
FKS = [
    ("household", "primary_contact_person_id", "person", "person_id", "solid"),
    ("household_person", "household_id", "household", "household_id", "solid"),
    ("household_person", "person_id", "person", "person_id", "solid"),
    ("household_address", "household_id", "household", "household_id", "solid"),
    ("email_address", "person_id", "person", "person_id", "solid"),
    ("phone_number", "person_id", "person", "person_id", "solid"),
    ("communication_preference", "person_id", "person", "person_id", "dashed"),
    ("communication_preference", "household_id", "household", "household_id", "dashed"),
    ("membership_type_price", "membership_type_id", "membership_type", "membership_type_id", "solid"),
    ("membership_type_price", "membership_period_id", "membership_period", "membership_period_id", "solid"),
    ("membership", "membership_period_id", "membership_period", "membership_period_id", "solid"),
    ("membership", "membership_type_id", "membership_type", "membership_type_id", "solid"),
    ("membership", "household_id", "household", "household_id", "solid"),
    ("membership_person", "membership_id", "membership", "membership_id", "solid"),
    ("membership_person", "person_id", "person", "person_id", "solid"),
    ("payment", "payer_person_id", "person", "person_id", "dashed"),
    ("payment_allocation", "payment_id", "payment", "payment_id", "solid"),
    ("payment_allocation", "membership_id", "membership", "membership_id", "dashed"),
    ("renewal_token", "membership_id", "membership", "membership_id", "solid"),
    ("email_send", "email_template_id", "email_template", "email_template_id", "dashed"),
    ("email_send", "membership_period_id", "membership_period", "membership_period_id", "solid"),
    ("email_send", "type_filter", "membership_type", "membership_type_id", "dashed"),
    ("email_send_recipient", "email_send_id", "email_send", "email_send_id", "solid"),
    ("email_send_recipient", "membership_id", "membership", "membership_id", "solid"),
    ("email_send_recipient", "person_id", "person", "person_id", "dashed"),
    ("email_send_recipient", "renewal_token_id", "renewal_token", "renewal_token_id", "dashed"),
    ("committee_appointment", "person_id", "person", "person_id", "solid"),
    ("membership_application", "membership_type_id", "membership_type", "membership_type_id", "solid"),
    ("membership_application", "created_household_id", "household", "household_id", "dashed"),
    ("membership_application", "created_membership_id", "membership", "membership_id", "dashed"),
    ("membership_application_person", "application_id", "membership_application", "application_id", "solid"),
]

def esc(s):
    return html.escape(s, quote=True)

def node_label(table, group, added, cols):
    hdr, _ = GROUPS[group]
    rows = []
    badge = "" if added == "V1" else f' <font point-size="9" color="#ffffff">[{added}]</font>'
    rows.append(
        f'<tr><td bgcolor="{hdr}" port="__title" align="left">'
        f'<font color="#ffffff"><b>{esc(table)}</b></font>{badge}</td></tr>'
    )
    for col, kind in cols:
        icon = ""
        note = ""
        col_txt = esc(col)
        base = kind.split(":")[0].split(" ")[0]
        if base == "pk":
            col_txt = f"<b>{col_txt}</b>"
            note = ' <font color="#b58900" point-size="9">PK</font>'
        elif base == "pkfk":
            col_txt = f"<b>{col_txt}</b>"
            note = ' <font color="#b58900" point-size="9">PK</font> <font color="#57606a" point-size="9">FK</font>'
        elif base == "fk":
            icon = ""
            note = ' <font color="#57606a" point-size="9">FK</font>'
        elif base == "u":
            note = ' <font color="#57606a" point-size="9">unique</font>'
        # added-in badge on a column (e.g. V3/V6/V9)
        if ":" in kind:
            tag = kind.split(":")[1]
            note += f' <font color="#8250df" point-size="9">[{tag}]</font>'
        elif base in ("V3", "V6", "V9"):
            note += f' <font color="#8250df" point-size="9">[{base}]</font>'
        elif "xor" in kind:
            note += ' <font color="#57606a" point-size="9">FK · xor</font>'
        icon_span = f'<font color="#b58900" point-size="9">{icon}</font>' if icon else ""
        rows.append(
            f'<tr><td port="{esc(col)}" align="left">'
            f'{icon_span}{col_txt}{note}</td></tr>'
        )
    inner = "".join(rows)
    return (f'  "{table}" [label=<'
            f'<table border="0" cellborder="1" cellspacing="0" cellpadding="4">'
            f'{inner}</table>>];')

out = []
out.append('digraph MemberRoll {')
out.append('  graph [rankdir=TB, splines=spline, overlap=false, nodesep=0.45, '
           'ranksep=0.9, fontname="Helvetica", pad=0.4, bgcolor="white"];')
out.append('  node  [shape=plain, fontname="Helvetica", fontsize=11];')
out.append('  edge  [color="#8b949e", arrowsize=0.8, arrowhead=normal, penwidth=1.1];')
out.append('')
out.append('  labelloc="t";')
out.append('  label=<<font point-size="22"><b>MemberRoll — database schema</b></font>'
           '<br/><font point-size="11" color="#57606a">'
           'Flyway V1–V10 · 22 tables · <b>bold</b>+PK = primary key · '
           'FK = foreign key · solid edge = required FK · dashed edge = nullable FK · '
           '[Vn] = column/table added in that migration</font>>;')
out.append('  fontname="Helvetica";')
out.append('')

for t, (g, added, cols) in TABLES.items():
    out.append(node_label(t, g, added, cols))

out.append('')
for ft, fc, tt, tc, style in FKS:
    out.append(f'  "{ft}":"{fc}":e -> "{tt}":"{tc}":w [style={style}];')

out.append('}')

with open("schema-erd.dot", "w") as f:
    f.write("\n".join(out))
print("wrote schema-erd.dot")
