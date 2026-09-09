#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/paths.sh"

base="${1:-}"
head="${2:-HEAD}"

if [ -z "$base" ] || ! git cat-file -e "$base^{commit}" 2>/dev/null; then
  echo "consumer=true"
  echo "provider=true"
  echo "shared=true"
  exit 0
fi

changed_files=$(git diff --name-only "$base" "$head")

touches() {
  for path in $1; do
    if echo "$changed_files" | grep -q "^$path\(/\|$\)"; then
      return 0
    fi
  done
  return 1
}

echo "consumer=$(touches "$CONSUMER_PATHS" && echo true || echo false)"
echo "provider=$(touches "$PROVIDER_PATHS" && echo true || echo false)"
echo "shared=$(touches "$SHARED_PATHS" && echo true || echo false)"
