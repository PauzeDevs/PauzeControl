# PauzeControl

> Private remote control and live screen viewer for your own macOS device.

PauzeControl is a small two-part project:

- Android controller — a private APK for connection management, Mac status, restriction control, activity feedback and live screen viewing.
- macOS agent — a background Swift service that exposes authenticated controls and captures the Mac display with ScreenCaptureKit.

## Current features

### Remote restriction

The Android controller can send authenticated commands to:

- RESTRICT — show the macOS restriction overlay and block normal keyboard/mouse input.
- ALLOW — remove the restriction.
- STATUS — query the current Mac state.

The restriction is intentionally separate from the macOS login screen. Knowing the Mac account password does not act as the PauzeControl release credential.

### Mac controls and system dashboard

PauzeControl can also request macOS lock, sleep, restart and shutdown actions, plus output mute/unmute and a 0–100 volume level. Restart and shutdown are confirmed in the Android UI.

The authenticated status endpoint includes live CPU, memory, disk, battery, uptime, macOS version and audio telemetry for the dashboard. The Android app refreshes this view periodically while open.

### Live screen

PauzeControl includes a view-only live Mac screen path:

- Maximum 1280×720 output.
- Maximum 30 FPS.
- Screen capture is performed natively on macOS with ScreenCaptureKit.
- Android decodes and displays the stream in a dedicated fullscreen viewer.
- The stream uses the same authenticated control secret to authorize the initial connection.
- Tailscale is intended as the private network transport.

The stream is a live JPEG frame stream in the current implementation. Its practical frame rate and bandwidth depend on the network and the Mac, but the application enforces a 720p/30 FPS ceiling.

## Security

The macOS agent generates a 256-bit pairing secret and stores it in the macOS Keychain.

The Android app stores the secret using an Android Keystore-backed AES-GCM key.

Control requests are authenticated with HMAC-SHA256 over a timestamp, nonce, method, path and request body. The Mac rejects stale timestamps and reused nonces.

The intended deployment is a private Tailscale network. PauzeControl does not require a public control server.

## Android UI

The Android app is designed around a dark, cinematic control-center layout with:

- Mac connection/status card
- Home / Activity / Settings navigation
- Restrict and Allow controls
- Connection and security sections
- Recent activity surface
- Live Screen entry point
- Animated entrance and interaction states
- Scrollable content for smaller displays

## macOS requirements

The Mac side requires macOS 14 or newer for the current Swift package target.

Screen viewing requires the user to grant macOS Screen Recording permission to the PauzeControl process.

Accessibility/Input Monitoring permissions may also be required for the restriction layer to block normal user input.

## Building

### macOS

From the mac/ directory:

    swift package dump-package
    swift build -c release

The repository also contains mac/install.sh for installing the executable as a per-user LaunchAgent.

### Android

Open android/ in Android Studio or build with Gradle:

    cd android
    gradle assembleDebug

GitHub Actions builds the debug APK automatically.

## Project structure

    PauzeControl/
    ├── android/                    # Android controller APK
    ├── mac/                        # macOS agent + screen capture
    ├── .github/workflows/          # CI builds/validation
    ├── SECURITY.md                 # Security notes
    └── README.md

## Important limitation

PauzeControl is a software restriction layer, not a hardware security boundary. Someone with physical access can still force a power-off, boot into recovery/another environment, or otherwise act outside the logged-in session.

## Status

Development build. The Android controller can be built by CI. Real-world macOS screen capture, Tailscale connectivity, permissions and the restriction behavior still need to be tested on the target MacBook before a production release.

---

© 2026 PauzeDevs — PauzeControl