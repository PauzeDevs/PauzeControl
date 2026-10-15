# PauzeControl v1.5.0 — Stable GitHub Channel

**Version:** 1.5.0 (version code 5) · **Channel:** Stable GitHub distribution · **Package:** `com.pauze.control`

## Release scope

This release updates the **Android PauzeControl app design and viewer interactions only**. The macOS agent source and all files under `mac/` are unchanged. **The Mac does not need a source-code update** to use this release.

## What's included

- Independent Home, Control, Activity, and Settings navigation pages.
- PauzeX Developments Skin Color Base theme, with paper `#F2E5D8`, skin `#D6AA91`, clay `#BD775D`, clay-deep `#955740`, and ink `#28211E`.
- Higher-contrast navigation and status indicators.
- Live Screen zoom up to 3.0×, pinch-to-zoom, two-finger panning, manual plus/minus controls, zoom-level display, and reset.
- Timestamped in-session activity entries for connection and remote-control events.

## Mac compatibility

- **No macOS source update is required.**
- The existing Mac agent, authenticated endpoints, pairing flow, Tailscale network model, and H.264 streaming protocol are unchanged.
- Zoom is a client-side transformation of the Android live-screen view; it does not change the Mac's capture or encoding behavior.

## Build logs and verification

The release workflow builds the Android app with JDK 17, Gradle 8.9, and Android SDK Platform 35. It runs `gradle clean assembleDebug --no-daemon`, checks the APK archive, validates its signature using `apksigner`, generates SHA-256 checksum files, and uploads the APK assets from the same CI run.

A dedicated scope-check step compares this release branch with `main`; it fails if any changed path is under `mac/` and writes an explicit GitHub Actions notice confirming that the Mac source is unchanged and does not need an update.

Build validation is not a substitute for hands-on testing. Verify pinch/pan behavior and click accuracy on the target Android device.

## Distribution and signing

This is a debug-signed GitHub APK, not a production-signed or Google Play build. An in-place update can be rejected if Android detects a different signing certificate from the installed APK. If that happens, back up needed connection details before uninstalling the previous version, because uninstalling removes app-local data.

© 2026 PauzeDevs — PauzeControl.
