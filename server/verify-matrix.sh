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
set -u
API=http://localhost:${PORT:-18080}/server/api
KC=http://localhost:${KEYCLOAK_PORT:-18081}/realms/memberroll/protocol/openid-connect/token
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

# --- notes CRUD + ownership --------------------------------------------------
check "10 notes guest 401"             "401" "$(code $API/notes)"
check "11 user PUT note 200"           "200" "$(code -X PUT $API/notes/alpha -H "Authorization: Bearer $USER" -H 'Content-Type: application/json' -d '{"title":"T1","body":"B1"}')"
check "12 user list has note"          "1" "$(curl -s $API/notes -H "Authorization: Bearer $USER" | jsq "len(j)")"
check "13 user GET own note 200"       "200" "$(code $API/notes/alpha -H "Authorization: Bearer $USER")"
check "13b note title"                 "T1" "$(body | jsq "j['title']")"
check "14 viewer GET same id 404"      "404" "$(code $API/notes/alpha -H "Authorization: Bearer $VIEWER")"
check "15 viewer foreign owner 403"    "403" "$(code "$API/notes/alpha?owner=$USER_SUB" -H "Authorization: Bearer $VIEWER")"
check "16 admin foreign owner 200"     "200" "$(code "$API/notes/alpha?owner=$USER_SUB" -H "Authorization: Bearer $ADMIN")"
check "17 bad id 400"                  "400" "$(code -X PUT "$API/notes/bad.id" -H "Authorization: Bearer $USER" -H 'Content-Type: application/json' -d '{"title":"x"}')"
check "18 bad body 400"                "400" "$(code -X PUT $API/notes/beta -H "Authorization: Bearer $USER" -H 'Content-Type: application/json' -d '{"nope":1}')"
check "19 update note 200"             "200" "$(code -X PUT $API/notes/alpha -H "Authorization: Bearer $USER" -H 'Content-Type: application/json' -d '{"title":"T2","body":"B2"}')"
check "19b updated title"              "T2" "$(curl -s $API/notes/alpha -H "Authorization: Bearer $USER" | jsq "j['title']")"
check "20 admin DELETE foreign 200"    "200" "$(code -X DELETE "$API/notes/alpha?owner=$USER_SUB" -H "Authorization: Bearer $ADMIN")"
check "21 user GET deleted 404"        "404" "$(code $API/notes/alpha -H "Authorization: Bearer $USER")"
check "22 DELETE absent 404"           "404" "$(code -X DELETE $API/notes/alpha -H "Authorization: Bearer $USER")"

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
psqlq() { PGPASSWORD=memberroll psql -h localhost -p "${POSTGRES_PORT:-5433}" -U memberroll -d memberroll -tAc "$1" 2>/dev/null; }
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
  # LIFE type (idempotent) — no type-management API by design
  psqlq "INSERT INTO membership_type (name, description, minimum_people, maximum_people) SELECT 'LIFE','Life member',1,NULL WHERE NOT EXISTS (SELECT 1 FROM membership_type WHERE name='LIFE')" >/dev/null
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
# fixtures' $P2526/$T_SINGLE/$T_HH/$T_LIFE/$TGTID: LIFE was added via psql
# AFTER $P2526 was seeded by V2, so it has no membership_type_price row there
# (the CR10-11 "no price" fixture) but IS priced (at 0) in $TGTID (CR3-05,
# the CR10-12 zero-due fixture).
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

  # CR10-11: type with no price for the period
  check "CR10-11 no price for period 400" "400" "$(JPOST $API/admin/new-member "{\"person\":{\"givenName\":\"Yun\",\"familyName\":\"${NM}I\"},\"membershipPeriodId\":$P2526,\"membershipTypeId\":$T_LIFE}")"

  # CR10-12: zero-due type (LIFE, priced in TGTID) -> 201, ACTIVE, no payment row
  check "CR10-12 LIFE zero-due 201"      "201" "$(JPOST $API/admin/new-member "{\"person\":{\"givenName\":\"Zane\",\"familyName\":\"${NM}J\"},\"membershipPeriodId\":$TGTID,\"membershipTypeId\":$T_LIFE}")"
  check "CR10-12b status ACTIVE"         "ACTIVE" "$(body | jsq "j['status']")"
  NM_J_MEM=$(body | jsq "j['membershipId']")
  check "CR10-12c approved today"        "t" "$(psqlq "SELECT approved_date = current_date FROM membership WHERE membership_id=$NM_J_MEM")"
  check "CR10-12d no payment row"        "0" "$(psqlq "SELECT count(*) FROM payment_allocation WHERE membership_id=$NM_J_MEM")"

  # CR10-13: an engineered period whose late_joining_cutoff is always
  # yesterday (relative to today, not a hardcoded seed date like $P2526's —
  # that made this check pass or fail on wall-clock timing rather than
  # testing the feature on its own terms). Via the periods API (like
  # CR3-05's TGT period) rather than psql, since psql -tAc can't cleanly
  # capture a bare INSERT...RETURNING id (the command-tag line rides along).
  check "CR10-13setup cutoff period 201" "201" "$(JPOST $API/admin/periods "{\"name\":\"NmCutoff$$\",\"startDate\":\"$(date -d '-400 days' +%F)\",\"endDate\":\"$(date -d '+300 days' +%F)\",\"lateJoiningCutoff\":\"$(date -d '-1 day' +%F)\",\"prices\":[{\"type\":\"SINGLE\",\"amountCents\":4500},{\"type\":\"HOUSEHOLD\",\"amountCents\":6500},{\"type\":\"LIFE\",\"amountCents\":0}]}")"
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

# --- static pages ---------------------------------------------------------------
check "CR4-25 pay page served"         "200" "$(code http://localhost:${PORT:-18080}/server/web/pay.html)"
check "CR4-25b pay.js served"          "200" "$(code http://localhost:${PORT:-18080}/server/web/pay.js)"

check "32 web page 200"                "200" "$(code http://localhost:${PORT:-18080}/server/web/)"
check "33 admin page 200"              "200" "$(code http://localhost:${PORT:-18080}/server/admin/)"
check "33b admin users page 200"       "200" "$(code http://localhost:${PORT:-18080}/server/admin/users.html)"
check "33c admin import page 200"      "200" "$(code http://localhost:${PORT:-18080}/server/admin/import.html)"
check "33d admin css served"           "200" "$(code http://localhost:${PORT:-18080}/server/admin/admin.css)"
check "33e admin new-member page 200"  "200" "$(code http://localhost:${PORT:-18080}/server/admin/new-member.html)"
check "34 auth.js served"              "200" "$(code http://localhost:${PORT:-18080}/server/shared/auth.js)"

echo
echo "PASS=$PASS FAIL=$FAIL"
rm -f /tmp/body.$$
[ "$FAIL" = 0 ]
