# PauzeControl — Repository Audit Report

**Audit date:** 2026-10-12  
**Scope:** Current PR branch `feat/android-control-center-quality-fix`; Android Java/XML, macOS Swift, manifest/build configuration, and GitHub Actions workflows.  
**Method:** Static code review plus CI/build validation. This is not a runtime penetration test.  
**Important:** Findings below are report-only. No code was changed to address these findings; the PR changes are limited to the launcher icon and performance optimizations requested.

## Summary

| Severity | Findings |
|---|---:|
| Critical | 2 |
| High | 5 |
| Medium | 6 |
| Low | 2 |
| **Total** | **15** |

## Critical

### C-01 — Unauthenticated timestamp can overflow and terminate the Mac agent
- **Location:** `mac/Sources/PauzeControl/ControlServer.swift:435–453`
- **Evidence:** The request-controlled `Int64` timestamp is checked with `abs(now - timestamp)` before the HMAC signature is verified.
- **Impact:** A request using the minimum signed 64-bit timestamp can overflow subtraction; Swift integer overflow traps at runtime. An unauthenticated network client may be able to terminate the control service before authentication completes.
- **Confidence:** High; the arithmetic failure was reproduced independently with Swift's checked integer operations.
- **Follow-up:** Validate timestamp range using overflow-safe comparisons before arithmetic and verify with a regression test.

### C-02 — Negative Content-Length can crash the Mac agent before authentication
- **Location:** `mac/Sources/PauzeControl/ControlServer.swift:785–793`
- **Evidence:** The parser converts `Content-Length` to an integer, checks `rawBody.utf8.count >= contentLength`, then calls `rawBody.utf8.prefix(contentLength)`. Negative values pass the comparison and a negative prefix length triggers a Swift runtime trap.
- **Impact:** A malformed unauthenticated HTTP request can terminate the service.
- **Confidence:** High; negative collection-prefix lengths were reproduced with the available Swift runtime.
- **Follow-up:** Reject invalid, negative, and unreasonably large lengths before slicing.

## High

### H-01 — Request buffering has no aggregate size or header timeout limit
- **Location:** `mac/Sources/PauzeControl/ControlServer.swift:91–126`; parser at `785–793`
- **Evidence:** Each incomplete request is re-read in chunks up to 65,536 bytes and appended to the accumulated buffer. No total-header/body cap or per-connection deadline is enforced before parsing/authentication.
- **Impact:** Slow or oversized unauthenticated requests can retain growing buffers and repeatedly re-parse the entire buffer, consuming memory and CPU; concurrent requests amplify the risk.
- **Follow-up:** Define strict request/header limits and deadlines and reject incomplete requests that exceed them.

### H-02 — Pairing secret is written to process logs
- **Location:** `mac/Sources/PauzeControl/AppController.swift:20–23`
- **Evidence:** Startup prints the complete pairing token.
- **Impact:** Terminal output, diagnostic bundles, or centralized logs can expose the credential required to authenticate HMAC requests.
- **Follow-up:** Never log the secret; log only that secure initialization succeeded and document a rotation procedure if this log has already been captured.

### H-03 — Android release workflow references a missing Gradle wrapper
- **Location:** `.github/workflows/android-release.yml:27–29`
- **Evidence:** The workflow runs `chmod +x ./gradlew` and later `./gradlew assembleRelease`, but the repository tree contains no `android/gradlew` or Gradle wrapper files.
- **Impact:** The tagged/manual Android release workflow will fail before producing an APK.
- **Follow-up:** Either commit a standard Gradle wrapper or use the already configured Gradle installation consistently in the release workflow.

### H-04 — Release workflow can publish an unsigned APK
- **Location:** `.github/workflows/android-release.yml:32–48`; `android/app/build.gradle:1–25`
- **Evidence:** The workflow builds `assembleRelease`, selects the first APK found, and uploads it; the Android Gradle configuration does not define a release signing configuration or signing step.
- **Impact:** The selected artifact is expected to be `app-release-unsigned.apk`, which Android will not install as a normal release package. The workflow also does not assert that the selected APK is signed.
- **Follow-up:** Set up a secure signing process for releases and validate the artifact name/signature before attaching it to a release.

### H-05 — H.264 decoder lifecycle is accessed from multiple threads
- **Location:** `android/app/src/main/java/com/pauze/control/ScreenActivity.java:198, 269–355, 491–505, 676–695, 773–786`
- **Evidence:** The stream worker calls `onFrame` and decodes through `decoder`, while surface destruction, reconnect, stream-close, and activity destruction paths can call `releaseDecoder` from other threads.
- **Impact:** A surface/lifecycle transition racing with frame decode can use a released `MediaCodec`, produce intermittent decoder failures, or crash the viewer.
- **Follow-up:** Serialize decoder operations and lifecycle teardown on one execution context; add a test that repeatedly reconnects/rotates/closes during streaming.

## Medium

### M-01 — Dequeued MediaCodec input buffers can be abandoned
- **Location:** `android/app/src/main/java/com/pauze/control/ScreenActivity.java:324–346`
- **Evidence:** After `dequeueInputBuffer` returns an index, the method returns early if `getInputBuffer` is null or the access unit exceeds the buffer's remaining capacity. That input index is not queued back.
- **Impact:** An oversized keyframe or unusual codec state can reduce the available input-buffer pool and stall decoding.
- **Follow-up:** Ensure all dequeued indices are returned safely and define behavior for access units that exceed the decoder input capacity.

### M-02 — Stream stop/start race and immediate reconnect loop
- **Location:** `android/app/src/main/java/com/pauze/control/ScreenStreamClient.java:33–100`; `ScreenActivity.java:491–505`
- **Evidence:** `connection` is written by the worker but read by `stop()` without volatile/synchronization. `running` becomes true only after connection setup, so a stop occurring before the connection is assigned can be overwritten by the worker. The viewer retries after a fixed 150 ms on close without backoff.
- **Impact:** Stopping during connection setup can fail to stop the attempt; unreachable hosts can cause rapid reconnect attempts, repeated socket activity, and excess battery use.
- **Follow-up:** Make the connection lifecycle cancellation-safe and apply bounded retry/backoff with a visible retry state.

### M-03 — Synchronous process calls can stall all Mac control requests
- **Location:** `mac/Sources/PauzeControl/ControlServer.swift:12–18, 284–299`; `mac/Sources/PauzeControl/SystemService.swift:195–220`
- **Evidence:** A single serial server queue handles requests, and the status endpoint synchronously obtains a system snapshot. The process helper waits for child exit before draining stdout; stderr is directed to an undrained pipe.
- **Impact:** Slow system commands delay other status/control requests; if a child fills a pipe, waiting before draining it can deadlock the helper and stall the request queue.
- **Follow-up:** Drain subprocess output concurrently or avoid pipes when output is not needed; move slow system telemetry/commands off the connection-handling queue.

### M-04 — HTTP connections are not disconnected on exceptional paths
- **Location:** `android/app/src/main/java/com/pauze/control/ApiClient.java:53–77`
- **Evidence:** `connection.disconnect()` is reached only after normal response reading, not from a `finally` block.
- **Impact:** Failures during output, response-code retrieval, or body reading can leave connection resources open longer than intended.
- **Follow-up:** Guarantee connection cleanup in a `finally` path and test timeout/error responses.

### M-05 — Failed automatic status refreshes can leave the UI showing stale Online state
- **Location:** `android/app/src/main/java/com/pauze/control/MainActivity.java:75–85, 624–675, 1080–1105`
- **Evidence:** The periodic poll calls `refreshStatus(false)`; errors are ignored when not user-initiated, and non-2xx status replies return without marking the connection offline.
- **Impact:** After a connection loss, the connection pill can continue to show the previous state until a user-triggered action fails.
- **Follow-up:** Track last successful poll and show a stale/offline state after a defined failure threshold.

### M-06 — Control/status and H.264 traffic are cleartext at the application layer
- **Location:** `android/app/src/main/AndroidManifest.xml:14`; `android/app/src/main/java/com/pauze/control/ApiClient.java:80–92`; `mac/Sources/PauzeControl/ControlServer.swift:145–170`
- **Evidence:** Cleartext traffic is enabled, hosts without a scheme default to HTTP, and the stream is sent over the same HTTP connection.
- **Impact:** This relies on the documented Tailscale/private-network boundary. If a user points the app at an untrusted network path, telemetry and H.264 video can be observed or altered even though command authentication uses HMAC.
- **Follow-up:** Keep the private-network requirement explicit and consider TLS/pinning or restricting supported interfaces if non-Tailscale use is expected.

## Low

### L-01 — Host values containing a URL path are assembled incorrectly
- **Location:** `android/app/src/main/java/com/pauze/control/ApiClient.java:80–92`
- **Evidence:** If the entered host has a path but no explicit port, the default port is appended to the full string rather than the URI authority; the endpoint path is then appended to that malformed URL.
- **Impact:** Entries such as `http://host/something` fail unexpectedly even though the field accepts URI-like input.
- **Follow-up:** Validate the host format or rebuild the URI from its scheme/authority/port components.

### L-02 — README navigation list does not match the current four-tab UI
- **Location:** `README.md`, section “Android Control Center”
- **Evidence:** It lists Home / Activity / Settings but omits the newly added Control tab.
- **Impact:** Documentation no longer fully describes the navigation visible in the app.
- **Follow-up:** Update feature documentation during the next docs pass.

## Audit limits

- The repository includes build/syntax workflows but no automated unit or integration-test suite was found in the tracked tree.
- CI success confirms compilation/parsing only; it does not validate H.264 lifecycle behavior, malicious HTTP input handling, or release installation/signing.
- All findings above remain unmodified by this audit.
