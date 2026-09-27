#!/usr/bin/env bash
#
# GUARD 7/7 — MODEL CATALOG URL HEALTH
#
# Fails if any catalog URL returns anything other than 206 for an unauthenticated Range request.
# Gated repos (401/403) or broken URLs (404) are strictly disallowed from shipping in the catalog.
#
# Plan-8.6 — THE GUARD MUST READ WHAT SHIPS.
# This script used to carry its own hardcoded URLS=( ... ) array. That array had been corrected at
# some point; configs/models.yaml had not. So the guard verified eight healthy URLs that no code
# path ever used, printed SUCCESS, and every RAM tier in getRecommendedModel() still downloaded a
# 401. Six of the eight shipped URLs were dead while CI was green.
#
# A guard that reads a copy of the data is not a guard. It now extracts the URLs from the YAML
# files that are actually loaded, and additionally asserts that every catalog file agrees.

set -euo pipefail

CATALOGS=(
  "configs/models.yaml"
  "app/src/main/assets/aniob_config/models.yaml"
  "android-agent/configs/models.yaml"
)

echo "=== Verifying Catalog Model URLs (Unauthenticated HTTP 206 Range Check) ==="

# --- 1. every catalog that ships must agree, or the app and the tools disagree about reality
REFERENCE="${CATALOGS[0]}"
for catalog in "${CATALOGS[@]}"; do
  if [ ! -f "$catalog" ]; then
    echo "ERROR: catalog file missing: $catalog"
    exit 1
  fi
  if ! diff -q "$REFERENCE" "$catalog" >/dev/null; then
    echo "ERROR: catalog drift between $REFERENCE and $catalog"
    diff "$REFERENCE" "$catalog" | head -20
    exit 1
  fi
done
echo "Catalog files agree (${#CATALOGS[@]} copies)."

# --- 2. check the URLs that are actually in the shipped catalog
mapfile -t URLS < <(grep -oE 'https://huggingface\.co[^"]*' "$REFERENCE" | sort -u)

if [ "${#URLS[@]}" -eq 0 ]; then
  echo "ERROR: no model URLs found in $REFERENCE - the guard would have passed vacuously."
  exit 1
fi
echo "Extracted ${#URLS[@]} URLs from $REFERENCE."

FAILED=0
for url in "${URLS[@]}"; do
  status=$(curl -s -o /dev/null -w "%{http_code}" -r 0-1 -L --max-time 30 "$url" || echo "000")
  if [ "$status" != "206" ]; then
    echo "ERROR: Catalog URL failed range check: HTTP $status (expected 206)"
    echo "       URL: $url"
    FAILED=1
  else
    echo "OK: [HTTP $status] $url"
  fi
done

if [ "$FAILED" -ne 0 ]; then
  echo "FAIL: One or more catalog URLs failed health check."
  echo "      These are the URLs the app downloads. A failure here means a broken RAM tier."
  exit 1
fi

echo "SUCCESS: All catalog URLs verified healthy with unauthenticated range support."
