#!/usr/bin/env bash
# Copyright 2026 Jason Harrop
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# The role x endpoint verification matrix, run against the dev stack.
# Ports via env: PORT (Tomcat, default 18080), KEYCLOAK_PORT (default 18081) —
# the project's dev stack lives on 18xxx ports (see docker-compose.yml).
# Needs the dev realm (test-cli + test users). Mutates testviewer's claim
# and grants manager — dev Keycloak only (docker compose down resets).
#
# CR-008: every environment coupling is env-overridable so the SAME matrix
# runs against the deploy local smoke (production topology + KEEP_TEST_FIXTURES
# render — deploy/README.md "Local smoke"); defaults keep the dev invocation
# byte-identical. ORIGIN (page/app origin), KC_BASE (public Keycloak base,
# /auth included where applicable), KC_ADMIN_BASE (master realm + admin REST —
# the smoke's Caddy 403s those publicly, so it uses the loopback admin port),
# CURL_OPTS (e.g. -k --resolve smoke.localhost:443:127.0.0.1), POSTGRES_PORT /
# MEMBERROLL_DB_PASSWORD (psql rows), MAILPIT_UI_PORT, MAILPIT_COMPOSE (which
# stack's mailpit the abort/resume rows stop), RELAY_HOST/RELAY_PORT (the
# relay as the SERVER reaches it — in-network for the smoke).
set -u
ORIGIN=${ORIGIN:-http://localhost:${PORT:-18080}}
API=$ORIGIN/server/api
KC_BASE=${KC_BASE:-http://localhost:${KEYCLOAK_PORT:-18081}}
KC_ADMIN_BASE=${KC_ADMIN_BASE:-$KC_BASE}
KC=$KC_BASE/realms/memberroll/protocol/openid-connect/token
MAILPIT_COMPOSE=${MAILPIT_COMPOSE:-docker compose -f server/docker-compose.yml}
RELAY_HOST=${RELAY_HOST:-localhost}
RELAY_PORT=${RELAY_PORT:-18026}
curl() { command curl ${CURL_OPTS:-} "$@"; }
PASS=0; FAIL=0

check() { # name expected actual
  if [ "$2" = "$3" ]; then PASS=$((PASS+1)); echo "ok   $1"; else FAIL=$((FAIL+1)); echo "FAIL $1: expected [$2] got [$3]"; fi
}

jsq() { python3 -c "import sys,json; j=json.load(sys.stdin); print($1)" 2>/dev/null; }

tok() { curl -s -X POST "$KC" -d grant_type=password -d client_id="$2" -d username="$1" -d password="$1" | jsq "j['access_token']"; }

code() { curl -s -o /tmp/body.$$ -w '%{http_code}' "$@"; }
body() { cat /tmp/body.$$; }

ADMIN=$(tok testadmin test-cli)
USER=$(tok testuser test-cli)
VIEWER=$(tok testviewer test-cli)
NOAUD=$(tok testuser test-cli-noaud)

# --- auth basics -----------------------------------------------------------
check "1  health guest 200"            "200" "$(code $API/health)"
check "1b health body"                 '{"status":"ok","db":"ok"}' "$(body)"
check "2  whoami guest 401"            "401" "$(code $API/whoami)"
check "3  admin/ping guest 403"        "403" "$(code $API/admin/ping)"
check "4  whoami admin 200"            "200" "$(code $API/whoami -H "Authorization: Bearer $ADMIN")"
check "4b whoami admin has role"       "true" "$(body | jsq "str('admin' in j['roles']).lower()")"
check "5  admin/ping admin 200"        "200" "$(code $API/admin/ping -H "Authorization: Bearer $ADMIN")"
check "6  admin/ping user 403"         "403" "$(code $API/admin/ping -H "Authorization: Bearer $USER")"
check "7  whoami user member role"     "true" "$(curl -s $API/whoami -H "Authorization: Bearer $USER" | jsq "str('member' in j['roles']).lower()")"
check "8  noaud token 401"             "401" "$(code $API/whoami -H "Authorization: Bearer $NOAUD")"
check "9  garbage token 401"           "401" "$(code $API/whoami -H "Authorization: Bearer garbage.garbage.garbage")"

USER_SUB=$(curl -s $API/whoami -H "Authorization: Bearer $USER" | jsq "j['sub']")
VIEWER_SUB=$(curl -s $API/whoami -H "Authorization: Bearer $VIEWER" | jsq "j['sub']")

# notes rows 10–22 retired with NotesResource (CR-006); the placeholder's
# absence is asserted as CR6-13 below.

# --- claims -----------------------------------------------------------------
check "23 claim bad role 400"          "400" "$(code -X PUT $API/me/claim -H "Authorization: Bearer $VIEWER" -H 'Content-Type: application/json' -d '{"role":"admin"}')"
check "24 viewer claims member 200"    "200" "$(code -X PUT $API/me/claim -H "Authorization: Bearer $VIEWER" -H 'Content-Type: application/json' -d '{"role":"member"}')"
check "24b claim granted"              '["member"]' "$(body | jsq "json.dumps(j['granted'],separators=(',',':'))")"
VIEWER2=$(tok testviewer test-cli)   # fresh token reflects the grant
check "25 fresh token has member"      "true" "$(curl -s $API/whoami -H "Authorization: Bearer $VIEWER2" | jsq "str('member' in j['roles']).lower()")"
check "25b claimed_role in whoami"     "member" "$(curl -s $API/whoami -H "Authorization: Bearer $VIEWER2" | jsq "j['claimed_role']")"
check "25c unverified after claim"     "false" "$(curl -s $API/whoami -H "Authorization: Bearer $VIEWER2" | jsq "str(j['verified']).lower()")"

# --- admin users section -------------------------------------------------------
check "26 users list user 403"         "403" "$(code "$API/admin/users" -H "Authorization: Bearer $USER")"
check "27 users list admin 200"        "200" "$(code "$API/admin/users" -H "Authorization: Bearer $ADMIN")"
check "27b list has testuser"          "true" "$(body | jsq "str(any(u['username']=='testuser' for u in j)).lower()")"
check "27c no service account"         "false" "$(body | jsq "str(any(u['username'].startswith('service-account') for u in j)).lower()")"
check "28 verify viewer claim 200"     "200" "$(code -X PUT "$API/admin/users/$VIEWER_SUB/verified" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{"verified":true}')"
VIEWER3=$(tok testviewer test-cli)
check "28b verified now true"          "true" "$(curl -s $API/whoami -H "Authorization: Bearer $VIEWER3" | jsq "str(j['verified']).lower()")"
check "29 grant manager 200"           "200" "$(code -X PUT "$API/admin/users/$VIEWER_SUB/manager" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{"granted":true}')"
check "29b manager in roles"           "true" "$(body | jsq "str('manager' in j['roles']).lower()")"
check "30 admin correct claim 200"     "200" "$(code -X PUT "$API/admin/users/$VIEWER_SUB/claim" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{"role":"other"}')"
check "30b claim now other"            "other" "$(body | jsq "j['claimed_role']")"
check "30c verified reset"             "false" "$(body | jsq "str(j['verified']).lower()")"
check "30d manager survives claim sync" "true" "$(body | jsq "str('manager' in j['roles']).lower()")"
check "31 users unknown id 404"        "404" "$(code -X PUT "$API/admin/users/00000000-0000-0000-0000-000000000000/verified" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{"verified":true}')"

# --- register: people + households (CR-001) ---------------------------------
# A unique per-run family name keeps re-runs green: people are never
# deleted (schema rule 7), so assertions search rather than assume a
# fresh database.
FAM="Vermat$$"

check "35 people guest 403"            "403" "$(code $API/admin/people)"
check "36 people viewer 403"           "403" "$(code $API/admin/people -H "Authorization: Bearer $VIEWER")"
check "37 people user 403"             "403" "$(code $API/admin/people -H "Authorization: Bearer $USER")"
check "38 people noaud 401"            "401" "$(code $API/admin/people -H "Authorization: Bearer $NOAUD")"
check "39 people admin 200"            "200" "$(code $API/admin/people -H "Authorization: Bearer $ADMIN")"
check "40 create person empty 400"     "400" "$(code -X POST $API/admin/people -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{}')"
check "41 create John 201"             "201" "$(code -X POST $API/admin/people -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d "{\"givenName\":\"John\",\"familyName\":\"$FAM\",\"emails\":[{\"email\":\"John.$$@Example.COM\",\"isPrimary\":true}]}")"
JOHN=$(body | jsq "j['id']")
check "41b email lowercased"           "john.$$@example.com" "$(body | jsq "j['emails'][0]['email']")"
check "42 GET John 200"                "200" "$(code $API/admin/people/$JOHN -H "Authorization: Bearer $ADMIN")"
check "42b family name"                "$FAM" "$(body | jsq "j['familyName']")"
check "43 GET absent person 404"       "404" "$(code $API/admin/people/999999 -H "Authorization: Bearer $ADMIN")"
check "44 PUT John phone 200"          "200" "$(code -X PUT $API/admin/people/$JOHN -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d "{\"givenName\":\"John\",\"familyName\":\"$FAM\",\"phones\":[{\"number\":\"0400 000 000\",\"type\":\"MOBILE\",\"isPrimary\":true}]}")"
check "44b phone stored"               "1" "$(body | jsq "len(j['phones'])")"
check "44c emails replaced"            "0" "$(body | jsq "len(j['emails'])")"
check "45 search family total 1"       "1" "$(curl -s "$API/admin/people?q=$FAM" -H "Authorization: Bearer $ADMIN" | jsq "j['total']")"
check "46 search miss total 0"         "0" "$(curl -s "$API/admin/people?q=zzznope$$" -H "Authorization: Bearer $ADMIN" | jsq "j['total']")"
check "47 create Mary 201"             "201" "$(code -X POST $API/admin/people -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d "{\"givenName\":\"Mary\",\"familyName\":\"$FAM\"}")"
MARY=$(body | jsq "j['id']")
check "48 households guest 403"        "403" "$(code $API/admin/households)"
check "49 create household 201"        "201" "$(code -X POST $API/admin/households -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d "{\"householdName\":\"$FAM household\",\"primaryContactPersonId\":$JOHN}")"
HH=$(body | jsq "j['id']")
check "49b primary is member row"      "1" "$(body | jsq "len(j['members'])")"
check "50 create household bad person 400" "400" "$(code -X POST $API/admin/households -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{"primaryContactPersonId":999999}')"
check "51 add Mary 201"                "201" "$(code -X POST $API/admin/households/$HH/people -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d "{\"personId\":$MARY,\"relationshipType\":\"PARTNER\"}")"
check "51b two current members"        "2" "$(body | jsq "sum(1 for m in j['members'] if m['leftDate'] is None)")"
check "52 add Mary again 409"          "409" "$(code -X POST $API/admin/households/$HH/people -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d "{\"personId\":$MARY,\"relationshipType\":\"PARTNER\"}")"
check "53 GET household 200"           "200" "$(code $API/admin/households/$HH -H "Authorization: Bearer $ADMIN")"
check "53b household absent 404"       "404" "$(code $API/admin/households/999999 -H "Authorization: Bearer $ADMIN")"
check "54 remove Mary 200"             "200" "$(code -X DELETE $API/admin/households/$HH/people/$MARY -H "Authorization: Bearer $ADMIN")"
check "54b Mary row kept with leftDate" "1" "$(body | jsq "sum(1 for m in j['members'] if m['personId']==$MARY and m['leftDate'] is not None)")"
check "54c one current member"         "1" "$(body | jsq "sum(1 for m in j['members'] if m['leftDate'] is None)")"
check "55 remove Mary again 404"       "404" "$(code -X DELETE $API/admin/households/$HH/people/$MARY -H "Authorization: Bearer $ADMIN")"
check "56 remove primary contact 400"  "400" "$(code -X DELETE $API/admin/households/$HH/people/$JOHN -H "Authorization: Bearer $ADMIN")"
check "57 Mary rejoins 201"            "201" "$(code -X POST $API/admin/households/$HH/people -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d "{\"personId\":$MARY,\"relationshipType\":\"PARTNER\"}")"
check "57b history has three rows"     "3" "$(body | jsq "len(j['members'])")"
check "58 reassign primary to Mary 200" "200" "$(code -X PUT $API/admin/households/$HH -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d "{\"householdName\":\"$FAM household\",\"primaryContactPersonId\":$MARY}")"
check "59 primary must be member 400"  "400" "$(code -X PUT $API/admin/households/$HH -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{"primaryContactPersonId":999999}')"

# --- member import (CR-002) -------------------------------------------------
# Fixtures are written per-run into tmp/cr002-fixtures/ with a unique family
# name (Imp$$), so re-runs converge instead of duplicating: a re-apply of the
# same file matches every person on dedup and creates nothing.
FX="$(dirname "$0")/../tmp/cr002-fixtures"
mkdir -p "$FX"
IMP="Imp$$"
imp() { code -X POST "$1" -H "Authorization: Bearer $2" -H 'Content-Type: text/csv' --data-binary @"$3"; }

# valid: 2 households, 5 people; Alpha (3, incl a DEPENDANT) paid, Beta (2) not
cat > "$FX/valid.csv" <<EOF
household,title,givenName,familyName,relationship,email,phone,phoneType,line1,locality,state,postcode,membershipType,paid,notes
AlphaHH$$,Mr,Aaron,$IMP,MEMBER,Aaron.$$@Example.COM,0400 111 111,MOBILE,1 Alpha St,Yass,NSW,2582,HOUSEHOLD,yes,
AlphaHH$$,Mrs,Alice,$IMP,PARTNER,alice.$$@example.com,,,1 Alpha St,Yass,NSW,2582,HOUSEHOLD,yes,
AlphaHH$$,,Andy,$IMP,DEPENDANT,,,,1 Alpha St,Yass,NSW,2582,HOUSEHOLD,yes,child
BetaHH$$,Mr,Bruno,$IMP,MEMBER,bruno.$$@example.com,0400 222 222,MOBILE,2 Beta St,Yass,NSW,2582,HOUSEHOLD,no,
BetaHH$$,Ms,Bella,$IMP,PARTNER,bella.$$@example.com,,,2 Beta St,Yass,NSW,2582,HOUSEHOLD,no,
EOF
# same file with one givenName blanked → that whole household is excluded
cat > "$FX/badname.csv" <<EOF
household,givenName,familyName,relationship,membershipType,paid
AlphaHH$$,,$IMP,MEMBER,HOUSEHOLD,yes
BetaHH$$,Bruno,$IMP,MEMBER,HOUSEHOLD,no
EOF
# unknown column
cat > "$FX/unknowncol.csv" <<EOF
household,givenName,familyName,surname,membershipType,paid
Gam$$,Greg,$IMP,X,SINGLE,no
EOF
# a row whose email collides with an imported Alpha person
cat > "$FX/dup.csv" <<EOF
household,givenName,familyName,email
Dup$$,Aaron,$IMP,aaron.$$@example.com
EOF
# blank membershipType → people + household, no membership
cat > "$FX/nomem.csv" <<EOF
household,givenName,familyName,line1,locality,state,postcode,membershipType,paid
NoMem$$,Nora,$IMP,9 No St,Yass,NSW,2582,,
EOF
# UTF-8 BOM prefix + a quoted field containing a comma + an accented name
printf '\xef\xbb\xbf' > "$FX/bom.csv"
cat >> "$FX/bom.csv" <<EOF
household,givenName,familyName,line1,locality,state,postcode,membershipType
BomHH$$,Zoé,$IMP,"5 Main St, Apt 2",Yass,NSW,2582,SINGLE
EOF
# > 1 MB body
{ echo "household,givenName,familyName"; yes "Big$$,Pad,${IMP}paddingpaddingpaddingpaddingpaddingpad" | head -n 20000; } > "$FX/big.csv"

check "60 import preview guest 403"     "403" "$(code -X POST $API/admin/import/preview -H 'Content-Type: text/csv' --data-binary @"$FX/valid.csv")"
check "61 import preview user 403"      "403" "$(imp $API/admin/import/preview "$USER" "$FX/valid.csv")"
check "62 preview valid 200"            "200" "$(imp $API/admin/import/preview "$ADMIN" "$FX/valid.csv")"
check "62b rows 5"                      "5"   "$(body | jsq "j['rows']")"
check "62c no errors"                   "0"   "$(body | jsq "len(j['errors'])")"
check "62d toCreate people 5"           "5"   "$(body | jsq "j['toCreate']['people']")"
check "62e toCreate households 2"       "2"   "$(body | jsq "j['toCreate']['households']")"
check "62f toCreate memberships 2"      "2"   "$(body | jsq "j['toCreate']['memberships']")"
check "62g toCreate payments 1"         "1"   "$(body | jsq "j['toCreate']['payments']")"
check "63 preview badname 200"          "200" "$(imp $API/admin/import/preview "$ADMIN" "$FX/badname.csv")"
check "63b badname has error"           "true" "$(body | jsq "str(len(j['errors'])>0).lower()")"
check "63c badname excludes household"  "1"   "$(body | jsq "j['toCreate']['households']")"
check "64 preview unknown col 200"      "200" "$(imp $API/admin/import/preview "$ADMIN" "$FX/unknowncol.csv")"
check "64b names the bad column"        "true" "$(body | jsq "str(any('surname' in e['message'] for e in j['errors'])).lower()")"

check "65 baseline no Imp people"       "0"   "$(curl -s "$API/admin/people?q=$IMP" -H "Authorization: Bearer $ADMIN" | jsq "j['total']")"
check "66 apply badname 400"            "400" "$(imp $API/admin/import "$ADMIN" "$FX/badname.csv")"
check "66b apply badname created nothing" "0" "$(curl -s "$API/admin/people?q=$IMP" -H "Authorization: Bearer $ADMIN" | jsq "j['total']")"
check "67 apply valid 200"              "200" "$(imp "$API/admin/import?period=2025-2026" "$ADMIN" "$FX/valid.csv")"
check "67b created people 5"            "5"   "$(body | jsq "j['created']['people']")"
check "67c created households 2"        "2"   "$(body | jsq "j['created']['households']")"
check "67d created memberships 2"       "2"   "$(body | jsq "j['created']['memberships']")"
check "67e created payments 1"          "1"   "$(body | jsq "j['created']['payments']")"
check "68 households Alpha found"       "1"   "$(curl -s "$API/admin/households?q=AlphaHH$$" -H "Authorization: Bearer $ADMIN" | jsq "j['total']")"
check "68b Alpha has 3 current"         "3"   "$(curl -s "$API/admin/households?q=AlphaHH$$" -H "Authorization: Bearer $ADMIN" | jsq "j['households'][0]['currentMembers']")"
check "69 people Imp total 5"           "5"   "$(curl -s "$API/admin/people?q=$IMP" -H "Authorization: Bearer $ADMIN" | jsq "j['total']")"
check "69b Aaron email lowercased"      "aaron.$$@example.com" "$(curl -s "$API/admin/people?q=$IMP" -H "Authorization: Bearer $ADMIN" | jsq "next(p['emails'][0]['email'] for p in j['people'] if p['givenName']=='Aaron')")"
check "69c Aaron phone attached"        "1"   "$(curl -s "$API/admin/people?q=$IMP" -H "Authorization: Bearer $ADMIN" | jsq "next(len(p['phones']) for p in j['people'] if p['givenName']=='Aaron')")"
check "70 preview dup 200"              "200" "$(imp $API/admin/import/preview "$ADMIN" "$FX/dup.csv")"
check "70b dup warned"                  "true" "$(body | jsq "str(len(j['warnings'])>0).lower()")"
check "70c dup skipped names person"    "true" "$(body | jsq "str(any('#' in s['reason'] for s in j['skipped'])).lower()")"
check "70d dup creates nobody"          "0"   "$(body | jsq "j['toCreate']['people']")"
check "71 re-apply valid 200"           "200" "$(imp "$API/admin/import?period=2025-2026" "$ADMIN" "$FX/valid.csv")"
check "71b re-apply creates nothing"    "0"   "$(body | jsq "j['created']['people']+j['created']['households']+j['created']['memberships']+j['created']['payments']")"
check "71c re-apply skips all 5"        "5"   "$(body | jsq "len(j['skipped'])")"
check "72 apply nomem 200"              "200" "$(imp "$API/admin/import?period=2025-2026" "$ADMIN" "$FX/nomem.csv")"
check "72b nomem created 1 person"      "1"   "$(body | jsq "j['created']['people']")"
check "72c nomem created 0 memberships" "0"   "$(body | jsq "j['created']['memberships']")"
check "73 apply bom 200"                "200" "$(imp "$API/admin/import?period=2025-2026" "$ADMIN" "$FX/bom.csv")"
check "73b accented name intact"        "Zoé" "$(curl -s "$API/admin/people?q=Zo" -H "Authorization: Bearer $ADMIN" | jsq "next(p['givenName'] for p in j['people'] if p['familyName']=='$IMP' and p['givenName'].startswith('Zo'))")"
check "74 body over 1MB 413"            "413" "$(imp $API/admin/import/preview "$ADMIN" "$FX/big.csv")"

# --- CR-002 side effects (psql; skipped where the client is absent) ----------
PSQL_OK=0; command -v psql >/dev/null 2>&1 && PSQL_OK=1
psqlq() { PGPASSWORD=${MEMBERROLL_DB_PASSWORD:-memberroll} psql -h localhost -p "${POSTGRES_PORT:-5433}" -U memberroll -d memberroll -tAc "$1" 2>/dev/null; }
if [ "$PSQL_OK" = 1 ]; then
  check "75 Alpha membership ACTIVE/due" "ACTIVE|6500" "$(psqlq "SELECT m.status||'|'||m.amount_due_cents FROM membership m JOIN household h ON h.household_id=m.household_id WHERE h.household_name='AlphaHH$$'")"
  check "76 Alpha payment recorded"      "6500|OTHER|testadmin" "$(psqlq "SELECT p.amount_cents||'|'||p.payment_method||'|'||p.recorded_by FROM payment p JOIN payment_allocation pa ON pa.payment_id=p.payment_id JOIN membership m ON m.membership_id=pa.membership_id JOIN household h ON h.household_id=m.household_id WHERE h.household_name='AlphaHH$$' AND pa.allocation_type='MEMBERSHIP'")"
  check "77 Beta membership PENDING"     "PENDING_PAYMENT" "$(psqlq "SELECT m.status FROM membership m JOIN household h ON h.household_id=m.household_id WHERE h.household_name='BetaHH$$'")"
  check "77b Beta no payment"            "0" "$(psqlq "SELECT count(*) FROM payment p JOIN payment_allocation pa ON pa.payment_id=p.payment_id JOIN membership m ON m.membership_id=pa.membership_id JOIN household h ON h.household_id=m.household_id WHERE h.household_name='BetaHH$$'")"
  check "78 dependant not statutory"     "f" "$(psqlq "SELECT mp.is_statutory_member FROM membership_person mp JOIN person pe ON pe.person_id=mp.person_id WHERE pe.given_name='Andy' AND pe.family_name='$IMP'")"
  check "78b member is statutory"        "t" "$(psqlq "SELECT mp.is_statutory_member FROM membership_person mp JOIN person pe ON pe.person_id=mp.person_id WHERE pe.given_name='Aaron' AND pe.family_name='$IMP'")"
  check "79 nomem household has no membership" "0" "$(psqlq "SELECT count(*) FROM membership m JOIN household h ON h.household_id=m.household_id WHERE h.household_name='NoMem$$'")"
else
  echo "note: psql not found — skipping CR-002 side-effect checks 75–79 (membership/payment/flags)"
fi

# --- renewals + manual payments (CR-003) ------------------------------------
# The rollover/payment lifecycle. Unique per-run names (Ren$$) keep re-runs
# green. The data-dependent cases need psql to seed the LIFE type (there is
# deliberately no type-management API) and the LIFE/left-member fixtures; the
# pure auth checks run regardless.
JADMIN() { code "$@" -H "Authorization: Bearer $ADMIN"; }        # admin GET/POST, body to file
JPOST() { code -X POST "$1" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d "$2"; }
JPUT()  { code -X PUT  "$1" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d "$2"; }

check "CR3-01 periods guest 403"        "403" "$(code $API/admin/periods)"
check "CR3-02 payments user 403"        "403" "$(code -X POST $API/admin/payments -H "Authorization: Bearer $USER" -H 'Content-Type: application/json' -d '{}')"
check "CR3-03 periods noaud 401"        "401" "$(code $API/admin/periods -H "Authorization: Bearer $NOAUD")"

if [ "$PSQL_OK" = 1 ]; then
  R="Ren$$"
  # LIFE type: V8 seeds it since CR-018; this guarded insert remains only so
  # the block still runs against a pre-CR-018 server
  psqlq "INSERT INTO membership_type (name, description, minimum_people, maximum_people) SELECT 'LIFE','Life member',1,NULL WHERE NOT EXISTS (SELECT 1 FROM membership_type WHERE name='LIFE')" >/dev/null
  # heal a crashed earlier run: a lingering CR10-11/CR18-08 throwaway type
  # would make every period creation below 400 (a price is required per type)
  psqlq "DELETE FROM membership_type WHERE name LIKE 'X10TMP%' OR name LIKE 'X18TMP%'" >/dev/null
  T_SINGLE=$(psqlq "SELECT membership_type_id FROM membership_type WHERE name='SINGLE'")
  T_HH=$(psqlq "SELECT membership_type_id FROM membership_type WHERE name='HOUSEHOLD'")
  T_LIFE=$(psqlq "SELECT membership_type_id FROM membership_type WHERE name='LIFE'")
  P2526=$(curl -s $API/admin/periods -H "Authorization: Bearer $ADMIN" | jsq "next(p['id'] for p in j['periods'] if p['name']=='2025-2026')")

  # CR3-04: the seeded period and its prices
  check "CR3-04 periods admin 200"      "200" "$(JADMIN $API/admin/periods)"
  check "CR3-04b 2025-2026 SINGLE 4500" "4500" "$(body | jsq "next(pr['amountCents'] for p in j['periods'] if p['name']=='2025-2026' for pr in p['prices'] if pr['type']=='SINGLE')")"
  check "CR3-04c 2025-2026 HOUSEHOLD 6500" "6500" "$(body | jsq "next(pr['amountCents'] for p in j['periods'] if p['name']=='2025-2026' for pr in p['prices'] if pr['type']=='HOUSEHOLD')")"

  # CR3-05/06/07: create a target period with a full price set; dup name; missing price
  TGT="Tgt$$"
  check "CR3-05 create target period 201" "201" "$(JPOST $API/admin/periods "{\"name\":\"$TGT\",\"startDate\":\"2027-07-01\",\"endDate\":\"2028-06-30\",\"renewalOpenDate\":\"2027-06-01\",\"lateJoiningCutoff\":\"2028-04-01\",\"prices\":[{\"type\":\"SINGLE\",\"amountCents\":4500},{\"type\":\"HOUSEHOLD\",\"amountCents\":6500},{\"type\":\"LIFE\",\"amountCents\":0}]}")"
  TGTID=$(body | jsq "j['id']")
  check "CR3-06 duplicate name 409"      "409" "$(JPOST $API/admin/periods "{\"name\":\"$TGT\",\"startDate\":\"2027-07-01\",\"endDate\":\"2028-06-30\",\"prices\":[{\"type\":\"SINGLE\",\"amountCents\":4500},{\"type\":\"HOUSEHOLD\",\"amountCents\":6500},{\"type\":\"LIFE\",\"amountCents\":0}]}")"
  check "CR3-07 missing a price 400"     "400" "$(JPOST $API/admin/periods "{\"name\":\"${TGT}mp\",\"startDate\":\"2027-07-01\",\"endDate\":\"2028-06-30\",\"prices\":[{\"type\":\"SINGLE\",\"amountCents\":4500},{\"type\":\"LIFE\",\"amountCents\":0}]}")"
  check "CR3-07b names the missing type" "true" "$(body | jsq "str('HOUSEHOLD' in j['error']).lower()")"

  # CR3-08: create a membership for a fresh household (MEMBER + DEPENDANT)
  JPOST $API/admin/people "{\"givenName\":\"Rhoda\",\"familyName\":\"$R\"}" >/dev/null; PA1=$(body | jsq "j['id']")
  JPOST $API/admin/people "{\"givenName\":\"Deb\",\"familyName\":\"$R\"}" >/dev/null;   PA2=$(body | jsq "j['id']")
  JPOST $API/admin/households "{\"householdName\":\"$R A\",\"primaryContactPersonId\":$PA1}" >/dev/null; HHA=$(body | jsq "j['id']")
  JPOST $API/admin/households/$HHA/people "{\"personId\":$PA2,\"relationshipType\":\"DEPENDANT\"}" >/dev/null
  psqlq "INSERT INTO household_address (household_id, address_type, line_1, locality, state, postcode, is_preferred) VALUES ($HHA,'POSTAL','7 Test St','Yass','NSW','2582',true)" >/dev/null
  check "CR3-08 create membership 201"   "201" "$(JPOST $API/admin/memberships "{\"householdId\":$HHA,\"membershipPeriodId\":$P2526,\"membershipTypeId\":$T_SINGLE}")"
  MA=$(body | jsq "j['id']")
  check "CR3-08b status PENDING_PAYMENT" "PENDING_PAYMENT" "$(body | jsq "j['status']")"
  check "CR3-08c amount_due 4500"        "4500" "$(body | jsq "j['amountDueCents']")"
  check "CR3-08d MEMBER statutory true"  "t" "$(psqlq "SELECT mp.is_statutory_member FROM membership_person mp WHERE mp.membership_id=$MA AND mp.person_id=$PA1")"
  check "CR3-08e DEPENDANT statutory false" "f" "$(psqlq "SELECT mp.is_statutory_member FROM membership_person mp WHERE mp.membership_id=$MA AND mp.person_id=$PA2")"
  check "CR3-09 duplicate membership 409" "409" "$(JPOST $API/admin/memberships "{\"householdId\":$HHA,\"membershipPeriodId\":$P2526,\"membershipTypeId\":$T_SINGLE}")"

  # CR3-10: change type re-snapshots amount_due
  check "CR3-10 change type HOUSEHOLD 200" "200" "$(JPUT $API/admin/memberships/$MA "{\"membershipTypeId\":$T_HH}")"
  check "CR3-10b amount_due 6500"        "6500" "$(body | jsq "j['amountDueCents']")"

  # CR3-11: partial payment; membership stays pending
  check "CR3-11 partial payment 201"     "201" "$(JPOST $API/admin/payments "{\"receivedDate\":\"2026-08-01\",\"amountCents\":3000,\"method\":\"BANK_TRANSFER\",\"bankReference\":\"BT-1\",\"allocations\":[{\"type\":\"MEMBERSHIP\",\"membershipId\":$MA,\"amountCents\":3000}]}")"
  check "CR3-11b still pending"          "PENDING_PAYMENT" "$(curl -s $API/admin/memberships/$MA -H "Authorization: Bearer $ADMIN" | jsq "j['status']")"
  check "CR3-11c paid 3000"              "3000" "$(curl -s $API/admin/memberships/$MA -H "Authorization: Bearer $ADMIN" | jsq "j['amountPaidCents']")"

  # CR3-12: type change refused once an allocation exists
  check "CR3-12 type change now 400"     "400" "$(JPUT $API/admin/memberships/$MA "{\"membershipTypeId\":$T_SINGLE}")"

  # CR3-13: second payment activates; approved_date = received date
  check "CR3-13 second payment 201"      "201" "$(JPOST $API/admin/payments "{\"receivedDate\":\"2026-08-15\",\"amountCents\":3500,\"method\":\"CHEQUE\",\"allocations\":[{\"type\":\"MEMBERSHIP\",\"membershipId\":$MA,\"amountCents\":3500}]}")"
  check "CR3-13b now ACTIVE"             "ACTIVE" "$(curl -s $API/admin/memberships/$MA -H "Authorization: Bearer $ADMIN" | jsq "j['status']")"
  check "CR3-13c approved = received"    "2026-08-15" "$(psqlq "SELECT approved_date FROM membership WHERE membership_id=$MA")"

  # CR3-14: allocations must sum to the payment amount
  check "CR3-14 sum mismatch 400"        "400" "$(JPOST $API/admin/payments "{\"receivedDate\":\"2026-08-20\",\"amountCents\":1000,\"method\":\"CASH\",\"allocations\":[{\"type\":\"MEMBERSHIP\",\"membershipId\":$MA,\"amountCents\":500}]}")"

  # CR3-15: negative (reversal) payment demotes back to pending
  check "CR3-15 reversal 201"            "201" "$(JPOST $API/admin/payments "{\"receivedDate\":\"2026-08-25\",\"amountCents\":-3500,\"method\":\"CHEQUE\",\"notes\":\"reversal of payment\",\"allocations\":[{\"type\":\"MEMBERSHIP\",\"membershipId\":$MA,\"amountCents\":-3500}]}")"
  check "CR3-15b back to pending"        "PENDING_PAYMENT" "$(curl -s $API/admin/memberships/$MA -H "Authorization: Bearer $ADMIN" | jsq "j['status']")"
  check "CR3-15c paid 3000"              "3000" "$(curl -s $API/admin/memberships/$MA -H "Authorization: Bearer $ADMIN" | jsq "j['amountPaidCents']")"

  # CR3-16: membership + donation in one payment; donation never counts
  check "CR3-16 payment+donation 201"    "201" "$(JPOST $API/admin/payments "{\"receivedDate\":\"2026-09-01\",\"amountCents\":4500,\"method\":\"BANK_TRANSFER\",\"allocations\":[{\"type\":\"MEMBERSHIP\",\"membershipId\":$MA,\"amountCents\":3500},{\"type\":\"DONATION\",\"amountCents\":1000}]}")"
  check "CR3-16b ACTIVE again"           "ACTIVE" "$(curl -s $API/admin/memberships/$MA -H "Authorization: Bearer $ADMIN" | jsq "j['status']")"
  check "CR3-16c membership-paid 6500"   "6500" "$(psqlq "SELECT COALESCE(SUM(amount_cents),0) FROM payment_allocation WHERE membership_id=$MA AND allocation_type='MEMBERSHIP'")"
  check "CR3-16d donation not counted"   "6500" "$(curl -s $API/admin/memberships/$MA -H "Authorization: Bearer $ADMIN" | jsq "j['amountPaidCents']")"

  # CR3-17: financial status view row
  check "CR3-17 status view ACTIVE 200"  "200" "$(JADMIN "$API/admin/periods/$P2526/memberships?status=ACTIVE&q=$R")"
  check "CR3-17b row due 6500"           "6500" "$(body | jsq "next(r['amountDueCents'] for r in j['rows'] if r['membershipId']==$MA)")"
  check "CR3-17c row paid 6500"          "6500" "$(body | jsq "next(r['amountPaidCents'] for r in j['rows'] if r['membershipId']==$MA)")"
  check "CR3-17d summary has ACTIVE"     "true" "$(body | jsq "str('ACTIVE' in j['summary']['countsByStatus']).lower()")"

  # HH_B: a pending membership for the lapse/reactivate cases
  JPOST $API/admin/people "{\"givenName\":\"Bruno\",\"familyName\":\"$R\"}" >/dev/null; PB1=$(body | jsq "j['id']")
  JPOST $API/admin/households "{\"householdName\":\"$R B\",\"primaryContactPersonId\":$PB1}" >/dev/null; HHB=$(body | jsq "j['id']")
  JPOST $API/admin/memberships "{\"householdId\":$HHB,\"membershipPeriodId\":$P2526,\"membershipTypeId\":$T_SINGLE}" >/dev/null; MB=$(body | jsq "j['id']")

  # CR3-18: bulk lapse of the unpaid
  check "CR3-18 lapse-unpaid 200"        "200" "$(code -X POST $API/admin/periods/$P2526/lapse-unpaid -H "Authorization: Bearer $ADMIN")"
  check "CR3-18b lapsed >= 1"            "true" "$(body | jsq "str(j['lapsed']>=1).lower()")"
  check "CR3-18c HH_B now lapsed"        "LAPSED" "$(psqlq "SELECT status FROM membership WHERE membership_id=$MB")"
  check "CR3-18d LAPSED filter lists it" "true" "$(curl -s "$API/admin/periods/$P2526/memberships?status=LAPSED&q=$R" -H "Authorization: Bearer $ADMIN" | jsq "str(any(r['membershipId']==$MB for r in j['rows'])).lower()")"

  # CR3-19: a late payment reactivates a lapsed membership
  check "CR3-19 pay lapsed 201"          "201" "$(JPOST $API/admin/payments "{\"receivedDate\":\"2026-09-15\",\"amountCents\":4500,\"method\":\"CASH\",\"allocations\":[{\"type\":\"MEMBERSHIP\",\"membershipId\":$MB,\"amountCents\":4500}]}")"
  check "CR3-19b reactivated ACTIVE"     "ACTIVE" "$(curl -s $API/admin/memberships/$MB -H "Authorization: Bearer $ADMIN" | jsq "j['status']")"

  # CR3-20: cease, then a later payment must not disturb CEASED
  JPOST $API/admin/people "{\"givenName\":\"Cara\",\"familyName\":\"$R\"}" >/dev/null; PC1=$(body | jsq "j['id']")
  JPOST $API/admin/households "{\"householdName\":\"$R C\",\"primaryContactPersonId\":$PC1}" >/dev/null; HHC=$(body | jsq "j['id']")
  JPOST $API/admin/memberships "{\"householdId\":$HHC,\"membershipPeriodId\":$P2526,\"membershipTypeId\":$T_SINGLE}" >/dev/null; MC=$(body | jsq "j['id']")
  JPOST $API/admin/payments "{\"receivedDate\":\"2026-09-20\",\"amountCents\":4500,\"method\":\"CASH\",\"allocations\":[{\"type\":\"MEMBERSHIP\",\"membershipId\":$MC,\"amountCents\":4500}]}" >/dev/null
  check "CR3-20 cease 200"               "200" "$(JPUT $API/admin/memberships/$MC "{\"status\":\"CEASED\",\"ceasedDate\":\"2026-10-01\",\"cessationReason\":\"RESIGNED\"}")"
  check "CR3-20b ceased_date set"        "2026-10-01" "$(psqlq "SELECT ceased_date FROM membership WHERE membership_id=$MC")"
  JPOST $API/admin/payments "{\"receivedDate\":\"2026-10-05\",\"amountCents\":4500,\"method\":\"CASH\",\"allocations\":[{\"type\":\"MEMBERSHIP\",\"membershipId\":$MC,\"amountCents\":4500}]}" >/dev/null
  check "CR3-20c stays CEASED"           "CEASED" "$(curl -s $API/admin/memberships/$MC -H "Authorization: Bearer $ADMIN" | jsq "j['status']")"

  # LIFE household with a departed member (psql: no type API, and $0 ACTIVE is a rollover shape)
  psqlq "WITH p1 AS (INSERT INTO person(given_name,family_name) VALUES ('Lifer','${R}L') RETURNING person_id),
              p2 AS (INSERT INTO person(given_name,family_name) VALUES ('Gone','${R}L') RETURNING person_id),
              hh AS (INSERT INTO household(household_name,primary_contact_person_id) SELECT '$R Life', person_id FROM p1 RETURNING household_id),
              a  AS (INSERT INTO household_person(household_id,person_id,relationship_type,joined_household_date) SELECT hh.household_id,p1.person_id,'MEMBER',current_date FROM hh,p1),
              b  AS (INSERT INTO household_person(household_id,person_id,relationship_type,joined_household_date,left_household_date) SELECT hh.household_id,p2.person_id,'OTHER',current_date-30,current_date-1 FROM hh,p2),
              m  AS (INSERT INTO membership(membership_period_id,membership_type_id,household_id,status,application_date,approved_date,start_date,end_date,amount_due_cents) SELECT $P2526,$T_LIFE,hh.household_id,'ACTIVE',current_date,current_date,DATE '2025-09-01',DATE '2026-08-31',0 FROM hh RETURNING membership_id)
         INSERT INTO membership_person(membership_id,person_id,membership_role,is_statutory_member,has_voting_rights,eligible_for_committee,start_date) SELECT m.membership_id,p1.person_id,'MEMBER',true,true,true,current_date FROM m,p1" >/dev/null

  # CR3-21: rollover setup — HH_A already renewed into the target, part-paid.
  # The source is pinned with ?from=$P2526 (the seeded period this run's
  # fixtures live in): default resolution picks the latest period before the
  # target, which is non-deterministic once the dev DB holds other periods
  # (e.g. one an admin created while testing) — pinning keeps the matrix
  # re-runnable regardless.
  JPOST $API/admin/memberships "{\"householdId\":$HHA,\"membershipPeriodId\":$TGTID,\"membershipTypeId\":$T_SINGLE}" >/dev/null; MA_TGT=$(body | jsq "j['id']")
  JPOST $API/admin/payments "{\"receivedDate\":\"2027-08-01\",\"amountCents\":2000,\"method\":\"CASH\",\"allocations\":[{\"type\":\"MEMBERSHIP\",\"membershipId\":$MA_TGT,\"amountCents\":2000}]}" >/dev/null
  TGT_BEFORE=$(psqlq "SELECT count(*) FROM membership WHERE membership_period_id=$TGTID")
  check "CR3-21 rollover preview 200"    "200" "$(code -X POST "$API/admin/periods/$TGTID/rollover/preview?from=$P2526" -H "Authorization: Bearer $ADMIN")"
  TOCREATE=$(body | jsq "j['toCreate']")
  check "CR3-21b source is 2025-2026"    "2025-2026" "$(body | jsq "j['fromPeriodName']")"
  check "CR3-21c skips already-renewed"  "true" "$(body | jsq "str(any(s['householdId']==$HHA for s in j['skipped'])).lower()")"
  check "CR3-21d toCreate >= 3"          "true" "$(body | jsq "str(j['toCreate']>=3).lower()")"
  check "CR3-21e preview wrote nothing"  "$TGT_BEFORE" "$(psqlq "SELECT count(*) FROM membership WHERE membership_period_id=$TGTID")"

  # CR3-22: apply
  check "CR3-22 rollover apply 200"      "200" "$(code -X POST "$API/admin/periods/$TGTID/rollover?from=$P2526" -H "Authorization: Bearer $ADMIN")"
  check "CR3-22b created == preview"     "$TOCREATE" "$(body | jsq "j['created']")"
  check "CR3-22c LIFE ACTIVE due 0"      "ACTIVE|0" "$(psqlq "SELECT m.status||'|'||amount_due_cents FROM membership m JOIN household h ON h.household_id=m.household_id WHERE h.household_name='$R Life' AND m.membership_period_id=$TGTID")"
  check "CR3-22d LIFE no payment row"    "0" "$(psqlq "SELECT count(*) FROM payment_allocation pa JOIN membership m ON m.membership_id=pa.membership_id JOIN household h ON h.household_id=m.household_id WHERE h.household_name='$R Life' AND m.membership_period_id=$TGTID")"
  check "CR3-22e left member absent"     "1" "$(psqlq "SELECT count(*) FROM membership_person mp JOIN membership m ON m.membership_id=mp.membership_id JOIN household h ON h.household_id=m.household_id WHERE h.household_name='$R Life' AND m.membership_period_id=$TGTID")"
  check "CR3-22f HH_B rolled PENDING 4500" "PENDING_PAYMENT|4500" "$(psqlq "SELECT m.status||'|'||amount_due_cents FROM membership m JOIN household h ON h.household_id=m.household_id WHERE h.household_name='$R B' AND m.membership_period_id=$TGTID")"

  # CR3-23: re-run converges
  check "CR3-23 rollover apply again 200" "200" "$(code -X POST "$API/admin/periods/$TGTID/rollover?from=$P2526" -H "Authorization: Bearer $ADMIN")"
  check "CR3-23b created 0"              "0" "$(body | jsq "j['created']")"

  # CR3-24: repriceUnpaid re-snapshots zero-allocation memberships only
  check "CR3-24 reprice 200"            "200" "$(JPUT "$API/admin/periods/$TGTID?repriceUnpaid=true" "{\"startDate\":\"2027-07-01\",\"endDate\":\"2028-06-30\",\"prices\":[{\"type\":\"SINGLE\",\"amountCents\":5000}]}")"
  check "CR3-24b unpaid re-priced 5000" "5000" "$(psqlq "SELECT amount_due_cents FROM membership m JOIN household h ON h.household_id=m.household_id WHERE h.household_name='$R B' AND m.membership_period_id=$TGTID")"
  check "CR3-24c part-paid untouched 4500" "4500" "$(psqlq "SELECT amount_due_cents FROM membership WHERE membership_id=$MA_TGT")"

  # CR3-25/26/27/28: CSV exports (on 2025-2026)
  check "CR3-25 agm 200"                "200" "$(JADMIN $API/admin/periods/$P2526/export/agm-register.csv)"
  check "CR3-25b agm is csv"            "text/csv" "$(curl -s -o /dev/null -w '%{content_type}' $API/admin/periods/$P2526/export/agm-register.csv -H "Authorization: Bearer $ADMIN" | grep -o 'text/csv' | head -1)"
  check "CR3-25c agm has voting member" "yes" "$(grep -q "Rhoda,$R" /tmp/body.$$ && echo yes || echo no)"
  check "CR3-25d agm excludes dependant" "yes" "$(grep -q "Deb,$R" /tmp/body.$$ && echo no || echo yes)"
  check "CR3-26 mailing labels 200"     "200" "$(JADMIN $API/admin/periods/$P2526/export/mailing-labels.csv)"
  check "CR3-26b address household has line" "yes" "$(grep -q '7 Test St' /tmp/body.$$ && echo yes || echo no)"
  check "CR3-26c addressless present"   "yes" "$(grep -q "$R B" /tmp/body.$$ && echo yes || echo no)"
  check "CR3-27 financial 200"          "200" "$(JADMIN $API/admin/periods/$P2526/export/financial.csv)"
  NROWS=$(python3 -c "import csv; print(sum(1 for _ in csv.reader(open('/tmp/body.$$')))-1)" 2>/dev/null)
  NMEM=$(psqlq "SELECT count(*) FROM membership WHERE membership_period_id=$P2526")
  check "CR3-27b row count == memberships" "$NMEM" "$NROWS"
  check "CR3-28 agm guest 403"          "403" "$(code $API/admin/periods/$P2526/export/agm-register.csv)"
else
  echo "note: psql not found — skipping CR-003 data cases (LIFE type + rollover fixtures need psql)"
fi

# --- Stripe pay links + webhook + lost-link (CR-004) -------------------------
# Offline by design: tokens come from the mint endpoint (or are seeded via
# psql for the expired case), and webhook signatures are SELF-SIGNED — the
# signature is HMAC-SHA256 over "t.payload" with STRIPE_WEBHOOK_SECRET, which
# this script holds (default matches the documented dev cargo invocation).
# Checkout-session rows need the network and a real test key: they run when
# STRIPE_SECRET_KEY is set and report SKIP otherwise. Webhook rows likewise
# SKIP when the server has no STRIPE_WEBHOOK_SECRET (probe below); mail rows
# SKIP when Mailpit isn't reachable.
WH_SECRET=${STRIPE_WEBHOOK_SECRET:-whsec_devmatrix}
MAILPIT=http://localhost:${MAILPIT_UI_PORT:-18025}

sha() { python3 -c "import hashlib,sys;print(hashlib.sha256(sys.argv[1].encode()).hexdigest())" "$1"; }
whsign() { # payload [timestamp] → prints "t=..,v1=.."
  local ts=${2:-$(date +%s)}
  local sig
  sig=$(python3 -c "import hmac,hashlib,sys;print(hmac.new(sys.argv[1].encode(),sys.argv[2].encode(),hashlib.sha256).hexdigest())" "$WH_SECRET" "$ts.$1")
  echo "t=$ts,v1=$sig"
}
whpost() { # payload signature-header
  code -X POST $API/stripe/webhook -H "Stripe-Signature: $2" -H 'Content-Type: application/json' --data-binary "$1"
}
whevent() { # session-json-fragment → full event json (created = now)
  echo "{\"id\":\"evt_cr4$$\",\"type\":\"checkout.session.completed\",\"created\":$(date +%s),\"data\":{\"object\":$1}}"
}
mailpit_text() { # to-address → text body of the newest matching message (polls briefly)
  local id=""
  for _ in 1 2 3 4 5 6; do
    id=$(curl -s "$MAILPIT/api/v1/search?query=to:%22$1%22" | jsq "j['messages'][0]['ID']")
    [ -n "$id" ] && break
    sleep 0.5
  done
  [ -n "$id" ] && curl -s "$MAILPIT/api/v1/message/$id" | jsq "j['Text']"
}

if [ "$PSQL_OK" = 1 ]; then
  PY="Pay$$"
  # fixtures: three households in the seeded (current) period
  JPOST $API/admin/people "{\"givenName\":\"Peta\",\"familyName\":\"$PY\",\"emails\":[{\"email\":\"receipt.$$@example.com\",\"isPrimary\":true}]}" >/dev/null; PP1=$(body | jsq "j['id']")
  JPOST $API/admin/households "{\"householdName\":\"$PY HH\",\"primaryContactPersonId\":$PP1}" >/dev/null; PHH=$(body | jsq "j['id']")
  JPOST $API/admin/memberships "{\"householdId\":$PHH,\"membershipPeriodId\":$P2526,\"membershipTypeId\":$T_SINGLE}" >/dev/null; MP=$(body | jsq "j['id']")
  JPOST $API/admin/people "{\"givenName\":\"Quinn\",\"familyName\":\"$PY\"}" >/dev/null; PP2=$(body | jsq "j['id']")
  JPOST $API/admin/households "{\"householdName\":\"$PY HH2\",\"primaryContactPersonId\":$PP2}" >/dev/null; PHH2=$(body | jsq "j['id']")
  JPOST $API/admin/memberships "{\"householdId\":$PHH2,\"membershipPeriodId\":$P2526,\"membershipTypeId\":$T_SINGLE}" >/dev/null; MP2=$(body | jsq "j['id']")

  # reset the seeded period's journal price so re-runs start from "not offered"
  JPUT "$API/admin/periods/$P2526" '{"journalPriceCents":null}' >/dev/null

  # CR4-01/02: admin mints pay links; each mint is a FRESH token (only hashes
  # are stored, so an earlier token cannot be re-presented) and older
  # unexpired links stay valid
  check "CR4-01 mint pay-link 200"       "200" "$(code -X POST $API/admin/memberships/$MP/pay-link -H "Authorization: Bearer $ADMIN")"
  URL1=$(body | jsq "j['url']"); TK1=${URL1##*t=}
  check "CR4-01b url points at pay page" "true" "$(python3 -c "print(str('/web/pay.html?t=' in '$URL1').lower())")"
  # expires the day after end_date in the SERVER's zone; psql may render the
  # timestamptz in another zone, so allow either calendar date
  check "CR4-01c hash row, expires at period end" "t" "$(psqlq "SELECT (rt.expires_at::date BETWEEN m.end_date AND m.end_date + 1) FROM renewal_token rt JOIN membership m ON m.membership_id=rt.membership_id WHERE rt.token_hash='$(sha "$TK1")'")"
  check "CR4-02 second mint 200"         "200" "$(code -X POST $API/admin/memberships/$MP/pay-link -H "Authorization: Bearer $ADMIN")"
  URL2=$(body | jsq "j['url']"); TK2=${URL2##*t=}
  check "CR4-02b fresh token each mint"  "false" "$(python3 -c "print(str('$TK1'=='$TK2').lower())")"
  check "CR4-02c older link still live"  "200" "$(code $API/pay/$TK1)"
  check "CR4-03 mint absent membership 404" "404" "$(code -X POST $API/admin/memberships/999999/pay-link -H "Authorization: Bearer $ADMIN")"
  check "CR4-04 mint guest 403"          "403" "$(code -X POST $API/admin/memberships/$MP/pay-link)"
  check "CR4-04b mint user 403"          "403" "$(code -X POST $API/admin/memberships/$MP/pay-link -H "Authorization: Bearer $USER")"
  check "CR4-04c mint noaud 401"         "401" "$(code -X POST $API/admin/memberships/$MP/pay-link -H "Authorization: Bearer $NOAUD")"

  # CR4-05: the guest pay view
  check "CR4-05 guest pay view 200"      "200" "$(code $API/pay/$TK2)"
  check "CR4-05b status pending"         "PENDING_PAYMENT" "$(body | jsq "j['status']")"
  check "CR4-05c due 4500 paid 0"        "4500|0" "$(body | jsq "str(j['dueCents'])+'|'+str(j['paidCents'])")"
  check "CR4-05d journal not offered"    "None" "$(body | jsq "j['journalPriceCents']")"
  check "CR4-06 unknown token 404"       "404" "$(code $API/pay/nosuchtoken$$)"
  NOTFOUND_BODY=$(body)
  # expired token: seeded directly — the API can only mint live ones
  EXPTOK="expired$$expired$$expired$$expired$$expi"
  psqlq "INSERT INTO renewal_token (membership_id, token_hash, expires_at) VALUES ($MP, '$(sha "$EXPTOK")', now() - interval '1 day')" >/dev/null
  check "CR4-07 expired token 404"       "404" "$(code $API/pay/$EXPTOK)"
  check "CR4-07b same body as unknown"   "$NOTFOUND_BODY" "$(body)"

  # CR4-08: journal add-on price is per-period config
  check "CR4-08 set journal price 200"   "200" "$(JPUT "$API/admin/periods/$P2526" '{"journalPriceCents":1000}')"
  check "CR4-08b period GET carries it"  "1000" "$(body | jsq "j['journalPriceCents']")"
  check "CR4-08c pay view now offers it" "1000" "$(curl -s $API/pay/$TK2 | jsq "j['journalPriceCents']")"
  check "CR4-08d negative price 400"     "400" "$(JPUT "$API/admin/periods/$P2526" '{"journalPriceCents":-5}')"

  # CR4-09..11: checkout-session creation (needs a real Stripe test key)
  if [ -n "${STRIPE_SECRET_KEY:-}" ]; then
    check "CR4-09 checkout 200"          "200" "$(code -X POST $API/pay/$TK2/checkout -H 'Content-Type: application/json' -d '{"journal":true,"donationCents":500}')"
    check "CR4-09b url is stripe"        "true" "$(body | jsq "str(j['url'].startswith('https://checkout.stripe.com')).lower()")"
  else
    echo "SKIP CR4-09 checkout-session rows (STRIPE_SECRET_KEY not set in this shell)"
    # the flip side is testable exactly when the SERVER also has no key: a
    # clear 503, never a startup failure (manual-payments-only is a valid
    # deployment). If the server HAS a key, this probe just made a session.
    CK=$(code -X POST $API/pay/$TK2/checkout -H 'Content-Type: application/json' -d '{}')
    if [ "$CK" = "503" ]; then
      check "CR4-09u checkout unconfigured 503" "503" "$CK"
    else
      echo "note: the server HAS a Stripe key (checkout answered $CK) — export STRIPE_SECRET_KEY here too to run the CR4-09 rows"
    fi
  fi

  # webhook rows: a SIGNED probe of an ignorable event type distinguishes all
  # three server states — 200 = configured with OUR secret (run the rows),
  # 400 = configured with a DIFFERENT secret (self-signed rows would all
  # bogusly fail), 503 = no secret at all
  WH_PING='{"type":"matrix.ping"}'
  WH_PROBE=$(whpost "$WH_PING" "$(whsign "$WH_PING")")
  if [ "$WH_PROBE" = "200" ]; then
    TK2ID=$(psqlq "SELECT renewal_token_id FROM renewal_token WHERE token_hash='$(sha "$TK2")'")
    PI="pi_cr4$$"
    SESSION="{\"id\":\"cs_cr4$$\",\"payment_status\":\"paid\",\"payment_intent\":\"$PI\",\"amount_total\":4500,\"customer_details\":{\"email\":\"receipt.$$@example.com\"},\"metadata\":{\"membershipId\":\"$MP\",\"tokenId\":\"$TK2ID\",\"membershipCents\":\"4500\",\"journalCents\":\"0\",\"donationCents\":\"0\"}}"
    EVENT=$(whevent "$SESSION")
    SIG=$(whsign "$EVENT")
    check "CR4-12 webhook records 200"   "200" "$(whpost "$EVENT" "$SIG")"
    check "CR4-12b payment row STRIPE"   "STRIPE|4500|stripe-webhook" "$(psqlq "SELECT payment_method||'|'||amount_cents||'|'||recorded_by FROM payment WHERE external_transaction_id='$PI'")"
    check "CR4-12c allocation MEMBERSHIP" "MEMBERSHIP|4500" "$(psqlq "SELECT pa.allocation_type||'|'||pa.amount_cents FROM payment_allocation pa JOIN payment p ON p.payment_id=pa.payment_id WHERE p.external_transaction_id='$PI'")"
    check "CR4-12d membership ACTIVE"    "ACTIVE" "$(psqlq "SELECT status FROM membership WHERE membership_id=$MP")"
    check "CR4-12e token used_at set"    "1" "$(psqlq "SELECT count(*) FROM renewal_token WHERE renewal_token_id=$TK2ID AND used_at IS NOT NULL")"
    check "CR4-12f pay view paid up"     "0" "$(curl -s $API/pay/$TK2 | jsq "j['balanceCents']")"
    check "CR4-13 redelivery no-op 200"  "200" "$(whpost "$EVENT" "$SIG")"
    check "CR4-13b still one payment"    "1" "$(psqlq "SELECT count(*) FROM payment WHERE external_transaction_id='$PI'")"
    check "CR4-14 bad signature 400"     "400" "$(whpost "$EVENT" "t=$(date +%s),v1=0000000000000000000000000000000000000000000000000000000000000000")"
    check "CR4-15 stale timestamp 400"   "400" "$(whpost "$EVENT" "$(whsign "$EVENT" $(($(date +%s) - 4000)))")"
    # build each mutated event ONCE — whevent stamps created=$(date +%s), so
    # re-evaluating it for the signature can straddle a second and self-flake
    WRONGTYPE=$(echo "$EVENT" | sed 's/checkout.session.completed/payment_intent.created/')
    check "CR4-16 wrong event type 200 ignored" "200" "$(whpost "$WRONGTYPE" "$(whsign "$WRONGTYPE")")"
    UNPAID=$(whevent "$(echo "$SESSION" | sed 's/\"paid\"/\"unpaid\"/')")
    check "CR4-16b unpaid session ignored" "200" "$(whpost "$UNPAID" "$(whsign "$UNPAID")")"
    # verified but unprocessable: names a membership that doesn't exist
    BADSESSION="{\"id\":\"cs_bad$$\",\"payment_status\":\"paid\",\"payment_intent\":\"pi_bad$$\",\"amount_total\":100,\"metadata\":{\"membershipId\":\"999999\",\"membershipCents\":\"100\",\"journalCents\":\"0\",\"donationCents\":\"0\"}}"
    BADEVENT=$(whevent "$BADSESSION")
    check "CR4-17 unknown membership 200" "200" "$(whpost "$BADEVENT" "$(whsign "$BADEVENT")")"
    check "CR4-17b nothing recorded"     "0" "$(psqlq "SELECT count(*) FROM payment WHERE external_transaction_id='pi_bad$$'")"
    # verified but truncated metadata (a money key missing) is unprocessable,
    # never silently zeroed — real money must not land as an OTHER 'mismatch'
    TRUNCSESSION="{\"id\":\"cs_tr$$\",\"payment_status\":\"paid\",\"payment_intent\":\"pi_tr$$\",\"amount_total\":100,\"metadata\":{\"membershipId\":\"$MP\",\"membershipCents\":\"100\"}}"
    TRUNCEVENT=$(whevent "$TRUNCSESSION")
    check "CR4-17c truncated metadata 200/no-op" "200|0" "$(whpost "$TRUNCEVENT" "$(whsign "$TRUNCEVENT")")|$(psqlq "SELECT count(*) FROM payment WHERE external_transaction_id='pi_tr$$'")"
    # journal + donation breakdown lands as three allocations
    PI2="pi2_cr4$$"
    S2="{\"id\":\"cs2_cr4$$\",\"payment_status\":\"paid\",\"payment_intent\":\"$PI2\",\"amount_total\":6000,\"metadata\":{\"membershipId\":\"$MP2\",\"membershipCents\":\"4500\",\"journalCents\":\"1000\",\"donationCents\":\"500\"}}"
    E2=$(whevent "$S2")
    check "CR4-18 journal+donation 200"  "200" "$(whpost "$E2" "$(whsign "$E2")")"
    check "CR4-18b three allocations sum" "3|6000" "$(psqlq "SELECT count(*)||'|'||sum(pa.amount_cents) FROM payment_allocation pa JOIN payment p ON p.payment_id=pa.payment_id WHERE p.external_transaction_id='$PI2'")"
    check "CR4-18c MP2 ACTIVE"           "ACTIVE" "$(psqlq "SELECT status FROM membership WHERE membership_id=$MP2")"
    U3=$(curl -s -X POST $API/admin/memberships/$MP2/pay-link -H "Authorization: Bearer $ADMIN" | jsq "j['url']"); TK3=${U3##*t=}
    check "CR4-18d journal now hidden on pay view" "None" "$(curl -s "$API/pay/$TK3" | jsq "j['journalPriceCents']")"
    # a refunded journal (negative correction) makes the add-on purchasable again
    check "CR4-18e journal refund 201"   "201" "$(JPOST $API/admin/payments "{\"receivedDate\":\"2026-07-18\",\"amountCents\":-1000,\"method\":\"STRIPE\",\"notes\":\"journal refund\",\"allocations\":[{\"type\":\"JOURNAL\",\"membershipId\":$MP2,\"amountCents\":-1000}]}")"
    check "CR4-18f journal offered again" "1000" "$(curl -s "$API/pay/$TK3" | jsq "j['journalPriceCents']")"
    # receipt email (best-effort, but the dev stack has Mailpit)
    if curl -s -m 2 -o /dev/null "$MAILPIT/api/v1/messages"; then
      RECEIPT=$(mailpit_text "receipt.$$@example.com")
      check "CR4-19 receipt in Mailpit"  "true" "$(python3 -c "import sys;print(str('financial for 2025-2026' in sys.argv[1]).lower())" "${RECEIPT:-none}")"
    else
      echo "SKIP CR4-19 receipt row (Mailpit not reachable at $MAILPIT)"
    fi
  elif [ "$WH_PROBE" = "400" ]; then
    echo "SKIP CR4-12..19 webhook rows (the server's STRIPE_WEBHOOK_SECRET differs from this shell's"
    echo "     — export the SAME value cargo uses: the whsec_ from 'stripe listen', or whsec_devmatrix for offline dev)"
  else
    echo "SKIP CR4-12..19 webhook rows (server answered $WH_PROBE — start cargo with STRIPE_WEBHOOK_SECRET=$WH_SECRET)"
  fi

  # lost-link (mail rows need Mailpit; the 202 contract holds regardless)
  JPOST $API/admin/people "{\"givenName\":\"Lucy\",\"familyName\":\"$PY\",\"emails\":[{\"email\":\"lost.$$@example.com\",\"isPrimary\":true}]}" >/dev/null; PP3=$(body | jsq "j['id']")
  JPOST $API/admin/households "{\"householdName\":\"$PY Lost\",\"primaryContactPersonId\":$PP3}" >/dev/null; PHH3=$(body | jsq "j['id']")
  JPOST $API/admin/memberships "{\"householdId\":$PHH3,\"membershipPeriodId\":$P2526,\"membershipTypeId\":$T_SINGLE}" >/dev/null
  check "CR4-20 lost-link 202"           "202" "$(code -X POST $API/pay/lost-link -H 'Content-Type: application/json' -d "{\"email\":\"Lost.$$@Example.COM\"}")"
  LL_BODY=$(body)
  check "CR4-21 unknown email same 202"  "202" "$(code -X POST $API/pay/lost-link -H 'Content-Type: application/json' -d "{\"email\":\"nobody.$$@example.com\"}")"
  check "CR4-21b identical body"         "$LL_BODY" "$(body)"
  check "CR4-21c garbage body still 202" "202" "$(code -X POST $API/pay/lost-link -H 'Content-Type: application/json' -d 'not json')"
  if curl -s -m 2 -o /dev/null "$MAILPIT/api/v1/messages"; then
    LOST=$(mailpit_text "lost.$$@example.com")
    check "CR4-20b mail has pay link"    "1" "$(python3 -c "import sys;print(sys.argv[1].count('/web/pay.html?t='))" "${LOST:-none}")"
    check "CR4-20c mail shows balance"   "true" "$(python3 -c "import sys;print(str('\$45.00' in sys.argv[1]).lower())" "${LOST:-none}")"
    check "CR4-21d no mail for unknown"  "" "$(curl -s "$MAILPIT/api/v1/search?query=to:%22nobody.$$@example.com%22" | jsq "j['messages'][0]['ID']")"
    # one address matching two people: each household's link listed once
    JPOST $API/admin/people "{\"givenName\":\"Sam\",\"familyName\":\"$PY\",\"emails\":[{\"email\":\"shared.$$@example.com\"}]}" >/dev/null; PS1=$(body | jsq "j['id']")
    JPOST $API/admin/people "{\"givenName\":\"Toni\",\"familyName\":\"$PY\",\"emails\":[{\"email\":\"shared.$$@example.com\"}]}" >/dev/null; PS2=$(body | jsq "j['id']")
    JPOST $API/admin/households "{\"householdName\":\"$PY S1\",\"primaryContactPersonId\":$PS1}" >/dev/null; SH1=$(body | jsq "j['id']")
    JPOST $API/admin/households "{\"householdName\":\"$PY S2\",\"primaryContactPersonId\":$PS2}" >/dev/null; SH2=$(body | jsq "j['id']")
    JPOST $API/admin/memberships "{\"householdId\":$SH1,\"membershipPeriodId\":$P2526,\"membershipTypeId\":$T_SINGLE}" >/dev/null
    JPOST $API/admin/memberships "{\"householdId\":$SH2,\"membershipPeriodId\":$P2526,\"membershipTypeId\":$T_SINGLE}" >/dev/null
    check "CR4-22 shared email 202"      "202" "$(code -X POST $API/pay/lost-link -H 'Content-Type: application/json' -d "{\"email\":\"shared.$$@example.com\"}")"
    SHARED=$(mailpit_text "shared.$$@example.com")
    check "CR4-22b two links, one each"  "2" "$(python3 -c "import sys;print(sys.argv[1].count('/web/pay.html?t='))" "${SHARED:-none}")"
  else
    echo "SKIP CR4-20b/c/21d/22 mail-content rows (Mailpit not reachable at $MAILPIT)"
  fi

  # CR4-23/24: the CR-003 amendment — hand-entered STRIPE must be ALL-negative
  check "CR4-23 positive STRIPE 400"     "400" "$(JPOST $API/admin/payments "{\"receivedDate\":\"2026-07-18\",\"amountCents\":4500,\"method\":\"STRIPE\",\"allocations\":[{\"type\":\"MEMBERSHIP\",\"membershipId\":$MP,\"amountCents\":4500}]}")"
  check "CR4-23b mixed-sign STRIPE 400"  "400" "$(JPOST $API/admin/payments "{\"receivedDate\":\"2026-07-18\",\"amountCents\":-100,\"method\":\"STRIPE\",\"allocations\":[{\"type\":\"MEMBERSHIP\",\"membershipId\":$MP,\"amountCents\":4500},{\"type\":\"OTHER\",\"amountCents\":-4600}]}")"
  check "CR4-23c zero amount 400"        "400" "$(JPOST $API/admin/payments "{\"receivedDate\":\"2026-07-18\",\"amountCents\":0,\"method\":\"CASH\",\"allocations\":[{\"type\":\"OTHER\",\"amountCents\":0}]}")"
  # only meaningful when the webhook rows ran (MP must be ACTIVE via STRIPE)
  if [ "$WH_PROBE" = "200" ]; then
    check "CR4-24 negative STRIPE refund 201" "201" "$(JPOST $API/admin/payments "{\"receivedDate\":\"2026-07-18\",\"amountCents\":-4500,\"method\":\"STRIPE\",\"notes\":\"dashboard refund\",\"allocations\":[{\"type\":\"MEMBERSHIP\",\"membershipId\":$MP,\"amountCents\":-4500}]}")"
    check "CR4-24b membership demoted"   "PENDING_PAYMENT" "$(psqlq "SELECT status FROM membership WHERE membership_id=$MP")"
  fi
else
  echo "note: psql not found — skipping CR-004 rows (token seeding and side-effect checks need psql)"
fi

# --- admin "new member" composite endpoint (CR-010) -------------------------
# One POST creates person(s) + household + membership atomically (the
# resource owns a single Handle transaction — any failure rolls back
# everything, including an already-inserted first person). Reuses the CR-003
# fixtures' $P2526/$T_SINGLE/$T_HH/$T_LIFE/$TGTID. Since CR-018's V8, LIFE
# is priced ($0) in EVERY period, so the CR10-11 "no price" case needs its
# own throwaway unpriced type; LIFE in $TGTID stays the CR10-12 zero-due
# fixture.
check "CR10-01 new-member guest 403"    "403" "$(code -X POST $API/admin/new-member -H 'Content-Type: application/json' -d '{}')"
check "CR10-02 new-member user 403"     "403" "$(code -X POST $API/admin/new-member -H "Authorization: Bearer $USER" -H 'Content-Type: application/json' -d '{}')"
check "CR10-03 new-member noaud 401"    "401" "$(code -X POST $API/admin/new-member -H "Authorization: Bearer $NOAUD" -H 'Content-Type: application/json' -d '{}')"

if [ "$PSQL_OK" = 1 ]; then
  NM="Nm$$"

  # CR10-04: SINGLE, person only
  check "CR10-04 SINGLE person only 201" "201" "$(JPOST $API/admin/new-member "{\"person\":{\"givenName\":\"Nora\",\"familyName\":\"${NM}A\"},\"membershipPeriodId\":$P2526,\"membershipTypeId\":$T_SINGLE}")"
  NM_A_HH=$(body | jsq "j['householdId']"); NM_A_MEM=$(body | jsq "j['membershipId']")
  check "CR10-04b status pending"        "PENDING_PAYMENT" "$(body | jsq "j['status']")"
  check "CR10-04c amount due 4500"       "4500" "$(body | jsq "j['amountDueCents']")"
  check "CR10-04d one personId"          "1" "$(body | jsq "len(j['personIds'])")"
  check "CR10-04e household name default" "${NM}A household" "$(psqlq "SELECT household_name FROM household WHERE household_id=$NM_A_HH")"
  check "CR10-04f primary contact is member" "t" "$(psqlq "SELECT h.primary_contact_person_id = hp.person_id FROM household h JOIN household_person hp ON hp.household_id=h.household_id WHERE h.household_id=$NM_A_HH")"
  check "CR10-04g household_person MEMBER"     "MEMBER" "$(psqlq "SELECT relationship_type FROM household_person WHERE household_id=$NM_A_HH")"
  check "CR10-04g2 household_person joined today" "t" "$(psqlq "SELECT joined_household_date=current_date FROM household_person WHERE household_id=$NM_A_HH")"
  check "CR10-04h membership_person statutory" "t" "$(psqlq "SELECT is_statutory_member FROM membership_person WHERE membership_id=$NM_A_MEM")"
  check "CR10-04h2 membership_person voting"   "t" "$(psqlq "SELECT has_voting_rights FROM membership_person WHERE membership_id=$NM_A_MEM")"

  # CR10-05: HOUSEHOLD, person + secondPerson (PARTNER) — only MEMBER votes
  # (corrected 2026-07-18: PARTNER receives membership benefits but does
  # not vote/hold statutory-member status, see ROADMAP.md "Voting rights,
  # corrected")
  check "CR10-05 HOUSEHOLD both 201"     "201" "$(JPOST $API/admin/new-member "{\"person\":{\"givenName\":\"Otto\",\"familyName\":\"${NM}B\"},\"secondPerson\":{\"givenName\":\"Pia\",\"familyName\":\"${NM}B\",\"relationship\":\"PARTNER\"},\"membershipPeriodId\":$P2526,\"membershipTypeId\":$T_HH}")"
  NM_B_HH=$(body | jsq "j['householdId']"); NM_B_MEM=$(body | jsq "j['membershipId']")
  check "CR10-05b two personIds"         "2" "$(body | jsq "len(j['personIds'])")"
  check "CR10-05c two household_person rows" "2" "$(psqlq "SELECT count(*) FROM household_person WHERE household_id=$NM_B_HH")"
  check "CR10-05d one membership"        "1" "$(psqlq "SELECT count(*) FROM membership WHERE household_id=$NM_B_HH")"
  check "CR10-05e only MEMBER votes"     "1" "$(psqlq "SELECT count(*) FROM membership_person WHERE membership_id=$NM_B_MEM AND has_voting_rights")"
  check "CR10-05f PARTNER not voting"    "f" "$(psqlq "SELECT has_voting_rights FROM membership_person mp JOIN person p ON p.person_id=mp.person_id WHERE mp.membership_id=$NM_B_MEM AND p.given_name='Pia'")"

  # CR10-06: SINGLE with a secondPerson relationship MEMBER -> 400
  # (maximum_people counts formal/MEMBER people only); nothing created
  BEFORE=$(psqlq "SELECT count(*) FROM person WHERE family_name='${NM}C'")
  check "CR10-06 SINGLE+second MEMBER 400" "400" "$(JPOST $API/admin/new-member "{\"person\":{\"givenName\":\"Quin\",\"familyName\":\"${NM}C\"},\"secondPerson\":{\"givenName\":\"Rex\",\"familyName\":\"${NM}C\",\"relationship\":\"MEMBER\"},\"membershipPeriodId\":$P2526,\"membershipTypeId\":$T_SINGLE}")"
  check "CR10-06b names the type"        "true" "$(body | jsq "str('SINGLE' in j['error']).lower()")"
  check "CR10-06c nothing created"       "$BEFORE" "$(psqlq "SELECT count(*) FROM person WHERE family_name='${NM}C'")"

  # CR10-06p: SINGLE with a secondPerson relationship PARTNER (the default)
  # -> 201 — a non-voting second person does NOT count against
  # maximum_people (2026-07-18 correction: the cap is on formal members,
  # not household occupants)
  check "CR10-06p SINGLE+PARTNER 201"    "201" "$(JPOST $API/admin/new-member "{\"person\":{\"givenName\":\"Sky\",\"familyName\":\"${NM}P\"},\"secondPerson\":{\"givenName\":\"Robin\",\"familyName\":\"${NM}P\"},\"membershipPeriodId\":$P2526,\"membershipTypeId\":$T_SINGLE}")"
  NM_P_MEM=$(body | jsq "j['membershipId']")
  check "CR10-06p2 two personIds"        "2" "$(body | jsq "len(j['personIds'])")"
  check "CR10-06p3 PARTNER not voting"   "f" "$(psqlq "SELECT has_voting_rights FROM membership_person mp JOIN person p ON p.person_id=mp.person_id WHERE mp.membership_id=$NM_P_MEM AND p.given_name='Robin'")"

  # CR10-07: HOUSEHOLD, person only -> 201 + minimum_people warning
  check "CR10-07 HOUSEHOLD person only 201" "201" "$(JPOST $API/admin/new-member "{\"person\":{\"givenName\":\"Sian\",\"familyName\":\"${NM}D\"},\"membershipPeriodId\":$P2526,\"membershipTypeId\":$T_HH}")"
  check "CR10-07b under-minimum warning" "true" "$(body | jsq "str(any('at least 2 people' in w for w in j['warnings'])).lower()")"

  # CR10-08: secondPerson relationship DEPENDANT -> non-voting membership_person
  check "CR10-08 DEPENDANT second 201"   "201" "$(JPOST $API/admin/new-member "{\"person\":{\"givenName\":\"Tam\",\"familyName\":\"${NM}E\"},\"secondPerson\":{\"givenName\":\"Uma\",\"familyName\":\"${NM}E\",\"relationship\":\"DEPENDANT\"},\"membershipPeriodId\":$P2526,\"membershipTypeId\":$T_HH}")"
  NM_E_MEM=$(body | jsq "j['membershipId']")
  check "CR10-08b dependant not voting"  "f" "$(psqlq "SELECT has_voting_rights FROM membership_person mp JOIN person p ON p.person_id=mp.person_id WHERE mp.membership_id=$NM_E_MEM AND p.given_name='Uma'")"

  # CR10-09: missing familyName on secondPerson -> 400, atomic (person 1 rolled back too)
  check "CR10-09 bad secondPerson 400"   "400" "$(JPOST $API/admin/new-member "{\"person\":{\"givenName\":\"Vic\",\"familyName\":\"${NM}F\"},\"secondPerson\":{\"givenName\":\"NoSurname\"},\"membershipPeriodId\":$P2526,\"membershipTypeId\":$T_HH}")"
  check "CR10-09b nothing created at all" "0" "$(psqlq "SELECT count(*) FROM person WHERE family_name='${NM}F'")"

  # CR10-10: unknown period/type ids -> 400 naming the field
  check "CR10-10 unknown period 400"     "400" "$(JPOST $API/admin/new-member "{\"person\":{\"givenName\":\"Wes\",\"familyName\":\"${NM}G\"},\"membershipPeriodId\":999999,\"membershipTypeId\":$T_SINGLE}")"
  check "CR10-10b names periodId"        "true" "$(body | jsq "str('membershipPeriodId' in j['error']).lower()")"
  check "CR10-10c unknown type 400"      "400" "$(JPOST $API/admin/new-member "{\"person\":{\"givenName\":\"Xia\",\"familyName\":\"${NM}H\"},\"membershipPeriodId\":$P2526,\"membershipTypeId\":999999}")"
  check "CR10-10d names typeId"          "true" "$(body | jsq "str('membershipTypeId' in j['error']).lower()")"

  # CR10-11: type with no price for the period — a throwaway unpriced type
  # (V8 prices LIFE everywhere now); dropped again at once so period creation
  # (which requires a price for EVERY type) stays green
  T_NM_X=$(psqlq "INSERT INTO membership_type (name, description, minimum_people) VALUES ('X10TMP$$','cr10 unpriced',1) RETURNING membership_type_id" | grep -E '^[0-9]+$' | head -1)
  check "CR10-11 no price for period 400" "400" "$(JPOST $API/admin/new-member "{\"person\":{\"givenName\":\"Yun\",\"familyName\":\"${NM}I\"},\"membershipPeriodId\":$P2526,\"membershipTypeId\":$T_NM_X}")"
  psqlq "DELETE FROM membership_type WHERE membership_type_id = $T_NM_X" >/dev/null

  # CR10-12: zero-due type (LIFE, priced in TGTID) -> 201, ACTIVE, no payment row
  check "CR10-12 LIFE zero-due 201"      "201" "$(JPOST $API/admin/new-member "{\"person\":{\"givenName\":\"Zane\",\"familyName\":\"${NM}J\"},\"membershipPeriodId\":$TGTID,\"membershipTypeId\":$T_LIFE}")"
  check "CR10-12b status ACTIVE"         "ACTIVE" "$(body | jsq "j['status']")"
  NM_J_MEM=$(body | jsq "j['membershipId']")
  check "CR10-12c approved today"        "t" "$(psqlq "SELECT approved_date = current_date FROM membership WHERE membership_id=$NM_J_MEM")"
  check "CR10-12d no payment row"        "0" "$(psqlq "SELECT count(*) FROM payment_allocation WHERE membership_id=$NM_J_MEM")"

  # CR10-13: an engineered period whose late_joining_cutoff is always in
  # the past (relative to today, not a hardcoded seed date like $P2526's —
  # that made this check pass or fail on wall-clock timing rather than
  # testing the feature on its own terms). -2 days, not -1: the server's
  # "today" can lag the host's by a day (the all-UTC smoke stack vs an
  # AEST host before 10:00), and a cutoff equal to server-today correctly
  # fires no warning — two days clears the skew in both environments.
  # Via the periods API (like CR3-05's TGT period) rather than psql, since
  # psql -tAc can't cleanly capture a bare INSERT...RETURNING id (the
  # command-tag line rides along).
  check "CR10-13setup cutoff period 201" "201" "$(JPOST $API/admin/periods "{\"name\":\"NmCutoff$$\",\"startDate\":\"$(date -d '-400 days' +%F)\",\"endDate\":\"$(date -d '+300 days' +%F)\",\"lateJoiningCutoff\":\"$(date -d '-2 days' +%F)\",\"prices\":[{\"type\":\"SINGLE\",\"amountCents\":4500},{\"type\":\"HOUSEHOLD\",\"amountCents\":6500},{\"type\":\"LIFE\",\"amountCents\":0}]}")"
  CUTOFF_PERIOD=$(body | jsq "j['id']")
  check "CR10-13 late-joining create 201" "201" "$(JPOST $API/admin/new-member "{\"person\":{\"givenName\":\"Amy\",\"familyName\":\"${NM}K\"},\"membershipPeriodId\":$CUTOFF_PERIOD,\"membershipTypeId\":$T_SINGLE}")"
  check "CR10-13b late-joining warning"  "true" "$(body | jsq "str(any('late-joining' in w for w in j['warnings'])).lower()")"

  # CR10-14: explicit householdName used verbatim
  check "CR10-14 explicit name 201"      "201" "$(JPOST $API/admin/new-member "{\"person\":{\"givenName\":\"Bo\",\"familyName\":\"${NM}L\"},\"householdName\":\"The $NM Lounge\",\"membershipPeriodId\":$P2526,\"membershipTypeId\":$T_SINGLE}")"
  NM_L_HH=$(body | jsq "j['householdId']")
  check "CR10-14b name verbatim"         "The $NM Lounge" "$(psqlq "SELECT household_name FROM household WHERE household_id=$NM_L_HH")"

  # CR10-15: re-run of CR10-04's exact body -> 201 again, no dedup rejection
  check "CR10-15 re-run same body 201"   "201" "$(JPOST $API/admin/new-member "{\"person\":{\"givenName\":\"Nora\",\"familyName\":\"${NM}A\"},\"membershipPeriodId\":$P2526,\"membershipTypeId\":$T_SINGLE}")"
  check "CR10-15b second household created" "2" "$(psqlq "SELECT count(*) FROM household h JOIN household_person hp ON hp.household_id=h.household_id JOIN person p ON p.person_id=hp.person_id WHERE p.given_name='Nora' AND p.family_name='${NM}A'")"
else
  echo "note: psql not found — skipping CR-010 data cases (household/membership_person assertions need psql)"
fi

# --- segment email: templates, merge fields, send log, preferences (CR-005) --
# Fixtures need psql (household composition + preferences) and Mailpit for the
# delivery rows. A DEDICATED period isolates the segment from the many
# memberships earlier blocks left in the seeded periods. Mailpit assertions are
# per-address (the shared instance accumulates mail across runs), so unique
# per-run addresses (Em$$) key every search.
mailpit_count() { # to-address -> number of matching messages (polls briefly for one)
  for _ in 1 2 3 4 5 6; do
    local n
    n=$(curl -s "$MAILPIT/api/v1/search?query=to:%22$1%22" | jsq "j['messages_count']")
    [ "${n:-0}" != "0" ] && { echo "$n"; return; }
    sleep 0.5
  done
  echo 0
}
poll_send_status() { # send-id -> final status (waits out RUNNING, ~60s cap)
  for _ in $(seq 1 120); do
    local s
    s=$(curl -s "$API/admin/email/sends/$1" -H "Authorization: Bearer $ADMIN" | jsq "j['status']")
    [ "$s" != "RUNNING" ] && { echo "$s"; return; }
    sleep 0.5
  done
  echo RUNNING
}

# auth (guest/user 403, noaud 401 — the admin-endpoint convention)
check "CR5-01 templates guest 403"     "403" "$(code $API/admin/email/templates)"
check "CR5-01b templates user 403"     "403" "$(code $API/admin/email/templates -H "Authorization: Bearer $USER")"
check "CR5-01c templates noaud 401"    "401" "$(code $API/admin/email/templates -H "Authorization: Bearer $NOAUD")"
check "CR5-01d sends guest 403"        "403" "$(code -X POST $API/admin/email/sends -H 'Content-Type: application/json' -d '{}')"
check "CR5-01e sends user 403"         "403" "$(code -X POST $API/admin/email/sends -H "Authorization: Bearer $USER" -H 'Content-Type: application/json' -d '{}')"

# template create + merge-field validation + duplicate name
EM="Em$$"
check "CR5-02 create template 201"     "201" "$(JPOST $API/admin/email/templates "{\"name\":\"$EM tpl\",\"subject\":\"$EM Renewal {{periodName}}\",\"body\":\"Dear {{givenName}}, your balance is {{balance}}. Pay: {{payLink}}\"}")"
EMTPL=$(body | jsq "j['id']")
check "CR5-03 bad merge field 400"     "400" "$(JPOST $API/admin/email/templates "{\"name\":\"$EM bad\",\"subject\":\"x\",\"body\":\"hi {{payLnk}}\"}")"
check "CR5-03b names the bad field"    "true" "$(body | jsq "str('payLnk' in j['error']).lower()")"
check "CR5-04 duplicate name 409"      "409" "$(JPOST $API/admin/email/templates "{\"name\":\"$EM tpl\",\"subject\":\"x\",\"body\":\"y\"}")"

# footer (row 21): merge fields validated; bogus rejected
check "CR5-21 footer PUT 200"          "200" "$(JPUT $API/admin/email/footer "{\"text\":\"Regards, {{societyName}}\"}")"
check "CR5-21b footer GET echoes"      "Regards, {{societyName}}" "$(curl -s $API/admin/email/footer -H "Authorization: Bearer $ADMIN" | jsq "j['text']")"
check "CR5-21c footer bogus field 400" "400" "$(JPUT $API/admin/email/footer "{\"text\":\"{{bogus}}\"}")"

if [ "$PSQL_OK" = 1 ]; then
  # self-heal: a crashed prior run may have left a test guard row RUNNING, which
  # would 409-wedge every send below. Only ever touches matrix guard rows.
  psqlq "UPDATE email_send SET status='COMPLETE', finished_at=now() WHERE created_by LIKE 'matrix-guard-%' AND status='RUNNING'" >/dev/null
  T_SINGLE=$(psqlq "SELECT membership_type_id FROM membership_type WHERE name='SINGLE'")
  T_HH=$(psqlq "SELECT membership_type_id FROM membership_type WHERE name='HOUSEHOLD'")
  psqlq "INSERT INTO membership_type (name, description, minimum_people, maximum_people) SELECT 'LIFE','Life member',1,NULL WHERE NOT EXISTS (SELECT 1 FROM membership_type WHERE name='LIFE')" >/dev/null
  JPOST $API/admin/periods "{\"name\":\"$EM period\",\"startDate\":\"$(date -d '-10 days' +%F)\",\"endDate\":\"$(date -d '+355 days' +%F)\",\"prices\":[{\"type\":\"SINGLE\",\"amountCents\":4500},{\"type\":\"HOUSEHOLD\",\"amountCents\":6500},{\"type\":\"LIFE\",\"amountCents\":0}]}" >/dev/null
  EMPID=$(body | jsq "j['id']")

  # household A: HOUSEHOLD, two MEMBER adults sharing one address + a PARTNER with a distinct address
  SHARED="shared.$$@em.test"; CLEO="cleo.$$@em.test"
  JPOST $API/admin/people "{\"givenName\":\"Ada\",\"familyName\":\"$EM\",\"emails\":[{\"email\":\"$SHARED\",\"isPrimary\":true}]}" >/dev/null; EMA1=$(body | jsq "j['id']")
  JPOST $API/admin/people "{\"givenName\":\"Bert\",\"familyName\":\"$EM\",\"emails\":[{\"email\":\"$SHARED\",\"isPrimary\":true}]}" >/dev/null; EMA2=$(body | jsq "j['id']")
  JPOST $API/admin/people "{\"givenName\":\"Cleo\",\"familyName\":\"$EM\",\"emails\":[{\"email\":\"$CLEO\",\"isPrimary\":true}]}" >/dev/null; EMA3=$(body | jsq "j['id']")
  JPOST $API/admin/households "{\"householdName\":\"$EM A\",\"primaryContactPersonId\":$EMA1}" >/dev/null; EMHA=$(body | jsq "j['id']")
  JPOST $API/admin/households/$EMHA/people "{\"personId\":$EMA2,\"relationshipType\":\"MEMBER\"}" >/dev/null
  JPOST $API/admin/households/$EMHA/people "{\"personId\":$EMA3,\"relationshipType\":\"PARTNER\"}" >/dev/null
  JPOST $API/admin/memberships "{\"householdId\":$EMHA,\"membershipPeriodId\":$EMPID,\"membershipTypeId\":$T_HH}" >/dev/null; EMMA=$(body | jsq "j['id']")

  # household B: one MEMBER with an email, RENEWAL preference POST
  BEA="bea.$$@em.test"
  JPOST $API/admin/people "{\"givenName\":\"Bea\",\"familyName\":\"$EM\",\"emails\":[{\"email\":\"$BEA\",\"isPrimary\":true}]}" >/dev/null; EMB1=$(body | jsq "j['id']")
  JPOST $API/admin/households "{\"householdName\":\"$EM B\",\"primaryContactPersonId\":$EMB1}" >/dev/null; EMHB=$(body | jsq "j['id']")
  JPOST $API/admin/memberships "{\"householdId\":$EMHB,\"membershipPeriodId\":$EMPID,\"membershipTypeId\":$T_SINGLE}" >/dev/null; EMMB=$(body | jsq "j['id']")
  check "CR5-05 person pref RENEWAL POST 200" "200" "$(JPUT $API/admin/people/$EMB1/preferences "{\"communicationType\":\"RENEWAL\",\"deliveryMethod\":\"POST\"}")"
  check "CR5-05b GET echoes person=POST"  "POST" "$(curl -s $API/admin/people/$EMB1/preferences -H "Authorization: Bearer $ADMIN" | jsq "j['preferences']['RENEWAL']['method']")"
  check "CR5-05c source is person"        "person" "$(curl -s $API/admin/people/$EMB1/preferences -H "Authorization: Bearer $ADMIN" | jsq "j['preferences']['RENEWAL']['source']")"
  check "CR5-05d one current row in db"   "1" "$(psqlq "SELECT count(*) FROM communication_preference WHERE person_id=$EMB1 AND communication_type='RENEWAL' AND effective_to IS NULL")"

  # household C: one MEMBER, NO email at all
  JPOST $API/admin/people "{\"givenName\":\"Cass\",\"familyName\":\"$EM\"}" >/dev/null; EMC1=$(body | jsq "j['id']")
  JPOST $API/admin/households "{\"householdName\":\"$EM C\",\"primaryContactPersonId\":$EMC1}" >/dev/null; EMHC=$(body | jsq "j['id']")
  JPOST $API/admin/memberships "{\"householdId\":$EMHC,\"membershipPeriodId\":$EMPID,\"membershipTypeId\":$T_SINGLE}" >/dev/null; EMMC=$(body | jsq "j['id']")

  # household D: household GENERAL=NONE, person override RENEWAL=EMAIL (precedence)
  DOT="dot.$$@em.test"
  JPOST $API/admin/people "{\"givenName\":\"Dot\",\"familyName\":\"$EM\",\"emails\":[{\"email\":\"$DOT\",\"isPrimary\":true}]}" >/dev/null; EMD1=$(body | jsq "j['id']")
  JPOST $API/admin/households "{\"householdName\":\"$EM D\",\"primaryContactPersonId\":$EMD1}" >/dev/null; EMHD=$(body | jsq "j['id']")
  JPOST $API/admin/memberships "{\"householdId\":$EMHD,\"membershipPeriodId\":$EMPID,\"membershipTypeId\":$T_SINGLE}" >/dev/null; EMMD=$(body | jsq "j['id']")
  check "CR5-06 household GENERAL NONE 200" "200" "$(JPUT $API/admin/households/$EMHD/preferences "{\"communicationType\":\"GENERAL\",\"deliveryMethod\":\"NONE\"}")"
  check "CR5-06b person RENEWAL EMAIL 200"  "200" "$(JPUT $API/admin/people/$EMD1/preferences "{\"communicationType\":\"RENEWAL\",\"deliveryMethod\":\"EMAIL\"}")"
  check "CR5-06c person RENEWAL effective EMAIL" "EMAIL" "$(curl -s $API/admin/people/$EMD1/preferences -H "Authorization: Bearer $ADMIN" | jsq "j['preferences']['RENEWAL']['method']")"
  check "CR5-06d person GENERAL inherits NONE"  "NONE" "$(curl -s $API/admin/people/$EMD1/preferences -H "Authorization: Bearer $ADMIN" | jsq "j['preferences']['GENERAL']['method']")"
  check "CR5-06e GENERAL source household"      "household" "$(curl -s $API/admin/people/$EMD1/preferences -H "Authorization: Bearer $ADMIN" | jsq "j['preferences']['GENERAL']['source']")"

  # preview RENEWAL (row 7): A deduped to 1, PARTNER nowhere, B post, C no-email, D included
  check "CR5-07 preview RENEWAL 200"     "200" "$(JPOST $API/admin/email/preview "{\"templateId\":$EMTPL,\"periodId\":$EMPID,\"statusFilter\":\"PENDING_PAYMENT\",\"communicationType\":\"RENEWAL\"}")"
  check "CR5-07b memberships 4"          "4" "$(body | jsq "j['counts']['memberships']")"
  check "CR5-07c toSend 2"               "2" "$(body | jsq "j['counts']['toSend']")"
  check "CR5-07d skippedPost 1"          "1" "$(body | jsq "j['counts']['skippedPost']")"
  check "CR5-07e noEmail 1"              "1" "$(body | jsq "j['counts']['noEmail']")"
  check "CR5-07f partner address nowhere" "false" "$(body | jsq "str(any(r['email']=='$CLEO' for r in j['toSend'])).lower()")"
  check "CR5-07g shared address present" "true" "$(body | jsq "str(any(r['email']=='$SHARED' for r in j['toSend'])).lower()")"
  check "CR5-07h D address present"      "true" "$(body | jsq "str(any(r['email']=='$DOT' for r in j['toSend'])).lower()")"
  check "CR5-07i B in skipped-post"      "true" "$(body | jsq "str(any(r['email']=='$BEA' for r in j['skippedPost'])).lower()")"
  check "CR5-07j sample has a pay URL"   "true" "$(body | jsq "str('/web/pay.html?t=' in j['sample']['body']).lower()")"
  check "CR5-07k sample has dollar amt"  "true" "$(body | jsq "str('\$' in j['sample']['body']).lower()")"

  # preview GENERAL (row 8): D excluded (household NONE), A still included
  check "CR5-08 preview GENERAL 200"     "200" "$(JPOST $API/admin/email/preview "{\"templateId\":$EMTPL,\"periodId\":$EMPID,\"communicationType\":\"GENERAL\"}")"
  check "CR5-08b D not in toSend"        "false" "$(body | jsq "str(any(r['email']=='$DOT' for r in j['toSend'])).lower()")"
  check "CR5-08c D in skipped-none"      "true" "$(body | jsq "str(any(r['email']=='$DOT' for r in j['skippedNone'])).lower()")"
  check "CR5-08d A still in toSend"      "true" "$(body | jsq "str(any(r['email']=='$SHARED' for r in j['toSend'])).lower()")"

  RTOK_BEFORE=$(psqlq "SELECT count(*) FROM renewal_token")

  if curl -s "$MAILPIT/api/v1/messages" >/dev/null 2>&1; then
    # send (row 10): RENEWAL to the PENDING_PAYMENT segment
    check "CR5-10 create send 201"       "201" "$(JPOST $API/admin/email/sends "{\"templateId\":$EMTPL,\"periodId\":$EMPID,\"statusFilter\":\"PENDING_PAYMENT\",\"communicationType\":\"RENEWAL\"}")"
    EMSEND=$(body | jsq "j['id']")
    check "CR5-10b send completes"       "COMPLETE" "$(poll_send_status $EMSEND)"
    SENDJSON=$(curl -s $API/admin/email/sends/$EMSEND -H "Authorization: Bearer $ADMIN")
    check "CR5-10c 2 SENT"               "2" "$(echo "$SENDJSON" | jsq "j['counts'].get('SENT',0)")"
    check "CR5-10d 1 SKIPPED_POST"       "1" "$(echo "$SENDJSON" | jsq "j['counts'].get('SKIPPED_POST',0)")"
    check "CR5-10e 1 NO_EMAIL"           "1" "$(echo "$SENDJSON" | jsq "j['counts'].get('NO_EMAIL',0)")"

    # Mailpit (row 11): the two members mailed, partner + post member NOT
    check "CR5-11 shared address got mail"  "1" "$(mailpit_count "$SHARED")"
    check "CR5-11b D address got mail"      "1" "$(mailpit_count "$DOT")"
    check "CR5-11c partner NOT mailed"      "0" "$(curl -s "$MAILPIT/api/v1/search?query=to:%22$CLEO%22" | jsq "j['messages_count']")"
    check "CR5-11d post member NOT mailed"  "0" "$(curl -s "$MAILPIT/api/v1/search?query=to:%22$BEA%22" | jsq "j['messages_count']")"
    ABODY=$(mailpit_text "$SHARED")
    check "CR5-11e body has given name"     "true" "$(python3 -c "print(str('Ada' in '''$ABODY''').lower())")"
    check "CR5-11f body has the footer"     "true" "$(python3 -c "print(str('Regards,' in '''$ABODY''').lower())")"
    check "CR5-11g body has a pay URL"      "true" "$(python3 -c "print(str('/web/pay.html?t=' in '''$ABODY''').lower())")"
    # From + Reply-To headers
    AMSGID=$(curl -s "$MAILPIT/api/v1/search?query=to:%22$SHARED%22" | jsq "j['messages'][0]['ID']")
    AHDRS=$(curl -s "$MAILPIT/api/v1/message/$AMSGID")
    check "CR5-11h From is MAIL_FROM"       "noreply@memberroll.dev" "$(echo "$AHDRS" | jsq "j['From']['Address']")"
    check "CR5-11i Reply-To is MAIL_REPLY_TO" "treasurer@memberroll.dev" "$(echo "$AHDRS" | jsq "j['ReplyTo'][0]['Address']")"

    # row 12: the emailed pay link actually resolves
    EMURL=$(python3 -c "import re;m=re.search(r'(http\S*/web/pay.html\?t=\S+)','''$ABODY''');print(m.group(1) if m else '')")
    EMTOKEN=${EMURL##*t=}
    check "CR5-12 emailed pay link works"   "200" "$(code $API/pay/$EMTOKEN)"
    check "CR5-12b resolves household A membership" "$EMMA" "$(psqlq "SELECT membership_id FROM renewal_token WHERE token_hash='$(sha "$EMTOKEN")'")"

    # row 13: each SENT row carries a renewal_token_id
    check "CR5-13 SENT rows have tokens"    "0" "$(psqlq "SELECT count(*) FROM email_send_recipient WHERE email_send_id=$EMSEND AND status='SENT' AND renewal_token_id IS NULL")"

    # row 14: template edit does not rewrite the send snapshot
    SNAP_BEFORE=$(echo "$SENDJSON" | jsq "j['subject']")
    JPUT $API/admin/email/templates/$EMTPL "{\"name\":\"$EM tpl\",\"subject\":\"CHANGED {{periodName}}\",\"body\":\"changed {{payLink}}\"}" >/dev/null
    check "CR5-14 send snapshot unchanged"  "$SNAP_BEFORE" "$(curl -s $API/admin/email/sends/$EMSEND -H "Authorization: Bearer $ADMIN" | jsq "j['subject']")"

    # row 15: test-send uses sample data, mints no token
    RTOK_BEFORE_15=$(psqlq "SELECT count(*) FROM renewal_token")
    check "CR5-15 test-send 200"            "200" "$(JPOST $API/admin/email/templates/$EMTPL/test "{\"to\":\"probe.$$@example.org\"}")"
    check "CR5-15b test mail delivered"     "1" "$(mailpit_count "probe.$$@example.org")"
    PROBEBODY=$(mailpit_text "probe.$$@example.org")
    # the edited template (CR5-14) no longer carries {{givenName}}, but the fake
    # pay-link placeholder is the definitive "sample data, no token minted" signal
    check "CR5-15c test uses sample placeholder" "true" "$(python3 -c "print(str('[pay link appears here]' in '''$PROBEBODY''').lower())")"
    check "CR5-15d no real token minted by test" "$RTOK_BEFORE_15" "$(psqlq "SELECT count(*) FROM renewal_token")"

    # row 22: inline footer overrides the saved one for this send only
    check "CR5-22 send inline footer 201"   "201" "$(JPOST $API/admin/email/sends "{\"templateId\":$EMTPL,\"periodId\":$EMPID,\"statusFilter\":\"ACTIVE\",\"communicationType\":\"RENEWAL\",\"footer\":\"INLINEFOOTER\"}")"
    EMSEND22=$(body | jsq "j['id']")
    poll_send_status $EMSEND22 >/dev/null
    check "CR5-22b saved footer unchanged"  "Regards, {{societyName}}" "$(curl -s $API/admin/email/footer -H "Authorization: Bearer $ADMIN" | jsq "j['text']")"
    check "CR5-22c send body has inline footer" "true" "$(curl -s $API/admin/email/sends/$EMSEND22 -H "Authorization: Bearer $ADMIN" | jsq "str('INLINEFOOTER' in j['body']).lower()")"

    # rows 16-17: abort then resume. A dedicated period of 6 sendable memberships;
    # stop Mailpit so every attempt fails, expect ABORTED after 5 consecutive.
    JPOST $API/admin/periods "{\"name\":\"$EM abort\",\"startDate\":\"$(date -d '-10 days' +%F)\",\"endDate\":\"$(date -d '+355 days' +%F)\",\"prices\":[{\"type\":\"SINGLE\",\"amountCents\":4500},{\"type\":\"HOUSEHOLD\",\"amountCents\":6500},{\"type\":\"LIFE\",\"amountCents\":0}]}" >/dev/null
    ABPID=$(body | jsq "j['id']")
    for i in 1 2 3 4 5 6; do
      JPOST $API/admin/people "{\"givenName\":\"Ab$i\",\"familyName\":\"$EM Ab\",\"emails\":[{\"email\":\"abort.$i.$$@em.test\",\"isPrimary\":true}]}" >/dev/null; AP=$(body | jsq "j['id']")
      JPOST $API/admin/households "{\"householdName\":\"$EM Ab$i\",\"primaryContactPersonId\":$AP}" >/dev/null; AH=$(body | jsq "j['id']")
      JPOST $API/admin/memberships "{\"householdId\":$AH,\"membershipPeriodId\":$ABPID,\"membershipTypeId\":$T_SINGLE}" >/dev/null
    done
    $MAILPIT_COMPOSE stop mailpit >/dev/null 2>&1
    check "CR5-16 abort send 201"           "201" "$(JPOST $API/admin/email/sends "{\"templateId\":$EMTPL,\"periodId\":$ABPID,\"communicationType\":\"RENEWAL\"}")"
    ABSEND=$(body | jsq "j['id']")
    check "CR5-16b aborts"                  "ABORTED" "$(poll_send_status $ABSEND)"
    ABJSON=$(curl -s $API/admin/email/sends/$ABSEND -H "Authorization: Bearer $ADMIN")
    check "CR5-16c 5 consecutive FAILED"    "5" "$(echo "$ABJSON" | jsq "j['counts'].get('FAILED',0)")"
    check "CR5-16d 1 left PENDING"          "1" "$(echo "$ABJSON" | jsq "j['counts'].get('PENDING',0)")"

    # row 18: a second send while one is RUNNING -> 409 (seed a RUNNING row so the
    # guard is tested deterministically, not against the fast-aborting send above)
    # seed by a UNIQUE created_by marker and clean up by that marker — psql -tAc
    # can't cleanly capture INSERT...RETURNING (the command tag rides along), so
    # id-based cleanup would silently fail and wedge the one-running guard
    GUARD="matrix-guard-$$"
    psqlq "INSERT INTO email_send (subject, body, membership_period_id, communication_type, status, created_by) VALUES ('guard','y',$EMPID,'RENEWAL','RUNNING','$GUARD')" >/dev/null
    check "CR5-18 second send while RUNNING 409" "409" "$(JPOST $API/admin/email/sends "{\"templateId\":$EMTPL,\"periodId\":$EMPID,\"communicationType\":\"RENEWAL\"}")"
    psqlq "UPDATE email_send SET status='COMPLETE', finished_at=now() WHERE created_by='$GUARD' AND status='RUNNING'" >/dev/null

    # row 17: bring Mailpit back, resume, expect COMPLETE with all 6 delivered
    $MAILPIT_COMPOSE start mailpit >/dev/null 2>&1
    for _ in $(seq 1 30); do curl -s "$MAILPIT/api/v1/messages" >/dev/null 2>&1 && break; sleep 1; done
    # a NAMED relay (the smoke's in-network mailpit) also needs the JVM's
    # negative-DNS cache from the down window to expire (10s default), or the
    # resume's attempts fail instantly and re-abort; localhost (dev) skips —
    # no DNS is involved, so no wait is earned
    [ "$RELAY_HOST" != "localhost" ] && sleep 11
    check "CR5-17 resume 200"               "200" "$(code -X POST $API/admin/email/sends/$ABSEND/resume -H "Authorization: Bearer $ADMIN")"
    check "CR5-17b resumed completes"       "COMPLETE" "$(poll_send_status $ABSEND)"
    check "CR5-17c all 6 SENT"              "6" "$(curl -s $API/admin/email/sends/$ABSEND -H "Authorization: Bearer $ADMIN" | jsq "j['counts'].get('SENT',0)")"
    check "CR5-17d first abort address delivered" "1" "$(mailpit_count "abort.1.$$@em.test")"
    check "CR5-17e no duplicate to first address" "1" "$(curl -s "$MAILPIT/api/v1/search?query=to:%22abort.1.$$@em.test%22" | jsq "j['messages_count']")"

    # row 19: delete a template used by a past send -> snapshot survives, FK nulled
    check "CR5-19 delete used template 200" "200" "$(code -X DELETE $API/admin/email/templates/$EMTPL -H "Authorization: Bearer $ADMIN")"
    check "CR5-19b send still intact"       "$SNAP_BEFORE" "$(curl -s $API/admin/email/sends/$EMSEND -H "Authorization: Bearer $ADMIN" | jsq "j['subject']")"
    check "CR5-19c templateName now null"   "None" "$(curl -s $API/admin/email/sends/$EMSEND -H "Authorization: Bearer $ADMIN" | jsq "j['templateName']")"

    # row 20: history lists the send with per-status counts
    check "CR5-20 history lists send"       "true" "$(curl -s $API/admin/email/sends -H "Authorization: Bearer $ADMIN" | jsq "str(any(s['id']==$EMSEND and s['counts'].get('SENT',0)==2 for s in j['sends'])).lower()")"
  else
    echo "SKIP CR5-10..20 send/delivery rows (Mailpit not reachable at $MAILPIT)"
  fi
else
  echo "note: psql not found — skipping CR-005 data cases (fixtures + preferences need psql)"
fi


# --- member self-serve: provisioning + my-membership (CR-006) ----------------
# Provisioning pushes register identity into Keycloak (an idempotent admin
# batch); the my-membership + pay-link endpoints authorize on the
# person.keycloak_subject link, never the self-claimed member role. Fixture
# households (Ss$$ names): E = two MEMBER adults sharing one address
# (attribution — primary contact wins), F = MEMBER + PARTNER with distinct
# addresses (PARTNER excluded entirely), G = MEMBER with no email (not a
# candidate), H1/H2 = the same address on MEMBERs in two households
# (conflict), J = a MEMBER carrying testuser's email (adopt). Data rows need
# psql; the unverified-account, user-count and reset-mail rows also need the
# Keycloak bootstrap admin (dev default admin/admin; KEYCLOAK_ADMIN_USER/
# KEYCLOAK_ADMIN_PASSWORD override — the smoke's are in its .env), reached
# via KC_ADMIN_BASE (defined at the top).

check "CR6-01 preview guest 403"        "403" "$(code -X POST $API/admin/self-serve/preview)"
check "CR6-01b preview user 403"        "403" "$(code -X POST $API/admin/self-serve/preview -H "Authorization: Bearer $USER")"
check "CR6-01c preview noaud 401"       "401" "$(code -X POST $API/admin/self-serve/preview -H "Authorization: Bearer $NOAUD")"
check "CR6-01d provision user 403"      "403" "$(code -X POST $API/admin/self-serve/provision -H "Authorization: Bearer $USER")"
check "CR6-01e unlink guest 403"        "403" "$(code -X DELETE $API/admin/people/1/keycloak-link)"
check "CR6-07 me/membership guest 401"  "401" "$(code $API/me/membership)"
check "CR6-07b me/membership noaud 401" "401" "$(code $API/me/membership -H "Authorization: Bearer $NOAUD")"
check "CR6-13 notes retired 404"        "404" "$(code $API/notes)"

if [ "$PSQL_OK" = 1 ]; then
  SS="Ss$$"
  # re-run hygiene: a previous run linked testuser's subject to ITS J person
  # (the UNIQUE column would turn this run's adopt into CONFLICT_SUBJECT), and
  # that person still carries testuser's fixed email in another household
  # (which would correctly turn this run's J into CONFLICT_HOUSEHOLDS) — reset
  # both so each run's J fixture starts clean
  psqlq "UPDATE person SET keycloak_subject=NULL WHERE keycloak_subject='$USER_SUB'" >/dev/null
  psqlq "DELETE FROM email_address WHERE email='testuser@example.invalid'" >/dev/null

  SEML="e.$$@ss.test"; FEML="fmem.$$@ss.test"; FPART="fpart.$$@ss.test"; HEML="h.$$@ss.test"
  # E: two MEMBERs, one shared address, E1 is primary contact
  JPOST $API/admin/people "{\"givenName\":\"Elsa\",\"familyName\":\"$SS\",\"emails\":[{\"email\":\"$SEML\",\"isPrimary\":true}]}" >/dev/null; SSE1=$(body | jsq "j['id']")
  JPOST $API/admin/people "{\"givenName\":\"Errol\",\"familyName\":\"$SS\",\"emails\":[{\"email\":\"$SEML\",\"isPrimary\":true}]}" >/dev/null; SSE2=$(body | jsq "j['id']")
  JPOST $API/admin/households "{\"householdName\":\"$SS E\",\"primaryContactPersonId\":$SSE1}" >/dev/null; SSHE=$(body | jsq "j['id']")
  JPOST $API/admin/households/$SSHE/people "{\"personId\":$SSE2,\"relationshipType\":\"MEMBER\"}" >/dev/null
  JPOST $API/admin/memberships "{\"householdId\":$SSHE,\"membershipPeriodId\":$P2526,\"membershipTypeId\":$T_HH}" >/dev/null; SSME=$(body | jsq "j['id']")
  # F: MEMBER with own address + PARTNER with a distinct one
  JPOST $API/admin/people "{\"givenName\":\"Faye\",\"familyName\":\"$SS\",\"emails\":[{\"email\":\"$FEML\",\"isPrimary\":true}]}" >/dev/null; SSF1=$(body | jsq "j['id']")
  JPOST $API/admin/people "{\"givenName\":\"Flip\",\"familyName\":\"$SS\",\"emails\":[{\"email\":\"$FPART\",\"isPrimary\":true}]}" >/dev/null; SSF2=$(body | jsq "j['id']")
  JPOST $API/admin/households "{\"householdName\":\"$SS F\",\"primaryContactPersonId\":$SSF1}" >/dev/null; SSHF=$(body | jsq "j['id']")
  JPOST $API/admin/households/$SSHF/people "{\"personId\":$SSF2,\"relationshipType\":\"PARTNER\"}" >/dev/null
  JPOST $API/admin/memberships "{\"householdId\":$SSHF,\"membershipPeriodId\":$P2526,\"membershipTypeId\":$T_HH}" >/dev/null
  # G: MEMBER, no email
  JPOST $API/admin/people "{\"givenName\":\"Gina\",\"familyName\":\"$SS\"}" >/dev/null; SSGP=$(body | jsq "j['id']")
  JPOST $API/admin/households "{\"householdName\":\"$SS G\",\"primaryContactPersonId\":$SSGP}" >/dev/null; SSHG=$(body | jsq "j['id']")
  JPOST $API/admin/memberships "{\"householdId\":$SSHG,\"membershipPeriodId\":$P2526,\"membershipTypeId\":$T_SINGLE}" >/dev/null
  # H1/H2: the same address on MEMBERs of two different households
  JPOST $API/admin/people "{\"givenName\":\"Hank\",\"familyName\":\"$SS\",\"emails\":[{\"email\":\"$HEML\",\"isPrimary\":true}]}" >/dev/null; SSH1=$(body | jsq "j['id']")
  JPOST $API/admin/people "{\"givenName\":\"Hope\",\"familyName\":\"$SS\",\"emails\":[{\"email\":\"$HEML\",\"isPrimary\":true}]}" >/dev/null; SSH2=$(body | jsq "j['id']")
  JPOST $API/admin/households "{\"householdName\":\"$SS H1\",\"primaryContactPersonId\":$SSH1}" >/dev/null; SSHH1=$(body | jsq "j['id']")
  JPOST $API/admin/households "{\"householdName\":\"$SS H2\",\"primaryContactPersonId\":$SSH2}" >/dev/null; SSHH2=$(body | jsq "j['id']")
  JPOST $API/admin/memberships "{\"householdId\":$SSHH1,\"membershipPeriodId\":$P2526,\"membershipTypeId\":$T_SINGLE}" >/dev/null
  JPOST $API/admin/memberships "{\"householdId\":$SSHH2,\"membershipPeriodId\":$P2526,\"membershipTypeId\":$T_SINGLE}" >/dev/null
  # J: a MEMBER whose email is testuser's (the adopt case)
  JPOST $API/admin/people "{\"givenName\":\"Jude\",\"familyName\":\"$SS\",\"emails\":[{\"email\":\"testuser@example.invalid\",\"isPrimary\":true}]}" >/dev/null; SSJP=$(body | jsq "j['id']")
  JPOST $API/admin/households "{\"householdName\":\"$SS J\",\"primaryContactPersonId\":$SSJP}" >/dev/null; SSHJ=$(body | jsq "j['id']")
  JPOST $API/admin/memberships "{\"householdId\":$SSHJ,\"membershipPeriodId\":$P2526,\"membershipTypeId\":$T_SINGLE}" >/dev/null; SSMJ=$(body | jsq "j['id']")

  # the Keycloak bootstrap admin backs the rows the app deliberately has no API for
  KCTOK=$(curl -s -X POST "$KC_ADMIN_BASE/realms/master/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=admin-cli \
    -d username="${KEYCLOAK_ADMIN_USER:-admin}" -d password="${KEYCLOAK_ADMIN_PASSWORD:-admin}" | jsq "j['access_token']")
  kc_users_count() { curl -s "$KC_ADMIN_BASE/admin/realms/memberroll/users/count" -H "Authorization: Bearer $KCTOK"; }

  # CR6-02: preview resolves the whole table, writes nothing
  check "CR6-02 preview 200"            "200" "$(code -X POST $API/admin/self-serve/preview -H "Authorization: Bearer $ADMIN")"
  PREV=$(body)
  ssrow() { echo "$PREV" | jsq "next(r['action'] for r in j['rows'] if r['personId']==$1)"; }
  check "CR6-02b E primary CREATE"      "CREATE" "$(ssrow $SSE1)"
  check "CR6-02c E sharer SHARED_ADDRESS" "SHARED_ADDRESS" "$(ssrow $SSE2)"
  check "CR6-02d F member CREATE"       "CREATE" "$(ssrow $SSF1)"
  check "CR6-02e F partner absent"      "0" "$(echo "$PREV" | jsq "sum(1 for r in j['rows'] if r['personId']==$SSF2)")"
  check "CR6-02f G (no email) absent"   "0" "$(echo "$PREV" | jsq "sum(1 for r in j['rows'] if r['personId']==$SSGP)")"
  check "CR6-02g H1 conflict"           "CONFLICT_HOUSEHOLDS" "$(ssrow $SSH1)"
  check "CR6-02g2 H2 conflict"          "CONFLICT_HOUSEHOLDS" "$(ssrow $SSH2)"
  check "CR6-02h J ADOPT"               "ADOPT" "$(ssrow $SSJP)"
  check "CR6-02i preview linked nothing" "0" "$(psqlq "SELECT count(*) FROM person WHERE person_id IN ($SSE1,$SSF1,$SSJP) AND keycloak_subject IS NOT NULL")"

  # CR6-03: provision — same actions applied; Keycloak first, DB second
  check "CR6-03 provision 200"          "200" "$(code -X POST $API/admin/self-serve/provision -H "Authorization: Bearer $ADMIN")"
  PREV=$(body)
  check "CR6-03b E primary CREATE"      "CREATE" "$(ssrow $SSE1)"
  check "CR6-03c J ADOPT"               "ADOPT" "$(ssrow $SSJP)"
  check "CR6-03d E1/F1/J linked"        "3" "$(psqlq "SELECT count(*) FROM person WHERE person_id IN ($SSE1,$SSF1,$SSJP) AND keycloak_subject IS NOT NULL")"
  check "CR6-03e nobody else linked"    "0" "$(psqlq "SELECT count(*) FROM person WHERE person_id IN ($SSE2,$SSF2,$SSGP,$SSH1,$SSH2) AND keycloak_subject IS NOT NULL")"
  check "CR6-03f J linked to testuser"  "$USER_SUB" "$(psqlq "SELECT keycloak_subject FROM person WHERE person_id=$SSJP")"

  # CR6-04: the created account carries claim=member, verified, member granted;
  # testuser's claim was set through the adopt path
  check "CR6-04 created account state"  "member|True|True" "$(curl -s "$API/admin/users?search=$SEML" -H "Authorization: Bearer $ADMIN" | jsq "next(str(u['claimed_role'])+'|'+str(u['verified'])+'|'+str('member' in u['roles']) for u in j if u['email']=='$SEML')")"
  USER4=$(tok testuser test-cli)
  check "CR6-04b testuser claim member" "member" "$(curl -s $API/whoami -H "Authorization: Bearer $USER4" | jsq "j['claimed_role']")"
  check "CR6-04c testuser verified"     "true" "$(curl -s $API/whoami -H "Authorization: Bearer $USER4" | jsq "str(j['verified']).lower()")"

  # CR6-05: idempotent — a second run re-reports ALREADY_LINKED, creates nobody
  KCN_BEFORE=$(kc_users_count)
  check "CR6-05 provision again 200"    "200" "$(code -X POST $API/admin/self-serve/provision -H "Authorization: Bearer $ADMIN")"
  PREV=$(body)
  check "CR6-05b E1 already linked"     "ALREADY_LINKED" "$(ssrow $SSE1)"
  check "CR6-05c F1 already linked"     "ALREADY_LINKED" "$(ssrow $SSF1)"
  check "CR6-05d J already linked"      "ALREADY_LINKED" "$(ssrow $SSJP)"
  check "CR6-05e user count unchanged"  "$KCN_BEFORE" "$(kc_users_count)"

  # CR6-06: the linked member's view
  check "CR6-06 testuser membership 200" "200" "$(code $API/me/membership -H "Authorization: Bearer $USER4")"
  check "CR6-06b linked true"           "true" "$(body | jsq "str(j['linked']).lower()")"
  check "CR6-06c J's household shown"   "$SSMJ" "$(body | jsq "next(m['membershipId'] for m in j['memberships'])")"
  check "CR6-06d amounts"               "4500|0|2025-2026|SINGLE" "$(curl -s $API/me/membership -H "Authorization: Bearer $USER4" | jsq "(lambda m: str(m['amountDueCents'])+'|'+str(m['amountPaidCents'])+'|'+m['periodName']+'|'+m['typeName'])(j['memberships'][0])")"
  # CR6-07 (rest): any authenticated-but-unlinked account learns only about itself
  check "CR6-07c viewer linked false"   "false" "$(curl -s $API/me/membership -H "Authorization: Bearer $VIEWER" | jsq "str(j['linked']).lower()")"

  # CR6-08: pay-link handoff — the CR-004 surface end-to-end
  check "CR6-08 pay-link 200"           "200" "$(code -X POST $API/me/membership/$SSMJ/pay-link -H "Authorization: Bearer $USER4")"
  SSURL=$(body | jsq "j['url']"); SSTOK=${SSURL##*t=}
  check "CR6-08b guest pay view 200"    "200" "$(code $API/pay/$SSTOK)"
  check "CR6-08c same membership"       "$SSMJ" "$(psqlq "SELECT membership_id FROM renewal_token WHERE token_hash='$(sha "$SSTOK")'")"
  # CR6-09: another household's membership is a plain 404 (no enumeration)
  check "CR6-09 foreign pay-link 404"   "404" "$(code -X POST $API/me/membership/$SSME/pay-link -H "Authorization: Bearer $USER4")"

  # CR6-10: an UNVERIFIED self-registration with a member's address never links
  GEML="gu.$$@ss.test"
  JPUT $API/admin/people/$SSGP "{\"givenName\":\"Gina\",\"familyName\":\"$SS\",\"emails\":[{\"email\":\"$GEML\",\"isPrimary\":true}]}" >/dev/null
  check "CR6-10 seed unverified user 201" "201" "$(code -X POST "$KC_ADMIN_BASE/admin/realms/memberroll/users" -H "Authorization: Bearer $KCTOK" -H 'Content-Type: application/json' -d "{\"username\":\"$GEML\",\"email\":\"$GEML\",\"firstName\":\"Gina\",\"lastName\":\"$SS\",\"enabled\":true,\"emailVerified\":false}")"
  check "CR6-10b preview 200"           "200" "$(code -X POST $API/admin/self-serve/preview -H "Authorization: Bearer $ADMIN")"
  PREV=$(body)
  check "CR6-10c G skipped unverified"  "SKIPPED_UNVERIFIED" "$(ssrow $SSGP)"
  check "CR6-10d G not linked"          "" "$(psqlq "SELECT keycloak_subject FROM person WHERE person_id=$SSGP")"

  # CR6-11: unlink — the account stays, the view empties, re-provision re-adopts
  check "CR6-11 unlink 200"             "200" "$(code -X DELETE $API/admin/people/$SSJP/keycloak-link -H "Authorization: Bearer $ADMIN")"
  check "CR6-11b now linked false"      "false" "$(curl -s $API/me/membership -H "Authorization: Bearer $USER4" | jsq "str(j['linked']).lower()")"
  check "CR6-11c unlink absent person 404" "404" "$(code -X DELETE $API/admin/people/999999/keycloak-link -H "Authorization: Bearer $ADMIN")"
  code -X POST $API/admin/self-serve/provision -H "Authorization: Bearer $ADMIN" >/dev/null
  check "CR6-11d re-adopted, same subject" "$USER_SUB" "$(psqlq "SELECT keycloak_subject FROM person WHERE person_id=$SSJP")"

  # CR6-12: the V6 column really is unique
  check "CR6-12 unique constraint exists" "1" "$(psqlq "SELECT count(*) FROM pg_constraint WHERE conrelid='person'::regclass AND contype='u'")"
  check "CR6-12b duplicate subject refused" "yes" "$(PGPASSWORD=${MEMBERROLL_DB_PASSWORD:-memberroll} psql -h localhost -p "${POSTGRES_PORT:-5433}" -U memberroll -d memberroll -tAc "UPDATE person SET keycloak_subject='$USER_SUB' WHERE person_id=$SSE1" 2>&1 | grep -q 'duplicate key' && echo yes || echo no)"

  # CR6-14: resetPasswordAllowed took — the login page offers Forgot Password.
  # The web client mandates PKCE, so the probe carries a throwaway S256
  # challenge (the page renders regardless of whether it is ever redeemed).
  WEB_REDIRECT=$(python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1],safe=''))" "$ORIGIN/server/web/")
  check "CR6-14 login page reset link"  "yes" "$(curl -s "$KC_BASE/realms/memberroll/protocol/openid-connect/auth?client_id=web&redirect_uri=$WEB_REDIRECT&response_type=code&scope=openid&code_challenge_method=S256&code_challenge=$(sha probe | head -c 43)" | grep -q 'reset-credentials' && echo yes || echo no)"

  # CR6-15: the realm smtpServer block works — an admin-triggered
  # reset-credentials mail (used here only as a relay probe) lands in Mailpit
  if curl -s -m 2 -o /dev/null "$MAILPIT/api/v1/messages"; then
    FUID=$(curl -s "$KC_ADMIN_BASE/admin/realms/memberroll/users?email=$FEML&exact=true" -H "Authorization: Bearer $KCTOK" | jsq "j[0]['id']")
    check "CR6-15 reset mail sent 204"  "204" "$(code -X PUT "$KC_ADMIN_BASE/admin/realms/memberroll/users/$FUID/execute-actions-email" -H "Authorization: Bearer $KCTOK" -H 'Content-Type: application/json' -d '["UPDATE_PASSWORD"]')"
    check "CR6-15b reset mail in Mailpit" "1" "$(mailpit_count "$FEML")"
  else
    echo "SKIP CR6-15 reset-mail relay probe (Mailpit not reachable at $MAILPIT)"
  fi
else
  echo "note: psql not found — skipping CR-006 data cases (fixtures + link assertions need psql)"
fi

# --- payment receipts (CR-012) ----------------------------------------------
# Receipts render from the RECORDED payment: the GET is stateless JSON (header/
# line/total + canonical text + defaultTo), the POST emails it (default address
# payer→household, or an explicit {to}). Mail rows key off the server's SMTP
# config, probed via the CR-005 templates GET (mailEnabled); the 503 row is the
# flip side, run exactly when the server has no SMTP.
if [ "$PSQL_OK" = 1 ]; then
  RC="Rcpt$$"
  # payer with a primary email; a household + membership fully paid by payment P
  # so its receipt carries the "financial for <period>" line
  JPOST $API/admin/people "{\"givenName\":\"Rhys\",\"familyName\":\"$RC\",\"emails\":[{\"email\":\"cr12payer.$$@example.com\",\"isPrimary\":true}]}" >/dev/null; RCPAYER=$(body | jsq "j['id']")
  JPOST $API/admin/households "{\"householdName\":\"$RC HH\",\"primaryContactPersonId\":$RCPAYER}" >/dev/null; RCHH=$(body | jsq "j['id']")
  JPOST $API/admin/memberships "{\"householdId\":$RCHH,\"membershipPeriodId\":$P2526,\"membershipTypeId\":$T_SINGLE}" >/dev/null; RCMEM=$(body | jsq "j['id']")
  # payment P: BANK_TRANSFER, MEMBERSHIP 4500 (fully pays) + DONATION 500, payer set
  JPOST $API/admin/payments "{\"receivedDate\":\"2026-07-18\",\"amountCents\":5000,\"method\":\"BANK_TRANSFER\",\"bankReference\":\"TFR-$$\",\"payerPersonId\":$RCPAYER,\"allocations\":[{\"type\":\"MEMBERSHIP\",\"membershipId\":$RCMEM,\"amountCents\":4500},{\"type\":\"DONATION\",\"amountCents\":500}]}" >/dev/null; RCP=$(body | jsq "j['id']")

  # rows 1–2: role gate (guest/user 403, noaud 401)
  check "CR12-01 GET receipt guest 403"  "403" "$(code $API/admin/payments/$RCP/receipt)"
  check "CR12-01b GET receipt user 403"  "403" "$(code $API/admin/payments/$RCP/receipt -H "Authorization: Bearer $USER")"
  check "CR12-01c GET receipt noaud 401" "401" "$(code $API/admin/payments/$RCP/receipt -H "Authorization: Bearer $NOAUD")"
  check "CR12-02 POST receipt guest 403" "403" "$(code -X POST $API/admin/payments/$RCP/receipt)"
  check "CR12-02b POST receipt user 403" "403" "$(code -X POST $API/admin/payments/$RCP/receipt -H "Authorization: Bearer $USER")"

  # row 3: the composed receipt (+ row 11 financial line, membership is ACTIVE)
  RGET=$(curl -s $API/admin/payments/$RCP/receipt -H "Authorization: Bearer $ADMIN")
  RTEXT=$(echo "$RGET" | jsq "j['text']")
  check "CR12-03 text has receipt no"    "true" "$(python3 -c "import sys;print(str('Receipt #$RCP' in sys.argv[1]).lower())" "$RTEXT")"
  check "CR12-03b text has method"       "true" "$(python3 -c "import sys;print(str('BANK_TRANSFER' in sys.argv[1]).lower())" "$RTEXT")"
  check "CR12-03c text membership line"  "true" "$(python3 -c "import sys;print(str('Membership 2025-2026 (SINGLE): \$45.00' in sys.argv[1]).lower())" "$RTEXT")"
  check "CR12-03d text donation line"    "true" "$(python3 -c "import sys;print(str('Donation: \$5.00' in sys.argv[1]).lower())" "$RTEXT")"
  check "CR12-03e text total"            "true" "$(python3 -c "import sys;print(str('Total: \$50.00' in sys.argv[1]).lower())" "$RTEXT")"
  check "CR12-03f defaultTo is payer"    "cr12payer.$$@example.com" "$(echo "$RGET" | jsq "j['defaultTo']")"
  check "CR12-03g not a refund"          "False" "$(echo "$RGET" | jsq "j['refund']")"
  check "CR12-11 financial-for line"     "true" "$(python3 -c "import sys;print(str('financial for 2025-2026' in sys.argv[1]).lower())" "$RTEXT")"

  # row 4: membership-only payment, payer unset → default = household attributed address
  JPOST $API/admin/payments "{\"receivedDate\":\"2026-07-18\",\"amountCents\":100,\"method\":\"CASH\",\"allocations\":[{\"type\":\"MEMBERSHIP\",\"membershipId\":$RCMEM,\"amountCents\":100}]}" >/dev/null; RCP4=$(body | jsq "j['id']")
  check "CR12-04 default = household email" "cr12payer.$$@example.com" "$(curl -s $API/admin/payments/$RCP4/receipt -H "Authorization: Bearer $ADMIN" | jsq "j['defaultTo']")"

  # row 5: unknown payment 404
  check "CR12-05 unknown payment 404"    "404" "$(code $API/admin/payments/999999/receipt -H "Authorization: Bearer $ADMIN")"

  # row 8 (GET half): a household with no member email yields no default
  JPOST $API/admin/people "{\"givenName\":\"Nemo\",\"familyName\":\"$RC\"}" >/dev/null; RCNOEM=$(body | jsq "j['id']")
  JPOST $API/admin/households "{\"householdName\":\"$RC NoMail\",\"primaryContactPersonId\":$RCNOEM}" >/dev/null; RCHH8=$(body | jsq "j['id']")
  JPOST $API/admin/memberships "{\"householdId\":$RCHH8,\"membershipPeriodId\":$P2526,\"membershipTypeId\":$T_SINGLE}" >/dev/null; RCMEM8=$(body | jsq "j['id']")
  JPOST $API/admin/payments "{\"receivedDate\":\"2026-07-18\",\"amountCents\":100,\"method\":\"CASH\",\"allocations\":[{\"type\":\"MEMBERSHIP\",\"membershipId\":$RCMEM8,\"amountCents\":100}]}" >/dev/null; RCP8=$(body | jsq "j['id']")
  check "CR12-08 GET no default null"    "None" "$(curl -s $API/admin/payments/$RCP8/receipt -H "Authorization: Bearer $ADMIN" | jsq "j['defaultTo']")"

  # rows 6/7/8-POST vs 10: the mail path depends on the server's SMTP config
  MAIL_ON=$(curl -s $API/admin/email/templates -H "Authorization: Bearer $ADMIN" | jsq "j['mailEnabled']")
  if [ "$MAIL_ON" = "True" ]; then
    # row 8 (POST half): no default and no `to` → 400 (never a silent no-op)
    check "CR12-08b POST no address 400" "400" "$(code -X POST $API/admin/payments/$RCP8/receipt -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{}')"
    # row 6: no body → default address; 202 and sentTo echoes the default
    check "CR12-06 POST default 202"     "202" "$(code -X POST $API/admin/payments/$RCP/receipt -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{}')"
    check "CR12-06b sentTo is default"   "cr12payer.$$@example.com" "$(body | jsq "j['sentTo']")"
    if curl -s -m 2 -o /dev/null "$MAILPIT/api/v1/messages"; then
      MBODY=$(mailpit_text "cr12payer.$$@example.com")
      RTEXT2=$(curl -s $API/admin/payments/$RCP/receipt -H "Authorization: Bearer $ADMIN" | jsq "j['text']")
      # SMTP canonicalises the text body to CRLF; strip \r before comparing content
      check "CR12-06c mail body == receipt text" "true" "$(python3 -c "import sys;n=lambda s:s.replace(chr(13),'').strip();print(str(n(sys.argv[1])==n(sys.argv[2])).lower())" "${MBODY:-x}" "${RTEXT2:-y}")"
      # row 7: an explicit {to} overrides the default
      code -X POST $API/admin/payments/$RCP/receipt -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d "{\"to\":\"cr12over.$$@example.com\"}" >/dev/null
      check "CR12-07 override delivered" "1" "$(mailpit_count "cr12over.$$@example.com")"
    else
      echo "SKIP CR12-06c/07 mail-content rows (Mailpit not reachable at $MAILPIT)"
    fi
  else
    # row 10: the flip side — no SMTP means an explicit 503, never a silent no-op
    check "CR12-10 POST mail unconfigured 503" "503" "$(code -X POST $API/admin/payments/$RCP/receipt -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{}')"
  fi

  # row 9: a reversal renders as a refund record with negative amounts. LAST in
  # this block — it demotes RCMEM below its fee, so run it after every P receipt
  JPOST $API/admin/payments "{\"receivedDate\":\"2026-07-18\",\"amountCents\":-5000,\"method\":\"BANK_TRANSFER\",\"notes\":\"reversal of #$RCP\",\"allocations\":[{\"type\":\"MEMBERSHIP\",\"membershipId\":$RCMEM,\"amountCents\":-4500},{\"type\":\"DONATION\",\"amountCents\":-500}]}" >/dev/null; RCN=$(body | jsq "j['id']")
  NGET=$(curl -s $API/admin/payments/$RCN/receipt -H "Authorization: Bearer $ADMIN")
  NTEXT=$(echo "$NGET" | jsq "j['text']")
  check "CR12-09 refund flag true"       "True" "$(echo "$NGET" | jsq "j['refund']")"
  check "CR12-09b refund heading"        "true" "$(python3 -c "import sys;print(str('Refund record #$RCN' in sys.argv[1]).lower())" "$NTEXT")"
  check "CR12-09c negative total"        "true" "$(python3 -c "import sys;print(str('Total: -\$50.00' in sys.argv[1]).lower())" "$NTEXT")"
  check "CR12-09d membership line -4500" "-4500" "$(echo "$NGET" | jsq "j['lines'][0]['amountCents']")"
else
  echo "note: psql not found — skipping CR-012 receipt cases (fixtures need psql)"
fi

# --- committee register (CR-013) --------------------------------------------
# Person-only fixtures (Com$$), so this block runs without psql — only the
# singular-office index check (row 13) needs it. The AGM roll closes ALL open
# appointments before inserting the slate, so each run supersedes any prior
# committee and re-runs converge. B carries a primary email for the CR-007
# contacts seam (row 14).
CM="Com$$"
AGM1=2026-08-01
AGM2=2027-08-01
# the committee is a global singleton (its AGM close-all touches every open
# appointment, not just this run's), so clear any prior run's rows first — the
# honest fixture for a singleton register. Uses the API, no psql needed.
for aid in $(curl -s "$API/admin/committee?includeEnded=true" -H "Authorization: Bearer $ADMIN" | jsq "' '.join(str(a['id']) for a in j['committee'])"); do
  code -X DELETE "$API/admin/committee/appointments/$aid" -H "Authorization: Bearer $ADMIN" >/dev/null
done
JPOST $API/admin/people "{\"givenName\":\"Alan\",\"familyName\":\"$CM\"}" >/dev/null; CA=$(body | jsq "j['id']")
JPOST $API/admin/people "{\"givenName\":\"Bea\",\"familyName\":\"$CM\",\"emails\":[{\"email\":\"sec.$$@example.com\",\"isPrimary\":true}]}" >/dev/null; CB=$(body | jsq "j['id']")
JPOST $API/admin/people "{\"givenName\":\"Cy\",\"familyName\":\"$CM\"}" >/dev/null; CC=$(body | jsq "j['id']")
JPOST $API/admin/people "{\"givenName\":\"Di\",\"familyName\":\"$CM\"}" >/dev/null; CD=$(body | jsq "j['id']")
JPOST $API/admin/people "{\"givenName\":\"Ed\",\"familyName\":\"$CM\"}" >/dev/null; CE=$(body | jsq "j['id']")

# row 1: current-committee GET role gate (guest/user 403, noaud 401)
check "CR13-01 committee guest 403"    "403" "$(code $API/admin/committee)"
check "CR13-01b committee user 403"    "403" "$(code $API/admin/committee -H "Authorization: Bearer $USER")"
check "CR13-01c committee noaud 401"   "401" "$(code $API/admin/committee -H "Authorization: Bearer $NOAUD")"
check "CR13-01d contacts guest 403"    "403" "$(code $API/admin/committee/contacts)"
# row 2: AGM roll role gate
check "CR13-02 agm guest 403"          "403" "$(code -X POST $API/admin/committee/agm)"
check "CR13-02b agm user 403"          "403" "$(code -X POST $API/admin/committee/agm -H "Authorization: Bearer $USER" -H 'Content-Type: application/json' -d '{}')"

# row 3: the AGM roll — A president, B secretary, C+D ordinary
check "CR13-03 agm roll 201"           "201" "$(JPOST $API/admin/committee/agm "{\"agmDate\":\"$AGM1\",\"minuteRef\":\"AGM $$ item 5\",\"appointments\":[{\"personId\":$CA,\"office\":\"PRESIDENT\"},{\"personId\":$CB,\"office\":\"SECRETARY\"},{\"personId\":$CC,\"office\":\"ORDINARY\"},{\"personId\":$CD,\"office\":\"ORDINARY\"}]}")"
check "CR13-03b committee has 4"       "4" "$(body | jsq "len(j['committee'])")"
check "CR13-03c ordered pres first"    "PRESIDENT" "$(body | jsq "j['committee'][0]['office']")"
check "CR13-03d then secretary"        "SECRETARY" "$(body | jsq "j['committee'][1]['office']")"
check "CR13-03e then ordinary"         "ORDINARY" "$(body | jsq "j['committee'][2]['office']")"

# row 4: GET current — A president, since = AGM date, ended null
CGET=$(curl -s "$API/admin/committee" -H "Authorization: Bearer $ADMIN")
check "CR13-04 A office PRESIDENT"     "PRESIDENT" "$(echo "$CGET" | jsq "next(a['office'] for a in j['committee'] if a['personId']==$CA)")"
check "CR13-04b A since AGM date"      "$AGM1" "$(echo "$CGET" | jsq "next(a['startedDate'] for a in j['committee'] if a['personId']==$CA)")"
check "CR13-04c A ended null"          "None" "$(echo "$CGET" | jsq "next(a['endedDate'] for a in j['committee'] if a['personId']==$CA)")"

# row 5: two presidents → 400, nothing written (prior committee intact)
check "CR13-05 two presidents 400"     "400" "$(JPOST $API/admin/committee/agm "{\"agmDate\":\"$AGM1\",\"appointments\":[{\"personId\":$CA,\"office\":\"PRESIDENT\"},{\"personId\":$CB,\"office\":\"PRESIDENT\"}]}")"
check "CR13-05b A still president"     "PRESIDENT" "$(curl -s "$API/admin/committee" -H "Authorization: Bearer $ADMIN" | jsq "next(a['office'] for a in j['committee'] if a['personId']==$CA)")"

# row 6: one person given both president and vice-president → 400
check "CR13-06 pres+vicepres 400"      "400" "$(JPOST $API/admin/committee/agm "{\"agmDate\":\"$AGM1\",\"appointments\":[{\"personId\":$CA,\"office\":\"PRESIDENT\"},{\"personId\":$CA,\"office\":\"VICE_PRESIDENT\"}]}")"

# row 7: a second AGM — A vice-president, B secretary again, C president. Prior
# terms carry ended_date = AGM2; A now spans two terms in the history.
check "CR13-07 second agm 201"         "201" "$(JPOST $API/admin/committee/agm "{\"agmDate\":\"$AGM2\",\"appointments\":[{\"personId\":$CC,\"office\":\"PRESIDENT\"},{\"personId\":$CA,\"office\":\"VICE_PRESIDENT\"},{\"personId\":$CB,\"office\":\"SECRETARY\"}]}")"
check "CR13-07b current now 3"         "3" "$(body | jsq "len(j['committee'])")"
HALL=$(curl -s "$API/admin/committee?includeEnded=true" -H "Authorization: Bearer $ADMIN")
check "CR13-07c A prior term ended"    "$AGM2" "$(echo "$HALL" | jsq "next(a['endedDate'] for a in j['committee'] if a['personId']==$CA and a['office']=='PRESIDENT')")"
check "CR13-07d A has two terms"       "2" "$(echo "$HALL" | jsq "sum(1 for a in j['committee'] if a['personId']==$CA)")"

# row 8: casual vacancy — A gets treasurer while vice-president (cl. 14(2): two offices)
check "CR13-08 second office 201"      "201" "$(JPOST $API/admin/committee/appointments "{\"personId\":$CA,\"office\":\"TREASURER\",\"startedDate\":\"2027-09-01\"}")"
CAT=$(body | jsq "j['appointment']['id']")
check "CR13-08b A holds 2 current"     "2" "$(curl -s "$API/admin/committee" -H "Authorization: Bearer $ADMIN" | jsq "sum(1 for a in j['committee'] if a['personId']==$CA)")"

# row 9: a second secretary (B already secretary) → 409 (singular office taken)
check "CR13-09 second secretary 409"   "409" "$(JPOST $API/admin/committee/appointments "{\"personId\":$CD,\"office\":\"SECRETARY\",\"startedDate\":\"2027-09-01\"}")"

# row 10: resignation — PUT endedDate closes the treasurer term
check "CR13-10 end term 200"           "200" "$(JPUT $API/admin/committee/appointments/$CAT "{\"endedDate\":\"2027-10-01\"}")"
check "CR13-10b treasurer gone current" "0" "$(curl -s "$API/admin/committee" -H "Authorization: Bearer $ADMIN" | jsq "sum(1 for a in j['committee'] if a['id']==$CAT)")"
check "CR13-10c treasurer in history"  "2027-10-01" "$(curl -s "$API/admin/committee?includeEnded=true" -H "Authorization: Bearer $ADMIN" | jsq "next(a['endedDate'] for a in j['committee'] if a['id']==$CAT)")"

# row 11: a non-member appointee → 201 with a warnings entry (soft guard, not blocked)
check "CR13-11 non-member appt 201"    "201" "$(JPOST $API/admin/committee/appointments "{\"personId\":$CE,\"office\":\"ORDINARY\",\"startedDate\":\"2027-09-01\"}")"
check "CR13-11b warns not a member"    "true" "$(body | jsq "str(len(j['warnings'])>0).lower()")"
CET=$(body | jsq "j['appointment']['id']")

# row 12: DELETE a mistaken row → gone from current and history; unknown → 404
check "CR13-12 delete appt 200"        "200" "$(code -X DELETE $API/admin/committee/appointments/$CET -H "Authorization: Bearer $ADMIN")"
check "CR13-12b gone from history"     "0" "$(curl -s "$API/admin/committee?includeEnded=true" -H "Authorization: Bearer $ADMIN" | jsq "sum(1 for a in j['committee'] if a['id']==$CET)")"
check "CR13-12c delete unknown 404"    "404" "$(code -X DELETE $API/admin/committee/appointments/999999 -H "Authorization: Bearer $ADMIN")"
check "CR13-12d put unknown 404"       "404" "$(JPUT $API/admin/committee/appointments/999999 "{\"endedDate\":\"2027-10-01\"}")"

# row 13: the singular-office partial unique index (psql)
if [ "$PSQL_OK" = 1 ]; then
  check "CR13-13 singular index present" "1" "$(psqlq "SELECT count(*) FROM pg_indexes WHERE indexname='committee_appointment_singular_office'")"
  # C is the current president (row 7); a hand-inserted second open president is
  # rejected by the index
  check "CR13-13b second open president rejected" "yes" "$(PGPASSWORD=${MEMBERROLL_DB_PASSWORD:-memberroll} psql -h localhost -p "${POSTGRES_PORT:-5433}" -U memberroll -d memberroll -tAc "INSERT INTO committee_appointment (person_id, office, started_date, recorded_by) VALUES ($CD,'PRESIDENT',current_date,'psql-test')" 2>&1 | grep -q 'duplicate key' && echo yes || echo no)"
else
  echo "note: psql not found — skipping CR13-13 (singular-office index check)"
fi

# row 14: the CR-007 contacts seam — current secretary (B) primary email
CT=$(curl -s "$API/admin/committee/contacts" -H "Authorization: Bearer $ADMIN")
check "CR13-14 secretary email = B"    "sec.$$@example.com" "$(echo "$CT" | jsq "j['secretary']['email']")"
check "CR13-14b members carry offices" "true" "$(echo "$CT" | jsq "str(any(m['office']=='SECRETARY' for m in j['members'])).lower()")"

# --- CR-014: SMTP settings page ---------------------------------------------
# Mail.resolve() precedence PAGE → ENV → NONE. The dev stack's env points at
# Mailpit, so ENV is the resting source; every mutating row cleans up (end
# state: no smtp_settings row) so the matrix stays re-runnable and every earlier
# mail row (CR4/CR5/CR12, all before this block) rode ENV. Rows 8–10 (dead-port
# precedence → DELETE restore → page-From end-to-end) run in that order and the
# block's unconditional cleanup restores ENV before the script ends.
MS=$API/admin/mail-settings
# true if the count of messages to $1 stays == $2 over ~3s (a negative-delivery
# assertion: a dead-port PAGE send that must produce nothing)
mailpit_stays() { for _ in 1 2 3 4 5 6; do
    n=$(curl -s "$MAILPIT/api/v1/search?query=to:%22$1%22" | jsq "j['messages_count']")
    [ "$n" != "$2" ] && { echo "no"; return; }; sleep 0.5; done; echo "yes"; }
# "yes" once the count of messages to $1 reaches >= $2 (mailpit_count returns the
# first non-zero it sees, so it can't assert a 1→2 increase)
mailpit_reaches() { local n; for _ in $(seq 1 12); do
    n=$(curl -s "$MAILPIT/api/v1/search?query=to:%22$1%22" | jsq "j['messages_count']")
    [ "${n:-0}" -ge "$2" ] 2>/dev/null && { echo "yes"; return; }; sleep 0.5; done; echo "no ($n)"; }

# row 1: role gate — guest/user 403, noaud 401, across the verbs
check "CR14-01 GET guest 403"          "403" "$(code $MS)"
check "CR14-01b GET user 403"          "403" "$(code $MS -H "Authorization: Bearer $USER")"
check "CR14-01c GET noaud 401"         "401" "$(code $MS -H "Authorization: Bearer $NOAUD")"
check "CR14-01d PUT user 403"          "403" "$(code -X PUT $MS -H "Authorization: Bearer $USER" -H 'Content-Type: application/json' -d '{}')"
check "CR14-01e DELETE user 403"       "403" "$(code -X DELETE $MS -H "Authorization: Bearer $USER")"
check "CR14-01f POST test user 403"    "403" "$(code -X POST $MS/test -H "Authorization: Bearer $USER" -H 'Content-Type: application/json' -d '{}')"
check "CR14-01g POST test noaud 401"   "401" "$(code -X POST $MS/test -H "Authorization: Bearer $NOAUD" -H 'Content-Type: application/json' -d '{}')"

# row 2: admin GET with no row → source=ENV, host mirrors env, no password
G0=$(curl -s $MS -H "Authorization: Bearer $ADMIN")
check "CR14-02 source ENV (no row)"    "ENV" "$(echo "$G0" | jsq "j['source']")"
check "CR14-02b host mirrors env"      "$RELAY_HOST" "$(echo "$G0" | jsq "j['host']")"
check "CR14-02c passwordSet false"     "False" "$(echo "$G0" | jsq "j['passwordSet']")"
check "CR14-02d no password key"       "False" "$(echo "$G0" | jsq "str('password' in j)")"

# row 3: PUT page settings (Mailpit host/port, NONE, username+password)
check "CR14-03 PUT settings 200"       "200" "$(JPUT $MS "{\"host\":\"$RELAY_HOST\",\"port\":$RELAY_PORT,\"security\":\"NONE\",\"from\":\"page.$$@memberroll.dev\",\"username\":\"pageuser\",\"password\":\"s3cret-$$\"}")"
G1=$(curl -s $MS -H "Authorization: Bearer $ADMIN")
check "CR14-03b source PAGE"           "PAGE" "$(echo "$G1" | jsq "j['source']")"
check "CR14-03c from echoed"           "page.$$@memberroll.dev" "$(echo "$G1" | jsq "j['from']")"
check "CR14-03d username echoed"       "pageuser" "$(echo "$G1" | jsq "j['username']")"
check "CR14-03e passwordSet true"      "True" "$(echo "$G1" | jsq "j['passwordSet']")"
check "CR14-03f no password key"       "False" "$(echo "$G1" | jsq "str('password' in j)")"

# row 4: five invalid PUTs each 400; GET unchanged (still the row-3 PAGE settings)
check "CR14-04 missing host 400"       "400" "$(JPUT $MS "{\"port\":587,\"security\":\"STARTTLS\",\"from\":\"a@b.co\"}")"
check "CR14-04b port 0 → 400"          "400" "$(JPUT $MS "{\"host\":\"h\",\"port\":0,\"security\":\"NONE\",\"from\":\"a@b.co\"}")"
check "CR14-04c port 70000 → 400"      "400" "$(JPUT $MS "{\"host\":\"h\",\"port\":70000,\"security\":\"NONE\",\"from\":\"a@b.co\"}")"
check "CR14-04d security BOGUS → 400"  "400" "$(JPUT $MS "{\"host\":\"h\",\"port\":587,\"security\":\"BOGUS\",\"from\":\"a@b.co\"}")"
check "CR14-04e unparseable from → 400" "400" "$(JPUT $MS "{\"host\":\"h\",\"port\":587,\"security\":\"NONE\",\"from\":\"not-an-email\"}")"
check "CR14-04f GET still PAGE"        "PAGE" "$(curl -s $MS -H "Authorization: Bearer $ADMIN" | jsq "j['source']")"
check "CR14-04g GET still Mailpit host" "$RELAY_HOST" "$(curl -s $MS -H "Authorization: Bearer $ADMIN" | jsq "j['host']")"

# row 5: password kept when absent; cleared on empty string
check "CR14-05 re-PUT no password 200" "200" "$(JPUT $MS "{\"host\":\"$RELAY_HOST\",\"port\":$RELAY_PORT,\"security\":\"NONE\",\"from\":\"page.$$@memberroll.dev\",\"username\":\"pageuser\"}")"
check "CR14-05b password kept"         "True" "$(curl -s $MS -H "Authorization: Bearer $ADMIN" | jsq "j['passwordSet']")"
check "CR14-05c PUT password:'' 200"   "200" "$(JPUT $MS "{\"host\":\"$RELAY_HOST\",\"port\":$RELAY_PORT,\"security\":\"NONE\",\"from\":\"page.$$@memberroll.dev\",\"username\":\"pageuser\",\"password\":\"\"}")"
check "CR14-05d password cleared"      "False" "$(curl -s $MS -H "Authorization: Bearer $ADMIN" | jsq "j['passwordSet']")"

# rows 6/7: synchronous test sends (need Mailpit; the saved row-5 PAGE row stands)
if curl -s -m 2 -o /dev/null "$MAILPIT/api/v1/messages"; then
  T6TO="cr14test.$$@example.com"
  T6=$(curl -s -X POST $MS/test -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d "{\"host\":\"$RELAY_HOST\",\"port\":$RELAY_PORT,\"security\":\"NONE\",\"from\":\"cr14from.$$@memberroll.dev\",\"to\":\"$T6TO\"}")
  check "CR14-06 test ok true"         "True" "$(echo "$T6" | jsq "j['ok']")"
  check "CR14-06b test delivered"      "1" "$(mailpit_count "$T6TO")"
  T6ID=$(curl -s "$MAILPIT/api/v1/search?query=to:%22$T6TO%22" | jsq "j['messages'][0]['ID']")
  check "CR14-06c candidate From used" "true" "$(curl -s "$MAILPIT/api/v1/message/$T6ID" | jsq "str('cr14from.$$' in j['From']['Address']).lower()")"
  check "CR14-06d saved row untouched" "PAGE" "$(curl -s $MS -H "Authorization: Bearer $ADMIN" | jsq "j['source']")"

  # row 7: dead port → ok:false, error names the connection failure, nothing sent
  T7=$(curl -s -X POST $MS/test -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d "{\"host\":\"localhost\",\"port\":59999,\"security\":\"NONE\",\"from\":\"a@b.co\",\"to\":\"cr14dead.$$@example.com\"}")
  check "CR14-07 test ok false"        "False" "$(echo "$T7" | jsq "j['ok']")"
  check "CR14-07b error names refusal" "true" "$(echo "$T7" | jsq "str('Connection refused' in j.get('error','')).lower()")"
  check "CR14-07c nothing delivered"   "0" "$(curl -s "$MAILPIT/api/v1/search?query=to:%22cr14dead.$$@example.com%22" | jsq "j['messages_count']")"

  # row 7b (hazard 1): a password-bearing send that fails must NOT echo the
  # secret. STARTTLS against Mailpit (which offers none) forces the PAGE
  # .required=true failure — also proving hazard 2's converse (required set on PAGE).
  T7PW="leaky-$$-secret"
  T7B=$(curl -s -X POST $MS/test -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d "{\"host\":\"$RELAY_HOST\",\"port\":$RELAY_PORT,\"security\":\"STARTTLS\",\"from\":\"a@b.co\",\"username\":\"u\",\"password\":\"$T7PW\",\"to\":\"cr14leak.$$@example.com\"}")
  check "CR14-07d starttls-required fails" "False" "$(echo "$T7B" | jsq "j['ok']")"
  check "CR14-07e error names STARTTLS" "true" "$(echo "$T7B" | jsq "str('STARTTLS' in j.get('error','')).lower()")"
  check "CR14-07f password not leaked"  "False" "$(echo "$T7B" | jsq "str('$T7PW' in j.get('error',''))")"

  # rows 8–10: precedence proven through the real CR-012 receipt send path
  # (needs psql for the payment fixture)
  if [ "$PSQL_OK" = 1 ]; then
    RTO="cr14rcpt.$$@example.com"
    JPOST $API/admin/people "{\"givenName\":\"Mel\",\"familyName\":\"Mail$$\",\"emails\":[{\"email\":\"$RTO\",\"isPrimary\":true}]}" >/dev/null; MSP=$(body | jsq "j['id']")
    JPOST $API/admin/households "{\"householdName\":\"Mail$$ HH\",\"primaryContactPersonId\":$MSP}" >/dev/null; MSHH=$(body | jsq "j['id']")
    JPOST $API/admin/memberships "{\"householdId\":$MSHH,\"membershipPeriodId\":$P2526,\"membershipTypeId\":$T_SINGLE}" >/dev/null; MSMEM=$(body | jsq "j['id']")
    JPOST $API/admin/payments "{\"receivedDate\":\"2026-07-19\",\"amountCents\":4500,\"method\":\"BANK_TRANSFER\",\"bankReference\":\"CR14-$$\",\"payerPersonId\":$MSP,\"allocations\":[{\"type\":\"MEMBERSHIP\",\"membershipId\":$MSMEM,\"amountCents\":4500}]}" >/dev/null; MSPAY=$(body | jsq "j['id']")

    # row 8: PAGE at a dead port beats ENV — the receipt POST is accepted
    # (async best-effort) but nothing arrives
    JPUT $MS "{\"host\":\"localhost\",\"port\":59999,\"security\":\"NONE\",\"from\":\"dead.$$@memberroll.dev\"}" >/dev/null
    check "CR14-08 receipt POST accepted" "202" "$(code -X POST $API/admin/payments/$MSPAY/receipt -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{}')"
    check "CR14-08b PAGE beat ENV (no mail)" "yes" "$(mailpit_stays "$RTO" "0")"

    # row 9: DELETE restores ENV — the same receipt now arrives
    check "CR14-09 DELETE 200"          "200" "$(code -X DELETE $MS -H "Authorization: Bearer $ADMIN")"
    check "CR14-09b source ENV again"   "ENV" "$(curl -s $MS -H "Authorization: Bearer $ADMIN" | jsq "j['source']")"
    code -X POST $API/admin/payments/$MSPAY/receipt -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{}' >/dev/null
    check "CR14-09c ENV fallback delivered" "1" "$(mailpit_count "$RTO")"

    # row 10: a PAGE row with a distinctive From — the real send reads the row
    check "CR14-10 PUT page From 200"   "200" "$(JPUT $MS "{\"host\":\"$RELAY_HOST\",\"port\":$RELAY_PORT,\"security\":\"NONE\",\"from\":\"cr14page.$$@memberroll.dev\"}")"
    code -X POST $API/admin/payments/$MSPAY/receipt -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{}' >/dev/null
    check "CR14-10b page-From delivered" "yes" "$(mailpit_reaches "$RTO" "2")"
    M10=$(curl -s "$MAILPIT/api/v1/search?query=to:%22$RTO%22" | jsq "j['messages'][0]['ID']")
    check "CR14-10c real send used page From" "true" "$(curl -s "$MAILPIT/api/v1/message/$M10" | jsq "str('cr14page.$$' in j['From']['Address']).lower()")"
  else
    echo "SKIP CR14-08..10 (precedence via receipt email — needs psql fixtures)"
  fi
else
  echo "SKIP CR14-06..10 mail rows (Mailpit not reachable at $MAILPIT)"
fi

# row 11: upsert not append — the run's many PUTs left exactly one row
if [ "$PSQL_OK" = 1 ]; then
  check "CR14-11 single settings row"  "1" "$(psqlq "SELECT count(*) FROM app_setting WHERE key='smtp_settings'")"
fi
# unconditional cleanup: no smtp_settings row may survive, so a re-run and every
# ENV-based mail row starts clean again
code -X DELETE $MS -H "Authorization: Bearer $ADMIN" >/dev/null
check "CR14-12 settings cleared at end" "ENV" "$(curl -s $MS -H "Authorization: Bearer $ADMIN" | jsq "j['source']")"

# --- CR-015: reconciliation export ------------------------------------------
# Self-cleaning against an insert-only ledger: fixtures live in a 2099 date
# window unique to this CR, every export/journal assertion passes
# unreconciledOnly=true, and the block ends by marking the whole window
# reconciled + dropping the xero_accounts row — so the next run's window starts
# with zero unreconciled residue and no saved mapping (the 409 test stays valid).
REX=$API/admin/payments/export/reconciliation
RXJ=$API/admin/payments/export/xero-journal.csv
RMAP=$API/admin/payments/xero-account-mapping
RRC=$API/admin/payments/reconcile

# row 1: role gates (guest/user 403, noaud 401, admin 200) across the surfaces
check "CR15-01 csv guest 403"          "403" "$(code $REX.csv)"
check "CR15-01b csv user 403"          "403" "$(code $REX.csv -H "Authorization: Bearer $USER")"
check "CR15-01c csv noaud 401"         "401" "$(code $REX.csv -H "Authorization: Bearer $NOAUD")"
check "CR15-01d csv admin 200"         "200" "$(code $REX.csv -H "Authorization: Bearer $ADMIN")"
check "CR15-01e json guest 403"        "403" "$(code $REX)"
check "CR15-01f json admin 200"        "200" "$(code $REX -H "Authorization: Bearer $ADMIN")"
check "CR15-01g reconcile guest 403"   "403" "$(code -X POST $RRC)"
check "CR15-01h reconcile user 403"    "403" "$(code -X POST $RRC -H "Authorization: Bearer $USER" -H 'Content-Type: application/json' -d '{}')"
check "CR15-01i reconcile noaud 401"   "401" "$(code -X POST $RRC -H "Authorization: Bearer $NOAUD" -H 'Content-Type: application/json' -d '{}')"
check "CR15-01j reconcile admin 200"   "200" "$(JPOST $RRC "{\"maxPaymentId\":0}")"
check "CR15-01k reconcile marks 0"     "0" "$(body | jsq "j['marked']")"
check "CR15-01l mapping guest 403"     "403" "$(code $RMAP)"
check "CR15-01m mapping user 403"      "403" "$(code $RMAP -H "Authorization: Bearer $USER")"
check "CR15-01n mapping PUT user 403"  "403" "$(code -X PUT $RMAP -H "Authorization: Bearer $USER" -H 'Content-Type: application/json' -d '{}')"
check "CR15-01o mapping noaud 401"     "401" "$(code $RMAP -H "Authorization: Bearer $NOAUD")"

# row 2: validation — reconcile needs maxPaymentId; export rejects bad dates
check "CR15-02 no maxPaymentId 400"    "400" "$(JPOST $RRC "{\"from\":\"2099-03-01\"}")"
check "CR15-02b bad from date 400"     "400" "$(code "$REX?from=not-a-date" -H "Authorization: Bearer $ADMIN")"
check "CR15-02c bad method 400"        "400" "$(code "$REX?method=BOGUS" -H "Authorization: Bearer $ADMIN")"

if [ "$PSQL_OK" = 1 ]; then
  T_SINGLE=$(psqlq "SELECT membership_type_id FROM membership_type WHERE name='SINGLE'")
  P2526=$(curl -s $API/admin/periods -H "Authorization: Bearer $ADMIN" | jsq "next(p['id'] for p in j['periods'] if p['name']=='2025-2026')")
  # a household + membership to hang MEMBERSHIP allocations on
  JPOST $API/admin/people "{\"givenName\":\"Reece\",\"familyName\":\"Rec$$\"}" >/dev/null; PREC=$(body | jsq "j['id']")
  JPOST $API/admin/households "{\"householdName\":\"Rec$$ HH\",\"primaryContactPersonId\":$PREC}" >/dev/null; HREC=$(body | jsq "j['id']")
  JPOST $API/admin/memberships "{\"householdId\":$HREC,\"membershipPeriodId\":$P2526,\"membershipTypeId\":$T_SINGLE}" >/dev/null; MREC=$(body | jsq "j['id']")

  # positive STRIPE cannot be POSTed (webhook-only), so seed the whole fixture
  # set via psql — full control over method / external id / date. Two March
  # dates + one April refund (the sign-flip window).
  # psql prints the "INSERT 0 1" command tag after the RETURNING value, so keep
  # only the numeric id line — a malformed id would corrupt the allocation inserts.
  pay() { psqlq "INSERT INTO payment (received_date, amount_cents, payment_method, bank_reference, external_transaction_id, recorded_by) VALUES ('$1',$2,'$3',$4,$5,'cr15') RETURNING payment_id" | grep -oE '^[0-9]+' | head -1; }
  PA=$(pay '2099-03-05' 4500 'BANK_TRANSFER' "'CR15BANK-$$'" 'NULL')
  psqlq "INSERT INTO payment_allocation (payment_id, allocation_type, membership_id, amount_cents) VALUES ($PA,'MEMBERSHIP',$MREC,4500)" >/dev/null
  PB=$(pay '2099-03-20' 6000 'STRIPE' 'NULL' "'cr15txn-$$'")
  psqlq "INSERT INTO payment_allocation (payment_id, allocation_type, membership_id, amount_cents) VALUES ($PB,'MEMBERSHIP',$MREC,4500),($PB,'JOURNAL',NULL,1000),($PB,'DONATION',NULL,500)" >/dev/null
  PC=$(pay '2099-03-05' 500 'CASH' 'NULL' 'NULL')
  psqlq "INSERT INTO payment_allocation (payment_id, allocation_type, amount_cents) VALUES ($PC,'OTHER',500)" >/dev/null
  PD=$(pay '2099-03-20' 3000 'STRIPE' 'NULL' "'cr15pd-$$'")
  psqlq "INSERT INTO payment_allocation (payment_id, allocation_type, membership_id, amount_cents) VALUES ($PD,'MEMBERSHIP',$MREC,3000)" >/dev/null
  PE=$(pay '2099-03-20' -3000 'STRIPE' 'NULL' 'NULL')
  psqlq "INSERT INTO payment_allocation (payment_id, allocation_type, membership_id, amount_cents) VALUES ($PE,'MEMBERSHIP',$MREC,-3000)" >/dev/null
  PF=$(pay '2099-04-10' -2000 'STRIPE' 'NULL' 'NULL')
  psqlq "INSERT INTO payment_allocation (payment_id, allocation_type, membership_id, amount_cents) VALUES ($PF,'MEMBERSHIP',$MREC,-2000)" >/dev/null
  # start clean: no saved mapping (the 409 test depends on it)
  psqlq "DELETE FROM app_setting WHERE key='xero_accounts'" >/dev/null

  MAR="from=2099-03-01&to=2099-03-31&unreconciledOnly=true"

  # row 3: JSON preview totals over the March window (5 payments, two dates)
  RJ=$(curl -s "$REX?$MAR" -H "Authorization: Bearer $ADMIN")
  check "CR15-03 preview count 5"       "5" "$(echo "$RJ" | jsq "j['count']")"
  check "CR15-03b membership net 9000"  "9000" "$(echo "$RJ" | jsq "j['totals']['byType']['MEMBERSHIP']")"
  check "CR15-03c journal 1000"         "1000" "$(echo "$RJ" | jsq "j['totals']['byType']['JOURNAL']")"
  check "CR15-03d donation 500"         "500" "$(echo "$RJ" | jsq "j['totals']['byType']['DONATION']")"
  check "CR15-03e other 500"            "500" "$(echo "$RJ" | jsq "j['totals']['byType']['OTHER']")"
  check "CR15-03f gross 11000"          "11000" "$(echo "$RJ" | jsq "j['totals']['gross']")"
  check "CR15-03g byMethod STRIPE 6000" "6000" "$(echo "$RJ" | jsq "j['totals']['byMethod']['STRIPE']")"
  check "CR15-03h byMethod BANK 4500"   "4500" "$(echo "$RJ" | jsq "j['totals']['byMethod']['BANK_TRANSFER']")"
  check "CR15-03i byMethod CASH 500"    "500" "$(echo "$RJ" | jsq "j['totals']['byMethod']['CASH']")"
  RJMAX=$(echo "$RJ" | jsq "j['maxPaymentId']")
  check "CR15-03j maxPaymentId is PE"   "$PE" "$RJMAX"

  # row 4: CSV shape — header exact, content type, mixed payment split, per-row sum
  check "CR15-04 csv content type"      "text/csv" "$(curl -s -o /dev/null -w '%{content_type}' "$REX.csv?$MAR" -H "Authorization: Bearer $ADMIN")"
  RCSV=$(curl -s "$REX.csv?$MAR" -H "Authorization: Bearer $ADMIN")
  check "CR15-04b header row exact"     "Payment id,Received date,Method,Payer,Household,Gross,Membership,Journal,Donation,Other,Period,Bank reference,Stripe txn id,Status,Recorded by,Notes" "$(echo "$RCSV" | head -1 | tr -d '\r')"
  check "CR15-04c mixed row split"      "60.00 45.00 10.00 5.00 0.00" "$(echo "$RCSV" | python3 -c "import sys,csv; rows=list(csv.reader(sys.stdin)); r=[x for x in rows if x and x[0]=='$PB'][0]; print(r[5],r[6],r[7],r[8],r[9])" 2>/dev/null)"
  check "CR15-04d mixed period named"   "2025-2026" "$(echo "$RCSV" | python3 -c "import sys,csv; rows=list(csv.reader(sys.stdin)); r=[x for x in rows if x and x[0]=='$PB'][0]; print(r[10])" 2>/dev/null)"
  check "CR15-04e splits sum to gross"  "yes" "$(echo "$RCSV" | python3 -c "import sys,csv; rows=list(csv.reader(sys.stdin)); ok=all(abs(float(r[5])-sum(float(r[i]) for i in (6,7,8,9)))<0.001 for r in rows[1:] if r and r[0].isdigit()); print('yes' if ok else 'no')" 2>/dev/null)"
  check "CR15-04f refund row negative"  "-30.00" "$(echo "$RCSV" | python3 -c "import sys,csv; rows=list(csv.reader(sys.stdin)); r=[x for x in rows if x and x[0]=='$PE'][0]; print(r[5])" 2>/dev/null)"
  check "CR15-04g summary TOTAL present" "yes" "$(echo "$RCSV" | python3 -c "import sys,csv; rows=list(csv.reader(sys.stdin)); print('yes' if any(r and r[0]=='TOTAL (net)' and r[5]=='110.00' for r in rows) else 'no')" 2>/dev/null)"

  # row 5: filters compose — narrower date window, method, and both
  check "CR15-05 to=D1 count 2"         "2" "$(curl -s "$REX?from=2099-03-01&to=2099-03-06&unreconciledOnly=true" -H "Authorization: Bearer $ADMIN" | jsq "j['count']")"
  RS=$(curl -s "$REX?from=2099-03-01&to=2099-03-31&method=STRIPE&unreconciledOnly=true" -H "Authorization: Bearer $ADMIN")
  check "CR15-05b method=STRIPE count 3" "3" "$(echo "$RS" | jsq "j['count']")"
  check "CR15-05c STRIPE excludes bank"  "False" "$(echo "$RS" | jsq "str('BANK_TRANSFER' in j['totals']['byMethod'])")"
  check "CR15-05d combined count 3"      "3" "$(curl -s "$REX?from=2099-03-20&to=2099-03-31&method=STRIPE&unreconciledOnly=true" -H "Authorization: Bearer $ADMIN" | jsq "j['count']")"

  # row 6: Xero journal — 409 until mapped, then a balanced importable journal
  check "CR15-06 journal 409 no mapping" "409" "$(code "$RXJ?$MAR" -H "Authorization: Bearer $ADMIN")"
  check "CR15-06b mapping missing code 400" "400" "$(JPUT $RMAP "{\"membershipCode\":\"4000\",\"journalCode\":\"4010\",\"donationCode\":\"4020\",\"otherCode\":\"4090\"}")"
  check "CR15-06c PUT mapping 200"       "200" "$(JPUT $RMAP "{\"membershipCode\":\"4000\",\"journalCode\":\"4010\",\"donationCode\":\"4020\",\"otherCode\":\"4090\",\"clearingCode\":\"1200\",\"taxRate\":\"BAS Excluded\"}")"
  check "CR15-06d GET echoes clearing"   "1200" "$(curl -s $RMAP -H "Authorization: Bearer $ADMIN" | jsq "j['clearingCode']")"
  check "CR15-06e GET configured true"   "True" "$(curl -s $RMAP -H "Authorization: Bearer $ADMIN" | jsq "j['configured']")"
  JCSV=$(curl -s "$RXJ?$MAR" -H "Authorization: Bearer $ADMIN")
  check "CR15-06f journal balances"      "0.00" "$(echo "$JCSV" | python3 -c "import sys,csv; rows=list(csv.reader(sys.stdin)); print('%.2f' % sum(float(r[4]) for r in rows[1:] if len(r)>=5))" 2>/dev/null)"
  check "CR15-06g journal 4 lines"       "4" "$(echo "$JCSV" | python3 -c "import sys; print(sum(1 for i,l in enumerate(sys.stdin) if i>0 and l.strip()))" 2>/dev/null)"
  check "CR15-06h clearing debit gross"  "60.00" "$(echo "$JCSV" | python3 -c "import sys,csv; rows=list(csv.reader(sys.stdin)); print(next(r[4] for r in rows[1:] if r[2]=='1200'))" 2>/dev/null)"
  check "CR15-06i membership credit"     "-45.00" "$(echo "$JCSV" | python3 -c "import sys,csv; rows=list(csv.reader(sys.stdin)); print(next(r[4] for r in rows[1:] if r[2]=='4000'))" 2>/dev/null)"
  check "CR15-06j tax rate every line"   "yes" "$(echo "$JCSV" | python3 -c "import sys,csv; rows=list(csv.reader(sys.stdin)); print('yes' if all(r[3]=='BAS Excluded' for r in rows[1:] if len(r)>=5) else 'no')" 2>/dev/null)"
  check "CR15-06k one shared narration"  "1" "$(echo "$JCSV" | python3 -c "import sys,csv; rows=list(csv.reader(sys.stdin)); print(len(set(r[0] for r in rows[1:] if len(r)>=5)))" 2>/dev/null)"
  # STRIPE forced even when method=BANK_TRANSFER is passed (bank line 4500 absent)
  check "CR15-06l STRIPE forced"         "60.00" "$(curl -s "$RXJ?$MAR&method=BANK_TRANSFER" -H "Authorization: Bearer $ADMIN" | python3 -c "import sys,csv; rows=list(csv.reader(sys.stdin)); print(next(r[4] for r in rows[1:] if r[2]=='1200'))" 2>/dev/null)"
  # refund-dominated April window flips the membership line to the debit side
  JFLIP=$(curl -s "$RXJ?from=2099-04-01&to=2099-04-30&unreconciledOnly=true" -H "Authorization: Bearer $ADMIN")
  check "CR15-06m flip membership debit" "20.00" "$(echo "$JFLIP" | python3 -c "import sys,csv; rows=list(csv.reader(sys.stdin)); print(next(r[4] for r in rows[1:] if r[2]=='4000'))" 2>/dev/null)"
  check "CR15-06n flip clearing credit"  "-20.00" "$(echo "$JFLIP" | python3 -c "import sys,csv; rows=list(csv.reader(sys.stdin)); print(next(r[4] for r in rows[1:] if r[2]=='1200'))" 2>/dev/null)"
  check "CR15-06o flip balances"         "0.00" "$(echo "$JFLIP" | python3 -c "import sys,csv; rows=list(csv.reader(sys.stdin)); print('%.2f' % sum(float(r[4]) for r in rows[1:] if len(r)>=5))" 2>/dev/null)"

  # row 7: reconcile — the bounded, idempotent mark; a straggler survives
  PG=$(pay '2099-03-25' 100 'CASH' 'NULL' 'NULL')   # recorded AFTER the preview
  psqlq "INSERT INTO payment_allocation (payment_id, allocation_type, amount_cents) VALUES ($PG,'OTHER',100)" >/dev/null
  JPOST $RRC "{\"from\":\"2099-03-01\",\"to\":\"2099-03-31\",\"maxPaymentId\":$RJMAX}" >/dev/null
  check "CR15-07 mark returns 5"         "5" "$(body | jsq "j['marked']")"
  check "CR15-07b PA reconciled"         "RECONCILED" "$(psqlq "SELECT reconciliation_status FROM payment WHERE payment_id=$PA")"
  check "CR15-07c PE reconciled"         "RECONCILED" "$(psqlq "SELECT reconciliation_status FROM payment WHERE payment_id=$PE")"
  check "CR15-07d straggler PG unmarked" "UNRECONCILED" "$(psqlq "SELECT reconciliation_status FROM payment WHERE payment_id=$PG")"
  check "CR15-07e exactly 5 flipped"     "5" "$(psqlq "SELECT count(*) FROM payment WHERE payment_id IN ($PA,$PB,$PC,$PD,$PE) AND reconciliation_status='RECONCILED'")"
  JPOST $RRC "{\"from\":\"2099-03-01\",\"to\":\"2099-03-31\",\"maxPaymentId\":$RJMAX}" >/dev/null
  check "CR15-07f re-mark idempotent 0"  "0" "$(body | jsq "j['marked']")"
  check "CR15-07g unreconciled now PG"   "1" "$(curl -s "$REX?$MAR" -H "Authorization: Bearer $ADMIN" | jsq "j['count']")"

  # cleanup: mark the whole 2099 window reconciled (PF, PG) and drop the mapping,
  # so the next run starts with zero unreconciled residue and no saved mapping
  JPOST $RRC "{\"from\":\"2099-03-01\",\"maxPaymentId\":9999999999}" >/dev/null
  psqlq "DELETE FROM app_setting WHERE key='xero_accounts'" >/dev/null
  check "CR15-08 window clean at end"    "0" "$(psqlq "SELECT count(*) FROM payment WHERE received_date >= '2099-01-01' AND reconciliation_status='UNRECONCILED'")"
  check "CR15-08b mapping dropped"       "409" "$(code "$RXJ?$MAR" -H "Authorization: Bearer $ADMIN")"
else
  echo "SKIP CR15-03..08 (reconciliation fixtures + marks — needs psql)"
fi

# --- membership card (CR-017) -----------------------------------------------
# One renderer, several surfaces: the on-screen copy, download, print pop-up and
# emailed attachment are the same PNG, composed from CURRENT register state and
# only for an ACTIVE membership (a card asserts financial standing). The member
# endpoints authorize on the person.keycloak_subject link (CR-006), never a
# role. Reuses the CR-006 linked account: L = SSJP (testuser's linked person),
# A = SSMJ made ACTIVE by a full payment; a PARTNER R shares A's household;
# P = SSME (a PENDING membership in another household). The PNG itself gets a
# magic-bytes/size check — the JSON companion is the assertable surface.
#
# The guest/noaud gate rows run always; the data rows ride the CR-006 fixtures
# (guard on SSJP set). Mail rows key off the server's SMTP config (mailEnabled),
# like CR-012; the 503 row is the flip side.
check "CR17-01 me card guest 401"      "401" "$(code $API/me/membership/1/card)"
check "CR17-01b me info guest 401"     "401" "$(code $API/me/membership/1/card/info)"
check "CR17-01c me email guest 401"    "401" "$(code -X POST $API/me/membership/1/card/email)"
check "CR17-01d me card noaud 401"     "401" "$(code $API/me/membership/1/card -H "Authorization: Bearer $NOAUD")"

if [ "$PSQL_OK" = 1 ] && [ -n "${SSJP:-}" ]; then
  CARDL=$SSJP; CARDA=$SSMJ; CARDP=$SSME
  CARDUSER=$(tok testuser test-cli)   # fresh token — the CR-006 USER4 may have aged out
  PEND_END=$(psqlq "SELECT end_date FROM membership_period WHERE membership_period_id=$P2526")
  # make A ACTIVE (SINGLE fee 4500), add a PARTNER R to A's household (household
  # composition, not the membership snapshot — Cards.compose reads the former)
  JPOST $API/admin/payments "{\"receivedDate\":\"2026-07-20\",\"amountCents\":4500,\"method\":\"CASH\",\"allocations\":[{\"type\":\"MEMBERSHIP\",\"membershipId\":$CARDA,\"amountCents\":4500}]}" >/dev/null
  JPOST $API/admin/people "{\"givenName\":\"Rae\",\"familyName\":\"$SS\",\"emails\":[{\"email\":\"rae.$$@ss.test\",\"isPrimary\":true}]}" >/dev/null; CARDR=$(body | jsq "j['id']")
  JPOST $API/admin/households/$SSHJ/people "{\"personId\":$CARDR,\"relationshipType\":\"PARTNER\"}" >/dev/null
  # a no-email MEMBER in an ACTIVE membership — for the admin "no default" 400 row
  JPOST $API/admin/people "{\"givenName\":\"Quill\",\"familyName\":\"$SS\"}" >/dev/null; CARDQ=$(body | jsq "j['id']")
  JPOST $API/admin/households "{\"householdName\":\"$SS Q\",\"primaryContactPersonId\":$CARDQ}" >/dev/null; SSHQ=$(body | jsq "j['id']")
  JPOST $API/admin/memberships "{\"householdId\":$SSHQ,\"membershipPeriodId\":$P2526,\"membershipTypeId\":$T_SINGLE}" >/dev/null; SSMQ=$(body | jsq "j['id']")
  JPOST $API/admin/payments "{\"receivedDate\":\"2026-07-20\",\"amountCents\":4500,\"method\":\"CASH\",\"allocations\":[{\"type\":\"MEMBERSHIP\",\"membershipId\":$SSMQ,\"amountCents\":4500}]}" >/dev/null

  # row 2: the composed fields for the linked member
  CINFO=$(curl -s $API/me/membership/$CARDA/card/info -H "Authorization: Bearer $CARDUSER")
  check "CR17-02 me info 200"           "200" "$(code $API/me/membership/$CARDA/card/info -H "Authorization: Bearer $CARDUSER")"
  check "CR17-02b name preferred/given" "Jude $SS" "$(echo "$CINFO" | jsq "j['name']")"
  check "CR17-02c type SINGLE"          "SINGLE" "$(echo "$CINFO" | jsq "j['typeName']")"
  check "CR17-02d period 2025-2026"     "2025-2026" "$(echo "$CINFO" | jsq "j['periodName']")"
  check "CR17-02e validTo = end_date"   "$PEND_END" "$(echo "$CINFO" | jsq "j['validTo']")"
  check "CR17-02f memberNo = person id" "$CARDL" "$(echo "$CINFO" | jsq "str(j['memberNo'])")"
  check "CR17-02g mailEnabled true"     "true" "$(echo "$CINFO" | jsq "str(j['mailEnabled']).lower()")"
  check "CR17-02h emailTo primary"      "testuser@example.invalid" "$(echo "$CINFO" | jsq "j['emailTo']")"

  # row 3: the PNG itself — image/png, magic bytes, > 10 kB
  check "CR17-03 me card 200"           "200" "$(code $API/me/membership/$CARDA/card -H "Authorization: Bearer $CARDUSER")"
  check "CR17-03b content-type png"     "image/png" "$(curl -s -o /dev/null -w '%{content_type}' $API/me/membership/$CARDA/card -H "Authorization: Bearer $CARDUSER")"
  check "CR17-03c PNG magic bytes"      "89504e47" "$(curl -s $API/me/membership/$CARDA/card -H "Authorization: Bearer $CARDUSER" | head -c 4 | xxd -p)"
  check "CR17-03d length > 10kB"        "yes" "$(curl -s $API/me/membership/$CARDA/card -H "Authorization: Bearer $CARDUSER" | wc -c | awk '{print ($1>10240)?"yes":"no"}')"

  # row 4: another household's membership + a nonexistent id are the same 404
  check "CR17-04 me foreign card 404"   "404" "$(code $API/me/membership/$CARDP/card -H "Authorization: Bearer $CARDUSER")"
  check "CR17-04b me foreign info 404"  "404" "$(code $API/me/membership/$CARDP/card/info -H "Authorization: Bearer $CARDUSER")"
  check "CR17-04c me foreign email 404" "404" "$(code -X POST $API/me/membership/$CARDP/card/email -H "Authorization: Bearer $CARDUSER")"
  check "CR17-04d me nonexistent 404"   "404" "$(code $API/me/membership/999999/card -H "Authorization: Bearer $CARDUSER")"

  # row 5: demote A below its fee → no card without financial standing; restore
  JPOST $API/admin/payments "{\"receivedDate\":\"2026-07-20\",\"amountCents\":-4500,\"method\":\"CASH\",\"notes\":\"cr17 demote\",\"allocations\":[{\"type\":\"MEMBERSHIP\",\"membershipId\":$CARDA,\"amountCents\":-4500}]}" >/dev/null
  check "CR17-05 demoted no card 404"   "404" "$(code $API/me/membership/$CARDA/card/info -H "Authorization: Bearer $CARDUSER")"
  JPOST $API/admin/payments "{\"receivedDate\":\"2026-07-20\",\"amountCents\":4500,\"method\":\"CASH\",\"notes\":\"cr17 restore\",\"allocations\":[{\"type\":\"MEMBERSHIP\",\"membershipId\":$CARDA,\"amountCents\":4500}]}" >/dev/null
  check "CR17-05b restored card 200"    "200" "$(code $API/me/membership/$CARDA/card/info -H "Authorization: Bearer $CARDUSER")"

  # row 7: an authenticated but UNLINKED account learns nothing (indistinct 404)
  check "CR17-07 unlinked info 404"     "404" "$(code $API/me/membership/$CARDA/card/info -H "Authorization: Bearer $VIEWER")"

  # row 8: admin endpoints role gate (guest/user 403, noaud 401)
  check "CR17-08 admin card guest 403"  "403" "$(code $API/admin/memberships/$CARDA/card/$CARDL)"
  check "CR17-08b admin card user 403"  "403" "$(code $API/admin/memberships/$CARDA/card/$CARDL -H "Authorization: Bearer $USER")"
  check "CR17-08c admin card noaud 401" "401" "$(code $API/admin/memberships/$CARDA/card/$CARDL -H "Authorization: Bearer $NOAUD")"

  # row 9: admin GET info + PNG for the same person — same fields, PNG magic
  AINFO=$(curl -s $API/admin/memberships/$CARDA/card/$CARDL/info -H "Authorization: Bearer $ADMIN")
  check "CR17-09 admin info 200"        "200" "$(code $API/admin/memberships/$CARDA/card/$CARDL/info -H "Authorization: Bearer $ADMIN")"
  check "CR17-09b admin name matches"   "Jude $SS" "$(echo "$AINFO" | jsq "j['name']")"
  check "CR17-09c admin defaultTo"      "testuser@example.invalid" "$(echo "$AINFO" | jsq "j['defaultTo']")"
  check "CR17-09d admin PNG magic"      "89504e47" "$(curl -s $API/admin/memberships/$CARDA/card/$CARDL -H "Authorization: Bearer $ADMIN" | head -c 4 | xxd -p)"

  # row 10: a PARTNER-relationship person of A's household gets no card (MEMBER-only)
  check "CR17-10 admin PARTNER 404"     "404" "$(code $API/admin/memberships/$CARDA/card/$CARDR -H "Authorization: Bearer $ADMIN")"
  check "CR17-10b admin PARTNER info 404" "404" "$(code $API/admin/memberships/$CARDA/card/$CARDR/info -H "Authorization: Bearer $ADMIN")"

  # row 11: PENDING membership / unknown membership / unknown person → 404 each
  check "CR17-11 admin PENDING 404"     "404" "$(code $API/admin/memberships/$CARDP/card/$SSE1 -H "Authorization: Bearer $ADMIN")"
  check "CR17-11b admin unknown mem 404" "404" "$(code $API/admin/memberships/999999/card/$CARDL -H "Authorization: Bearer $ADMIN")"
  check "CR17-11c admin unknown person 404" "404" "$(code $API/admin/memberships/$CARDA/card/999999 -H "Authorization: Bearer $ADMIN")"

  # rows 6/12/13: the mail path depends on the server's SMTP config
  MAIL_ON=$(curl -s $API/admin/email/templates -H "Authorization: Bearer $ADMIN" | jsq "j['mailEnabled']")
  if [ "$MAIL_ON" = "True" ]; then
    # row 12 (400 half): admin, no default and no `to` → 400 (Quill has no email)
    check "CR17-12c admin no address 400" "400" "$(code -X POST $API/admin/memberships/$SSMQ/card/$CARDQ/email -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{}')"
    # row 12 (202 halves): admin default + explicit override both 202
    check "CR17-12 admin default 202"    "202" "$(code -X POST $API/admin/memberships/$CARDA/card/$CARDL/email -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{}')"
    check "CR17-12b default sentTo"      "testuser@example.invalid" "$(body | jsq "j['sentTo']")"
    check "CR17-12d admin override 202"  "202" "$(code -X POST $API/admin/memberships/$CARDA/card/$CARDL/email -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d "{\"to\":\"card.over.$$@example.com\"}")"
    check "CR17-12e override sentTo"     "card.over.$$@example.com" "$(body | jsq "j['sentTo']")"
    # row 6: member emails their own card → 202 to their primary; attachment lands
    check "CR17-06 me email 202"         "202" "$(code -X POST $API/me/membership/$CARDA/card/email -H "Authorization: Bearer $CARDUSER")"
    check "CR17-06b me sentTo primary"   "testuser@example.invalid" "$(body | jsq "j['sentTo']")"
    if curl -s -m 2 -o /dev/null "$MAILPIT/api/v1/messages"; then
      # newest message to the member — assert the PNG attachment + society body
      CMID=""; for _ in $(seq 1 8); do CMID=$(curl -s "$MAILPIT/api/v1/search?query=to:%22testuser@example.invalid%22" | jsq "j['messages'][0]['ID']"); [ -n "$CMID" ] && break; sleep 0.5; done
      CMSG=$(curl -s "$MAILPIT/api/v1/message/$CMID")
      check "CR17-06c one attachment"    "1" "$(echo "$CMSG" | jsq "len(j['Attachments'])")"
      check "CR17-06d attachment name"   "membership-card-2025-2026.png" "$(echo "$CMSG" | jsq "j['Attachments'][0]['FileName']")"
      check "CR17-06e attachment png"    "image/png" "$(echo "$CMSG" | jsq "j['Attachments'][0]['ContentType']")"
      check "CR17-06f body has society"  "true" "$(echo "$CMSG" | jsq "str('MemberRoll Dev Society' in j['Text']).lower()")"
    else
      echo "SKIP CR17-06c..f mail-attachment rows (Mailpit not reachable at $MAILPIT)"
    fi
  else
    # row 13: no SMTP → an explicit 503 (both surfaces), never a silent no-op
    check "CR17-13 me email unconfigured 503" "503" "$(code -X POST $API/me/membership/$CARDA/card/email -H "Authorization: Bearer $CARDUSER")"
    check "CR17-13b admin email unconfigured 503" "503" "$(code -X POST $API/admin/memberships/$CARDA/card/$CARDL/email -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{}')"
  fi
else
  echo "note: CR-017 data rows skipped (need psql + the CR-006 linked fixtures)"
fi

# --- life membership (CR-018) ------------------------------------------------
# LIFE is real reference data now (V8: the type + a $0 price in every period),
# and changeType's guard is refined: refused while NET allocated money != 0 (a
# fully-reversed payment is history, not money — the reverse-then-retype path
# the YDHS remediation needs) and always on CEASED. The rollover shape of a
# LIFE membership (carried ACTIVE, due 0) is already asserted by CR3-22c/d/e.
check "CR18-01 retype guest 403"       "403" "$(code -X PUT $API/admin/memberships/1 -H 'Content-Type: application/json' -d '{"membershipTypeId":1}')"
check "CR18-01b retype user 403"       "403" "$(code -X PUT $API/admin/memberships/1 -H "Authorization: Bearer $USER" -H 'Content-Type: application/json' -d '{"membershipTypeId":1}')"
check "CR18-01c retype noaud 401"      "401" "$(code -X PUT $API/admin/memberships/1 -H "Authorization: Bearer $NOAUD" -H 'Content-Type: application/json' -d '{"membershipTypeId":1}')"

# row 2: the migration's work is visible on the periods surface — every period
# prices LIFE at $0 (V8 covered the periods that existed; PeriodStore requires
# a price for every type at creation, so later periods carry it too)
check "CR18-02 LIFE \$0 in every period" "true" "$(curl -s $API/admin/periods -H "Authorization: Bearer $ADMIN" | jsq "str(all(any(pr['type']=='LIFE' and pr['amountCents']==0 for pr in p['prices']) for p in j['periods'])).lower()")"

L18="Life$$"
PJSON=$(curl -s $API/admin/periods -H "Authorization: Bearer $ADMIN")
P18=$(echo "$PJSON" | jsq "next(p['id'] for p in j['periods'] if p['name']=='2025-2026')")
T18_LIFE=$(echo "$PJSON" | jsq "next(pr['typeId'] for p in j['periods'] for pr in p['prices'] if pr['type']=='LIFE')")
T18_SINGLE=$(echo "$PJSON" | jsq "next(pr['typeId'] for p in j['periods'] for pr in p['prices'] if pr['type']=='SINGLE')")

# fixture: a MEMBER household with an unpaid SINGLE membership in 2025-2026
JPOST $API/admin/people "{\"givenName\":\"Vera\",\"familyName\":\"$L18\"}" >/dev/null; PL18=$(body | jsq "j['id']")
JPOST $API/admin/households "{\"householdName\":\"$L18 household\",\"primaryContactPersonId\":$PL18}" >/dev/null; HL18=$(body | jsq "j['id']")
JPOST $API/admin/memberships "{\"householdId\":$HL18,\"membershipPeriodId\":$P18,\"membershipTypeId\":$T18_SINGLE}" >/dev/null; ML18=$(body | jsq "j['id']")

# row 3: set LIFE on an unpaid membership — due $0, ACTIVE at once, approved set
check "CR18-03 set LIFE 200"           "200" "$(JPUT $API/admin/memberships/$ML18 "{\"membershipTypeId\":$T18_LIFE}")"
check "CR18-03b due 0"                 "0" "$(body | jsq "j['amountDueCents']")"
check "CR18-03c ACTIVE"                "ACTIVE" "$(body | jsq "j['status']")"
check "CR18-03d approved set"          "true" "$(body | jsq "str(j['approvedDate'] is not None).lower()")"

# row 4: unset (LIFE -> SINGLE) — the real price returns and so does the debt
check "CR18-04 unset LIFE 200"         "200" "$(JPUT $API/admin/memberships/$ML18 "{\"membershipTypeId\":$T18_SINGLE}")"
check "CR18-04b due 4500"              "4500" "$(body | jsq "j['amountDueCents']")"
check "CR18-04c PENDING_PAYMENT"       "PENDING_PAYMENT" "$(body | jsq "j['status']")"

# row 5: net money on the membership still refuses a retype
JPOST $API/admin/payments "{\"receivedDate\":\"2026-07-01\",\"amountCents\":4500,\"method\":\"OTHER\",\"notes\":\"cr18 import-style\",\"allocations\":[{\"type\":\"MEMBERSHIP\",\"membershipId\":$ML18,\"amountCents\":4500}]}" >/dev/null
check "CR18-05 retype under money 400" "400" "$(JPUT $API/admin/memberships/$ML18 "{\"membershipTypeId\":$T18_LIFE}")"
check "CR18-05b names the remedy"      "true" "$(body | jsq "str('reverse' in j['error']).lower()")"

# row 6: reverse first, then retype — the net-zero path (the YDHS runbook)
JPOST $API/admin/payments "{\"receivedDate\":\"2026-07-02\",\"amountCents\":-4500,\"method\":\"OTHER\",\"notes\":\"cr18 reversal\",\"allocations\":[{\"type\":\"MEMBERSHIP\",\"membershipId\":$ML18,\"amountCents\":-4500}]}" >/dev/null
check "CR18-06 retype after reverse 200" "200" "$(JPUT $API/admin/memberships/$ML18 "{\"membershipTypeId\":$T18_LIFE}")"
check "CR18-06b ACTIVE due 0"          "ACTIVE|0" "$(body | jsq "j['status']+'|'+str(j['amountDueCents'])")"

# row 7: CEASED is closed — no retype
JPOST $API/admin/people "{\"givenName\":\"Cei\",\"familyName\":\"$L18\"}" >/dev/null; PL18C=$(body | jsq "j['id']")
JPOST $API/admin/households "{\"householdName\":\"$L18 C\",\"primaryContactPersonId\":$PL18C}" >/dev/null; HL18C=$(body | jsq "j['id']")
JPOST $API/admin/memberships "{\"householdId\":$HL18C,\"membershipPeriodId\":$P18,\"membershipTypeId\":$T18_SINGLE}" >/dev/null; ML18C=$(body | jsq "j['id']")
JPUT $API/admin/memberships/$ML18C "{\"status\":\"CEASED\",\"ceasedDate\":\"2026-07-01\",\"cessationReason\":\"OTHER\"}" >/dev/null
check "CR18-07 retype CEASED 400"      "400" "$(JPUT $API/admin/memberships/$ML18C "{\"membershipTypeId\":$T18_LIFE}")"

# row 8: a type with no price in the membership's period (psql: a throwaway
# type is the only way to produce one — period creation forbids the gap).
# Deleted right after so period creation (which requires a price for EVERY
# type) stays green; the CR-003 block's heal covers a crashed run.
if [ "$PSQL_OK" = 1 ]; then
  T18_X=$(psqlq "INSERT INTO membership_type (name, description, minimum_people) VALUES ('X18TMP$$','cr18 unpriced',1) RETURNING membership_type_id" | grep -E '^[0-9]+$' | head -1)
  check "CR18-08 unpriced type 400"    "400" "$(JPUT $API/admin/memberships/$ML18 "{\"membershipTypeId\":$T18_X}")"
  check "CR18-08b names the gap"       "true" "$(body | jsq "str('no price' in j['error']).lower()")"
  psqlq "DELETE FROM membership_type WHERE membership_type_id = $T18_X" >/dev/null
else
  echo "SKIP CR18-08 (unpriced-type fixture needs psql)"
fi

# rows 10/11: a new period must price LIFE like any type — omitted is a 400
# naming it, $0 is accepted (the far-future dates keep it out of every other
# block's way; unique name keeps re-runs green)
check "CR18-10 period sans LIFE 400"   "400" "$(JPOST $API/admin/periods "{\"name\":\"$L18 np\",\"startDate\":\"2097-07-01\",\"endDate\":\"2098-06-30\",\"prices\":[{\"type\":\"SINGLE\",\"amountCents\":4500},{\"type\":\"HOUSEHOLD\",\"amountCents\":6500}]}")"
check "CR18-10b names LIFE"            "true" "$(body | jsq "str('LIFE' in j['error']).lower()")"
check "CR18-11 period with LIFE 201"   "201" "$(JPOST $API/admin/periods "{\"name\":\"$L18 p\",\"startDate\":\"2097-07-01\",\"endDate\":\"2098-06-30\",\"prices\":[{\"type\":\"SINGLE\",\"amountCents\":4500},{\"type\":\"HOUSEHOLD\",\"amountCents\":6500},{\"type\":\"LIFE\",\"amountCents\":0}]}")"
check "CR18-11b LIFE \$0 in its prices" "0" "$(body | jsq "next(pr['amountCents'] for pr in j['prices'] if pr['type']=='LIFE')")"

# row 12: the status view's ?type= filter (the param CR-003 shipped, now with
# a UI): LIFE lists the retyped membership, SINGLE does not — and the ceased
# SINGLE household proves the filter, not the search, does the narrowing
SV_LIFE=$(curl -s "$API/admin/periods/$P18/memberships?type=LIFE&q=$L18" -H "Authorization: Bearer $ADMIN")
SV_SINGLE=$(curl -s "$API/admin/periods/$P18/memberships?type=SINGLE&q=$L18" -H "Authorization: Bearer $ADMIN")
check "CR18-12 type=LIFE lists it"     "true" "$(echo "$SV_LIFE" | jsq "str(any(r['membershipId']==$ML18 for r in j['rows'])).lower()")"
check "CR18-12b rows all LIFE"         "true" "$(echo "$SV_LIFE" | jsq "str(all(r['typeName']=='LIFE' for r in j['rows'])).lower()")"
check "CR18-12c type=SINGLE excludes it" "false" "$(echo "$SV_SINGLE" | jsq "str(any(r['membershipId']==$ML18 for r in j['rows'])).lower()")"
check "CR18-12d ceased SINGLE still there" "true" "$(echo "$SV_SINGLE" | jsq "str(any(r['membershipId']==$ML18C for r in j['rows'])).lower()")"

# row 13: a life member votes — the AGM register carries the MEMBER person
check "CR18-13 agm has life member"    "yes" "$(curl -s $API/admin/periods/$P18/export/agm-register.csv -H "Authorization: Bearer $ADMIN" | grep -q "Vera,$L18" && echo yes || echo no)"

# row 14: financial.csv shows the LIFE row at due 0, paid 0
check "CR18-14 financial LIFE 0/0"     "LIFE|0|0" "$(curl -s $API/admin/periods/$P18/export/financial.csv -H "Authorization: Bearer $ADMIN" | python3 -c "import sys,csv; r=next(r for r in csv.reader(sys.stdin) if r and r[0]=='$L18 household'); print(r[2]+'|'+r[4]+'|'+r[5])")"

# --- reports & exports (CR-019) ----------------------------------------------
# Four cross-cutting CSVs on the new AdminReportsResource (/admin/export/...).
# Fixtures live in two far-future periods (2094/2095) so nothing else's rows
# bleed in; unique Rep$$ names keep re-runs green. Bad parameters are a 400
# JSON error, never an empty CSV.
check "CR19-01 register guest 403"     "403" "$(code $API/admin/export/register-of-members.csv)"
check "CR19-01b register user 403"     "403" "$(code $API/admin/export/register-of-members.csv -H "Authorization: Bearer $USER")"
check "CR19-01c register noaud 401"    "401" "$(code $API/admin/export/register-of-members.csv -H "Authorization: Bearer $NOAUD")"
check "CR19-01d no-mem guest 403"      "403" "$(code "$API/admin/export/no-current-membership.csv?periodId=1")"
check "CR19-01e no-mem user 403"       "403" "$(code "$API/admin/export/no-current-membership.csv?periodId=1" -H "Authorization: Bearer $USER")"
check "CR19-01f no-mem noaud 401"      "401" "$(code "$API/admin/export/no-current-membership.csv?periodId=1" -H "Authorization: Bearer $NOAUD")"
check "CR19-01g unrenewed guest 403"   "403" "$(code "$API/admin/export/unrenewed.csv?fromPeriodId=1&toPeriodId=2")"
check "CR19-01h unrenewed user 403"    "403" "$(code "$API/admin/export/unrenewed.csv?fromPeriodId=1&toPeriodId=2" -H "Authorization: Bearer $USER")"
check "CR19-01i unrenewed noaud 401"   "401" "$(code "$API/admin/export/unrenewed.csv?fromPeriodId=1&toPeriodId=2" -H "Authorization: Bearer $NOAUD")"
check "CR19-01j donations guest 403"   "403" "$(code $API/admin/export/donations.csv)"
check "CR19-01k donations user 403"    "403" "$(code $API/admin/export/donations.csv -H "Authorization: Bearer $USER")"
check "CR19-01l donations noaud 401"   "401" "$(code $API/admin/export/donations.csv -H "Authorization: Bearer $NOAUD")"

# row 13 (parameter validation, the part needing no fixtures)
check "CR19-13 no-mem unknown period 400" "400" "$(JADMIN "$API/admin/export/no-current-membership.csv?periodId=999999")"
check "CR19-13b no-mem missing period 400" "400" "$(JADMIN "$API/admin/export/no-current-membership.csv")"
check "CR19-13c donations from>to 400" "400" "$(JADMIN "$API/admin/export/donations.csv?from=2095-01-01&to=2094-01-01")"
check "CR19-13d donations bad date 400" "400" "$(JADMIN "$API/admin/export/donations.csv?from=notadate")"
check "CR19-13e unrenewed unknown 400" "400" "$(JADMIN "$API/admin/export/unrenewed.csv?fromPeriodId=999998&toPeriodId=999999")"

if [ "$PSQL_OK" = 1 ]; then
  RP="Rep$$"
  # two consecutive far-future periods
  JPOST $API/admin/periods "{\"name\":\"$RP A\",\"startDate\":\"2094-09-01\",\"endDate\":\"2095-08-31\",\"prices\":[{\"type\":\"SINGLE\",\"amountCents\":4500},{\"type\":\"HOUSEHOLD\",\"amountCents\":6500},{\"type\":\"LIFE\",\"amountCents\":0}]}" >/dev/null; RPA=$(body | jsq "j['id']")
  JPOST $API/admin/periods "{\"name\":\"$RP B\",\"startDate\":\"2095-09-01\",\"endDate\":\"2096-08-31\",\"prices\":[{\"type\":\"SINGLE\",\"amountCents\":4500},{\"type\":\"HOUSEHOLD\",\"amountCents\":6500},{\"type\":\"LIFE\",\"amountCents\":0}]}" >/dev/null; RPB=$(body | jsq "j['id']")
  check "CR19-13f unrenewed to==from 400" "400" "$(JADMIN "$API/admin/export/unrenewed.csv?fromPeriodId=$RPA&toPeriodId=$RPA")"
  check "CR19-13g unrenewed to<from 400" "400" "$(JADMIN "$API/admin/export/unrenewed.csv?fromPeriodId=$RPB&toPeriodId=$RPA")"

  # households: A renews nothing (email-only person, joined at period start);
  # B lapses in the to-period (and has a postal address); C renews (ACTIVE in
  # both); D ceases mid-A; E never pays (LAPSED in A — the capped-at-today
  # ceased derivation)
  JPOST $API/admin/people "{\"givenName\":\"Ann\",\"familyName\":\"$RP\",\"emails\":[{\"email\":\"ann.$$@rep.test\",\"isPrimary\":true}],\"phones\":[{\"number\":\"0400 019 001\",\"type\":\"MOBILE\",\"isPrimary\":true}]}" >/dev/null; RP_ANN=$(body | jsq "j['id']")
  JPOST $API/admin/households "{\"householdName\":\"$RP A hh\",\"primaryContactPersonId\":$RP_ANN}" >/dev/null; RPHA=$(body | jsq "j['id']")
  JPOST $API/admin/memberships "{\"householdId\":$RPHA,\"membershipPeriodId\":$RPA,\"membershipTypeId\":$T18_SINGLE,\"startDate\":\"2094-09-01\"}" >/dev/null; RPMA=$(body | jsq "j['id']")
  JPOST $API/admin/payments "{\"receivedDate\":\"2094-09-15\",\"amountCents\":4500,\"method\":\"CASH\",\"allocations\":[{\"type\":\"MEMBERSHIP\",\"membershipId\":$RPMA,\"amountCents\":4500}]}" >/dev/null

  JPOST $API/admin/people "{\"givenName\":\"Bob\",\"familyName\":\"$RP\",\"emails\":[{\"email\":\"bob.$$@rep.test\",\"isPrimary\":true}]}" >/dev/null; RP_BOB=$(body | jsq "j['id']")
  JPOST $API/admin/households "{\"householdName\":\"$RP B hh\",\"primaryContactPersonId\":$RP_BOB}" >/dev/null; RPHB=$(body | jsq "j['id']")
  psqlq "INSERT INTO household_address (household_id, address_type, line_1, locality, state, postcode, is_preferred) VALUES ($RPHB,'POSTAL','9 Report St','Yass','NSW','2582',true)" >/dev/null
  JPOST $API/admin/memberships "{\"householdId\":$RPHB,\"membershipPeriodId\":$RPA,\"membershipTypeId\":$T18_SINGLE}" >/dev/null; RPMB=$(body | jsq "j['id']")
  JPOST $API/admin/payments "{\"receivedDate\":\"2094-09-16\",\"amountCents\":4500,\"method\":\"CASH\",\"allocations\":[{\"type\":\"MEMBERSHIP\",\"membershipId\":$RPMB,\"amountCents\":4500}]}" >/dev/null
  JPOST $API/admin/memberships "{\"householdId\":$RPHB,\"membershipPeriodId\":$RPB,\"membershipTypeId\":$T18_SINGLE}" >/dev/null; RPMB2=$(body | jsq "j['id']")
  JPUT $API/admin/memberships/$RPMB2 "{\"status\":\"LAPSED\"}" >/dev/null

  JPOST $API/admin/people "{\"givenName\":\"Cal\",\"familyName\":\"$RP\"}" >/dev/null; RP_CAL=$(body | jsq "j['id']")
  JPOST $API/admin/households "{\"householdName\":\"$RP C hh\",\"primaryContactPersonId\":$RP_CAL}" >/dev/null; RPHC=$(body | jsq "j['id']")
  JPOST $API/admin/memberships "{\"householdId\":$RPHC,\"membershipPeriodId\":$RPA,\"membershipTypeId\":$T18_SINGLE}" >/dev/null; RPMC=$(body | jsq "j['id']")
  JPOST $API/admin/payments "{\"receivedDate\":\"2094-09-17\",\"amountCents\":4500,\"method\":\"CASH\",\"allocations\":[{\"type\":\"MEMBERSHIP\",\"membershipId\":$RPMC,\"amountCents\":4500}]}" >/dev/null
  JPOST $API/admin/memberships "{\"householdId\":$RPHC,\"membershipPeriodId\":$RPB,\"membershipTypeId\":$T18_SINGLE}" >/dev/null; RPMC2=$(body | jsq "j['id']")
  JPOST $API/admin/payments "{\"receivedDate\":\"2095-09-17\",\"amountCents\":4500,\"method\":\"CASH\",\"allocations\":[{\"type\":\"MEMBERSHIP\",\"membershipId\":$RPMC2,\"amountCents\":4500}]}" >/dev/null

  JPOST $API/admin/people "{\"givenName\":\"Dee\",\"familyName\":\"$RP\"}" >/dev/null; RP_DEE=$(body | jsq "j['id']")
  JPOST $API/admin/households "{\"householdName\":\"$RP D hh\",\"primaryContactPersonId\":$RP_DEE}" >/dev/null; RPHD=$(body | jsq "j['id']")
  JPOST $API/admin/memberships "{\"householdId\":$RPHD,\"membershipPeriodId\":$RPA,\"membershipTypeId\":$T18_SINGLE}" >/dev/null; RPMD=$(body | jsq "j['id']")
  JPOST $API/admin/payments "{\"receivedDate\":\"2094-09-18\",\"amountCents\":4500,\"method\":\"CASH\",\"allocations\":[{\"type\":\"MEMBERSHIP\",\"membershipId\":$RPMD,\"amountCents\":4500}]}" >/dev/null
  JPUT $API/admin/memberships/$RPMD "{\"status\":\"CEASED\",\"ceasedDate\":\"2095-01-15\",\"cessationReason\":\"RESIGNED\"}" >/dev/null

  JPOST $API/admin/people "{\"givenName\":\"Eve\",\"familyName\":\"$RP\"}" >/dev/null; RP_EVE=$(body | jsq "j['id']")
  JPOST $API/admin/households "{\"householdName\":\"$RP E hh\",\"primaryContactPersonId\":$RP_EVE}" >/dev/null; RPHE=$(body | jsq "j['id']")
  JPOST $API/admin/memberships "{\"householdId\":$RPHE,\"membershipPeriodId\":$RPA,\"membershipTypeId\":$T18_SINGLE}" >/dev/null; RPME=$(body | jsq "j['id']")
  JPUT $API/admin/memberships/$RPME "{\"status\":\"LAPSED\"}" >/dev/null

  # a left and a deceased person in household A (both invisible to report B)
  JPOST $API/admin/people "{\"givenName\":\"Lea\",\"familyName\":\"$RP\"}" >/dev/null; RP_LEA=$(body | jsq "j['id']")
  JPOST $API/admin/households/$RPHA/people "{\"personId\":$RP_LEA,\"relationshipType\":\"PARTNER\"}" >/dev/null
  code -X DELETE $API/admin/households/$RPHA/people/$RP_LEA -H "Authorization: Bearer $ADMIN" >/dev/null
  JPOST $API/admin/people "{\"givenName\":\"Mor\",\"familyName\":\"$RP\"}" >/dev/null; RP_MOR=$(body | jsq "j['id']")
  JPOST $API/admin/households/$RPHA/people "{\"personId\":$RP_MOR,\"relationshipType\":\"OTHER\"}" >/dev/null
  psqlq "UPDATE person SET deceased_date = DATE '2095-01-01' WHERE person_id = $RP_MOR" >/dev/null

  # rows 2/3: the register — python-parsed per person (name is unique per run)
  REG=$(curl -s $API/admin/export/register-of-members.csv -H "Authorization: Bearer $ADMIN")
  regq() { echo "$REG" | python3 -c "
import sys, csv
rows = {(r[1], r[0]): r for r in csv.reader(sys.stdin)}
r = rows.get(('$1', '$RP'))
print('MISSING' if r is None else r[$2])"; }
  check "CR19-02 register is csv 200"    "200" "$(JADMIN $API/admin/export/register-of-members.csv)"
  check "CR19-02b current member blank ceased" "" "$(regq Ann 4)"
  check "CR19-02c email-only address"    "ann.$$@rep.test" "$(regq Ann 2)"
  check "CR19-02d postal beats email"    "9 Report St, Yass, NSW, 2582" "$(regq Bob 2)"
  check "CR19-02e ceased carries date"   "2095-01-15" "$(regq Dee 4)"
  check "CR19-02f lapsed-never-paid ceased today" "$(date +%F)" "$(regq Eve 4)"
  check "CR19-02g non-member absent"     "MISSING" "$(regq Mor 4)"
  check "CR19-03 became = period start"  "2094-09-01" "$(regq Ann 3)"

  # rows 4/5/6: report B on period B
  NOM=$(curl -s "$API/admin/export/no-current-membership.csv?periodId=$RPB" -H "Authorization: Bearer $ADMIN")
  nomq() { echo "$NOM" | python3 -c "
import sys, csv
rows = {(r[1], r[0]): r for r in csv.reader(sys.stdin)}
r = rows.get(('$1', '$RP'))
print('MISSING' if r is None else r[$2])"; }
  check "CR19-04 unheld person present"  "$RP A hh" "$(nomq Ann 2)"
  check "CR19-04b with last-held period" "$RP A" "$(nomq Ann 6)"
  check "CR19-04c email and phone ride"  "ann.$$@rep.test|0400 019 001" "$(nomq Ann 4)|$(nomq Ann 5)"
  check "CR19-05 ACTIVE-member absent"   "MISSING" "$(nomq Cal 0)"
  check "CR19-05b lapsed-in-B absent (holds a place)" "MISSING" "$(nomq Bob 0)"
  check "CR19-06 left person absent"     "MISSING" "$(nomq Lea 0)"
  check "CR19-06b deceased absent"       "MISSING" "$(nomq Mor 0)"

  # rows 7/8/9: report C from A to B
  UNR=$(curl -s "$API/admin/export/unrenewed.csv?fromPeriodId=$RPA&toPeriodId=$RPB" -H "Authorization: Bearer $ADMIN")
  unrq() { echo "$UNR" | python3 -c "
import sys, csv
rows = {r[0]: r for r in csv.reader(sys.stdin)}
r = rows.get('$RP $1 hh')
print('MISSING' if r is None else r[$2])"; }
  check "CR19-07 no-membership household present" "—" "$(unrq A 5)"
  check "CR19-07b with contact email"    "ann.$$@rep.test" "$(unrq A 2)"
  check "CR19-08 lapsed household LAPSED" "LAPSED" "$(unrq B 5)"
  check "CR19-09 renewed household absent" "MISSING" "$(unrq C 5)"
  check "CR19-09b ceased-in-from absent (not ACTIVE)" "MISSING" "$(unrq D 5)"

  # rows 10/11/12: donations in the period-A window
  JPOST $API/admin/payments "{\"receivedDate\":\"2094-10-01\",\"amountCents\":1500,\"method\":\"CASH\",\"payerPersonId\":$RP_ANN,\"allocations\":[{\"type\":\"DONATION\",\"amountCents\":1500}]}" >/dev/null; RPD1=$(body | jsq "j['id']")
  JPOST $API/admin/payments "{\"receivedDate\":\"2094-10-02\",\"amountCents\":-500,\"method\":\"CASH\",\"notes\":\"cr19 reversal\",\"allocations\":[{\"type\":\"DONATION\",\"amountCents\":-500}]}" >/dev/null; RPD2=$(body | jsq "j['id']")
  JPOST $API/admin/payments "{\"receivedDate\":\"2096-01-01\",\"amountCents\":800,\"method\":\"CASH\",\"allocations\":[{\"type\":\"DONATION\",\"amountCents\":800}]}" >/dev/null; RPD3=$(body | jsq "j['id']")
  DON=$(curl -s "$API/admin/export/donations.csv?from=2094-09-01&to=2095-08-31" -H "Authorization: Bearer $ADMIN")
  donq() { echo "$DON" | python3 -c "
import sys, csv
rows = {r[1]: r for r in csv.reader(sys.stdin) if len(r) > 1}
r = rows.get('$1')
print('MISSING' if r is None else r[$2])"; }
  check "CR19-10 donation present w/ payer" "Ann $RP" "$(donq $RPD1 2)"
  check "CR19-10b donation amount 15.00" "15.00" "$(donq $RPD1 4)"
  # payments are insert-only, so prior runs' fixtures accumulate in the shared
  # window — assert the labelled total EQUALS the sum of the rows, not a number
  check "CR19-10c trailing total = row sum" "match" "$(echo "$DON" | python3 -c "
import sys, csv
rows = [r for r in csv.reader(sys.stdin) if r]
total = next(r[4] for r in rows if r[0] == 'Total donations')
cents = sum(round(float(r[4]) * 100) for r in rows if r[0] not in ('Date', 'Total donations'))
print('match' if round(float(total) * 100) == cents else f'{total} != {cents}')")"
  check "CR19-11 reversal row -5.00"     "-5.00" "$(donq $RPD2 4)"
  check "CR19-12 out-of-range absent"    "MISSING" "$(donq $RPD3 0)"
  check "CR19-12b membership-only payment absent" "0" "$(echo "$DON" | grep -c ",2094-09-15," | head -1)"
else
  echo "note: psql not found — skipping CR-019 data rows (address/deceased fixtures need psql)"
fi

# --- member numbers (CR-020) --------------------------------------------------
# person.member_no (V9) decouples the card's printed number from person_id:
# nullable, unique where set, COALESCE at card-compose time. Assigned/cleared
# through the person payload's memberNo (absent = clear — the form's wholesale-
# replace semantics, like emails). A unique-per-run number plus the clearing
# row keeps re-runs green (member_no is globally unique and people are never
# deleted). Role gates ride rows 35-38 (same endpoint); CR17-02f above is the
# no-number-falls-back-to-person-id regression guard.
N20="Num$$"; MN20=$(( $$ % 30000 + 1000 ))
JPOST $API/admin/people "{\"givenName\":\"Numa\",\"familyName\":\"$N20\"}" >/dev/null; PN20=$(body | jsq "j['id']")

# row 1: assign via PUT; PUT response and a fresh GET both echo it
check "CR20-01 PUT memberNo 200"       "200" "$(JPUT $API/admin/people/$PN20 "{\"givenName\":\"Numa\",\"familyName\":\"$N20\",\"memberNo\":$MN20}")"
check "CR20-01b PUT echoes it"         "$MN20" "$(body | jsq "str(j['memberNo'])")"
check "CR20-01c GET echoes it"         "$MN20" "$(curl -s $API/admin/people/$PN20 -H "Authorization: Bearer $ADMIN" | jsq "str(j['memberNo'])")"

# row 2: the card shows the assigned number (a LIFE membership is ACTIVE at
# once — the CR-018 zero-due path gives the compose gate a card to serve)
JPOST $API/admin/households "{\"householdName\":\"$N20 hh\",\"primaryContactPersonId\":$PN20}" >/dev/null; HN20=$(body | jsq "j['id']")
JPOST $API/admin/memberships "{\"householdId\":$HN20,\"membershipPeriodId\":$P18,\"membershipTypeId\":$T18_LIFE}" >/dev/null; MM20=$(body | jsq "j['id']")
check "CR20-02 card info memberNo"     "$MN20" "$(curl -s $API/admin/memberships/$MM20/card/$PN20/info -H "Authorization: Bearer $ADMIN" | jsq "str(j['memberNo'])")"
check "CR20-02b PNG still renders"     "89504e47" "$(curl -s $API/admin/memberships/$MM20/card/$PN20 -H "Authorization: Bearer $ADMIN" | head -c 4 | xxd -p)"

# row 4: a duplicate number is a 409 naming the number (PUT and POST alike)
JPOST $API/admin/people "{\"givenName\":\"Nyla\",\"familyName\":\"$N20\"}" >/dev/null; PN20B=$(body | jsq "j['id']")
check "CR20-04 duplicate PUT 409"      "409" "$(JPUT $API/admin/people/$PN20B "{\"givenName\":\"Nyla\",\"familyName\":\"$N20\",\"memberNo\":$MN20}")"
check "CR20-04b names the number"      "true" "$(body | jsq "str('$MN20' in j['error']).lower()")"
check "CR20-04c duplicate POST 409"    "409" "$(JPOST $API/admin/people "{\"givenName\":\"Nix\",\"familyName\":\"$N20\",\"memberNo\":$MN20}")"

# row 5: zero / negative / non-numeric are 400s (the CHECK's belt-and-braces)
check "CR20-05 memberNo 0 400"         "400" "$(JPUT $API/admin/people/$PN20B "{\"givenName\":\"Nyla\",\"familyName\":\"$N20\",\"memberNo\":0}")"
check "CR20-05b negative 400"          "400" "$(JPUT $API/admin/people/$PN20B "{\"givenName\":\"Nyla\",\"familyName\":\"$N20\",\"memberNo\":-3}")"
check "CR20-05c non-numeric 400"       "400" "$(JPUT $API/admin/people/$PN20B "{\"givenName\":\"Nyla\",\"familyName\":\"$N20\",\"memberNo\":\"seven\"}")"

# row 6: an absent memberNo clears; the card falls back to the person id
check "CR20-06 clear 200"              "200" "$(JPUT $API/admin/people/$PN20 "{\"givenName\":\"Numa\",\"familyName\":\"$N20\"}")"
check "CR20-06b cleared to null"       "None" "$(body | jsq "str(j['memberNo'])")"
check "CR20-06c card falls back to id" "$PN20" "$(curl -s $API/admin/memberships/$MM20/card/$PN20/info -H "Authorization: Bearer $ADMIN" | jsq "str(j['memberNo'])")"

# rows 7/8/9: importer zero-due fix — a LIFE group imports ACTIVE (approved
# set) with NO payment row regardless of the paid flag, and the preview's
# payment count excludes it. The CR2-* rows above stay the non-zero-due guard.
IMP20="Nimp$$"
cat > "$FX/life-paid.csv" <<EOF
household,givenName,familyName,relationship,membershipType,paid
LifeY$$,Lena,$IMP20,MEMBER,LIFE,yes
EOF
cat > "$FX/life-unpaid.csv" <<EOF
household,givenName,familyName,relationship,membershipType,paid
LifeN$$,Levi,$IMP20,MEMBER,LIFE,no
EOF
check "CR20-09 preview zero-due paid"  "200" "$(imp "$API/admin/import/preview?period=2025-2026" "$ADMIN" "$FX/life-paid.csv")"
check "CR20-09b toCreate memberships 1" "1" "$(body | jsq "j['toCreate']['memberships']")"
check "CR20-09c toCreate payments 0"   "0" "$(body | jsq "j['toCreate']['payments']")"
check "CR20-07 apply zero-due paid"    "200" "$(imp "$API/admin/import?period=2025-2026" "$ADMIN" "$FX/life-paid.csv")"
check "CR20-07b created no payment"    "0" "$(body | jsq "j['created']['payments']")"
check "CR20-08 apply zero-due unpaid"  "200" "$(imp "$API/admin/import?period=2025-2026" "$ADMIN" "$FX/life-unpaid.csv")"
if [ "$PSQL_OK" = 1 ]; then
  check "CR20-07c ACTIVE, approved set" "ACTIVE|true" "$(psqlq "SELECT m.status||'|'||(m.approved_date IS NOT NULL) FROM membership m JOIN household h ON h.household_id=m.household_id WHERE h.household_name='LifeY$$'")"
  check "CR20-07d no payment row"      "0" "$(psqlq "SELECT count(*) FROM payment_allocation pa JOIN membership m ON m.membership_id=pa.membership_id JOIN household h ON h.household_id=m.household_id WHERE h.household_name='LifeY$$'")"
  check "CR20-08b unpaid also ACTIVE"  "ACTIVE" "$(psqlq "SELECT m.status FROM membership m JOIN household h ON h.household_id=m.household_id WHERE h.household_name='LifeN$$'")"
else
  echo "SKIP CR20-07c/d,08b (import side effects need psql)"
fi

# --- public application form (CR-007) ----------------------------------------
# Staging pair + email round trip: a submission lands RECEIVED, only the
# emailed confirmation link moves it into the queue, and approval is the only
# door into the register (the CR-010 path, recording the committee's decision
# date). The application_settings row is deleted first (crash recovery) and
# last (leave-as-found): with no row the form is CLOSED — the deploy-dark
# default the clause-3-minute gate relies on. Emails are $$-unique so the
# per-address cooldown never trips across runs; decision dates are -2d (the
# CR10-13 UTC lesson: container current_date lags AEST mornings).

check "CR7-01 applications guest 403"  "403" "$(code $API/admin/applications)"
check "CR7-01b applications user 403"  "403" "$(code $API/admin/applications -H "Authorization: Bearer $USER")"
check "CR7-01c applications noaud 401" "401" "$(code $API/admin/applications -H "Authorization: Bearer $NOAUD")"
check "CR7-02 settings PUT guest 403"  "403" "$(code -X PUT $API/admin/applications/settings -H 'Content-Type: application/json' -d '{}')"
check "CR7-02b settings PUT user 403"  "403" "$(code -X PUT $API/admin/applications/settings -H "Authorization: Bearer $USER" -H 'Content-Type: application/json' -d '{}')"
check "CR7-02c settings PUT noaud 401" "401" "$(code -X PUT $API/admin/applications/settings -H "Authorization: Bearer $NOAUD" -H 'Content-Type: application/json' -d '{}')"

if [ "$PSQL_OK" = 1 ]; then
  psqlq "DELETE FROM app_setting WHERE key='application_settings'" >/dev/null

  # no row saved: settings default closed, options report closed, submit 503
  check "CR7-03 settings default closed" "False|None" "$(curl -s $API/admin/applications/settings -H "Authorization: Bearer $ADMIN" | jsq "str(j['formEnabled'])+'|'+str(j['alertMailbox'])")"
  check "CR7-04 options closed"          "False" "$(curl -s $API/apply/options | jsq "str(j['open'])")"
  check "CR7-05 submit while closed 503" "503" "$(code -X POST $API/apply -H 'Content-Type: application/json' -d "{\"membershipTypeId\":$T_SINGLE,\"givenName\":\"Cade\",\"familyName\":\"Closed$$\",\"email\":\"closed.$$@example.com\"}")"

  ALERT7="alert7.$$@example.com"
  check "CR7-06 settings save 200"       "200" "$(JPUT $API/admin/applications/settings "{\"alertMailbox\":\"$ALERT7\",\"formEnabled\":true}")"
  check "CR7-06b GET echoes"             "True|$ALERT7" "$(curl -s $API/admin/applications/settings -H "Authorization: Bearer $ADMIN" | jsq "str(j['formEnabled'])+'|'+j['alertMailbox']")"
  check "CR7-06c bad mailbox 400"        "400" "$(JPUT $API/admin/applications/settings "{\"alertMailbox\":\"not-an-email\",\"formEnabled\":true}")"

  # options: positively-priced types only — V8's LIFE $0 price must not be offered
  check "CR7-07 options open"            "True" "$(curl -s $API/apply/options | jsq "str(j['open'])")"
  check "CR7-07b SINGLE offered w/ price" "4500" "$(curl -s $API/apply/options | jsq "next(t['priceCents'] for t in j['types'] if t['name']=='SINGLE')")"
  check "CR7-07c LIFE (zero-priced) absent" "False" "$(curl -s $API/apply/options | jsq "str(any(t['name']=='LIFE' for t in j['types']))")"

  A1MAIL="apply1.$$@example.com"
  check "CR7-08 submit 202"              "202" "$(code -X POST $API/apply -H 'Content-Type: application/json' -d "{\"membershipTypeId\":$T_SINGLE,\"givenName\":\"Ada\",\"familyName\":\"App$$\",\"email\":\"$A1MAIL\",\"phone\":\"0400 000 001\",\"addressLine1\":\"1 Staging St\",\"locality\":\"Yass\",\"state\":\"NSW\",\"postcode\":\"2582\",\"message\":\"via matrix\"}")"
  check "CR7-08b row RECEIVED"           "RECEIVED" "$(psqlq "SELECT a.status FROM membership_application a JOIN membership_application_person p ON p.application_id=a.application_id WHERE p.email='$A1MAIL'")"

  # honeypot: the bot gets its 202 and nothing is written
  check "CR7-09 honeypot 202"            "202" "$(code -X POST $API/apply -H 'Content-Type: application/json' -d "{\"membershipTypeId\":$T_SINGLE,\"givenName\":\"Bot\",\"familyName\":\"Hp$$\",\"email\":\"hp.$$@example.com\",\"website\":\"http://spam.example\"}")"
  check "CR7-09b nothing written"        "0" "$(psqlq "SELECT count(*) FROM membership_application_person WHERE email='hp.$$@example.com'")"

  check "CR7-10 missing email 400"       "400" "$(code -X POST $API/apply -H 'Content-Type: application/json' -d "{\"membershipTypeId\":$T_SINGLE,\"givenName\":\"No\",\"familyName\":\"Mail$$\"}")"
  check "CR7-10b missing name 400"       "400" "$(code -X POST $API/apply -H 'Content-Type: application/json' -d "{\"membershipTypeId\":$T_SINGLE,\"email\":\"noname.$$@example.com\"}")"
  check "CR7-10c unknown type 400"       "400" "$(code -X POST $API/apply -H 'Content-Type: application/json' -d "{\"membershipTypeId\":999999,\"givenName\":\"Bad\",\"familyName\":\"Type$$\",\"email\":\"badtype.$$@example.com\"}")"
  check "CR7-10d zero-priced type 400"   "400" "$(code -X POST $API/apply -H 'Content-Type: application/json' -d "{\"membershipTypeId\":$T_LIFE,\"givenName\":\"Life\",\"familyName\":\"Type$$\",\"email\":\"lifetype.$$@example.com\"}")"
  check "CR7-10e single+second MEMBER 400" "400" "$(code -X POST $API/apply -H 'Content-Type: application/json' -d "{\"membershipTypeId\":$T_SINGLE,\"givenName\":\"One\",\"familyName\":\"Max$$\",\"email\":\"max.$$@example.com\",\"secondPerson\":{\"givenName\":\"Two\",\"familyName\":\"Max$$\",\"relationship\":\"MEMBER\"}}")"

  # per-address cooldown: an immediate resubmit from the same address is 429
  check "CR7-11 immediate resubmit 429"  "429" "$(code -X POST $API/apply -H 'Content-Type: application/json' -d "{\"membershipTypeId\":$T_SINGLE,\"givenName\":\"Ada\",\"familyName\":\"App$$\",\"email\":\"$A1MAIL\"}")"

  if curl -s -m 2 -o /dev/null "$MAILPIT/api/v1/messages"; then
    D2AGO=$(date -u -d '-2 days' +%F)
    DFUT=$(date -u -d '+3 days' +%F)

    # the round trip: token only ever exists in the confirmation email
    TOK7=$(mailpit_text "$A1MAIL" | grep -o 'confirm=[A-Za-z0-9_-]*' | head -1 | cut -d= -f2)
    check "CR7-12 confirmation mail carries token" "yes" "$([ -n "$TOK7" ] && echo yes)"
    check "CR7-12b confirm 200"          "200" "$(code -X POST $API/apply/confirm -H 'Content-Type: application/json' -d "{\"token\":\"$TOK7\"}")"
    check "CR7-12c row CONFIRMED"        "CONFIRMED" "$(psqlq "SELECT a.status FROM membership_application a JOIN membership_application_person p ON p.application_id=a.application_id WHERE p.email='$A1MAIL'")"
    check "CR7-12d alert mail to mailbox" "1" "$(mailpit_count "$ALERT7")"
    check "CR7-13 confirm again 200"     "200" "$(code -X POST $API/apply/confirm -H 'Content-Type: application/json' -d "{\"token\":\"$TOK7\"}")"
    check "CR7-13b still one alert"      "1" "$(mailpit_count "$ALERT7")"
    check "CR7-14 garbage token 404"     "404" "$(code -X POST $API/apply/confirm -H 'Content-Type: application/json' -d '{"token":"nonsense"}')"

    # expired: seed a submission and wind its expiry back — 404, same as unknown
    A5MAIL="apply5.$$@example.com"
    code -X POST $API/apply -H 'Content-Type: application/json' -d "{\"membershipTypeId\":$T_SINGLE,\"givenName\":\"Expi\",\"familyName\":\"Red$$\",\"email\":\"$A5MAIL\"}" >/dev/null
    TOK5=$(mailpit_text "$A5MAIL" | grep -o 'confirm=[A-Za-z0-9_-]*' | head -1 | cut -d= -f2)
    A5=$(psqlq "SELECT a.application_id FROM membership_application a JOIN membership_application_person p ON p.application_id=a.application_id WHERE p.email='$A5MAIL'")
    psqlq "UPDATE membership_application SET confirm_expires_at = now() - interval '1 hour' WHERE application_id = $A5" >/dev/null
    check "CR7-15 expired token 404"     "404" "$(code -X POST $API/apply/confirm -H 'Content-Type: application/json' -d "{\"token\":\"$TOK5\"}")"

    # approval guards: unconfirmed 409, future decision date 400
    A1=$(psqlq "SELECT a.application_id FROM membership_application a JOIN membership_application_person p ON p.application_id=a.application_id WHERE p.email='$A1MAIL'")
    check "CR7-16 approve unconfirmed 409" "409" "$(JPOST $API/admin/applications/$A5/approve "{\"membershipPeriodId\":$P2526,\"membershipTypeId\":$T_SINGLE,\"decisionDate\":\"$D2AGO\"}")"
    check "CR7-27 future decisionDate 400" "400" "$(JPOST $API/admin/applications/$A1/approve "{\"membershipPeriodId\":$P2526,\"membershipTypeId\":$T_SINGLE,\"decisionDate\":\"$DFUT\"}")"

    # the approval: one transaction into the register, committee dates stamped,
    # household_address's FIRST writer, pay link in the clause-3(5)(a) notice
    check "CR7-17 approve 200"           "200" "$(JPOST $API/admin/applications/$A1/approve "{\"membershipPeriodId\":$P2526,\"membershipTypeId\":$T_SINGLE,\"decisionDate\":\"$D2AGO\",\"minuteReference\":\"committee $$ item 1\"}")"
    M7=$(body | jsq "j['membershipId']"); H7=$(body | jsq "j['householdId']")
    check "CR7-17b status PENDING_PAYMENT" "PENDING_PAYMENT" "$(psqlq "SELECT status FROM membership WHERE membership_id=$M7")"
    check "CR7-17c committee dates stamped" "true|$D2AGO" "$(psqlq "SELECT (application_date IS NOT NULL)||'|'||approved_date FROM membership WHERE membership_id=$M7")"
    check "CR7-18 postal address row"    "POSTAL|1 Staging St" "$(psqlq "SELECT address_type||'|'||line_1 FROM household_address WHERE household_id=$H7")"
    check "CR7-18b person email landed"  "1" "$(psqlq "SELECT count(*) FROM email_address WHERE email='$A1MAIL'")"
    PT7=$(mailpit_text "$A1MAIL" | grep -o 'pay\.html?t=[A-Za-z0-9_-]*' | head -1 | cut -d= -f2)
    check "CR7-18c notice carries pay link" "yes" "$([ -n "$PT7" ] && echo yes)"
    check "CR7-19 pay link resolves"     "200" "$(code $API/pay/$PT7)"
    check "CR7-19b pay view balance 4500" "4500" "$(body | jsq "j['balanceCents']")"
    check "CR7-20 approve again 409"     "409" "$(JPOST $API/admin/applications/$A1/approve "{\"membershipPeriodId\":$P2526,\"membershipTypeId\":$T_SINGLE,\"decisionDate\":\"$D2AGO\"}")"
    check "CR7-20b reject decided 409"   "409" "$(JPOST $API/admin/applications/$A1/reject "{\"decisionDate\":\"$D2AGO\"}")"

    # household application, second applicant a MEMBER: both get voting rights
    A2MAIL="apply2.$$@example.com"
    code -X POST $API/apply -H 'Content-Type: application/json' -d "{\"membershipTypeId\":$T_HH,\"givenName\":\"Hera\",\"familyName\":\"Hh$$\",\"email\":\"$A2MAIL\",\"secondPerson\":{\"givenName\":\"Hugo\",\"familyName\":\"Hh$$\",\"relationship\":\"MEMBER\"}}" >/dev/null
    TOK2=$(mailpit_text "$A2MAIL" | grep -o 'confirm=[A-Za-z0-9_-]*' | head -1 | cut -d= -f2)
    code -X POST $API/apply/confirm -H 'Content-Type: application/json' -d "{\"token\":\"$TOK2\"}" >/dev/null
    A2=$(psqlq "SELECT a.application_id FROM membership_application a JOIN membership_application_person p ON p.application_id=a.application_id WHERE p.email='$A2MAIL'")
    check "CR7-21 approve household 200" "200" "$(JPOST $API/admin/applications/$A2/approve "{\"membershipPeriodId\":$P2526,\"membershipTypeId\":$T_HH,\"decisionDate\":\"$D2AGO\"}")"
    M7B=$(body | jsq "j['membershipId']")
    check "CR7-21b both applicants vote" "2" "$(psqlq "SELECT count(*) FROM membership_person WHERE membership_id=$M7B AND has_voting_rights")"

    # partner variant: covered, never voting, doesn't count against max people
    A3MAIL="apply3.$$@example.com"
    code -X POST $API/apply -H 'Content-Type: application/json' -d "{\"membershipTypeId\":$T_SINGLE,\"givenName\":\"Solo\",\"familyName\":\"Pt$$\",\"email\":\"$A3MAIL\",\"secondPerson\":{\"givenName\":\"Pal\",\"familyName\":\"Pt$$\",\"relationship\":\"PARTNER\"}}" >/dev/null
    TOK3=$(mailpit_text "$A3MAIL" | grep -o 'confirm=[A-Za-z0-9_-]*' | head -1 | cut -d= -f2)
    code -X POST $API/apply/confirm -H 'Content-Type: application/json' -d "{\"token\":\"$TOK3\"}" >/dev/null
    A3=$(psqlq "SELECT a.application_id FROM membership_application a JOIN membership_application_person p ON p.application_id=a.application_id WHERE p.email='$A3MAIL'")
    check "CR7-22 approve single+partner 200" "200" "$(JPOST $API/admin/applications/$A3/approve "{\"membershipPeriodId\":$P2526,\"membershipTypeId\":$T_SINGLE,\"decisionDate\":\"$D2AGO\"}")"
    M7C=$(body | jsq "j['membershipId']")
    check "CR7-22b partner covered not voting" "2|1" "$(psqlq "SELECT count(*)||'|'||count(*) FILTER (WHERE has_voting_rights) FROM membership_person WHERE membership_id=$M7C")"

    # rejection: decision recorded, notice sent, register untouched
    A4MAIL="apply4.$$@example.com"
    code -X POST $API/apply -H 'Content-Type: application/json' -d "{\"membershipTypeId\":$T_SINGLE,\"givenName\":\"Rex\",\"familyName\":\"Rej$$\",\"email\":\"$A4MAIL\"}" >/dev/null
    TOK4=$(mailpit_text "$A4MAIL" | grep -o 'confirm=[A-Za-z0-9_-]*' | head -1 | cut -d= -f2)
    code -X POST $API/apply/confirm -H 'Content-Type: application/json' -d "{\"token\":\"$TOK4\"}" >/dev/null
    A4=$(psqlq "SELECT a.application_id FROM membership_application a JOIN membership_application_person p ON p.application_id=a.application_id WHERE p.email='$A4MAIL'")
    check "CR7-23 reject 200"            "200" "$(JPOST $API/admin/applications/$A4/reject "{\"decisionDate\":\"$D2AGO\",\"reason\":\"internal only\"}")"
    check "CR7-23b register untouched"   "0" "$(psqlq "SELECT count(*) FROM person WHERE family_name='Rej$$'")"
    check "CR7-23c rejection notice sent" "2" "$(mailpit_count "$A4MAIL")"
    check "CR7-23d reason never emailed" "0" "$(mailpit_text "$A4MAIL" | grep -c 'internal only')"

    # delete: junk yes, decided records never
    A6MAIL="apply6.$$@example.com"
    code -X POST $API/apply -H 'Content-Type: application/json' -d "{\"membershipTypeId\":$T_SINGLE,\"givenName\":\"Junk\",\"familyName\":\"Del$$\",\"email\":\"$A6MAIL\"}" >/dev/null
    A6=$(psqlq "SELECT a.application_id FROM membership_application a JOIN membership_application_person p ON p.application_id=a.application_id WHERE p.email='$A6MAIL'")
    check "CR7-24 delete junk 204"       "204" "$(code -X DELETE $API/admin/applications/$A6 -H "Authorization: Bearer $ADMIN")"
    check "CR7-24b cascade took people"  "0" "$(psqlq "SELECT count(*) FROM membership_application_person WHERE application_id=$A6")"
    check "CR7-24c delete decided 409"   "409" "$(code -X DELETE $API/admin/applications/$A1 -H "Authorization: Bearer $ADMIN")"

    # duplicate soft-flag: same name as the person CR7-17 created
    A7MAIL="apply7.$$@example.com"
    code -X POST $API/apply -H 'Content-Type: application/json' -d "{\"membershipTypeId\":$T_SINGLE,\"givenName\":\"Ada\",\"familyName\":\"App$$\",\"email\":\"$A7MAIL\"}" >/dev/null
    A7=$(psqlq "SELECT a.application_id FROM membership_application a JOIN membership_application_person p ON p.application_id=a.application_id WHERE p.email='$A7MAIL'")
    check "CR7-25 duplicate flagged"     "Ada App$$|True" "$(curl -s $API/admin/applications/$A7 -H "Authorization: Bearer $ADMIN" | jsq "j['applicants'][0]['matches'][0]['name']+'|'+str(j['applicants'][0]['matches'][0]['hasCurrentMembership'])")"

    # the 28-day aging view: backdate the decision, the list shows it unpaid.
    # >=30, not ==30: psql's session runs on server (UTC) time, the app's JDBC
    # session on JVM (local) time, so the two current_dates can differ by a day
    psqlq "UPDATE membership_application SET decision_date = current_date - 30 WHERE application_id = $A2" >/dev/null
    check "CR7-26 aging visible"         "False|True" "$(curl -s "$API/admin/applications?status=APPROVED" -H "Authorization: Bearer $ADMIN" | jsq "next(str(a['paid'])+'|'+str(a['daysSinceDecision']>=30) for a in j['applications'] if a['id']==$A2)")"
  else
    echo "SKIP CR7-12..27 confirm/approve rows (Mailpit not reachable at $MAILPIT — the token only exists in the confirmation email)"
  fi

  # leave-as-found: the row gone means the form is closed again (deploy-dark)
  psqlq "DELETE FROM app_setting WHERE key='application_settings'" >/dev/null
else
  echo "note: psql not found — skipping CR-007 data rows"
fi

# --- CR-021: mail sandbox redirect ------------------------------------------
# The optional redirectTo field in the smtp_settings blob: while set, EVERY
# message the app sends is delivered there instead of its real recipient, with
# the original recipient named in the subject ("[SANDBOX for a@b] ...") and the
# body's first line. PAGE-only (the ENV path stays byte-for-byte CR-004/014 —
# proven by every earlier mail row riding ENV with the field absent). The block
# is self-cleaning: it ends by deleting the row, restoring ENV.
# Physical order differs from row numbers: lost-link (06) must run BEFORE the
# fixture is paid (a paid membership has no payable balance to lose a link to).
SBX="sandpit.$$@example.com"
SBXQ() { curl -s "$MAILPIT/api/v1/search?query=to:%22$SBX%22"; }

# row 1: validation — a redirectTo that isn't an email address is a 400, writes nothing
check "CR21-01 redirectTo lacking @ 400" "400" "$(JPUT $MS "{\"host\":\"$RELAY_HOST\",\"port\":$RELAY_PORT,\"security\":\"NONE\",\"from\":\"sbx.$$@memberroll.dev\",\"redirectTo\":\"not-an-email\"}")"
check "CR21-01b nothing saved"          "ENV" "$(curl -s $MS -H "Authorization: Bearer $ADMIN" | jsq "j['source']")"

# rows 2 + 9: save with username+password+redirectTo; GET echoes the field; a
# re-save with the password ABSENT keeps it — the CR-014 keep rule is untouched
# by the new field, so the sandbox round trip never needs the secret retyped
check "CR21-02 PUT redirectTo 200"      "200" "$(JPUT $MS "{\"host\":\"$RELAY_HOST\",\"port\":$RELAY_PORT,\"security\":\"NONE\",\"from\":\"sbx.$$@memberroll.dev\",\"username\":\"sbxuser\",\"password\":\"sbx-secret-$$\",\"redirectTo\":\"$SBX\"}")"
G21=$(curl -s $MS -H "Authorization: Bearer $ADMIN")
check "CR21-02b GET echoes redirectTo"  "$SBX" "$(echo "$G21" | jsq "j['redirectTo']")"
check "CR21-02c source PAGE"            "PAGE" "$(echo "$G21" | jsq "j['source']")"
check "CR21-02d passwordSet true"       "True" "$(echo "$G21" | jsq "j['passwordSet']")"
check "CR21-09 re-PUT absent password 200" "200" "$(JPUT $MS "{\"host\":\"$RELAY_HOST\",\"port\":$RELAY_PORT,\"security\":\"NONE\",\"from\":\"sbx.$$@memberroll.dev\",\"username\":\"sbxuser\",\"redirectTo\":\"$SBX\"}")"
G21B=$(curl -s $MS -H "Authorization: Bearer $ADMIN")
check "CR21-09b password survives sandbox save" "True" "$(echo "$G21B" | jsq "j['passwordSet']")"
check "CR21-09c redirectTo survives too" "$SBX" "$(echo "$G21B" | jsq "j['redirectTo']")"
# Mailpit advertises no AUTH (a username would make every send fail), so the
# send rows run an auth-free sandbox blob: password cleared, username dropped
check "CR21-02e auth-free sandbox blob 200" "200" "$(JPUT $MS "{\"host\":\"$RELAY_HOST\",\"port\":$RELAY_PORT,\"security\":\"NONE\",\"from\":\"sbx.$$@memberroll.dev\",\"password\":\"\",\"redirectTo\":\"$SBX\"}")"

if curl -s -m 2 -o /dev/null "$MAILPIT/api/v1/messages"; then
  # fixture: a member with a real (fixture) address; membership stays unpaid
  # until after the lost-link row
  MEM21="cr21mem.$$@example.com"
  SXPER=$(curl -s $API/admin/periods -H "Authorization: Bearer $ADMIN")
  P21=$(echo "$SXPER" | jsq "next(p['id'] for p in j['periods'] if p['name']=='2025-2026')")
  T21=$(echo "$SXPER" | jsq "next(pr['typeId'] for p in j['periods'] if p['name']=='2025-2026' for pr in p['prices'] if pr['type']=='SINGLE')")
  JPOST $API/admin/people "{\"givenName\":\"Sandy\",\"familyName\":\"Sbx$$\",\"emails\":[{\"email\":\"$MEM21\",\"isPrimary\":true}]}" >/dev/null; SXP=$(body | jsq "j['id']")
  JPOST $API/admin/households "{\"householdName\":\"Sbx$$ HH\",\"primaryContactPersonId\":$SXP}" >/dev/null; SXH=$(body | jsq "j['id']")
  JPOST $API/admin/memberships "{\"householdId\":$SXH,\"membershipPeriodId\":$P21,\"membershipTypeId\":$T21}" >/dev/null; SXM=$(body | jsq "j['id']")

  # row 6: the guest-triggered lost-link mail is redirected — no admin in the loop
  check "CR21-06 lost-link 202"         "202" "$(code -X POST $API/pay/lost-link -H 'Content-Type: application/json' -d "{\"email\":\"$MEM21\"}")"
  check "CR21-06b pay-link at sandbox"  "yes" "$(mailpit_reaches "$SBX" 1)"
  check "CR21-06c marker names member"  "true" "$(SBXQ | jsq "str(j['messages'][0]['Subject'].startswith('[SANDBOX for $MEM21]')).lower()")"

  # row 3: the CR-012 receipt email — marker in subject + first line, real
  # address gets NOTHING
  JPOST $API/admin/payments "{\"receivedDate\":\"$(date +%F)\",\"amountCents\":4500,\"method\":\"BANK_TRANSFER\",\"payerPersonId\":$SXP,\"allocations\":[{\"type\":\"MEMBERSHIP\",\"membershipId\":$SXM,\"amountCents\":4500}]}" >/dev/null; SXPAY=$(body | jsq "j['id']")
  check "CR21-03 receipt POST 202"      "202" "$(code -X POST $API/admin/payments/$SXPAY/receipt -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{}')"
  check "CR21-03b arrives at sandbox"   "yes" "$(mailpit_reaches "$SBX" 2)"
  check "CR21-03c subject carries marker" "true" "$(SBXQ | jsq "str(any(m['Subject'].startswith('[SANDBOX for $MEM21]') and 'receipt' in m['Subject'] for m in j['messages'])).lower()")"
  M21R=$(SBXQ | jsq "next(m['ID'] for m in j['messages'] if 'receipt' in m['Subject'])")
  check "CR21-03d body first line names member" "SANDBOX REDIRECT — this message was addressed to: $MEM21" "$(curl -s "$MAILPIT/api/v1/message/$M21R" | jsq "j['Text'].splitlines()[0]")"
  check "CR21-03e real address got nothing" "yes" "$(mailpit_stays "$MEM21" "0")"

  # row 4: the CR-017 card email is redirected AND the PNG attachment survives
  check "CR21-04 card email 202"        "202" "$(code -X POST $API/admin/memberships/$SXM/card/$SXP/email -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{}')"
  check "CR21-04b redirected"           "yes" "$(mailpit_reaches "$SBX" 3)"
  check "CR21-04c attachment survives"  "true" "$(SBXQ | jsq "str(any(m['Attachments']==1 and m['Subject'].startswith('[SANDBOX for $MEM21]') for m in j['messages'])).lower()")"

  # row 5: a CR-005 segment send (2 recipients) — both messages at the sandbox,
  # while the send log records the REAL recipients (intent vs transport)
  JPOST $API/admin/email/templates "{\"name\":\"Sbx$$ tpl\",\"subject\":\"Sbx$$ hello\",\"body\":\"Dear {{givenName}},\"}" >/dev/null; SXTPL=$(body | jsq "j['id']")
  JPOST $API/admin/periods "{\"name\":\"Sbx$$ period\",\"startDate\":\"$(date -d '-10 days' +%F)\",\"endDate\":\"$(date -d '+355 days' +%F)\",\"prices\":[{\"type\":\"SINGLE\",\"amountCents\":4500},{\"type\":\"HOUSEHOLD\",\"amountCents\":6500},{\"type\":\"LIFE\",\"amountCents\":0}]}" >/dev/null; SXPID=$(body | jsq "j['id']")
  A21="cr21a.$$@em.test"; B21="cr21b.$$@em.test"
  for pair in "Ann:$A21" "Bob:$B21"; do
    nm=${pair%%:*}; em=${pair##*:}
    JPOST $API/admin/people "{\"givenName\":\"$nm\",\"familyName\":\"Sbx$$ Seg\",\"emails\":[{\"email\":\"$em\",\"isPrimary\":true}]}" >/dev/null; sp=$(body | jsq "j['id']")
    JPOST $API/admin/households "{\"householdName\":\"Sbx$$ $nm\",\"primaryContactPersonId\":$sp}" >/dev/null; sh=$(body | jsq "j['id']")
    JPOST $API/admin/memberships "{\"householdId\":$sh,\"membershipPeriodId\":$SXPID,\"membershipTypeId\":$T21}" >/dev/null
  done
  check "CR21-05 segment send 201"      "201" "$(JPOST $API/admin/email/sends "{\"templateId\":$SXTPL,\"periodId\":$SXPID,\"communicationType\":\"RENEWAL\"}")"
  SXSEND=$(body | jsq "j['id']")
  check "CR21-05b completes"            "COMPLETE" "$(poll_send_status $SXSEND)"
  check "CR21-05c both at sandbox"      "yes" "$(mailpit_reaches "$SBX" 5)"
  check "CR21-05d markers name both"    "true" "$(SBXQ | jsq "str(any('[SANDBOX for $A21]' in m['Subject'] for m in j['messages']) and any('[SANDBOX for $B21]' in m['Subject'] for m in j['messages'])).lower()")"
  check "CR21-05e A real address got nothing" "yes" "$(mailpit_stays "$A21" "0")"
  check "CR21-05f B real address got nothing" "yes" "$(mailpit_stays "$B21" "0")"
  if [ "$PSQL_OK" = 1 ]; then
    check "CR21-05g log records real recipients" "2" "$(psqlq "SELECT count(*) FROM email_send_recipient WHERE email_send_id=$SXSEND AND status='SENT' AND email IN ('$A21','$B21')")"
  fi

  # row 7: the settings page's own test button honours the redirect — the
  # sandbox path is provable from the page
  T21J=$(curl -s -X POST $MS/test -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d "{\"host\":\"$RELAY_HOST\",\"port\":$RELAY_PORT,\"security\":\"NONE\",\"from\":\"sbx.$$@memberroll.dev\",\"redirectTo\":\"$SBX\",\"to\":\"cr21probe.$$@example.com\"}")
  check "CR21-07 test ok true"          "True" "$(echo "$T21J" | jsq "j['ok']")"
  check "CR21-07b test redirected"      "yes" "$(mailpit_reaches "$SBX" 6)"
  check "CR21-07c marker names typed address" "true" "$(SBXQ | jsq "str(any(m['Subject'].startswith('[SANDBOX for cr21probe.$$@example.com]') for m in j['messages'])).lower()")"
  check "CR21-07d typed address got nothing" "yes" "$(mailpit_stays "cr21probe.$$@example.com" "0")"

  # row 8: blank clears — live mail restored, no marker
  check "CR21-08 PUT blank redirectTo 200" "200" "$(JPUT $MS "{\"host\":\"$RELAY_HOST\",\"port\":$RELAY_PORT,\"security\":\"NONE\",\"from\":\"sbx.$$@memberroll.dev\",\"redirectTo\":\"\"}")"
  check "CR21-08b GET redirectTo cleared" "None" "$(curl -s $MS -H "Authorization: Bearer $ADMIN" | jsq "j['redirectTo']")"
  code -X POST $API/admin/payments/$SXPAY/receipt -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{}' >/dev/null
  check "CR21-08c live mail to real address" "yes" "$(mailpit_reaches "$MEM21" 1)"
  check "CR21-08d no marker on live mail" "False" "$(curl -s "$MAILPIT/api/v1/search?query=to:%22$MEM21%22" | jsq "str('SANDBOX' in j['messages'][0]['Subject'])")"
else
  echo "SKIP CR21-03..08 send rows (Mailpit not reachable at $MAILPIT)"
fi

# unconditional cleanup: the blob goes away entirely → ENV, the resting state
# every other mail row relies on
code -X DELETE $MS -H "Authorization: Bearer $ADMIN" >/dev/null
check "CR21-10 settings cleared at end" "ENV" "$(curl -s $MS -H "Authorization: Bearer $ADMIN" | jsq "j['source']")"

# --- static pages ---------------------------------------------------------------
check "CR4-25 pay page served"         "200" "$(code $ORIGIN/server/web/pay.html)"
check "CR4-25b pay.js served"          "200" "$(code $ORIGIN/server/web/pay.js)"
check "CR7-28 apply page served"       "200" "$(code $ORIGIN/server/web/apply.html)"
check "CR7-28b apply.js served"        "200" "$(code $ORIGIN/server/web/apply.js)"

check "32 web page 200"                "200" "$(code $ORIGIN/server/web/)"
check "33 admin page 200"              "200" "$(code $ORIGIN/server/admin/)"
check "33b admin users page 200"       "200" "$(code $ORIGIN/server/admin/users.html)"
check "33c admin import page 200"      "200" "$(code $ORIGIN/server/admin/import.html)"
check "33d admin css served"           "200" "$(code $ORIGIN/server/admin/admin.css)"
check "33e admin new-member page 200"  "200" "$(code $ORIGIN/server/admin/new-member.html)"
check "33f admin email page 200"        "200" "$(code $ORIGIN/server/admin/email.html)"
check "33g admin committee page 200"    "200" "$(code $ORIGIN/server/admin/committee.html)"
check "33h admin mail-settings page 200" "200" "$(code $ORIGIN/server/admin/mail-settings.html)"
check "33i admin reports page 200"       "200" "$(code $ORIGIN/server/admin/reports.html)"
check "33j admin applications page 200"  "200" "$(code $ORIGIN/server/admin/applications.html)"
check "34 auth.js served"              "200" "$(code $ORIGIN/server/shared/auth.js)"

echo
echo "PASS=$PASS FAIL=$FAIL"
rm -f /tmp/body.$$
[ "$FAIL" = 0 ]
