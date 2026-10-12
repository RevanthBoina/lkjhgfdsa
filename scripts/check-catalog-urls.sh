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
