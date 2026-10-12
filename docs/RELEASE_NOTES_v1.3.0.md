# PauzeControl v1.3.0 — Release Notes

**Release track:** Published debug/testing build — not stable production-signed  
**Android application ID:** com.pauze.control  
**Version name / code:** 1.3.0 / 2

**Download:** [v1.3.0 APK](https://github.com/PauzeDevs/PauzeControl/releases/download/v1.3.0/PauzeControl-v1.3.0-debug.apk) · [Release page](https://github.com/PauzeDevs/PauzeControl/releases/tag/v1.3.0) · [RC1 preview](https://github.com/PauzeDevs/PauzeControl/releases/tag/v1.3.0-rc.1)

> The published APK is a debug-signed test build. The production stable-signed APK is not available until the private release keystore and CI signing configuration are ready.

## Highlights

### Control center and launcher
- Added a custom PauzeControl Android launcher icon.
- Introduced persistent Home, Control, Activity, and Settings bottom navigation.
- Corrected Activity/Settings navigation targets and selected states.

### Manual H.264 quality
- Removed Auto / Power Aware mode and the macOS battery-profile override.
- Exposed manual targets: 144p, 240p, 360p, 480p, 720p, and 1080p.
- 720p and 1080p support 30 or 60 FPS; lower targets use 30 FPS.
- Saved the user selection and applied it on the Mac before opening the stream.
- Retained aspect-ratio preservation and hardware-accelerated encode/decode.

### Reliability and resource handling
- Made Android HTTP cleanup reliable on exceptional request paths.
- Validated host URI inputs before building control endpoint URLs.
- Prevented foreground-only status polling from accumulating requests and surfaced stale connections.
- Made screen-stream cancellation safer and introduced capped exponential reconnect backoff.
- Synchronized decoder surface teardown, reset the decoder on unusable input buffers, and improved cleanup behavior.
- Combined macOS audio status into one subprocess and bounded external process execution.

### macOS network hardening
- Made timestamp-window validation overflow-safe.
- Rejected invalid and excessive HTTP Content-Length values and bounded header/body/total request size.
- Added an overall deadline for incomplete HTTP requests.
- Limited the agent to Tailscale IPv4/IPv6 peers and loopback.
- Registers replay nonces only after HMAC signature validation.
- Removed pairing secrets from normal startup logs.
- Improved subprocess pipe handling and added timeouts.

### Release engineering
- Updated release packaging to use configured Gradle 8.9 instead of a missing Gradle wrapper.
- Added CI keystore decoding/validation, release signing, and mandatory APK signature verification.
- Expanded macOS CI to build/type-check the Swift package.
- Added complete build, signing, verification, and smoke-test instructions in docs/BUILD_NOTES.md.

## Requirements
- Android 8.0 (API 26) or later.
- macOS 14 or later for the Mac agent.
- Tailscale connectivity between the devices.
- macOS Screen Recording permission for live screen capture; Accessibility/Input Monitoring may be needed for the restriction layer.

## Before stable distribution
- Confirm CI succeeds for the exact release commit.
- Configure the four repository Actions signing secrets: ANDROID_KEYSTORE_BASE64, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, and ANDROID_KEY_PASSWORD.
- Verify the signed APK with apksigner and record its SHA-256 checksum.
- Install and smoke-test on a physical Android device paired with a Mac.
- Do not describe a debug APK or unsigned APK as the stable release artifact.

## Known verification limits
Automated CI compiles the Android debug variant and type-checks/builds the Swift package. It does not replace device testing of H.264 codec behavior, macOS privacy permissions, Tailscale routing, power commands, or malformed-HTTP resilience. The final published release should link a successful signed release workflow and include its checksum.
