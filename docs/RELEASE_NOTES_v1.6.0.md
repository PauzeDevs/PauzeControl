# PauzeControl v1.6.0 Beta 1 🚀

A pretty big update for PauzeControl is here. This build adds Device Studio and a few features that make it easier to use PauzeControl across more than one Mac and move content between your devices.

## ✨ What's new?

- **Multi-Mac profiles** — save more than one Mac connection and switch profiles when needed.
- **Personalisation** — choose Warm Paper, Midnight, or Ocean, change the accent colour, and use compact spacing.
- **File transfer** — upload and download files between Android and your Mac, with a 50 MiB per-file limit.
- **Clipboard sharing** — send text to the Mac clipboard or retrieve text from the Mac when you ask it to. Clipboard access is manual; the app doesn't continuously collect clipboard contents in the background.
- **Audio controls** — view the Mac's available audio devices and change the default input or output.
- **Settings update** — Device Studio is available from Settings, and existing saved connection settings are migrated where possible.

## 🔒 A quick security note

The new remote operations use the existing authenticated connection system. Android pairing secrets are stored through Android Keystore-backed encryption. File transfers validate names and chunk order and avoid overwriting existing files.

PauzeControl is designed for a private Tailscale network. Don't expose port 47777 to the public internet, and keep your pairing secret private.

## 📦 Install and upgrade

This beta includes the Android application source version **1.6.0 (version code 6)** and requires the matching v1.6.0 macOS agent for the new Device Studio operations.

- **Android:** use the attached `PauzeControl-v1.6.0-debug.apk` after the release workflow has completed, or build `android/app/build/outputs/apk/debug/app-debug.apk` from source.
- **Mac:** from the repository's `mac/` directory, run `swift build -c release` and then `./install.sh`. Follow the full steps in the [README](../README.md#install-the-mac-agent).
- **Network:** keep both devices connected to your trusted Tailscale network and add the Mac Tailscale address plus the pairing secret in Android's Device Studio.

The beta APK is a debug-signed direct download, not a Google Play or production-signed release. Android may reject an in-place update if its signing certificate differs from the APK already installed.

## 🧪 Testing

The Android build and macOS validation workflows have passed for the merged v1.6.0 source. That confirms the code builds and parses; it is not a claim that every feature has been tested on every physical device. Please test pairing, profile switching, clipboard size limits, file uploads/downloads, theme changes, and actual audio-device selection before relying on them.

The existing v1.5.0 stable release is unchanged. This beta is a separate test build.

---

© 2026 PauzeDevs — PauzeControl.
