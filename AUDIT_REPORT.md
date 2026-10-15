# PauzeControl — Repository Audit Remediation Report

**Original audit:** 2026-10-12  
**Follow-up remediation pass:** 2026-10-16  
**Scope:** Android Java/XML application, H.264 viewer/client, macOS Swift agent, HTTP/HMAC layer, audio and file transfer, profile/key storage, installer, and GitHub Actions workflows.  
**Method:** Static source review and GitHub Actions build/type-checks. No live adversarial network test or paired-device test is claimed.

## Outcome and scope limits

The October 12 counts below describe the previous audit snapshot only. Follow-up review on October 16 found additional defects; current changes and remaining device-only verification are recorded in the addendum at the end of this report. Passing CI demonstrates compilation, not operational correctness.

All changes are being made on `feat/live-screen-system-audio` / draft PR #12. They have not been merged into `main`, and no GitHub Release or published release asset was edited.

| Check | Result at the time of this report |
|---|---|
| Android Gradle debug build | Passed on code head 24c42d95575aac83c654bcbf10d673c4dadc0504; later commits in this pass are documentation-only |
| macOS Swift package build and parse | Passed on code head 24c42d95575aac83c654bcbf10d673c4dadc0504; later commits in this pass are documentation-only |
| File transfer, Core Audio device switching, mute permissions, restriction enforcement, live audio/video timing | Requires paired Mac + Android runtime testing |
| Release integrity | Existing assets are protected by an explicit no-overwrite guard in the one-time v1.6.0 beta workflow |

## Critical

### C-01 — Timestamp overflow could terminate the Mac agent
- **Original location:** `mac/Sources/PauzeControl/ControlServer.swift`, authentication timestamp check.
- **Fix:** Replaced `abs(now - timestamp)` with direct lower/upper-bound comparisons, so an attacker-supplied `Int64.min` no longer reaches overflowing subtraction before HMAC validation.
- **Regression guidance:** Send timestamps at `Int64.min`, `Int64.max`, and one second outside the accepted window; every request must be rejected without process termination.

### C-02 — Negative/invalid Content-Length could trap during parsing
- **Original location:** `mac/Sources/PauzeControl/ControlServer.swift`, `HTTPRequest.parse`.
- **Fix:** Validate the header before slicing, reject duplicate/malformed/negative/oversized lengths, and enforce total request, header, and body limits.
- **Regression guidance:** Test negative, nonnumeric, duplicated, extremely large, truncated, and overlong Content-Length values; the service must close/reject the request without crashing.

## High

### H-01 — No aggregate request-size or incomplete-request deadline
- **Fix:** Added an overall 80 KiB request cap, 16 KiB header cap, 64 KiB body cap, and a 10-second request-construction deadline. Requests exceeding limits are cancelled before authentication/dispatch.

### H-02 — Pairing secret exposed in startup logs
- **Fix:** Removed the complete pairing secret from normal app startup logging. Startup logs now indicate only that the secret was loaded successfully. The explicit installer token-display option remains an intentional interactive step.

### H-03 — Release workflow depended on a missing Gradle wrapper
- **Fix:** Replaced `./gradlew` calls with a configured Gradle 8.9 installation in `.github/workflows/android-release.yml`.

### H-04 — Release artifact signing and overwrite safety
- **Current behavior:** `android/app/build.gradle` supports environment-driven signing for the `assembleRelease` variant. The one-time `android-release.yml` beta workflow deliberately publishes a debug-signed testing APK, not a production-signed or Google Play build.
- **Follow-up fix:** Added an early guard that fails if `v1.6.0-beta.1` tag or release already exists and disabled asset overwrites. This protects the already-published beta from accidental replacement.
- **Before a future production-signed release:** Use a separate new-version workflow and configure the documented private signing key/secrets. The current one-time beta workflow is not a reusable release pipeline.

### H-05 — H.264 decoder was accessed across lifecycle and stream threads
- **Fix:** Decoder frame operations and release are synchronized, and surface creation/destruction/activity teardown use the same monitor before mutating decoder surface state or releasing codec resources.

## Medium

### M-01 — Dequeued MediaCodec input buffers could be abandoned
- **Fix:** A missing input buffer causes a decoder reset. Oversized access units attempt to return the dequeued slot with an empty buffer and always reset the decoder afterward, preventing the input slot from being silently stranded.

### M-02 — Stream stop/start race and immediate retry loop
- **Fix:** The stream connection reference is volatile, active state is set before connection setup, and the worker rechecks cancellation immediately after socket creation. Unexpected stream closure uses capped exponential backoff; user-triggered reconnect remains prompt.

### M-03 — Process calls could deadlock pipe handling or block all requests
- **Fix:** The server now accepts requests on a concurrent dispatch queue; shared nonce/client collections retain locks. Process output is drained while the child runs, stderr/output are directed appropriately, and subprocess calls have bounded timeouts.

### M-04 — HTTP connections leaked on exceptional paths
- **Fix:** `ApiClient.request` now disconnects in a `finally` block. Host URI construction validates scheme/authority and rejects path/query/fragment/user-info input instead of concatenating malformed strings.

### M-05 — Automatic status failures left stale Online state
- **Fix:** Consecutive status failures are counted and mark the connection offline after two failed polls. Successful status responses reset the count, and periodic polling is disabled while the activity is paused.

### M-06 — Cleartext protocol relied solely on the private-network convention
- **Fix/mitigation:** The Mac listener now rejects peers outside Tailscale IPv4 `100.64.0.0/10`, Tailscale IPv6 `fd7a:115c:a1e0::/48`, and loopback. README security guidance now states the network restriction explicitly. The API/video transport still uses HTTP, so all real device connections must traverse Tailscale; this is not equivalent to end-to-end TLS and should be revisited if non-tailnet connectivity becomes a requirement.

## Low

### L-01 — Host URLs with a path produced malformed endpoint URLs
- **Fix:** URI building now reconstructs the endpoint from scheme, host, port, and the API path; it rejects invalid schemes, user-info, query/fragment, and non-root host paths with a clear validation error.

### L-02 — README omitted the Control tab
- **Fix:** Updated the documented navigation to Home / Control / Activity / Settings.

## Additional validation changes

- The macOS workflow now runs `swift build -c debug` in addition to package parsing and per-file Swift parsing, which type-checks the full package.
- The Android debug workflow validates the launcher resources as part of its normal build.
- Focused performance changes remain in place: reusable Android nonce generation/direct HMAC hex encoding, and one combined macOS audio telemetry AppleScript call.

## Additional hardening during remediation

- Replay-nonce entries are now registered only after the HMAC signature is valid, preventing unauthenticated requests from filling the replay cache with arbitrary nonce values.
- Requests that carry a body without a valid Content-Length are rejected.
- Periodic Android status polls are now single-flight, preventing slow timeouts from building an unbounded executor queue.

## Follow-up remediation — 2026-10-16

The following changes were committed to the existing draft branch during the follow-up audit:

- **Credential migration and profile lifecycle:** Android secure storage decrypts V3 values first, falls back to existing V2 values, then persists recovered tokens/profile secrets encrypted under V3. Removing the active profile clears the global token/host, and updating the active profile re-activates it with the new secret. The host is persisted only after token encryption succeeds.
- **File-transfer validation and integrity:** Upload offsets are checked without overflow-prone addition. A staging sidecar binds every chunk to the first chunk's filename and declared total size, preventing a transfer ID from changing metadata mid-upload. Symlinks are rejected; abandoned partial uploads older than 24 hours are cleaned up; staging is capped at 100 MiB and 16 concurrent sessions. Android verifies each download chunk's status, filename, offset and total, including zero-byte files.
- **Audio controls and diagnostics:** Corrected the mute/unmute AppleScript syntax. macOS subprocess stderr now reaches the API error instead of being hidden. Audio-device listing tolerates a temporarily absent default device and client operations check response status.
- **Restriction enforcement:** macOS fails closed if it cannot create the input-blocking event tap; the restrict endpoint returns HTTP 503 with a permissions explanation. Android displays the real input-blocking status and reconciles optimistic toggle state after rejected commands.
- **Release safety:** The one-time v1.6.0 beta workflow fails if the published tag/release exists and disallows asset overwrites. No release or published asset was deleted or modified.
- **Capture/listener reliability:** Screen capture retry attempts accumulate through repeated failures, use capped progressive backoff and reset after a 30-second healthy interval. The Mac control listener exposes health and the LaunchAgent retries startup/failed listener states.
- **H.264 and live A/V:** H.264 access units are copied from possibly segmented CoreMedia buffers before parsing and capped at the Android 5 MiB limit. Audio/video packets share a local monotonic presentation-time mapping on Android; video renders are timestamp-scheduled and audio writes are paced by packet PTS. Audio packets no longer starve video packets on a constrained connection. Synchronization remains subject to physical-device output latency and jitter tests.
- **Input/UI correctness:** Android control mode serializes mouse/typing requests, surfaces CoreGraphics creation failures, ignores duplicate switch taps while animations dispatch, uses the captured click state for mute/restriction commands, and removes touch-intercepting invisible viewer overlays. AudioTrack negative writes are reported and shut down cleanly; zero-byte writes are retried without dropping the chunk.
- **Diagnostics/metadata:** macOS bundle version is aligned to 1.6.0; user-facing API errors include Core Audio/subprocess failure details.

## Required final verification

1. Confirm current-head Android and macOS CI passes after all follow-up commits.
2. On an Android install that still has V2-encrypted credentials, verify pairing and every saved Mac profile survive upgrade and migrate without re-entry.
3. On paired devices, test zero-byte and large file transfers, interrupted/retried uploads, duplicate filenames, storage exhaustion, and hostile offset values.
4. Verify mute/unmute and input/output device switching on the actual macOS version and audio devices; capture the returned diagnostic if a permission prompt remains.
5. Test restriction with Input Monitoring granted and denied, plus restart while restricted; verify the app never claims input is blocked when the event tap is absent.
6. Test video/audio start-stop, reconnect, audio opt-in, packet timing, surface rotation/backgrounding, and sustained CPU/network/battery use. Verify the best-effort PTS schedule on multiple Android devices and measure any persistent lead/lag.
7. Confirm listener recovery by making port 47777 unavailable at startup, then freeing it; verify the 20-second health timer rebinds the port without restarting the LaunchAgent.

The current branch should not be described as fully runtime-verified until those device checks are complete. File any newly reproduced issue with logs and device/OS versions rather than marking it fixed based only on compilation.
