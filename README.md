# PauzeControl

> **Private remote control for your Mac — from your Android phone.**

PauzeControl is a personal, security-focused remote control system built by **PauzeDevs** for controlling access to a paired macOS device from an Android phone.

## ✨ What it does

- 🔒 **Restrict Mac** — remotely place the paired Mac into its restricted state.
- 🔓 **Allow Mac** — remotely restore normal access.
- 📡 **Live status** — view the Mac's connection and control state.
- ⚡ **Quick controls** — perform the main actions directly from the dashboard.
- 🕒 **Activity** — designed to surface recent remote-control events.
- 🔐 **Pairing protection** — commands use a locally stored pairing secret.
- 🌐 **Remote connectivity** — designed around a private Tailscale connection rather than requiring both devices to be on the same Wi-Fi network.
- 🎨 **Modern Android UI** — dark control-center design, responsive cards, animated interactions and dedicated Home, Activity and Settings areas.

## 🧩 Project structure

```text
PauzeControl/
├── android/                 # Android controller application
├── mac/                    # macOS control agent
├── .github/workflows/      # CI validation and Android builds
└── README.md
```

## 📱 Android app

The Android application provides the mobile control center:

- Mac connection dashboard
- Restrict / Allow controls
- Connection configuration
- Status refresh
- Activity and security information
- Scroll-based UI animations
- Persistent local connection configuration

The Android app is intended to be used as a **private controller**, not as a general-purpose remote desktop application.

## 🍎 macOS agent

The macOS side runs as a native AppKit application and handles the paired Mac's control state. It also exposes the pairing secret through the command-line token flow used during setup.

## 🔐 Security model

PauzeControl is designed for private use between the owner's phone and Mac.

- Pairing credentials are stored locally on the Android controller.
- Commands are intended to travel through a private Tailscale network.
- The controller targets a configured Mac rather than discovering arbitrary devices.
- No public remote-control server is required by the project architecture.

**Important:** network privacy and application authentication are separate layers. Keep your Tailscale account, pairing secret and device access private.

## 🚧 Current status

PauzeControl is under active development.

Current focus:

1. Polishing the Android control-center UI.
2. Completing reliable Android ↔ macOS pairing.
3. Validating restriction / allow behaviour on real macOS hardware.
4. Improving activity and status reporting.
5. Keeping CI builds reproducible.

The application should be considered **experimental until the macOS agent has been tested on the target Mac**.

## 🛠️ Development

### Android

Open the `android` project with Android Studio and build the debug APK using the Gradle wrapper.

### macOS

The macOS project is a Swift Package Manager executable targeting macOS 14 or later.

```bash
cd mac
swift package resolve
swift build
```

## 📄 License

This project is private and intended for personal use by PauzeDevs. Do not redistribute or deploy it for unauthorized access to another person's computer.

---

**PauzeControl** • Private Remote Control • Built by **PauzeDevs**
