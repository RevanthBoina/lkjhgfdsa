# Cloud AI via OmniRoute

Aniob talks to a self-hosted [OmniRoute](https://github.com/diegosouzapw/OmniRoute) gateway when a
task escalates to the cloud. OmniRoute is an MIT-licensed, OpenAI-compatible router: one key selects
whatever providers you connected there (Claude, GPT, Gemini, DeepSeek, local gateways, …), and
`model: auto` lets it fall back across them.

The app has no bundled cloud account and no hosted default. **You provide the gateway address and
the key.** Settings → *Cloud AI (OmniRoute)* is the only place both are entered.

## 1. Run the gateway

Any one of:

```bash
npm install -g omniroute
omniroute                 # dashboard + API on port 20128
```

Docker and a desktop build are documented in the project's Setup Guide. Verify it locally:

```bash
curl http://localhost:20128/v1/models -H "Authorization: Bearer YOUR_KEY"
```

## 2. Copy the endpoint key

Dashboard → **Endpoints**. This is the key OmniRoute issues to *clients*; it is not an upstream
provider key from OpenAI/Anthropic. A request with an upstream key returns 401/403 here.

A gateway started with `REQUIRE_API_KEY=false` (the default for a self-hosted install) has no key
to copy. Leave the key field empty and enter the address: a gateway on this device, the emulator
or your local network is accepted without one. Any address outside that range still needs a key,
so a public endpoint is never used without an explicit key.

## 3. Reach the gateway from the phone

The phone is not the computer running OmniRoute, so `localhost` will not work. Pick one:

| Setup | Address to enter in Aniob |
|---|---|
| Phone and computer on the same Wi-Fi | `http://<computer-lan-ip>:20128/v1` (e.g. `http://192.168.1.20:20128/v1`; `/v1` is auto-added if omitted) |
| USB cable, gateway on the computer | Run `adb reverse tcp:20128 tcp:20128`, then use `http://localhost:20128/v1` |
| Reached from outside the home network | An `https://` reverse proxy or tunnel in front of port 20128 |

For a LAN address, allow inbound TCP 20128 in the computer's firewall.

## 4. Connect it in Aniob

1. Open **Settings → Cloud AI (OmniRoute)**.
2. **Gateway address** — the URL from step 3. A bare host such as `http://192.168.1.20:20128`
   works: `/v1` is added when the address has no path. Addresses with `?` or `#` parts are
   rejected, and validation accepts:
   - `https://` for any host;
   - `http://` only for `localhost`, `127.0.0.1`, `::1`, the emulator alias `10.0.2.2`,
     `10.x`, `172.16–31.x`, `192.168.x`, `169.254.x`, `100.64–127.x` (Tailscale/CGNAT), IPv6
     `fc00::/7`/`fe80::/10`, and
     `.local`/`.lan`/`.home`/`.internal` names.
3. **OmniRoute key** — paste it. Tap the eye to check it; it is stored with the Android Keystore
   (Tink AES-256), never in plaintext preferences.
4. **Routing model** — `auto` (recommended) or `auto/coding`, `auto/fast`, `auto/cheap`. Any
   `provider/model` id shown in the dashboard is also accepted.
5. **Save**, then **Test**.

`Test` sends one `max_tokens: 1` completion to the entered address without saving anything first, so
a typo cannot replace a working setup. `Save` and `Test` are separate on purpose.

### What the banners mean

| Banner | Meaning | Next step |
|---|---|---|
| Saved | Key, address and model are stored | Run Test to confirm the gateway answers |
| Connected | The gateway returned a 2xx for the key | Press Save to keep it; cloud routing is then available |
| Key rejected | 401/403 | Copy the *endpoint* key again from Dashboard → Endpoints |
| Gateway unreachable | No HTTP answer, or a non-2xx other than 401/403 (for example no provider connected upstream) | Check Wi-Fi, firewall, port, that OmniRoute is running and that it has a provider connected |
| Check these details | Address/model failed validation, key or address missing, or the Keystore is unavailable | Follow the message under the field |

Clearing the key *and* the address and pressing Save turns Cloud AI off.

## What leaves the phone

- Only requests to the address you entered, with `Authorization: Bearer <key>`.
- Chat prompts, and a JPEG screenshot only when a task needs vision and no password/OTP field is on
  screen.
- The key is never logged. `providers.yaml` documents the same contract; the Kotlin
  `AniobProviderConfig` is the executable source of truth.
- Keyless gateways (`REQUIRE_API_KEY=false`) need no value in the key field when the address is on
  this device or the local network; the request then carries no `Authorization` header at all.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| 401/403 | Upstream provider key pasted instead of the OmniRoute endpoint key |
| Connection refused / timeout | Wrong IP, different Wi-Fi/guest network, firewall blocking 20128, or OmniRoute not running |
| 404 | A proxy or a path-prefixed address produced the wrong route; try the bare `http://<host>:20128` form |
| 400 with a model message | The model id does not exist in this gateway; use `auto` |
| "Plain http is only allowed on your local network" | Public host entered with `http://` — use `https://` |
| "This device cannot store the key securely" | The Android Keystore is unavailable (some emulators/rooted builds); cloud stays off by design |

## Verification in this repository

```bash
bash scripts/ci-preflight.sh
```

Guard 6 checks that new user-facing copy does not leak internal names, hardcoded colours stay out of
screens and the shared component kit is used. Guard 7 performs a network range check against the
Hugging Face model files; it fails in offline sandboxes and passes in GitHub Actions (see
`.github/workflows/release.yml`).
