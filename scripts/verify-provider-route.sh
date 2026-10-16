#!/usr/bin/env bash
# Register an adapter contract through the ingress and tell which gateway got
# it: the org's fint-core-adapter-gateway (new) or the shared
# fint-core-provider-gateway (old).
#
# The network gateway injects x-allowed-asset-ids from the token, so the
# request must carry a real adapter token for the org.
#
#   export TOKEN=<adapter access token>
#   FA_USERNAME=test-adapter@adapter.vlfk.no scripts/verify-provider-route.sh
#
# Both gateways answer /provider/register and /provider/status the same way,
# so the response cannot tell them apart. The script instead reads the new
# gateway's request counter for /provider/register (Kubernetes API proxy, no
# port-forward) before and after, and greps the old gateway's log for the
# contract. Needs kubectl access to the cluster.
#
# Exit code: 0 new adapter-gateway, 1 old provider-gateway, 2 undetermined.
set -euo pipefail

ENV=${ENV:-api}
ORG_ID=${ORG_ID:-vlfk.no}
ADAPTER_ID=${ADAPTER_ID:-test}
case $ENV in
  api)  HOST=api.felleskomponent.no;  CONTEXT=${CONTEXT:-aks-api-fint-2022-02-08} ;;
  beta) HOST=beta.felleskomponent.no; CONTEXT=${CONTEXT:-aks-beta-fint-2021-11-23} ;;
  *) echo "ENV must be api or beta" >&2; exit 2 ;;
esac
[ -n "${TOKEN:-}" ] || { echo "export TOKEN=<adapter access token>" >&2; exit 2; }
[ -n "${FA_USERNAME:-}" ] || { echo "set FA_USERNAME to the adapter username, e.g. test-adapter@adapter.vlfk.no" >&2; exit 2; }
NS=${ORG_ID//./-}
k() { kubectl --context "$CONTEXT" "$@"; }

new_pod=$(k get pod -n "$NS" -l app.kubernetes.io/name=fint-core-adapter-gateway \
  -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)
register_count() {
  [ -n "$new_pod" ] || { echo 0; return; }
  k get --raw "/api/v1/namespaces/$NS/pods/$new_pod:8080/proxy/actuator/prometheus" 2>/dev/null \
    | awk '/^http_server_requests_seconds_count/ && /uri="\/provider\/register"/ {s+=$NF} END {print s+0}'
}

echo "new gateway pod: ${new_pod:-<none in $NS>}"
before=$(register_count)
since=$(date -u +%Y-%m-%dT%H:%M:%SZ)

body=$(jq -n --arg id "$ADAPTER_ID" --arg org "$ORG_ID" --arg user "$FA_USERNAME" '{
  adapterId: $id, orgId: $org, username: $user,
  heartbeatIntervalInMinutes: 2, capabilities: [], eventCapabilities: []
}')
echo "POST https://$HOST/provider/register"
echo "$body" | jq -c .
resp=$(curl -sS -D - -o /dev/stdout -w '\n%{http_code}' -X POST "https://$HOST/provider/register" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$body")
code=${resp##*$'\n'}
echo "HTTP $code"
printf '%s\n' "${resp%$'\n'*}" | tr -d '\r' | grep -v -i -E '^(date|set-cookie|strict-transport|x-content|x-frame|cache-control|pragma|expires|vary|connection|keep-alive):' | sed 's/^/  /'

sleep 3
after=$(register_count)
old_hit=$(k logs -n fint-core deploy/fint-core-provider-gateway --since-time="$since" 2>/dev/null \
  | grep -F "$FA_USERNAME" | tail -3 || true)

echo
echo "new gateway /provider/register count: $before -> $after"
echo "old gateway log lines for $FA_USERNAME since $since:"
printf '%s\n' "${old_hit:-  (none)}" | sed 's/^/  /'
echo

if [ "$after" -gt "$before" ]; then
  echo "RESULT: NEW fint-core-adapter-gateway in $NS received the request (HTTP $code)"
  exit 0
elif [ -n "$old_hit" ]; then
  echo "RESULT: OLD fint-core-provider-gateway received the request. x-allowed-asset-ids was not injected or did not match $ORG_ID"
  exit 1
else
  echo "RESULT: undetermined. HTTP $code with no trace in either gateway; 401/403 usually means the network gateway rejected the token"
  exit 2
fi
