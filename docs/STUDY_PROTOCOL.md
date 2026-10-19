# Empirical Study Protocol: 4-Condition Evaluation on Physical Phone

This document defines the standardized evaluation protocol for evaluating on-device agent performance across four experimental conditions on a physical Android smartphone.

Authoritative task battery definition: `com.aniob.core.study.StudyBattery` (`core-agent`).

---

## 1. Experimental Conditions

The four experimental conditions are:

| Condition Identifier | Mechanics | Description |
| :--- | :--- | :--- |
| `BASELINE` | Full observation; one action per turn | Standard sequential sense-plan-act loop with complete screen capture and single UI action per model step. |
| `INTENT_SHORTCUT_ONLY` | Deterministic intent reflex | Intent classifier resolves supported device actions directly to platform intents without invoking the model ladder. |
| `BURST_AND_FAST_OBSERVE` | Action bursts and pruned accessibility tree | Multi-action execution bursts combined with structural compaction and pruned accessibility tree inspection. |
| `FULL_HUMAN_SPEED` | Verified replay and event-driven verify | End-to-end acceleration combining cached verified replay (`Remember`) and two-tier event-driven verification (`FastVerify`). |

> [!IMPORTANT]
> Non-negotiable naming invariant: Condition IDs in telemetry, code, logs, and CSV artifacts are strictly `BASELINE`, `INTENT_SHORTCUT_ONLY`, `BURST_AND_FAST_OBSERVE`, and `FULL_HUMAN_SPEED`. Generic descriptors are used exclusively.

---

## 2. Preconditions & Device Setup

The physical evaluation device must meet the following preconditions prior to beginning the study:

1. **Target Applications Installed:**
   - Clock (`com.google.android.deskclock`)
   - Contacts (`com.google.android.contacts`)
   - Settings (`com.android.settings`)
   - Calculator (`com.google.android.calculator`)
   - Chrome (`com.android.chrome`)

2. **First-Run Setup:**
   - Chrome initial setup / welcome wizard completed.
   - Default writable local account configured for Contacts (avoid syncing delay or cloud collision).

3. **Window Animations Disabled:**
   Eliminate animation delays to ensure consistent perception timing:
   ```bash
   adb shell settings put global window_animation_scale 0
   adb shell settings put global transition_animation_scale 0
   adb shell settings put global animator_duration_scale 0
   ```

4. **Battery Optimisation Whitelist:**
   Ensure background telemetry and accessibility services are not throttled:
   ```bash
   adb shell dumpsys deviceidle whitelist +com.aniob.app
   ```

5. **Screen Stay-Awake Enabled:**
   Keep the device screen on during execution:
   ```bash
   adb shell svc power stayon true
   ```

6. **Cloud Gateway Reachability:**
   Verify cloud gateway connectivity in `Settings` (`com.aniob.app`). Ensure network latency is stable before starting.

---

## 3. Evaluation Battery (15 Tasks)

The 15 evaluation tasks span 5 standard device applications (3 tasks each). Every prompt begins with the imperative verb `Open` to guarantee deterministic classification as `DEVICE_AUTOMATION` by `AniobTaskIntentClassifier`. Every prompt contains at least one quoted string denoting explicit end-state evidence.

### Clock
1. `Open Clock and set an alarm for 7:30 AM, leaving the alarm list showing "7:30 AM".`
2. `Open Clock and set an alarm for 6:15 PM, leaving the alarm list showing "6:15 PM".`
3. `Open Clock and start a 5 minute timer, leaving the running timer showing "Pause".`

### Contacts
4. `Open Contacts and create a new contact named "John Study" with the phone number 9876543210.`
5. `Open Contacts and search for "John Study", leaving the result on screen.`
6. `Open Contacts and add the email study@example.com to "John Study", leaving the contact card showing "study@example.com".`

### Settings
7. `Open Settings and open Wi-Fi settings, leaving the screen showing "Wi-Fi".`
8. `Open Settings and open the battery screen, leaving the screen showing "Battery".`
9. `Open Settings and open Bluetooth settings, leaving the screen showing "Bluetooth".`

### Calculator
10. `Open Calculator and compute 12 times 8, leaving the result "96" on screen.`
11. `Open Calculator and compute 145 plus 55, leaving the result "200" on screen.`
12. `Open Calculator and compute 7 times 6, leaving the result "42" on screen.`

### Chrome
13. `Open Chrome and open a new tab, leaving the page showing "New tab".`
14. `Open Chrome and open the Downloads page, leaving it showing "Downloads".`
15. `Open Chrome and open the History page, leaving it showing "History".`

---

## 4. Execution Order and Warm-Up Protocol

Tasks must be executed in identical sequence (1 through 15) across all passes:

1. **Pass 1:** `BASELINE` (Study pass = Measured)
2. **Pass 2:** `INTENT_SHORTCUT_ONLY` (Study pass = Measured)
3. **Pass 3:** `BURST_AND_FAST_OBSERVE` (Study pass = Measured)
4. **Pass 4:** `FULL_HUMAN_SPEED` Pass A (Study pass = **Warm-up**)
5. **Pass 5:** `FULL_HUMAN_SPEED` Pass B (Study pass = **Measured**)

### The Warm-Up Rule
In `FULL_HUMAN_SPEED`, the agent leverages cached verified trajectory replay (`Remember`).
- **Pass A (Warm-up):** Configured with **Study pass: Warm-up** in Settings. This run populates the trajectory cache from active execution. These runs are recorded in `research_runs.csv` tagged as `WARMUP`.
- **Pass B (Measured):** Configured with **Study pass: Measured** in Settings. This run benchmarks true replay and fast verification execution.
- **Reporting Invariant:** `research_summary_table.csv` aggregates **MEASURED runs only**. Warm-up rows are excluded from latency, step, and call averages.

---

## 5. Between-Pass State Reset Procedures

Between passes, reset device state so that each condition starts from identical preconditions:

1. **Stop Application Processes:**
   ```bash
   adb shell am force-stop com.google.android.deskclock
   adb shell am force-stop com.google.android.contacts
   adb shell am force-stop com.android.settings
   adb shell am force-stop com.google.android.calculator
   adb shell am force-stop com.android.chrome
   ```

2. **Clean Contact State:**
   Delete test contacts created during the run:
   ```bash
   adb shell pm clear com.google.android.contacts
   ```

3. **Reset Clock Alarms & Timers:**
   Clear created alarms and running timers in Clock:
   ```bash
   adb shell pm clear com.google.android.deskclock
   ```

4. **Return to Home Screen:**
   ```bash
   adb shell input keyevent KEYCODE_HOME
   ```

---

## 6. Data Collection and Extraction

Data is logged automatically by `ResearchTelemetryManager` under app-specific external storage:
- Granular runs: `/sdcard/Android/data/com.aniob.app/files/research_runs.csv` (15 columns)
- Summary table: `/sdcard/Android/data/com.aniob.app/files/research_summary_table.csv` (13 columns)

To pull the files and inspect the results:
```bash
bash scripts/pull-research.sh
```

To format the paper-ready LaTeX table:
```bash
python scripts/research_table_to_latex.py research_summary_table.csv
```
