#!/usr/bin/env bash
#
# GUARD 7/7 — MODEL CATALOG URL HEALTH
# ===================================
# Fails if any catalog URL returns anything other than 206 for an unauthenticated Range request.
# Gated repos (401/403) or broken URLs (404) are strictly disallowed from shipping in the catalog.
#

set -euo pipefail

URLS=(
  "https://huggingface.co/nomic-ai/nomic-embed-text-v1.5-GGUF/resolve/main/nomic-embed-text-v1.5.Q4_K_M.gguf"
  "https://huggingface.co/unsloth/Phi-4-mini-instruct-GGUF/resolve/main/Phi-4-mini-instruct-Q4_K_M.gguf"
  "https://huggingface.co/ggml-org/gemma-3-4b-it-GGUF/resolve/main/gemma-3-4b-it-Q4_K_M.gguf"
  "https://huggingface.co/unsloth/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf"
  "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf"
  "https://huggingface.co/Qwen/Qwen2.5-Coder-7B-Instruct-GGUF/resolve/main/qwen2.5-coder-7b-instruct-q4_k_m.gguf"
  "https://huggingface.co/bartowski/Mistral-7B-Instruct-v0.3-GGUF/resolve/main/Mistral-7B-Instruct-v0.3-Q4_K_M.gguf"
  "https://huggingface.co/ggml-org/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf"
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
