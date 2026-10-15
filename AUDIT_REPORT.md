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
| Android Gradle debug build | Must pass on the latest branch head before the remediation pass is considered CI-verified |
| macOS Swift package build and parse | Must pass on the latest branch head before the remediation pass is considered CI-verified |
| File transfer, Core Audio device switching, mute permissions, restriction enforcement, live audio/video synchronization | Requires paired Mac + Android runtime testing |
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

- **Credential migration:** Android secure storage now tries the current V3 Keystore key, falls back to decrypting V2 values, then persists the recovered token/profile secret encrypted with V3. Removing the active saved device also clears its global saved connection, and updating the currently active profile refreshes its credential.
- **File-transfer bounds:** Upload validation now checks `offset <= total` and compares the chunk size against `total - offset`, avoiding an overflow-prone addition. Staging cleanup removes partial uploads older than 24 hours and caps reserved staging at 100 MiB.
- **Audio controls:** Corrected the AppleScript `set volume output muted true/false` syntax for mute/unmute. macOS subprocess failures now return the captured diagnostic instead of hiding all stderr, which should make remaining permission errors actionable.
- **Restriction enforcement:** The Mac no longer reports a successful restriction if it could not create the input-blocking event tap. The endpoint returns HTTP 503 with a permissions explanation and the failed startup path clears the persisted restricted state.
- **Release safety:** The one-time beta workflow refuses to run when the existing v1.6.0 beta tag/release is present, and no longer permits asset overwrites. No release was deleted or modified by this audit.
- **Screen capture reliability:** Retry attempts now accumulate across rapid failures, use capped progressive delay, and reset only after a 30-second healthy capture interval.
- **H.264 buffer safety:** The encoder output parser copies CoreMedia block buffers into owned contiguous data before indexing, and rejects access units over the Android client's 5 MiB parser limit.
- **Remote input ordering:** Pointer and typing requests are serialized through a single Android executor rather than creating an unbounded new thread per action.
- **Diagnostics/metadata:** Updated the macOS bundle version to 1.6.0, and this report/build notes are being aligned with the current workflow behavior.

## Required final verification

1. Confirm current-head Android and macOS CI passes after all follow-up commits.
2. On an Android install that still has V2-encrypted credentials, verify pairing and every saved Mac profile survive upgrade and migrate without re-entry.
3. On paired devices, test zero-byte and large file transfers, interrupted/retried uploads, duplicate filenames, storage exhaustion, and hostile offset values.
4. Verify mute/unmute and input/output device switching on the actual macOS version and audio devices; capture the returned diagnostic if a permission prompt remains.
5. Test restriction with Input Monitoring granted and denied, plus restart while restricted; verify the app never claims input is blocked when the event tap is absent.
6. Test video/audio start-stop, reconnect, audio opt-in, packet timing, surface rotation/backgrounding, and sustained CPU/network/battery use. A/V synchronization is not certified by the compile checks alone.

The current branch should not be described as fully runtime-verified until those device checks are complete. File any newly reproduced issue with logs and device/OS versions rather than marking it fixed based only on compilation.
