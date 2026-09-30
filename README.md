# PAUZE CONTROL

> Private Mac → Android remote control and adaptive H.264 live screen viewer for your own macOS device.

PauzeControl is a two-part system built around a private connection between an Android controller and a Mac agent. It is designed to give you a clean control-center experience without relying on a paid cloud/API service.

## ✨ Current Features

### 🖥️ Live Mac Screen

- Real-time **H.264** screen streaming from macOS to Android.
- Native macOS capture using **ScreenCaptureKit**.
- Native hardware-accelerated H.264 encoding through **VideoToolbox**.
- Android hardware decoding through **MediaCodec**.
- Adaptive streaming profiles:
  - 🔌 **On charger:** up to **1920×1080 (1080p) / 60 FPS**.
  - 🔋 **Battery ≥30%:** up to **1280×720 / 30 FPS**.
  - 🪫 **Battery <30%:** up to **1280×720 / 20 FPS**.
  - 🪫 **Battery <15%:** up to **1280×720 / 15 FPS** saver mode.
- Preserves the Mac display's aspect ratio instead of stretching the image.
- Dedicated fullscreen viewer with live stream information.
- View-only streaming — the Android viewer does not directly control the Mac screen.

The stream is only started while there is an active viewer. When nobody is watching, the capture and encoder stop completely to avoid unnecessary CPU/GPU, memory and battery use.

### 🔒 Remote Restriction

The Android controller can send authenticated commands to the Mac:

- **RESTRICT** — activate the macOS restriction layer and block normal keyboard/mouse input.
- **ALLOW** — remove the restriction.
- **STATUS** — retrieve the current Mac state.

The restriction layer is separate from the macOS login screen and is not intended to be a replacement for macOS security.

### ⚡ Mac Controls

PauzeControl supports authenticated requests for:

- Lock
- Sleep
- Restart
- Shutdown
- Audio mute/unmute
- Volume control from 0–100

Restart and shutdown actions are confirmed in the Android UI.

### 📊 Live System Dashboard

The Mac status endpoint can provide:

- CPU usage
- Memory usage
- Disk information
- Battery information
- Uptime
- macOS version
- Audio state/telemetry

The Android controller refreshes the dashboard while it is open.

### 📱 Android Control Center

The Android app uses a dark, cinematic control-center design with:

- Home / Activity / Settings navigation
- Mac connection card
- Connection status
- Restrict / Allow controls
- Refresh control
- Live Screen entry point
- Security information
- Recent activity
- Animated UI interactions
- Scrollable layouts for smaller displays

### 🌐 Private Networking

PauzeControl is designed to work over **Tailscale** as the private network transport.

There is no required paid cloud control server or third-party API for the core application.

## 🔐 Security

PauzeControl uses several layers of local authentication:

- The macOS agent generates a 256-bit pairing secret and stores it in the macOS Keychain.
- Android stores the pairing secret using an Android Keystore-backed AES-GCM key.
- Control requests use HMAC-SHA256 authentication over a timestamp, nonce, method, path and request body.
- The Mac rejects stale timestamps and reused nonces.
- The intended deployment is a private Tailscale network.

**Important:** PauzeControl is intended for controlling your own devices. Do not expose the control service directly to the public internet.

## 🍎 macOS Requirements

- macOS 14 or newer for the current Swift package target.
- Screen Recording permission is required for live screen viewing.
- Accessibility/Input Monitoring permissions may be required for the restriction layer.
- Tailscale is recommended for private device-to-device connectivity.

After changing Screen Recording permissions, restart PauzeControl so ScreenCaptureKit can use the updated authorization.

## 🛠️ Building

### macOS

From the `mac/` directory:

```bash
swift package dump-package
swift build -c release
```

The repository also includes `mac/install.sh` for installing PauzeControl as a per-user app bundle and LaunchAgent. The bundle includes the Screen Recording usage description required by macOS.

### Android

Open `android/` in Android Studio or build with Gradle:

```bash
cd android
gradle assembleDebug
```

GitHub Actions builds the Android debug APK automatically.

## 📁 Project Structure

```text
PauzeControl/
├── android/                    # Android controller APK
├── mac/                        # macOS agent + screen capture
├── .github/workflows/          # CI builds and validation
├── SECURITY.md                 # Security notes
├── LICENSE                    # PauzeControl license
└── README.md
```

## ⚠️ Limitations

PauzeControl is a software control/restriction layer, not a hardware security boundary. Someone with physical access to the Mac can still force a power-off, boot into another environment, use recovery tools, or otherwise act outside the logged-in session.

Live screen streaming depends on macOS permissions and the available CPU/GPU/network resources. The configured profile is a maximum target; actual FPS can vary depending on the Mac, display, network, thermal state and current workload. 1080p/60 FPS is used only while the Mac reports AC/charging power.

## 🚧 Project Status

PauzeControl is actively being developed. The current build includes the Android control center, authenticated Mac controls, system telemetry, adaptive H.264 live-screen streaming and power-aware capture behavior. Further work includes polishing the macOS background service/auto-start experience and continued testing across different Mac configurations.

## 📜 License

Copyright © 2026 PauzeDevs. All rights reserved.

PauzeControl is source-available but **not open-source**. See [`LICENSE`](LICENSE) for the full terms. No permission is granted to copy, modify, redistribute, repackage, sell, sublicense or publish derivative versions of the software without prior written permission from PauzeDevs.

---

**© 2026 PauzeDevs — PauzeControl. All rights reserved.**
