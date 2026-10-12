#!/usr/bin/env bash
#
# GUARD 7/7 — MODEL CATALOG URL HEALTH
# ===================================
# Fails if any catalog URL returns anything other than 206 for an unauthenticated Range request.
# Gated repos (401/403) or broken URLs (404) are strictly disallowed from shipping in the catalog.
#

set -euo pipefail

URLS=(
  "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm"
  "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
  "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm"
  "https://huggingface.co/litert-community/Phi-4-mini-instruct/resolve/main/Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm"
)

echo "=== Verifying Catalog Models Consistency & Defaults ==="
if ! diff -q configs/models.yaml app/src/main/assets/aniob_config/models.yaml >/dev/null 2>&1 || \
   ! diff -q configs/models.yaml android-agent/configs/models.yaml >/dev/null 2>&1; then
  echo "ERROR: Catalog models.yaml files drift across mirrored locations."
  exit 1
fi
echo "OK: All 3 catalog copies agree."

DECLARED_IDS=$(grep -E '^[[:space:]]*-[[:space:]]*id:' configs/models.yaml | sed -E 's/.*id:[[:space:]]*"?([^"]*)"?/\1/')
for key in ram_4gb ram_6gb ram_8gb ram_8gb_coding ram_8gb_balanced; do
  val=$(grep -E "^[[:space:]]*${key}:" configs/models.yaml | sed -E "s/.*${key}:[[:space:]]*\"?([^\"]*)\"?/\1/")
  if [ -z "$val" ]; then
    echo "ERROR: Missing default mapping for key: $key"
    exit 1
  fi
  if ! echo "$DECLARED_IDS" | grep -qx "$val"; then
    echo "ERROR: Default '$key' points to undeclared model ID '$val'"
    exit 1
  fi
  echo "OK: Default '$key' -> '$val' (verified declared)"
done

echo "=== Verifying Catalog Model URLs (Unauthenticated HTTP 206 Range Check) ==="
FAILED=0

for url in "${URLS[@]}"; do
  # Follow redirects (-L) and perform a byte range request for bytes 0-0
  status=$(curl -s -L -o /dev/null -w "%{http_code}" -r 0-0 "$url" || echo "000")
  if [ "$status" != "206" ]; then
    echo "ERROR: Catalog URL failed range check: HTTP $status (expected 206)"
    echo "       URL: $url"
    FAILED=1
  else
    echo "OK: [HTTP 206] $url"
  fi
done

if [ "$FAILED" -ne 0 ]; then
  echo "FAIL: One or more catalog URLs failed health check."
  exit 1
fi

echo "SUCCESS: All catalog URLs verified healthy with unauthenticated range support."
exit 0
