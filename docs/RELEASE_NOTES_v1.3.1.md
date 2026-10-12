# PauzeControl v1.3.1 — Release Notes

**Version name / code:** 1.3.1 / 3  
**Distribution:** GitHub APK downloads only; not a Google Play release  
**Signing:** Debug signing for direct installation and testing

## Changes

- Corrected the Android action-switch thumb mapping: ON keeps the thumb on the right; OFF positions it on the left.
- Preserved the existing control layout, colours, and sizing while fixing the switch state indicator.
- Carries forward the v1.3.0 control-center redesign, Home / Control / Activity / Settings navigation, and manual H.264 quality selection.
- Continues to use the project’s existing Android reliability and macOS network-hardening changes.

## Release channels

- **Beta:** `v1.3.1-beta.1` — for beta testers; includes a SHA-256 checksum.
- **Stable:** `v1.3.1` — stable GitHub distribution for direct APK installation; includes a SHA-256 checksum.
- Both APKs are built from the same release commit and are debug-signed. They are not uploaded to Google Play.

## Verification scope

CI builds the Android debug APK, validates the APK archive, and verifies its Android package signature before publishing the releases. Device-level interaction with a physical Android phone should still be confirmed after installation.
