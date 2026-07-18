# CR 001: Membership register data layer

Status: VERIFIED

## Problem

The app has no member register. Everything in the roadmap — financial
status, renewals, payments, segment email — needs the core schema of
[membership_management_database_schema.md](../membership_management_database_schema.md)
behind real queries. The existing `NoteStore` (per-owner JSON files) was
the webapp-template's worked example and cannot answer cross-member
questions ("who has paid for 2026–27?"), which is exactly the threshold
CLAUDE.md set for adding a database.

This CR delivers the foundations: Postgres in both stacks, Flyway
migrations for the full minimum schema, JDBI-backed store classes, an
admin-only people/households CRUD API, and a minimal register section in
the admin panel. Memberships and payments get tables now (the schema is
designed as a whole) but grow their APIs in CR-003; publications wait
for CR-004/005.

Decisions inherited from [docs/ROADMAP.md](../ROADMAP.md): Postgres,
JDBI 3 + hand-written SQL + Flyway, no ORM, single-tenant, only the
`relationship_type` MEMBER is a statutory voting member of a household
membership (corrected 2026-07-18 — see ROADMAP.md).

## Approach

### Stacks

Dev (`server/docker-compose.yml`): add a `postgres:17` service beside
Keycloak. Same dev semantics as Keycloak — **no volume**: `down` wipes
it, Flyway recreates the schema at next webapp start, fixtures reseed.
Host port `${POSTGRES_PORT:-5433}` (5433 because dev boxes often run
their own Postgres on 5432). Dev credentials `memberroll`/`memberroll`,
database `memberroll`.

Prod (`server/deploy/compose.yml`): second database in the existing
`postgres` service, per the comment already there — do NOT share the
`keycloak` database. An init script mounted at
`/docker-entrypoint-initdb.d` creates the `memberroll` role + database
on fresh provisions; because init scripts only run on an empty data
dir, the deploy README gains the one-liner for an already-provisioned
instance (`docker compose exec postgres createuser/createdb ...`).
Tomcat service gains the `MEMBERROLL_DB_*` env vars. Actual production
provisioning is CR-008; this CR keeps the compose files and Local smoke
green.

### Configuration

| env | default | notes |
|---|---|---|
| `MEMBERROLL_DB_URL` | `jdbc:postgresql://localhost:5433/memberroll` | dev default matches the dev compose |
| `MEMBERROLL_DB_USER` | `memberroll` | |
| `MEMBERROLL_DB_PASSWORD` | `memberroll` | prod sets a real secret via compose `.env` |

### Dependencies (each earning its keep)

- `org.jdbi:jdbi3-core` — fluent API only (no sqlobject plugin, no
  codegen): SQL stays literal text, rows map onto Java records via
  `ConstructorMapper`.
- `org.flywaydb:flyway-core` + `flyway-database-postgresql` —
  migrations run programmatically at startup; the SQL files under
  `src/main/resources/db/migration/` are the schema's source of truth.
- `com.zaxxer:HikariCP` — connection pool (max ~5; this is a
  hundreds-of-members app).
- `org.postgresql:postgresql` — driver.

### Lifecycle

A `@WebListener` (`Db`) builds the Hikari `DataSource`, runs
`Flyway.migrate()`, and exposes a `Jdbi` instance via a static accessor
(the `NoteStore.fromEnv()` idiom, adapted). Startup **fails fast** if
the database is unreachable or a migration fails — a war that can't see
its register should not come up half-alive. `GET /api/health` gains a
`db` field: `{"status":"ok","db":"ok"}`, or 503
`{"status":"degraded","db":"down"}` when a probe (`SELECT 1`) fails —
health stays public, no detail beyond that.

### Schema (V1 migration, all 14 minimum tables)

`person`, `household`, `household_person`, `membership_type`,
`membership_type_price`, `membership_period`, `membership`,
`membership_person`, `payment`, `payment_allocation`,
`household_address`, `email_address`, `phone_number`,
`communication_preference` — columns per the schema doc **as amended
2026-07-17** (see its revision history: household NOT NULL on
membership, no `amount_paid`, per-period prices, state-only status
list, `allocation_type` discriminator, audit columns on payment,
owned addresses). Engine decisions:

- `bigint GENERATED ALWAYS AS IDENTITY` primary keys (single-tenant;
  UUIDs rejected — no merge/distribution scenario, and bigints are
  friendlier to volunteers reading a psql prompt).
- Enumerations are `text` + `CHECK` constraints, not Postgres enum
  types (adding a value is a one-line migration; keeps the porting
  door open).
- Money is integer cents (`amount_cents`); no floating point, no
  `money` type.
- `date` for civil dates, `timestamptz` (UTC) for instants.
- Foreign keys `ON DELETE RESTRICT` throughout — the schema doc's
  business rules forbid history-destroying deletes.
- snake_case, singular table names, matching the schema doc.
- Constraints carried from the amended doc: `UNIQUE (household_id,
  membership_period_id)` on membership; partial UNIQUE on
  `payment.external_transaction_id` (webhook idempotency, needed
  before CR-004 can be trusted); `lower(email)` index and
  lowercase-on-write for `email_address`; person-XOR-household CHECK
  on `communication_preference`.

V2 migration seeds reference data: membership types SINGLE and
HOUSEHOLD, a 2026–27 `membership_period` with placeholder boundary
dates (flagged: confirm with the society; the 1 July rule lands in
`late_joining_cutoff`), and `membership_type_price` rows for that
period at the 2025/26 prices — SINGLE 4500, HOUSEHOLD 6500 cents
(flagged: confirm current prices before go-live).

### Store classes and API

`PersonStore` and `HouseholdStore` (package-private, constructor takes
`Jdbi`), hand-written SQL, rows mapped onto records by explicit lambda
row mappers (chosen over `ConstructorMapper` during implementation:
no reliance on `-parameters` compilation or name-matching magic — the
mapping is spelled out, like everything else here). Resources stay thin
and follow `AdminUsersResource`'s shape (jakarta.json in/out,
`@RolesAllowed("admin")`).

New endpoints, all admin-only:

| method | path | body/params | behaviour |
|---|---|---|---|
| GET | `/api/admin/people` | `q` (name/email substring), `limit` (default 50), `offset` | paged list, `{people:[...], total:n}` |
| POST | `/api/admin/people` | person JSON | 201 + stored person |
| GET | `/api/admin/people/{id}` | | 200 or 404 |
| PUT | `/api/admin/people/{id}` | person JSON | 200 + stored person |
| GET | `/api/admin/households` | `q`, `limit`, `offset` | paged list with member counts |
| POST | `/api/admin/households` | `{householdName, primaryContactPersonId}` | 201; creates the `household_person` row for the primary contact |
| GET | `/api/admin/households/{id}` | | household + current/past members |
| PUT | `/api/admin/households/{id}` | name / primary contact | 200 |
| POST | `/api/admin/households/{id}/people` | `{personId, relationshipType}` | adds a `household_person` row |
| DELETE | `/api/admin/households/{id}/people/{personId}` | | sets `left_household_date` (history-preserving; the row is never deleted) |

Person JSON carries nested `emails[]` and `phones[]` (the server
reconciles the child rows on PUT). There is deliberately **no** DELETE
for a person (schema-doc rule 7); departures are modelled by dates and,
later, membership status. Validation: `givenName` + `familyName`
required; ids are server-issued (no caller-chosen ids — unlike notes,
these are not path-component names).

### Admin UI

New "Register" section in `admin/`: People tab (search box, paged
list, create/edit form with emails/phones) and Households tab (list,
detail view showing members, add/remove member). Same client-side
gating + `@RolesAllowed` server half as the existing users section;
same framework-free classic-script style.

### NoteStore

Stays for now — it is still the template's worked example and the
verify-matrix covers it. It gets removed (or the notes endpoints do)
in a later cleanup once the register replaces it as the reference
pattern; flagged as a follow-up rather than mixed into this CR.

### Rejected alternatives

- **SQLite / MariaDB, ORM/Hibernate, jOOQ**: see ROADMAP decisions
  (2026-07-17) — recorded there because they bind the whole roadmap,
  not just this CR.
- **Sharing the `keycloak` database**: prod compose already warns
  against it; separate database = independent backup/restore and no
  entanglement with Keycloak upgrades.
- **Postgres enum types / UUID keys / numeric dollars**: above, inline.
- **Dev Postgres with a volume**: would diverge from the dev-Keycloak
  philosophy (checked-in config + fixtures ARE the state; `down` means
  fresh).

## Verification plan

Scripted matrix (extend `server/verify-matrix.sh`; fixtures under
`tmp/cr001-fixtures/`). Roles: guest, `testviewer` (no roles),
`testuser` (member), `testadmin` (admin).

*(Plan correction during implementation: guest on an admin endpoint is
403, not 401 — `RolesAllowedDynamicFeature` answers 403 for a caller
without the role whether or not they authenticated, matching the
pre-existing `admin/ping guest 403` case. Cases 2 and 19 updated.)*

| # | caller | call | expect |
|---|---|---|---|
| 1 | guest | GET /api/health | 200 `{"status":"ok","db":"ok"}` |
| 2 | guest | GET /api/admin/people | 403 |
| 3 | testviewer | GET /api/admin/people | 403 |
| 4 | testuser | GET /api/admin/people | 403 |
| 5 | testadmin | GET /api/admin/people | 200, `{people:[],total:0}` on fresh db |
| 6 | testadmin | POST /api/admin/people {givenName:"John",familyName:"Smith",emails:[{email:"john@example.com",isPrimary:true}]} | 201, body echoes stored person with server id |
| 7 | testadmin | POST /api/admin/people {} | 400 (missing required names) |
| 8 | testadmin | GET /api/admin/people/{id from 6} | 200, matches 6 |
| 9 | testadmin | GET /api/admin/people/999999 | 404 |
| 10 | testadmin | PUT /api/admin/people/{id} (add phone, change familyName) | 200, reflected on subsequent GET |
| 11 | testadmin | GET /api/admin/people?q=smith | 200, contains person from 6 |
| 12 | testadmin | GET /api/admin/people?q=zzz | 200, `total:0` |
| 13 | testadmin | POST /api/admin/people (second person "Mary Smith") | 201 |
| 14 | testadmin | POST /api/admin/households {householdName:"Smith household",primaryContactPersonId:{John}} | 201 |
| 15 | testadmin | POST /api/admin/households/{id}/people {personId:{Mary},relationshipType:"PARTNER"} | 200/201 |
| 16 | testadmin | GET /api/admin/households/{id} | 200, two current members, John primary |
| 17 | testadmin | DELETE /api/admin/households/{id}/people/{Mary} | 200; GET shows Mary with left_household_date, one current member |
| 18 | testadmin | POST /api/admin/households {primaryContactPersonId:999999} | 400/404 |
| 19 | guest | POST /api/admin/people | 403 |
| 20 | testuser | POST /api/admin/people | 403 |
| 21 | test-cli-noaud token | GET /api/admin/people | 401 (audience rejected — existing negative path still holds for new endpoints) |

Migration/lifecycle checks (scripted around the matrix):

| # | check | expect |
|---|---|---|
| M1 | fresh `docker compose down && up`, start Tomcat | Flyway applies V1+V2; `flyway_schema_history` has both; health db:ok |
| M2 | restart Tomcat against same db | no re-application, clean start |
| M3 | stop dev Postgres, hit /api/health | 503 `{"status":"degraded","db":"down"}` |
| M4 | seed check | `membership_type` contains SINGLE and HOUSEHOLD; one 2026–27 period row; `membership_type_price` has 4500/6500-cent rows for that period |

Existing matrix (notes, users, claims) must stay green — health body
assertions in the script updated for the new `db` field.

Browser walkthrough: log in to `admin/` as testadmin → Register →
create person, search finds them, edit adds a phone; create household,
add second member, remove them; confirm testuser sees the panel's
access-denied state, not the register.

Deploy Local smoke (deploy/README §6): required — this CR touches
`deploy/compose.yml` (init script + Tomcat env).

## Results

**2026-07-17**, dev machine. Environment note: another project's cargo
Tomcat + dev Keycloak were live on 8080/8081/8205, so this run used
Tomcat `18080` (cargo servlet/rmi/ajp port overrides), Keycloak `18081`
(`KEYCLOAK_PORT`, with `KEYCLOAK_ISSUER` passed to cargo), Postgres
`5433` (the dev default).

**HTTP matrix** (`PORT=18080 KEYCLOAK_PORT=18081 server/verify-matrix.sh`):
**PASS=83 FAIL=0** — the 48 pre-existing checks (health body updated for
the `db` field) plus new checks 35–59 covering plan cases 1–21:
role gates (guest 403, viewer 403, member 403, no-audience 401,
admin 200 on both collections), person create/validate/get/404/update
(email lowercased on write: `John.PID@Example.COM` stored as
`john.pid@example.com`; contact reconcile-by-replace confirmed: PUT with
phones-only left 1 phone, 0 emails), search hit=1/miss=0, household
create (primary contact auto-membership), bad-person 400, add member
201 → 2 current, duplicate add 409, remove sets `left_household_date`
(row retained) → 1 current, re-remove 404, remove-primary 400, rejoin
201 → 3 history rows, reassign primary 200, reassign-to-non-member 400.

**Lifecycle** (`tmp/cr001-fixtures/lifecycle.sh`):
- M1 fresh db: Flyway applied `1 core schema` + `2 reference data`,
  both `success=t`.
- M2 restart, same db: "Schema \"public\" is up to date. No migration
  necessary."
- M3 `docker stop memberroll-postgres-1` → health **503**
  `{"status":"degraded","db":"down"}`; after start, 200
  `{"status":"ok","db":"ok"}`.
- M4 seed: `SINGLE 4500`, `HOUSEHOLD 6500` for period `2026-2027`.

**Deploy Local smoke** (README §6, `KEYCLOAK_ADMIN_PORT=28081`): all
green — health 200 `{"status":"ok","db":"ok"}` through Caddy (proves
postgres-init created the `memberroll` database on a fresh provision
and Flyway migrated in the prod topology), issuer
`https://smoke.localhost/auth/realms/memberroll`, `/auth/admin/` 403,
root redirect `/server/web/`, password-grant `whoami` 200,
`membership_type` count 2 in the smoke database. Torn down with `-v`.

**Browser walkthrough: DONE** (Jason, 2026-07-17, on the project's
18xxx dev ports — the stack moved to Tomcat 18080 / Keycloak 18081,
pinned in pom.xml / docker-compose.yml / auth.js, after the matrix
runs above). One finding: the household "Add person id" number input
could spin below 1 — fixed with `min="1"` on both id inputs
(hdPersonId, hfContact); negative ids were already rejected
server-side (400 "no such person"), so this was cosmetic only. Matrix
re-run after the fix: PASS=83 FAIL=0.

**Surprises recorded:**
1. `DriverManager` service discovery misses drivers in Tomcat's webapp
   classloader — fixed with explicit
   `config.setDriverClassName("org.postgresql.Driver")`.
2. JDBI's SQL parser treats `\'` inside a string literal as an escaped
   quote, so `ESCAPE '\'` swallowed the rest of the statement and a
   `:pat` parameter reached Postgres raw. Fix: drop the `ESCAPE`
   clauses — backslash is already Postgres's default LIKE escape.
3. Guest on `@RolesAllowed` endpoints is 403, not 401 (plan corrected).
4. Discovered while starting the dev stack: docker compose infers the
   project name from the directory (`server`), which collided with
   another checkout's `server/` compose project and recreated its
   containers. Fixed permanently with a top-level `name: memberroll`
   in the dev compose (see the new CLAUDE.md bite).

## Follow-ups / amendments

- Replace/retire NoteStore + notes endpoints once the register is
  established as the reference pattern.
- Confirm with the society: 2026–27 prices, exact period boundary
  dates, renewal-open date (seeded values are placeholders).
- PersonAddress / publication tables arrive with the CR that needs
  them (CR-004/005).
