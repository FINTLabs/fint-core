#!/usr/bin/env bash
# Render the client-api IngressRoute of every overlay under kustomize/overlays
# (alpha excluded: it has no core 1 to fall back to) into a Traefik
# file-provider config. A catch-all "core1" router per host, at the lowest
# priority, stands in for every route that is not the client-api. No priority
# is set on the client-api routers, so Traefik ranks them by rule length as in
# the clusters. Also writes targets.txt: "<env> <org> <host> <org-id> <admin>"
# per overlay, where admin is 1 when the client-api takes administrasjon (and
# everything else) wholesale: the overlay includes components/administrasjon or
# patches in a PathPrefix(`/`) catch-all like the shared novari route.
set -euo pipefail
cd "$(dirname "$0")"
: > targets.txt
hosts=""
{
  echo "http:"
  echo "  routers:"
  for overlay in ../overlays/*/*/; do
    env=$(basename "$(dirname "$overlay")")
    org=$(basename "$overlay")
    [ "$env" = alpha ] && continue
    host=$(sed -n 's/^ *host: *//p' "$overlay/org-values.yaml")
    id=$(sed -n 's/^ *id-dotted: *//p' "$overlay/org-values.yaml")
    admin=0; grep -qE 'components/administrasjon|PathPrefix\(`/`\)' "$overlay/kustomization.yaml" && admin=1
    echo "$env $org $host $id $admin" >> targets.txt
    case " $hosts " in *" $host "*) ;; *) hosts="$hosts $host" ;; esac
    i=0
    kubectl kustomize "$overlay" \
      | awk '/^kind: IngressRoute/{ir=1} ir&&/match:/{m=1} m{sub(/^ +/," "); printf "%s", $0} m&&/services:/{m=0; print ""}' \
      | sed -e 's/^ match: //' -e 's/ priority:.*$//' -e 's/ services:.*$//' \
      | while IFS= read -r rule; do
          echo "    client-api-$env-$org-$i:"
          echo "      rule: '$rule'"
          echo "      service: client-api"
          echo "      entryPoints: [web]"
          i=$((i+1))
        done
  done
  for host in $hosts; do
    echo "    core1-${host//./-}:"
    echo "      rule: 'Host(\`$host\`) && PathPrefix(\`/\`)'"
    echo "      priority: 1"
    echo "      service: core1"
    echo "      entryPoints: [web]"
  done
  echo "  services:"
  for s in core1 client-api; do
    echo "    $s:"
    echo "      loadBalancer:"
    echo "        servers:"
    echo "          - url: http://$s:80"
  done
} > dynamic.yaml
echo "wrote dynamic.yaml with $(grep -c 'rule:' dynamic.yaml) routers for $(wc -l < targets.txt | tr -d ' ') overlays"
