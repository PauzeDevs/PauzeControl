# PauzeControl v1.4.0 — Stable GitHub Release

**Version:** 1.4.0 (version code 4) · **Channel:** Stable GitHub distribution · **Package:** `com.pauze.control`

## What's included

- Interactive Live Screen CONTROL mode with tap-to-click, swipe scrolling, long-press dragging, and typed text. VIEW mode remains read-only and is the default.
- A connected-but-no-video status to distinguish an open stream connection from received screen frames.
- Authenticated Android-to-Mac input requests.

## Requirements

- Update both the Android app and Mac agent.
- Enable macOS Screen Recording for the video feed and Accessibility for remote input.

## Signing and support

This stable-channel APK is debug-signed because production release signing is not configured in the repository workflow. It is a direct GitHub distribution, not a Google Play release or a production-signed APK. A separately built debug APK may not have the same signing identity; uninstalling an older installation may be required before installing this release. Back up anything you need before uninstalling.

CI verifies the APK archive and signature. Physical-device video streaming and remote input should still be confirmed on the target Mac and Android device.
