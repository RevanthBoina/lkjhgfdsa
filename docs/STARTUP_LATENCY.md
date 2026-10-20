# Time to first action, and per-step time

The default OmniRoute setup is not slow at generating an action: it is slow because the first
action waits for work that has nothing to do with the model. On a cold task the app used to

1. hydrate learned state to completion,
2. sleep a fixed 500 ms so the chat window could move to the background,
3. take a second capture of a screen it had just captured,
4. open a fresh TLS connection to the gateway on the first chat request,
5. wait for the model to finish the whole reply before parsing, and
6. sleep again after dispatching, before looking at the screen.

Each of those is a fixed cost the user pays on the very first step, where nothing is known yet.
This change removes the fixed parts and keeps every correctness gate: the same verifier, the same
safety interceptor, the same action schema.

## Layers

| # | Where | Before | Now |
|---|---|---|---|
| 1 | Step 0 planning | full model round-trip | `open/launch/start/run/go to X` resolves against the installed-app catalog and dispatches `OpenApp` with **zero** model calls |
| 2 | Post-dispatch wait | `delay(500)` | poll the foreground package every 40 ms, ceiling 500 ms; returns as soon as the change lands |
| 3 | Hydration + first capture | sequential | hydration runs behind a 300 ms budget while the first capture happens; whichever is slower wins |
| 4 | Step-0 capture | always a fresh read | a capture younger than 1.5 s is reused |
| 5 | Gateway | cold socket on the first action | `GET /models` warms DNS/TLS/HTTP2 at task start and while the user is still typing |
| 6 | Model reply | wait for the full body | stream, timestamp the first delta, parse the first complete JSON object and cancel the rest |
| 7 | `OpenApp` verification | 800 ms cap | 4 s event-driven cap that still returns the moment the foreground package matches |
| 8 | Startup log | none | one `STARTUP_LATENCY{...}` line per run |

## Step 0 without a model

`AniobExecutionRouter` gained an `AppLaunch` rung that sits **after** `DirectIntent` and **before**
`RememberReplay`, `FastPath` and model dispatch. It only answers when

- the step is 0,
- the intent-shortcut switch is on,
- the prompt starts with an imperative (`open`, `launch`, `start`, `run`, `go to`, optionally
  `please` and an article), and
- exactly one installed app matches the named app, longest match first.

Anything else — an ambiguous name, an app that is not installed, a mention in the middle of a
sentence — falls through to the model, unchanged. `AniobAppLaunchResolver` performs the match so it
can be unit-tested without Android.

## Streaming and early parse

`AniobOmniRouteProvider.getNextActionRawStreaming` sends `stream: true` with the same payload as the
plain call. `JsonPrefix` scans the accumulated text and returns the first balanced `{...}` object,
which is legal JSON whatever prose the model wrapped around it. The socket is cancelled at that
point: the rest of the reply cannot improve an action that is already complete.

Gateways differ. A 4xx on the streaming shape is retried once without `response_format`, and if the
shape is still refused the provider remembers it for the session and every later step uses the
non-streaming call — one wasted round-trip, not one per action. A gateway that ignores `stream`
and answers with a whole JSON body is accepted too.

The scanner keeps a bounded window that always retains the opening brace: once the buffer is full
while starting with `{`, further deltas are dropped rather than truncating the head. A well-formed
action reply is a few hundred characters, so the cap only guards against a runaway stream.

## Verifying it on a device

Every run logs exactly one line, in the same stream as the step narration:

```
adb logcat -s AniobViewModel | grep STARTUP_LATENCY
```

```
STARTUP_LATENCY{task_start=0,backgrounded=96,hydrated=180,capture=210,planned=214,prompt=220,ttft=640,action=690,dispatched=700,total_ms=700}
```

Every value is milliseconds since the task start, so adjacent deltas are the real cost of each
phase. `total_ms` is the time to first action.

What to expect:

- `backgrounded` — tens of milliseconds instead of a flat 500.
- `planned` ≈ `capture` on a launch task: the app-launch rung does no model work.
- `ttft … action` — the parse is a few milliseconds after the first token, instead of the full
  generation time.
- No `ttft` at all means the request did not stream (a gateway that refused it, or a remembered
  fallback); the step still completes through the plain call.

## What did not change

- `DeterministicVerifier` still passes `OpenApp` only when the foreground package matches; the
  longer budget is a ceiling, not a delay, and the wait still ends on the package change.
- Safety, confirmation, blocked-action memory, replay gating and the model ladder order are
  untouched apart from the new rung.
- A cache miss or an unparseable stream falls back to the existing path instead of failing.
