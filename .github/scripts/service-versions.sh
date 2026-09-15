#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/paths.sh"

last_commit() {
  git log -1 --abbrev=7 --format=%h -- $1
}

echo "consumer=$(last_commit "$CONSUMER_PATHS")"
echo "provider=$(last_commit "$PROVIDER_PATHS")"
