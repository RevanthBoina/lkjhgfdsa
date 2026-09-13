#!/usr/bin/env bash
set -e

echo "=== Running Aniob CI Preflight Inspection ==="

# 1. Billing & Payment Zero-Reference Guard
echo "[Guard 1/4] Verifying zero billing or payment SDKs..."
bash scripts/check_no_billing.sh

# 2. Skill YAML <-> Assets Sync Guard
echo "[Guard 2/4] Verifying YAML skill library and assets sync..."
bash scripts/skill-assets-sync-check.sh

# 3. Core Agent Purity Guard (zero android.* imports in core-agent)
echo "[Guard 3/4] Verifying core-agent purity..."
BAD_CORE_IMPORTS=$(grep -rn "import android\." core-agent/src/main/kotlin/ 2>/dev/null || true)
if [ -n "$BAD_CORE_IMPORTS" ]; then
    echo "ERROR: core-agent purity violation detected:"
    echo "$BAD_CORE_IMPORTS"
    exit 1
fi
echo "Core-agent purity verified (0 android.* imports)."

# 4. Check for empty catch blocks
echo "[Guard 4/4] Checking for empty catch blocks..."
EMPTY_CATCHES=$(grep -rn "catch\\s*(.*)\\s*{\\s*}" app/src/ core-agent/src/ 2>/dev/null || true)
if [ -n "$EMPTY_CATCHES" ]; then
    echo "WARNING: Empty catch blocks detected:"
    echo "$EMPTY_CATCHES"
fi

echo "=== ALL PREFLIGHT CI GUARDS PASSED SUCCESSFULLY ==="
exit 0
