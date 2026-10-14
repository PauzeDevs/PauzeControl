# PauzeControl

**Private macOS control center and live H.264 screen viewer for Android.**

PauzeControl consists of an Android controller and a native macOS agent. They communicate directly over a private Tailscale network; the project does not require a hosted control backend.

> **Downloads:** [PauzeControl v1.4.0 Debug APK](https://github.com/PauzeDevs/PauzeControl/releases/download/v1.4.0-beta.1/PauzeControl-v1.4.0-debug.apk) · [v1.4.0 Stable GitHub release](https://github.com/PauzeDevs/PauzeControl/releases/tag/v1.4.0) · [v1.4.0 Beta release](https://github.com/PauzeDevs/PauzeControl/releases/tag/v1.4.0-beta.1).
>
> **Signing:** Both channel APKs are debug-signed direct GitHub downloads. The stable channel is not a production-signed or Google Play release. See [Build notes](docs/BUILD_NOTES.md).

## Features

### Android control center
- Four persistent navigation tabs: Home, Control, Activity, and Settings.
- Connection card and live macOS system dashboard.
- Remote restriction/allow commands, lock, sleep, restart, shutdown, mute/unmute, and volume control.
- Connection status and automatic refresh while the app is foregrounded.
- PauzeControl application icon and dark control-center UI.

### Live screen
- Native macOS display capture through ScreenCaptureKit.
- Hardware-accelerated H.264 encoding using VideoToolbox.
- Android hardware decoding through MediaCodec.
- Full-screen viewer with a manual quality selector and reconnect controls.
- Aspect-ratio-preserving viewer with separate read-only VIEW and interactive CONTROL modes.

Manual quality targets:

| Target | Frame-rate options |
|---|---|
| 144p, 240p, 360p, 480p | 30 FPS |
| 720p | 30 or 60 FPS |
| 1080p | 30 or 60 FPS |

The selected target is sent to the Mac and persisted on Android. Battery level does not silently replace the selected target. Actual output can still vary with display dimensions, available hardware resources, thermal limits, and network conditions.

### macOS agent
- Authenticated HTTP control API.
- HMAC-SHA256 request authentication with timestamps and one-use nonces.
- Pairing secret stored in the macOS Keychain; Android stores its copy through Android Keystore-backed AES-GCM encryption.
- System telemetry for CPU, memory, disk, battery, uptime, macOS version, and audio state.
- Optional restriction overlay and input event tap, subject to macOS permissions.
- Live Screen offers view-only and interactive modes; interactive mode supports taps, swipe scrolling, long-press dragging, and typed text. macOS Accessibility permission is required for remote input.

## Requirements

### Android build
- JDK 17.
- Android SDK Platform 35 and Android Build Tools installed.
- Gradle 8.9.
- Android 8.0 (API 26) or newer on the device.

### macOS agent
- macOS 14 or newer.
- Xcode Command Line Tools with Swift toolchain.
- Screen Recording permission for live screen capture.
- Accessibility/Input Monitoring permissions may be required for the restriction layer.
- Tailscale installed and connected on both devices.

## Quick start

1. Connect the Mac and Android device to the same trusted Tailscale network.
2. Build and install the Android APK using the [Android build instructions](docs/BUILD_NOTES.md#android-debug-build).
3. Build/install the Mac agent using the [macOS instructions](docs/BUILD_NOTES.md#macos-agent).
4. During installation, securely record the displayed pairing secret. Enter the Mac's Tailscale IP address or resolvable Tailscale hostname and the pairing secret in Android, then save the connection.
5. Grant the required macOS permissions, restart the agent after changing Screen Recording access, and test Status before trying restriction or power commands.

Use only on devices you own or are explicitly authorized to administer.

## Build and release

The project has separate CI workflows for:
- Android debug builds (Build Android).
- macOS package build/type-check plus Swift syntax checks (Validate macOS).
- Android beta/debug and stable GitHub channel APKs, triggered by the `v1.4.0` release tag. Both are currently debug-signed; production release signing requires the project’s private release keystore.

A green debug build does not mean a production-signed APK has been generated. Signing setup and release limitations are documented in **[docs/BUILD_NOTES.md](docs/BUILD_NOTES.md)**.

## Security model and limitations

- The Mac agent accepts connections from Tailscale IPv4 peers in 100.64.0.0/10, Tailscale IPv6 peers in fd7a:115c:a1e0::/48, and loopback.
- Do not expose TCP port 47777 directly to the public internet. The application protocol uses HTTP rather than end-to-end TLS; its intended protection boundary is the private network plus HMAC authentication for requests.
- Keep the Android pairing secret private. Do not paste it into issues, screenshots, logs, or public posts.
- The restriction overlay is a software control layer, not a hardware or operating-system security boundary. A person with physical access can power off the Mac, use recovery tools, or otherwise act outside the logged-in session.
- Restart the Mac agent after changing Screen Recording permissions.

## Troubleshooting

**Android cannot connect**
- Confirm both devices are connected to Tailscale.
- Enter only the Mac Tailscale hostname/IP (with optional port), without a URL path.
- Confirm the agent is running and listening on port 47777.
- Re-save the pairing secret if authentication fails, and check that the Mac/Android clocks are accurate.

**Live screen is black or immediately reconnects**
- Grant Screen Recording permission to PauzeControl in macOS Privacy & Security.
- Restart the agent after changing permission.
- Try 720p/30 FPS first, then increase quality if the link and Mac can sustain it.

**Release workflow fails before building**
- The repository Actions secrets for release signing may be absent or invalid. See [release signing configuration](docs/BUILD_NOTES.md#configure-github-actions-release-signing).

**APK installs but Android rejects an update**
- Android requires an update to be signed with the same signing key as the installed build. Do not replace or discard the release keystore. A differently signed APK may require uninstalling the older app first, which removes its app-local preferences.

## Project layout

- android/ — Android controller (Java + XML)
- mac/ — Native macOS agent (Swift)
- docs/BUILD_NOTES.md — reproducible build, signing, and verification
- AUDIT_REPORT.md — audit remediation and remaining runtime tests
- SECURITY.md — security notes
- .github/workflows/ — CI and release build workflows

## License

Copyright © 2026 PauzeDevs. All rights reserved.

PauzeControl is source-available and **not open-source** under the repository's current terms. See LICENSE for the governing conditions. No permission is granted to redistribute, repackage, sell, sublicense, or publish derivative builds without prior written permission from PauzeDevs.

---

© 2026 PauzeDevs — PauzeControl.
