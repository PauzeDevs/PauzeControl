# PauzeControl — Build, Install, and Release Notes

This guide covers local builds, signing, installation checks, and release verification for the Android controller and the native macOS agent.

## Current source and downloads

- **Source version on `main`:** v1.6.0 / Android version code 6.
- **Latest published stable APK:** [PauzeControl v1.5.0](https://github.com/PauzeDevs/PauzeControl/releases/tag/v1.5.0).
- **v1.6.0 Beta 1:** already published at [v1.6.0-beta.1](https://github.com/PauzeDevs/PauzeControl/releases/tag/v1.6.0-beta.1). The one-time workflow now fails closed if that tag or release already exists; it must not be reused to overwrite the existing APK.
- **Package:** `com.pauze.control`.
- **Android minimum:** API 26 (Android 8.0); compile/target SDK 35.
- **Android toolchain:** JDK 17 and Gradle 8.9.
- **Mac minimum:** macOS 14 or later.

A successful debug build is not a production-signed release build. Both the current published stable channel and the planned beta direct-download package are debug builds; neither is a Google Play release.

## Android debug build

### Prerequisites

Install JDK 17, Android SDK Platform 35, Android Build Tools, and Gradle 8.9. Configure `ANDROID_HOME` or `ANDROID_SDK_ROOT` to point to the SDK directory.

Verify the tools:

```sh
java -version
gradle --version
adb version
```

This repository does not currently commit a Gradle wrapper, so use an installed Gradle 8.9 or add a wrapper as a separately reviewed change.

### Build

From the repository root:

```sh
cd android
gradle clean assembleDebug --no-daemon
```

Output:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

Install to a connected device with USB debugging enabled:

```sh
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If Android rejects an update with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, the new APK was signed with a different key than the installed app. Back up anything important before uninstalling the old app; uninstalling clears application data.

## Android production signing

The configured Gradle release variant reads these environment variables:

- `ANDROID_KEYSTORE_PATH` — absolute path to the keystore
- `ANDROID_KEYSTORE_PASSWORD` — keystore password
- `ANDROID_KEY_ALIAS` — key alias
- `ANDROID_KEY_PASSWORD` — private-key password

Create a long-lived keystore outside the repository and back it up securely. Never commit the keystore or passwords. Losing the signing key prevents compatible updates for apps signed with it.

For an existing installation, a new release must use the same signing key as that installation. A different key cannot update it in place.

Example local environment setup (replace the example values locally; do not commit them):

```sh
export ANDROID_KEYSTORE_PATH="$HOME/.keys/pauze-release.jks"
export ANDROID_KEYSTORE_PASSWORD='your-keystore-password'
export ANDROID_KEY_ALIAS='pauzecontrol'
export ANDROID_KEY_PASSWORD='your-key-password'
cd android
gradle clean assembleRelease --no-daemon
```

Expected output:

```text
android/app/build/outputs/apk/release/app-release.apk
```

If the signing variables are missing, Gradle may create an unsigned release APK. Do not distribute an unsigned build as a release.

### Verify an APK

From the repository root:

```sh
APKSIGNER="$(find "$ANDROID_HOME/build-tools" -type f -name apksigner | sort -V | tail -n 1)"
test -x "$APKSIGNER"
"$APKSIGNER" verify --verbose --print-certs android/app/build/outputs/apk/debug/app-debug.apk
sha256sum android/app/build/outputs/apk/debug/app-debug.apk
```

Use the release output path instead when verifying a signed release variant. Keep the checksum and signing-certificate digest with the release record. Test installation and upgrade on a physical device.

## macOS agent build and install

### Prerequisites

- macOS 14 or later
- Xcode Command Line Tools and Swift
- Tailscale installed and connected
- Screen Recording permission for Live Screen; Accessibility for interactive CONTROL mode

Install Command Line Tools if needed:

```sh
xcode-select --install
swift --version
```

### Build and install

From the repository root:

```sh
git clone https://github.com/PauzeDevs/PauzeControl.git
cd PauzeControl/mac
swift package dump-package
swift build -c release
./install.sh
```

The installer places the app bundle at `~/.local/PauzeControl/PauzeControl.app`, registers the LaunchAgent at `~/Library/LaunchAgents/com.pauze.control.plist`, starts the agent, and prints the pairing secret. Keep this secret private and enter it in Android's Device Studio profile. Do not paste it into issues, screenshots, or public logs.

Logs are written to:

```text
~/Library/Logs/PauzeControl/stdout.log
~/Library/Logs/PauzeControl/stderr.log
```

### macOS permissions

Open **System Settings → Privacy & Security**.

1. Grant Screen Recording access for Live Screen.
2. Restart the agent after changing Screen Recording access.
3. Grant Accessibility when using Live Screen's CONTROL mode.
4. Enable Input Monitoring only when needed by restriction functionality.

Permission UI can vary between macOS releases. Grant only permissions needed for the features you use.

### Network

Both devices must be connected to the same trusted Tailscale network. Enter the Mac's Tailscale IP or hostname in Android, without a URL path. The agent listens on port 47777 by default and restricts peers to Tailscale address ranges plus loopback.

Do not forward TCP port 47777 to the public internet.

## v1.6.0 beta packaging

The `.github/workflows/android-release.yml` workflow is a one-time v1.6.0 beta publisher. It is gated by the `[release-v1.6.0-beta]` commit marker, but now fails early if tag `v1.6.0-beta.1` or its GitHub Release already exists. It must not replace the existing APK or checksum. For any future version, create a reviewed workflow with a new tag/version and immutable-asset behavior.

The beta APK is debug-signed and is intended for direct testing, not Google Play distribution. Do not label it production-signed.

## CI workflows

| Workflow | Purpose |
| --- | --- |
| [Build Android](../.github/workflows/build-android.yml) | Runs the Android debug build on pushes, pull requests, and manual dispatch; uploads an APK artifact. |
| [Validate macOS](../.github/workflows/build-macos.yml) | Builds the Swift package, parses Swift source files, and checks that the Android source tree exists. |
| [PauzeControl Android Beta Release](../.github/workflows/android-release.yml) | One-time v1.6.0 beta workflow; intentionally fails closed when its existing tag/release is present to protect published assets. |

CI validates compilation and source structure; it does not confirm runtime behaviour on every Mac/Android device. Before calling v1.6.0 stable, test on physical devices.

## Release smoke-test checklist

- [ ] Android debug build completes and APK archive verification passes.
- [ ] APK installs on a physical Android 8.0+ device.
- [ ] Existing installation upgrade works with the same signing key.
- [ ] Mac agent starts after install and after login/restart as expected.
- [ ] Pairing works over Tailscale and fails from an unsupported network peer.
- [ ] Status telemetry refreshes and shows offline after connectivity is lost.
- [ ] Live screen starts, stops, reconnects, and survives foreground/background changes.
- [ ] Test supported quality targets on the actual Mac hardware.
- [ ] Test Screen Recording permission denied/granted and restart after granting.
- [ ] Test profile add/switch/remove and legacy saved-connection migration.
- [ ] Test theme/accent changes, including returning to the Home screen.
- [ ] Test clipboard copy in both directions, empty text, and the 24 KiB limit.
- [ ] Test file upload/download, empty files, chunk order, 50 MiB limit, and overwrite refusal.
- [ ] Test audio input/output listing and selecting actual Core Audio devices.
- [ ] Confirm pairing secrets do not appear in normal logs.
- [ ] Record source tag, commit SHA, CI workflow run, APK SHA-256, signing certificate digest, and device test results.

## Troubleshooting

**Android cannot contact the Mac:** check Tailscale on both endpoints, host/IP, port 47777, agent status, and system-clock accuracy. Re-enter the secret if authentication fails.

**Live Screen is black:** grant Screen Recording permission and restart the agent; then try 720p/30 FPS.

**Release signing fails:** check all four environment variables, the absolute keystore path, key alias, and passwords.

**An APK will not update:** Android requires a matching signing certificate for in-place upgrades. Use the original keystore. Uninstalling a conflicting installation clears local preferences.

**A v1.6.0 control is missing or errors:** both Android and the Mac agent must use matching v1.6.0 source. The v1.5.0 APK cannot access the new Device Studio operations.

## Reproducibility record

For each release, record the release tag and source commit SHA, Android versionName/versionCode, JDK/Gradle/SDK versions, CI run, APK SHA-256, signing-certificate digest, device/OS versions, and smoke-test results. Never include a private signing key or password in the record.

---

© 2026 PauzeDevs — PauzeControl.
