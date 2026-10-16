#!/usr/bin/env bash
# Verify, end to end through the network gateway, which provider backend an
# adapter for an org reaches: the org's fint-core-adapter-gateway (new) or the
# shared fint-core-provider-gateway (old).
#
# The network gateway injects x-allowed-asset-ids from the adapter's token, so
# the probe must carry a real adapter token for that org. Get the adapter's
# client id/secret and username/password from Kundeportalen.
#
# Usage:
#   FINT_CLIENT_ID=... FINT_CLIENT_SECRET=... FINT_USERNAME=... FINT_PASSWORD=... \
#     scripts/verify-provider-route.sh api|beta
#
# Exit code: 0 new adapter-gateway, 1 old provider-gateway, 2 could not tell.
set -euo pipefail

env=${1:-api}
case $env in
  api)  host=api.felleskomponent.no ;;
  beta) host=beta.felleskomponent.no ;;
  *) echo "usage: $0 api|beta" >&2; exit 2 ;;
esac
for v in FINT_CLIENT_ID FINT_CLIENT_SECRET FINT_USERNAME FINT_PASSWORD; do
  [ -n "${!v:-}" ] || { echo "$v is not set" >&2; exit 2; }
done

idp="https://idp.felleskomponent.no/nidp/oauth/nam/token?resourceServer=fint-api"

echo "1. token from $idp (scope fint-adapter, same as fint-core-adapter-common)"
token=$(curl -sS -u "$FINT_CLIENT_ID:$FINT_CLIENT_SECRET" \
  --data-urlencode grant_type=password \
  --data-urlencode scope=fint-adapter \
  --data-urlencode "username=$FINT_USERNAME" \
  --data-urlencode "password=$FINT_PASSWORD" \
  "$idp" | python3 -I -c 'import sys,json; print(json.load(sys.stdin).get("access_token",""))')
[ -n "$token" ] || { echo "   no access_token in the IdP response" >&2; exit 2; }

echo "2. assets in the token (what the network gateway should put in x-allowed-asset-ids)"
printf '%s' "$token" | cut -d. -f2 | tr '_-' '/+' | python3 -I -c '
import sys,base64,json
p=sys.stdin.read().strip(); p+="="*(-len(p)%4)
c=json.loads(base64.b64decode(p))
for k in ("fintAssets","fintAssetIDs","assets","fintOrgId","organizationId","organisationId"):
    if k in c: print("   %s: %s" % (k, c[k]))
'

echo "3. GET https://$host/provider/status with the token"
resp=$(curl -sS -o /dev/stdout -w '\n%{http_code}' -H "Authorization: Bearer $token" "https://$host/provider/status")
code=${resp##*$'\n'}
body=${resp%$'\n'*}
echo "   HTTP $code"

if [ "$code" = 200 ] && printf '%s' "$body" | grep -q "Greetings form FINTLabs"; then
  echo "   -> NEW fint-core-adapter-gateway answered"
  printf '%s' "$body" | python3 -I -c '
import sys,json
d=json.load(sys.stdin); p=d.get("corePrincipal",{})
print("   principal orgId=%s assets=%s" % (p.get("orgId"), p.get("assets")))' 2>/dev/null || true
  exit 0
elif [ "$code" = 200 ]; then
  echo "   -> OLD fint-core-provider-gateway answered (x-allowed-asset-ids was not injected, or no route for this org)"
  echo "   body: $(printf '%s' "$body" | head -c 200)"
  exit 1
else
  echo "   -> could not tell. 401/403 means the token was rejected before routing; check the credentials and the org's assets"
  echo "   body: $(printf '%s' "$body" | head -c 300)"
  exit 2
fi
