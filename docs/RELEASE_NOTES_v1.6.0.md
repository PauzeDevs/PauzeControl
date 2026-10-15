# PauzeControl v1.6.0 — Unreleased

**Version:** 1.6.0 (version code 6) · **Channel:** Development source only · **Package:** `com.pauze.control`

No v1.6.0 GitHub release or downloadable APK is published by this change.

## Planned feature set

- **Multi-Mac Device Manager:** save named Mac profiles, each with an independently encrypted pairing secret, switch the active Mac and remove saved profiles.
- **Personalisation Studio:** Warm Paper, Midnight and Ocean themes, Terracotta/Blue/Violet/Emerald accents and compact spacing.
- **Cross-device clipboard:** explicit, user-triggered text transfer between Android and macOS, capped at 24 KiB. No background clipboard polling or syncing.
- **Secure file transfer:** authenticated chunked upload/download to the macOS `Application Support/PauzeControl/Transfers` directory, with 24 KiB chunks, 50 MiB maximum file size, filename/path validation, ordered writes and no overwrites.
- **Advanced audio controls:** enumerate macOS audio devices and select the system-default input and output via Core Audio.

## Compatibility and requirements

- The new remote operations require the matching updated macOS agent from this source tree. The v1.5.0 APK does not provide these Device Studio controls.
- Continue to connect devices over the existing trusted private Tailscale network.
- All new remote routes use the existing HMAC request authentication.
- Existing four-tab navigation and live-screen controls remain in place; Device Studio is launched from Settings.
- Saved host data is migrated from the earlier activity preferences when the app starts, preserving existing v1.5.x connections.

## Verification status

GitHub Actions Android build and macOS Swift validation must pass for the final merged commit. Live-device verification is still required before publishing: test adding/switching/removing Mac profiles, theme changes on return to Home, large and empty file transfers, clipboard size limits, and selection of actual Core Audio devices.

The development branch does not create or modify existing v1.5.0 stable or beta releases.

© 2026 PauzeDevs — PauzeControl.
