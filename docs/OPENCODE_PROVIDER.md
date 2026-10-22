# Eta OpenCode (Zen / opencode2api) provider

Updated 2026-10-22. Integration references:
- https://github.com/jasonxu114514/opencode2api
- https://opencode.ai/docs/zen/

## What is integrated

- Eta includes one new persistent built-in OpenCode provider:
  - Source type: `opencode`
  - Default Base URL: `https://opencode.ai/zen/v1`
  - Default protocol: OpenAI Chat Completions (switchable in provider settings)
  - Default API key: empty
  - No models pre-seeded: the user pulls the actual current model list
- Its model management page has a separate **拉取 OpenCode 免费模型** action.
- The app calls the configured `GET <Base URL>/models`, and only shows items
  explicitly marked free in the ID (e.g. `-free`) or with input and output costs
  explicitly stated as zero. Deprecated models, Jev System One models and other
  known non-chat IDs are excluded. Paid and unknown-cost models are never silently
  tagged as free.
- An import review sheet lets the user choose which free models to add.
  This operation is additive: existing paid/other remote/custom models remain.
  Original model IDs, including `-free`, are preserved.
- **Catalog readiness fix (October 22, 2026):** When the selected OpenCode
  provider uses the official `https://opencode.ai/zen/v1` URL, model discovery
  (both full and free-only) calls the public HTTPS Zen catalog directly instead
  of starting the embedded Go gateway and waiting up to 18 seconds for its
  independent catalog synchronization. The official `/v1/models` endpoint
  was tested directly (HTTP 200, 86 models / 9 free-ID models at test time).
  A user-specified non-Zen API URL is never silently redirected to Zen.
- Gateway start and upstream catalog readiness are now shown independently
  in the provider settings. When a custom gateway is not ready, its health
  status identifies the catalog state without exposing API keys or raw logs.
  **This fix only makes catalog import reliable; it does not bypass OpenCode
  account restrictions or guarantee that embedded-gateway inference succeeds.**
- Standard Chat Completions and Responses endpoints are available. The separate
  opencode2api Go service handles upstream-specific protocol routing/conversion
  and may publish protocol support for more models than official direct Zen.
- Eta sends an `x-opencode-session` header for a stable conversation hash even
  when the gateway is on another host.

## Android ARM64 embedded gateway (new)

Eta now **ships a separate opencode2api Go executable** built for Android/ARM64
from the pinned upstream commit `0bb23c7a7f0b7cbe08c475e7148c56bc477abcf9`.
The executable is packaged as
`lib/arm64-v8a/libeta_opencode2api.so` to use Android's executable native
library installation location. It is not loaded or linked as JNI; Eta launches
it as a **separate, unprivileged subprocess** via ProcessBuilder.

- The single OpenCode provider defaults to embedded mode on Android ARM64.
- Only `127.0.0.1` is bound, the separate management WebUI is disabled,
  and Eta generates a random 256-bit local `server_key`. The app stores
  the gateway configuration under its private files directory.
- The child starts lazily for gateway-specific discovery or inference, so the
  APK does not create a permanent hidden background/Root service. Controls
  in the provider settings allow manual start/test and stop.
- Settings offer optional Zen and Go upstream keys (stored app-private).
  Default anonymous mode and `models.free_only=true` request a free-only
  catalogue; **this does not guarantee upstream accepts or does not charge
  requests when real upstream keys are supplied**.
- The provider's saved external Base URL and API Key are unchanged while the
  internal mode is active. Toggle the internal mode off to return to those
  external credentials and endpoint.
- Android ARM64 compatibility of the binary is verified statically as a
  Go `android/arm64`, ELF ET_DYN (PIE) executable. The same revision is smoke
  tested on Windows with its own real `/healthz` and `/v1/models` listeners.
  **On-device execution and upstream inference require a physical-phone test**;
  an Android device was not attached during development.
- Only ARM64 is bundled. Other architectures can use the external API mode.

### License and reproducibility

opencode2api is an **independent GPL-3.0-or-later program**. Eta does not
statically link its code. The APK contains:

- `assets/licenses/opencode2api-GPL-3.0-or-later.txt`
- `assets/third_party/opencode2api-source.zip` with the exact pinned upstream
  source, build instructions and source-level license.

The source is maintained separately under `build/opencode2api-upstream` in the
developer checkout. Run `scripts/build_opencode2api_android.ps1` from the Eta
project root to cross-build the native executable and regenerate the bundled
corresponding source archive.

## Important: model catalog availability is not inference authorization

At the time of implementation, `https://opencode.ai/zen/v1/models` returned
86 model IDs, including 9 IDs containing `free`. An explicit test to the
official `https://opencode.ai/zen/v1/chat/completions` using the public
credential and a free model returned:

HTTP 403 `FreeTierError`:
"OpenCode's free tier can only be used from within OpenCode."

Therefore **Eta cannot promise that OpenCode free models work anonymously**.
Catalogue enumeration must not be presented as evidence that paid usage is free.
The Go gateway's own protocol translation is independent of Eta's credential
handling; upstream providers can still enforce restrictions or block requests.

## Using an external opencode2api gateway (optional)

1. Deploy `jasonxu114514/opencode2api` on a machine or VPS that your phone
   can reach through HTTPS. Do not set Eta's Base URL to your *PC's*
   `localhost`: that refers to the phone itself.
2. Configure a strong unique `server_keys` entry on the gateway. This is an
   access key **for your gateway**, separate from OpenCode `zen_keys` and
   `go_keys`. Set a nondefault management password and restrict WebUI access.
3. In Eta's OpenCode provider settings, switch off **手机内置 opencode2api 网关**,
   then change Base URL to
   `https://YOUR-SECURE-HOST/v1` and API Key to the matching gateway
   `server_key`; save.
4. Go to Models and select **拉取 OpenCode 免费模型**. Select desired free models.
5. Try an actual conversation. The gateway may still receive a FreeTierError
   or rate limit from upstream. If you enable its fallback to paid keys, even
   nominally free IDs can be routed via an authenticated paid account. Set
   explicit upstream limits and review billing before enabling any such fallback.

For official Zen paid models, you can keep the official Zen Base URL and supply
your own legitimate Zen API key. The provider-wide protocol switch applies to
direct Zen calls, but not all Zen models expose the same native protocol.
Using opencode2api's OpenAI-compatible gateway provides protocol translation
and is preferred for heterogeneous models.

## Not included

The app does not extract your OpenCode account credentials, automatically
purchase upstream quota, bypass server-side free-tier authorization, install
the embedded gateway as a privileged daemon, or expose the local gateway
to the LAN. Root is not needed for its default unprivileged launch.
