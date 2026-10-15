# PauzeControl v1.5.0 Beta 1 — Android Design Update

**Version:** 1.5.0 (version code 5) · **Channel:** Beta/debug testing · **Package:** `com.pauze.control`

## Release scope

This is an **Android app design and interaction release**. The macOS agent source and every file under `mac/` remain unchanged. **The Mac does not need a source-code update** to use this version. Existing pairing, authentication, control endpoints, H.264 stream protocol, and macOS permissions are unchanged.

## What's new

- Reworked Home, Control, Activity, and Settings into separate Android navigation pages.
- Applied the PauzeX Developments **Skin Color Base** palette: warm paper, skin, clay, and ink tones.
- Improved contrast for navigation, status indicators, and activity entries.
- Added client-side Live Screen zoom up to **3.0×**, with pinch-to-zoom, two-finger panning, plus/minus controls, a zoom indicator, and reset.
- Added a timestamped in-session activity timeline for connection verification/loss, remote commands, and volume updates.

## Compatibility

- The existing Mac agent remains compatible; no Mac update is required.
- Zoom is applied in the Android viewer. It does not change the Mac encoder or require a new Mac endpoint.
- Existing control commands, pairing/authentication, and saved H.264 resolution/FPS preferences are retained.
- Activity entries are currently held in memory for the current app session; they are not a persistent audit log.

## Build logs and verification

GitHub Actions builds the Android APK with JDK 17, Gradle 8.9, and Android SDK Platform 35. The workflow runs `gradle clean assembleDebug --no-daemon`, validates the APK archive, and verifies its APK signature with `apksigner`.

The workflow also checks the changed-file list against `main`, fails if any `mac/` source file is included, and emits a build notice stating that the release is Android-only and the Mac source does not need updating.

Physical-device verification of zoom/pan and click coordinates at different zoom levels is still recommended.

## Distribution and signing

This is a debug-signed APK for direct GitHub installation and testing. It is **not** a production-signed or Google Play release. As with prior debug builds, Android may reject an in-place update if the installed APK has a different signing certificate. If that occurs, back up required connection details before uninstalling the older app; uninstalling removes app-local data.

© 2026 PauzeDevs — PauzeControl.
