# PauzeControl v1.4.0 — Debug Release

**Version:** 1.4.0 (version code 4) · **Channel:** Beta/debug testing · **Package:** `com.pauze.control`

## What's new

- Added an interactive CONTROL mode to Live Screen alongside the default VIEW mode.
- Tap the streamed Mac screen to click, swipe to scroll, and long-press while dragging to move or select items.
- Added a text-entry control that types into the Mac app currently in focus.
- Added an explicit connected-but-no-video status when no screen frames arrive.
- Added an authenticated Mac endpoint for remote pointer and text input.

## Setup and permissions

- Update both the Android app and Mac agent from this release. The input endpoint is not available in older Mac agents.
- macOS Accessibility permission is required for CONTROL mode. Screen Recording permission remains required for the video feed.
- VIEW mode remains read-only.

## Distribution and verification

This is a debug-signed APK for direct GitHub installation and testing. It is not a Google Play release or production-signed build. The beta and stable GitHub channel assets are built from the same CI run and use the same signing identity. The APK archive and signature are verified by CI; physical-device streaming and input behavior still need device verification.
