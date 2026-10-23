#!/usr/bin/env bash
# For every overlay in targets.txt, send a matrix of version-header
# combinations through the local Traefik and check which backend answers
# against the routing policy below. "core1" stands for every backend that is
# not the client-api, so core 1 and the old core 2 consumers.
# Usage: ./render.sh && docker compose up -d && ./test.sh
set -o pipefail
cd "$(dirname "$0")"
T=http://127.0.0.1:18080

# wait until Traefik has loaded every router from dynamic.yaml
want=$(grep -c 'rule:' dynamic.yaml)
for _ in $(seq 1 60); do
  n=$(curl -s --retry-connrefused "http://127.0.0.1:18081/api/http/routers?per_page=1000" 2>/dev/null | grep -o '"status":"enabled"' | wc -l)
  [ "$n" -ge "$want" ] && break
  sleep 1
done
[ "$n" -ge "$want" ] || { echo "only $n of $want routers enabled"; curl -s "http://127.0.0.1:18081/api/http/routers?per_page=1000"; exit 1; }

fail=0
# check <expected> <host> <path> <org> ["hdr1|hdr2"]
check() {
  local exp=$1 host=$2 path=$3 org=$4 hs=${5:-}
  local args=(-s -H "Host: $host" -H "x-org-id: $org")
  local old=$IFS; IFS='|'; set -- $hs; IFS=$old
  for h in "$@"; do args+=(-H "$h"); done
  local got; got=$(curl "${args[@]}" "$T$path" | sed -n 's/^Name: //p'); got=${got:-none}
  if [ "$got" = "$exp" ]; then printf '  ok    %-36s %-32s %-60s -> %s\n' "$path" "$org" "${hs:-<no version headers>}" "$got"
  else printf '  FAIL  %-36s %-32s %-60s -> %s (expected %s)\n' "$path" "$org" "${hs:-<no version headers>}" "$got" "$exp"; fail=1; fi
}

# Orgs whose administrasjon is on core 2. Their overlays send administrasjon to
# the client-api once enabled, and a disabled one is not checked. Every other
# org must never get administrasjon on the client-api.
ADMIN_ORGS="rogfk.no telemarkfylke.no trondelagfylke.no vestfoldfylke.no"
# Environments where personvern goes to the client-api. In api it stays on core 2 for now.
PERSONVERN_ENVS="beta"
# Environments where core 1 serves utdanning next to the client-api, so only V4 reaches the client-api there.
UTDANNING_GATED_ENVS="api"
# Shared test orgs the client-api serves whole, apart from felles kodeverk and okonomi on core 1.
WHOLE_ORGS="novari.no"

V3=("" "x-fint-model-version: V3" "x-fint-model-version-override: V3" "x-fint-model-version-override: v3|x-fint-model-version: V4")
V4=("x-fint-model-version: V4" "x-fint-model-version: v4" "x-fint-model-version-override: V4" "x-fint-model-version-override: V4|x-fint-model-version: V3")

has() { case " $2 " in *" $1 "*) return 0 ;; esac; return 1; }
# always <expected> <path>... gives the same backend whatever the headers
always() { local exp=$1 path hs; shift; for path; do for hs in "${V3[@]}" "${V4[@]}"; do check "$exp" "$host" "$path" "$id" "$hs"; done; done; }
# gated <path>... sends only a V4 request to the client-api
gated() {
  local path hs
  for path; do
    for hs in "${V3[@]}"; do check core1 "$host" "$path" "$id" "$hs"; done
    for hs in "${V4[@]}"; do check client-api "$host" "$path" "$id" "$hs"; done
  done
}

admin=(/administrasjon/personal/ /administrasjon/organisasjon/ /administrasjon/kodeverk/ /administrasjon/fullmakt/)
while read -r env org host id enabled; do
  echo "== $env/$org ($id @ $host)$([ "$enabled" = 0 ] && echo ', disabled')"
  whole=0; has "$id" "$WHOLE_ORGS" && whole=1
  # where core 1 serves these utdanning packages as V3, only V4 reaches the client-api. Larling and ot have no core 1.
  if has "$env" "$UTDANNING_GATED_ENVS"; then
    gated /utdanning/elev/ /utdanning/kodeverk/ /utdanning/timeplan/ /utdanning/utdanningsprogram/ /utdanning/vurdering/
  else
    always client-api /utdanning/elev/ /utdanning/kodeverk/ /utdanning/timeplan/ /utdanning/utdanningsprogram/ /utdanning/vurdering/
  fi
  always client-api /utdanning/larling/ /utdanning/ot/
  # okonomi goes by package, not by header. Core 1 serves faktura and kodeverk, core 2 serves regnskap.
  always core1 /okonomi/faktura/ /okonomi/kodeverk/
  always client-api /okonomi/regnskap/
  always client-api /felles/person/ /ressurs/datautstyr/
  always core1 /felles/kodeverk/ /utdanning/elev/provider/ /administrasjon/personal/provider/
  if [ $whole = 1 ] || has "$env" "$PERSONVERN_ENVS"; then
    always client-api /personvern/samtykke/ /personvern/kodeverk/
  else
    always core1 /personvern/samtykke/ /personvern/kodeverk/
  fi
  if [ $whole = 1 ] || { has "$id" "$ADMIN_ORGS" && [ "$enabled" = 1 ]; }; then
    always client-api "${admin[@]}"
  elif ! has "$id" "$ADMIN_ORGS"; then
    always core1 "${admin[@]}"
  else
    echo "  skip  administrasjon, the overlay is disabled"
  fi
  if [ $whole = 1 ]; then always client-api /arkiv/noark/ /profilbilde/; else always core1 /arkiv/noark/ /profilbilde/; fi
  # a sub-org follows its parent. In api (no shared catch-all) a look-alike org does not.
  check client-api "$host" /utdanning/utdanningsprogram/ "sub.$id" "x-fint-model-version: V4"
  if has "$env" "$UTDANNING_GATED_ENVS"; then want=core1; else want=client-api; fi
  check "$want" "$host" /utdanning/utdanningsprogram/ "sub.$id"
  [ "$env" = api ] && check core1 "$host" /utdanning/utdanningsprogram/ "not$id" "x-fint-model-version: V4"
done < targets.txt

echo
if [ $fail = 0 ]; then echo "ALL OK"; else echo "FAILURES"; exit 1; fi
