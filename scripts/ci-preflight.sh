#!/usr/bin/env bash
set -e

echo "=== Running Aniob CI Preflight Inspection ==="

# 1. Billing & Payment Zero-Reference Guard
echo "[Guard 1/6] Verifying zero billing or payment SDKs..."
bash scripts/check_no_billing.sh

# 2. Skill YAML <-> Assets Sync Guard
echo "[Guard 2/6] Verifying YAML skill library and assets sync..."
bash scripts/skill-assets-sync-check.sh

# 3. Core Agent Purity Guard (zero android.* imports in core-agent)
echo "[Guard 3/6] Verifying core-agent purity..."
BAD_CORE_IMPORTS=$(grep -rn "import android\." core-agent/src/main/kotlin/ 2>/dev/null || true)
if [ -n "$BAD_CORE_IMPORTS" ]; then
    echo "ERROR: core-agent purity violation detected:"
    echo "$BAD_CORE_IMPORTS"
    exit 1
fi
echo "Core-agent purity verified (0 android.* imports)."

# 4. Check for empty catch blocks
echo "[Guard 4/6] Checking for empty catch blocks..."
EMPTY_CATCHES=$(grep -rn "catch\\s*(.*)\\s*{\\s*}" app/src/ core-agent/src/ 2>/dev/null || true)
if [ -n "$EMPTY_CATCHES" ]; then
    echo "WARNING: Empty catch blocks detected:"
    echo "$EMPTY_CATCHES"
fi

# 5. Prompt 4 Master-Spec smoke M0 (UI-TARS / Ferret-UI / CogAgent / SeeClick)
echo "[Guard 5/6] Verifying Prompt-4 visual-perception smoke M0 pins..."
SMOKE_FAIL=0

# 5a. 60ms delta throttle + screenshot Base64 streaming in OmniRoute provider
if ! grep -q "minIntervalMs = 60L\|60L" app/src/main/java/com/aniob/app/provider/AniobOmniRouteProvider.kt 2>/dev/null; then
    echo "ERROR: OmniRoute 60ms delta throttle not found."
    SMOKE_FAIL=1
fi
if ! grep -q "data:image/jpeg;base64" app/src/main/java/com/aniob/app/provider/AniobOmniRouteProvider.kt 2>/dev/null; then
    echo "ERROR: OmniRoute screenshot Base64 vision payload not found."
    SMOKE_FAIL=1
fi
# ConnectionPool reuse via AniobHttpClientSingleton
if ! grep -q "AniobHttpClientSingleton.client" app/src/main/java/com/aniob/app/provider/AniobOmniRouteProvider.kt 2>/dev/null; then
    echo "ERROR: OmniRoute provider not reusing shared ConnectionPool."
    SMOKE_FAIL=1
fi

# 5b. Reflex intent mappings (SeeClick)
if ! grep -q "turn_on_flashlight" core-agent/src/main/kotlin/com/aniob/core/ladder/AniobIntentResolver.kt 2>/dev/null; then
    echo "ERROR: flashlight reflex intent mapping missing."
    SMOKE_FAIL=1
fi
if ! grep -q "get_device_info" core-agent/src/main/kotlin/com/aniob/core/ladder/AniobIntentResolver.kt 2>/dev/null; then
    echo "ERROR: check_battery reflex intent mapping missing."
    SMOKE_FAIL=1
fi

# 5c. Battery-low reflex (pure JVM)
if ! grep -q "fun isLowBattery" core-agent/src/main/kotlin/com/aniob/core/tools/AniobBatteryHealth.kt 2>/dev/null; then
    echo "ERROR: isLowBattery reflex missing."
    SMOKE_FAIL=1
fi

# 5d. Watchdog N=3 same-action detection
if ! grep -q "actionKey" core-agent/src/main/kotlin/com/aniob/core/verifier/AniobWatchdog.kt 2>/dev/null || ! grep -q "takeLast(loopThreshold)" core-agent/src/main/kotlin/com/aniob/core/verifier/AniobWatchdog.kt 2>/dev/null; then
    echo "ERROR: Watchdog same-action-3x detection missing."
    SMOKE_FAIL=1
fi

# 5e. Package receiver reflex
if [ ! -f app/src/main/java/com/aniob/app/receiver/AniobPackageReceiver.kt ]; then
    echo "ERROR: AniobPackageReceiver reflex missing."
    SMOKE_FAIL=1
fi

# 5f. Safety interceptor confirm-with-user + notification OTP blocklist
if ! grep -q "ConfirmWithUser" core-agent/src/main/kotlin/com/aniob/core/safety/AniobSafetyInterceptor.kt 2>/dev/null; then
    echo "ERROR: confirm_with_user safety gate missing."
    SMOKE_FAIL=1
fi
if [ ! -f app/src/main/java/com/aniob/app/service/AniobNotificationListenerService.kt ]; then
    echo "ERROR: NotificationListenerService OTP reflex missing."
    SMOKE_FAIL=1
fi

# 5g. Omniroute JsonMode gate
if ! grep -q "response_format" app/src/main/java/com/aniob/app/provider/AniobOmniRouteProvider.kt 2>/dev/null; then
    echo "ERROR: Omniroute JsonMode (response_format) missing."
    SMOKE_FAIL=1
fi

if [ "$SMOKE_FAIL" -eq 1 ]; then
    echo "ERROR: Prompt-4 smoke M0 pins failed (see above)."
    exit 1
fi
echo "Prompt-4 smoke M0 pins verified (streaming vision, reflexes, watchdog, safety)."

# 6. User-Facing Copy, Color, Touch Target, and Component Kit Guard (UX-7)
bash scripts/ux-copy-guard.sh

echo "=== ALL PREFLIGHT CI GUARDS PASSED SUCCESSFULLY ==="
exit 0
