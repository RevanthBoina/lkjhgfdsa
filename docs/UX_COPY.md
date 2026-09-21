# ANIOB UX Copy Deck

Single source of truth for every user-visible string (UX-0 §4, finalized in UX-7). Strings shipped
in `strings.xml` or rendered by composables must match this deck. Technical names (FAST_PATH,
OMNIROUTE, M0, "32dp", "hierarchies", screen hashes) live behind a "Details" affordance only — never
on a user surface.

## Ban list (CI-enforced in UX-7)

`M0`, `32dp`, `hierarchies`, `actuates`, `FAST_PATH`, `OMNIROUTE`, `Pre-flight`.

## Onboarding / Setup Concierge (UX-1)

| Slot | Copy |
|---|---|
| Welcome title | Your phone, on autopilot |
| Welcome bullet 1 | Tell it what you want in plain words |
| Welcome bullet 2 | It taps the buttons for you |
| Welcome bullet 3 | You approve anything risky |
| See how it works | See how it works |
| Demo caption | Watch it do a task — no permissions needed yet |
| Accessibility why | To tap buttons for you — only while a task runs |
| Overlay why | Shows a small status pill and approvals while you watch |
| Notifications why | So you can pause or stop a task from anywhere |
| Battery why | Stops the system from killing Aniob mid-task |
| Brain why | Aniob needs a brain: on-device (private) or cloud (fast) |
| First task | Try your first task |
| Celebration | 🎉 Your first automation |
| What happened card | Here's what just happened |
| Skip | Skip for now |
| Remind later | Finish setup when you're ready |

## Chat / Composer (UX-2)

| Slot | Copy |
|---|---|
| Input placeholder | Message or command… |
| Empty title | What do you want Aniob to do? |
| Empty body | Type, speak, or tap a suggestion below. |
| Queue chip | After this task: %1$s |
| Grill title | Help me understand |
| Grill subtitle | A couple of quick details so I get this right. |
| Remember answer | Remember this answer |
| Remembered badge | saved ✓ |
| Grill footer | %1$d of %2$d · remembered %3$d |
| Cancel task | Cancel task |
| Template search | Search in… |
| Template message | Send message to… |
| Template alarm | Set alarm for… |

## Glass Cockpit (UX-3)

| Slot | Copy |
|---|---|
| Ticker running | Working on '%1$s' — step %2$d |
| Ticker paused | Paused — do this step yourself, then Resume |
| Pill failure | Stuck — tap to help |
| Takeover offer | Learn what you just did? |
| Jump to live | Jump to live |

## Trust & Safety (UX-4)

| Slot | Copy |
|---|---|
| Confirm title | Aniob needs your approval |
| Confirm what | What: %1$s |
| Confirm why | Why: %1$s |
| Confirm approve once | Approve once |
| Confirm approve task | Approve for this task |
| Confirm deny | Deny |
| Evidence success | Done — here's the proof |
| Evidence failure | Couldn't finish — here's why |
| Stopped outcome | Stopped by you at step %1$d — nothing was undone |
| Trust center title | Trust Center |
| Safety standard | Standard |
| Safety strict | Strict |
| Disable safety | I UNDERSTAND |
| Safety off banner | Safety checks are off — they turn back on next task |

## Learning & Progress (UX-5)

| Slot | Copy |
|---|---|
| Skills title | Skills |
| Skills active | Active |
| Skills review | Needs review |
| Skills builtin | Built-in |
| Skills empty | No skills yet — teach your first |
| Moment vault | Used saved preference: %1$s ✓ |
| Moment skill | Replayed '%1$s' · 0 tokens |
| Stats completed | Tasks completed |
| Stats success | Success rate |
| Stats time saved | ≈Time saved (steps × 8s, estimate) |
| Stats streak | Current streak |
| Provider instant replay | Instant replay |
| Provider on-device | On-device |
| Provider cloud | Cloud |

## System Surfaces (UX-6)

| Slot | Copy |
|---|---|
| Notification progress title | %1$s |
| Notification pause | ⏸ Pause |
| Notification resume | ▶ Resume |
| Notification stop | ■ Stop |
| Done title | ✓ Done: %1$s |
| Failure title | Needs attention: %1$s |
| Interrupted title | Aniob was interrupted |
| Interrupted body | Aniob was killed by the system at step %1$d — run it again or view the partial steps. |
| A11y lost title | Aniob lost access |
| A11y lost body | Re-enable accessibility to continue, or stop the task. |
| Tile idle | Ask Aniob |
| Tile running | Stop task |
| Shortcut new task | New task |
| Shortcut teach | Teach a task |
| Shortcut skills | Skills |

## Failure reasons (UX-4 plain language)

| Internal code | User copy |
|---|---|
| grounding_miss | Couldn't find '<target>' on screen |
| no-effect | Nothing changed on screen after the action |
| expected text … not present | Expected to see '…', but it wasn't there |
| expected package … not foreground | '…' didn't open |
| watchdog loop_detected | Aniob got stuck repeating an action |
| payment blocked | Aniob doesn't do payments |
| destructive blocked | That action could erase your data, so Aniob stopped |
| sensitive blocked | That field holds a secret Aniob never reads |
| network unavailable | No internet connection |
| accessibility disabled | Accessibility access is turned off |
| timeout | The app took too long to respond |
