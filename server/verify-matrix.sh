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

# --- static pages ---------------------------------------------------------------
check "32 web page 200"                "200" "$(code http://localhost:${PORT:-18080}/server/web/)"
check "33 admin page 200"              "200" "$(code http://localhost:${PORT:-18080}/server/admin/)"
check "34 auth.js served"              "200" "$(code http://localhost:${PORT:-18080}/server/shared/auth.js)"

echo
echo "PASS=$PASS FAIL=$FAIL"
rm -f /tmp/body.$$
[ "$FAIL" = 0 ]
