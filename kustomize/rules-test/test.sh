#!/usr/bin/env bash
# For every overlay in targets.txt, send a matrix of version-header
# combinations through the local Traefik and check which backend answers.
# The rule under test: where core 1 still serves a package as V3, the client-api
# (core 2) gets the request only when the client asks for V4; packages without
# any V3 consumer go to the client-api whatever the headers.
# Usage: ./render.sh && docker compose up -d && ./test.sh
set -o pipefail
cd "$(dirname "$0")"
T=http://127.0.0.1:18080

# wait until Traefik has loaded every router from dynamic.yaml
want=$(grep -c 'rule:' dynamic.yaml)
for _ in $(seq 1 60); do
  n=$(curl -s --retry-connrefused http://127.0.0.1:18081/api/http/routers 2>/dev/null | grep -o '"status":"enabled"' | wc -l)
  [ "$n" -ge "$want" ] && break
  sleep 1
done
[ "$n" -ge "$want" ] || { echo "only $n of $want routers enabled"; curl -s http://127.0.0.1:18081/api/http/routers; exit 1; }

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

V3=("" "x-fint-model-version: V3" "x-fint-model-version-override: V3" "x-fint-version-override: V3" "x-fint-model-version-override: v3|x-fint-model-version: V4" "x-fint-version-override: V3|x-fint-model-version: V4")
V4=("x-fint-model-version: V4" "x-fint-model-version: v4" "x-fint-model-version-override: V4" "x-fint-version-override: V4" "x-fint-model-version-override: V4|x-fint-model-version: V3")

while read -r env org host id admin; do
  echo "== $env/$org ($id @ $host)"
  # packages core 1 still serves as V3: only a V4 client reaches the client-api
  for path in /utdanning/utdanningsprogram/ /utdanning/elev/ /utdanning/kodeverk/ /utdanning/timeplan/ /utdanning/vurdering/ /okonomi/faktura/ /okonomi/kodeverk/; do
    for hs in "${V3[@]}"; do check core1 "$host" "$path" "$id" "$hs"; done
    for hs in "${V4[@]}"; do check client-api "$host" "$path" "$id" "$hs"; done
  done
  # packages with no V3 consumer anywhere: the client-api takes them whatever the headers
  for path in /utdanning/larling/ /utdanning/ot/ /okonomi/regnskap/ /personvern/samtykke/ /personvern/kodeverk/ /ressurs/datautstyr/ /felles/person/; do
    for hs in "${V3[@]}" "${V4[@]}"; do check client-api "$host" "$path" "$id" "$hs"; done
  done
  # never the client-api, whatever the headers
  check core1 "$host" /felles/kodeverk/ "$id" "x-fint-model-version: V4"
  check core1 "$host" /utdanning/utdanningsprogram/provider/ "$id" "x-fint-model-version: V4"
  # administrasjon (and arkiv): wholesale to the client-api only when the overlay says so
  if [ "$admin" = 1 ]; then
    check client-api "$host" /administrasjon/personal/ "$id"
    check client-api "$host" /administrasjon/personal/ "$id" "x-fint-model-version: V3"
  else
    check core1 "$host" /administrasjon/personal/ "$id"
    check core1 "$host" /administrasjon/personal/ "$id" "x-fint-model-version: V4"
    check core1 "$host" /arkiv/noark/ "$id" "x-fint-model-version: V4"
  fi
  # a sub-org follows its parent; in api (no shared catch-all) a look-alike org does not
  check client-api "$host" /utdanning/utdanningsprogram/ "sub.$id" "x-fint-model-version: V4"
  check core1 "$host" /utdanning/utdanningsprogram/ "sub.$id"
  [ "$env" = api ] && check core1 "$host" /utdanning/utdanningsprogram/ "not$id" "x-fint-model-version: V4"
done < targets.txt

echo
if [ $fail = 0 ]; then echo "ALL OK"; else echo "FAILURES"; exit 1; fi
