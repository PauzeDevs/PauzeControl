# PauzeControl — Repository Audit Remediation Report

**Original audit:** 2026-10-12  
**Remediation pass:** 2026-10-12  
**Scope:** Android Java/XML application, H.264 viewer/client, macOS Swift agent, HTTP/HMAC layer, process invocation, launcher assets, and GitHub Actions release workflows.  
**Method:** Code review, resource/reference checks, and GitHub Actions build/type-checks. No live adversarial network test or device/codec stress test has been run.

## Outcome

All 15 findings from the prior audit have been addressed in the current PR branch. Remediations are reflected in source/configuration below; this report does not claim that unperformed runtime penetration and device tests have passed.

| Severity from original audit | Findings | Status |
|---|---:|---|
| Critical | 2 | Fixed in code |
| High | 5 | Fixed in code/configuration |
| Medium | 6 | Fixed or mitigated in code |
| Low | 2 | Fixed in code/documentation |
| **Total** | **15** | **Remediated; final CI runs must pass** |

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

### H-04 — Release artifact was not guaranteed to be signed
- **Fix:** Added environment-driven release signing in `android/app/build.gradle`. The release workflow validates the keystore, builds the release APK, verifies its signature with `apksigner`, and uploads/attaches only the expected signed APK.
- **Required repository configuration:** Before running the tag/manual release workflow, configure these GitHub Actions repository secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`. The workflow intentionally fails closed when they are absent. The private release keystore must remain outside the repository.

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

## Remaining verification

1. Configure the four release-signing secrets before the first tagged/manual release build; this environment cannot create repository secrets or access your private release keystore.
2. After final CI passes, run a runtime test matrix on a Mac and Android device: stream start/stop/reconnect, rotation/backgrounding, all quality/FPS selections, low bandwidth, denied Screen Recording/Input Monitoring permissions, and malformed HTTP requests against the agent.
3. Keep this report as a remediation record. Any new issue discovered during device/runtime testing should be filed separately rather than silently treated as already resolved.
