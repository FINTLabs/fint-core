#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/paths.sh"

last_commit() {
  git log -1 --abbrev=7 --format=%h -- $1
}

echo "client=$(last_commit "$CLIENT_PATHS")"
echo "adapter_gateway=$(last_commit "$ADAPTER_GATEWAY_PATHS")"
