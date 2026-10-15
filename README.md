<!-- Animated heading: falls back to alt text if the external animation service is unavailable. -->
<p align="center">
  <img src="https://readme-typing-svg.demolab.com?font=Inter&weight=600&size=30&duration=2800&pause=900&color=7C83FD&center=true&vCenter=true&width=760&lines=PauzeControl;Your+Mac%2C+from+Android;Private+remote+control+%C2%B7+Live+H.264+screen" alt="PauzeControl — your Mac, from Android" />
</p>

<p align="center">
  <strong>Control your Mac from your Android device over your private Tailscale network.</strong><br />
  Live screen streaming, optional system-output audio, system controls, device profiles, clipboard transfer, file transfer, and audio-device selection.
</p>

<p align="center">
  <a href="https://github.com/PauzeDevs/PauzeControl/actions/workflows/build-android.yml"><img alt="Android build" src="https://github.com/PauzeDevs/PauzeControl/actions/workflows/build-android.yml/badge.svg?branch=main" /></a>
  <a href="https://github.com/PauzeDevs/PauzeControl/actions/workflows/build-macos.yml"><img alt="macOS validation" src="https://github.com/PauzeDevs/PauzeControl/actions/workflows/build-macos.yml/badge.svg?branch=main" /></a>
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white" />
  <img alt="macOS 14+" src="https://img.shields.io/badge/macOS-14%2B-777777?style=flat-square&logo=apple&logoColor=white" />
  <img alt="Swift" src="https://img.shields.io/badge/Mac%20agent-Swift-F05138?style=flat-square&logo=swift&logoColor=white" />
</p>

---

## What is PauzeControl?

PauzeControl is made up of two parts: an Android app you use as the controller, and a small native agent that runs on the Mac you want to control. The two communicate over Tailscale, so you don't need to run a separate cloud server.

Use it to check your Mac's status, control common system actions, view or interact with its screen, and move selected text or files between devices. The v1.6.0 update adds Device Studio for multi-Mac profiles and personalisation, plus manual clipboard transfer, file transfer, and audio-device selection.

> **Current download:** [PauzeControl v1.5.0 Stable APK](https://github.com/PauzeDevs/PauzeControl/releases/download/v1.5.0/PauzeControl-v1.5.0.apk) · [v1.5.0 release notes](https://github.com/PauzeDevs/PauzeControl/releases/tag/v1.5.0)  
> **v1.6.0 Beta 1:** [release page](https://github.com/PauzeDevs/PauzeControl/releases/tag/v1.6.0-beta.1) · [direct APK](https://github.com/PauzeDevs/PauzeControl/releases/download/v1.6.0-beta.1/PauzeControl-v1.6.0-debug.apk). The beta APK is published and debug-signed; it does not include the live system-audio development feature described below.

## Features

### 📱 Android controller

- Home, Control, Activity, and Settings tabs.
- Device Studio for saving multiple Mac profiles and switching between them.
- Personalisation Studio with Warm Paper, Midnight, and Ocean themes, accent colours, and compact spacing.
- A live Mac status dashboard for CPU, memory, disk, battery, uptime, macOS version, and audio state.
- Remote lock, sleep, restart, shutdown, mute, volume, and restriction/allow controls.
- Manual clipboard transfer and authenticated file transfers.
- An activity feed for recent control actions and connection errors.

### 🖥️ Live screen

- Streams the Mac display as H.264 using Apple's ScreenCaptureKit and VideoToolbox.
- Decodes the stream on Android using MediaCodec.
- VIEW mode is for watching; CONTROL mode supports remote input where macOS permissions allow it.
- Optional Mac **system-output audio** (music, videos, and app sounds), disabled by default. The agent does not capture microphone input for this feature.
- The Android viewer has an audio on/off control; long-press it to open local playback volume and mute controls.
- Quality targets from 144p through 1080p, with 60 FPS options at 720p and 1080p.
- Pinch-to-zoom, panning, zoom buttons, and reset.

> **Live audio availability:** this feature is being developed on `feat/live-screen-system-audio`. Build both the Mac agent and Android app from that same branch. Previously published v1.5.0 Stable and v1.6.0 Beta 1 APKs do not contain this audio feature yet. Follow [the audio build and test guide](docs/LIVE_SCREEN_AUDIO.md).

Actual frame rate and image quality depend on the Mac, display size, temperature, and network conditions.

### 🍎 macOS agent

- Native Swift agent installed as a user LaunchAgent.
- Authenticated control endpoints using HMAC-SHA256 request authentication, timestamps, and one-use nonces.
- System telemetry and remote system/audio controls.
- Clipboard text read/write only when requested by the user.
- Chunked file transfer into a dedicated application-support directory.
- Audio input/output device listing and selection through Core Audio.

### 📦 v1.6.0 additions

| Feature | What it does |
| --- | --- |
| Multi-Mac profiles | Save a profile for each Mac and switch between them. |
| Personalisation Studio | Change theme, accent colour, and spacing. |
| Clipboard | Send or retrieve text manually; maximum 24 KiB. No continuous background clipboard polling. |
| File transfer | Upload and download files in chunks; maximum 50 MiB per file. Existing files are not overwritten. |
| Audio devices | List available Mac input/output devices and change the system default. |

## Requirements

**Mac**
- macOS 14 (Sonoma) or newer.
- Git, Xcode Command Line Tools, and the Swift toolchain.
- Tailscale installed and connected.
- Screen Recording permission for Live Screen. Accessibility is required for CONTROL mode; Input Monitoring may be needed for restriction features.

**Android**
- Android 8.0 (API 26) or newer.
- A way to install an APK, or JDK 17 + Android SDK Platform 35 + Gradle 8.9 to build it yourself.
- Tailscale installed and connected.

Both devices should be signed into your trusted Tailscale network. Do not expose the Mac agent's port to the public internet.

---

## Install the Mac agent

The agent must be installed on the Mac you want to control. Do this first so you can copy its pairing secret into Android.

### 1. Install the build tools

Open Terminal on the Mac:

```sh
xcode-select --install
```

If Xcode Command Line Tools are already installed, you can skip that command. Check that Swift and Git are available:

```sh
swift --version
git --version
```

### 2. Download the source

```sh
git clone https://github.com/PauzeDevs/PauzeControl.git
cd PauzeControl/mac
```

### 3. Build and install

```sh
swift package dump-package
swift build -c release
./install.sh
```

The installer creates the app bundle at `~/.local/PauzeControl/PauzeControl.app`, registers it as a LaunchAgent, starts it, and prints a pairing secret. Keep that secret private. You will need it in the Android app; don't put it in screenshots, issues, logs, or public messages.

Agent logs are written to:

```text
~/Library/Logs/PauzeControl/stdout.log
~/Library/Logs/PauzeControl/stderr.log
```

### 4. Allow the required macOS permissions

Open **System Settings → Privacy & Security**.

- Allow **Screen Recording** for PauzeControl to use Live Screen. Restart the agent after changing this permission.
- Allow **Accessibility** when using CONTROL mode for mouse/touch and keyboard input.
- Enable **Input Monitoring** only if the restriction feature requests it.

The exact permission prompts vary by macOS version. Only grant permissions needed for features you intend to use.

---

## Install the Android app

There are two options.

### Option A — Download a published APK

Use the [latest published release](https://github.com/PauzeDevs/PauzeControl/releases). The stable build remains v1.5.0, while [v1.6.0 Beta 1](https://github.com/PauzeDevs/PauzeControl/releases/tag/v1.6.0-beta.1) contains the Device Studio additions. Neither published APK includes live system-audio streaming yet. To test that feature, build both apps from the same development branch as described in [the audio test guide](docs/LIVE_SCREEN_AUDIO.md).

After downloading an APK to your phone, open it and follow Android's prompt to allow installation from that source. Only install APKs from a source you trust.

### Option B — Build v1.6.0 from source

On a computer, install JDK 17, Android SDK Platform 35, Android Build Tools, and Gradle 8.9. Confirm the tools are available:

```sh
java -version
gradle --version
adb version
```

From the repository root:

```sh
cd android
gradle clean assembleDebug --no-daemon
```

The debug APK is created at:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

You can transfer that APK to your phone and open it, or install it over USB with Android Debug Bridge (ADB):

```sh
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If you run the ADB commands from inside the `android` directory, the path is still `app/build/outputs/apk/debug/app-debug.apk`.

**Upgrade warning:** an APK must be signed with the same certificate as the installed app to update it in place. A locally built debug APK may have a different signing key from a downloaded APK. If Android reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, back up anything you need before uninstalling the old app; uninstalling clears its app-local preferences.

For signing, checksums, and the difference between debug and production signing, see [Build and release notes](docs/BUILD_NOTES.md).

---

## Connect Android to your Mac

### 1. Check Tailscale

Open Tailscale on both devices and make sure they are online in the same trusted network. On the Mac, find its Tailscale IPv4 address (usually in the `100.x.x.x` range) or its Tailscale hostname.

### 2. Add a Mac profile

Open PauzeControl on Android, go to **Settings → Device Studio**, and add a profile. Enter:

- **Profile name:** any label you will recognise, such as “MacBook Air”.
- **Host:** the Mac's Tailscale IP or hostname. Use the default port `47777` unless you have configured something else. Enter the host only, without `http://` or a URL path.
- **Pairing secret:** the secret printed by the Mac installer.

Save the profile and make it active. The Android app keeps saved pairing secrets encrypted using Android Keystore-backed storage. Keep the pairing secret private.

### 3. Confirm the connection

Return to the main screen and wait for the status dashboard to refresh. If the Mac is shown offline, check the Tailscale connection, host entry, pairing secret, and Mac agent logs before trying remote controls.

---

## Everyday use

### Control the Mac

Use the Home and Control areas for status and remote actions. Lock, sleep, restart, and shutdown affect the Mac itself—check which device profile is active before sending one of these commands. Restriction features are a software control layer, not a substitute for physical or operating-system security.

### View or control the screen

Open Live Screen from the app and choose VIEW or CONTROL mode. Start at 720p/30 FPS if the connection is unstable. Increase quality only if the network and Mac can keep up. Grant Screen Recording permission on the Mac, and Accessibility permission when using CONTROL mode.

### Listen to Mac system audio

On a build containing live-audio support, tap **♫ OFF** in the Live Screen controls to enable system-output audio. Tap it again to stop capture. Long-press the button to open Android's local playback volume and mute controls. Audio capture is requested only after the viewer explicitly enables it for that session; it starts off again the next time Live Screen opens. Turning it off returns the stream to video-only operation. Both the Mac agent and Android app must come from the same live-audio-enabled source revision.

### Use Device Studio

- **Profiles:** add, switch, or remove saved Macs.
- **Personalisation:** choose Warm Paper, Midnight, or Ocean; select an accent colour; enable compact spacing.
- **Clipboard:** explicitly read text from the Mac or write text to its clipboard. Clipboard data is not continuously collected in the background.
- **File transfer:** select a file to upload or a destination for a download. Transfers use chunks and support up to 50 MiB per file. Files are stored on the Mac under `~/Library/Application Support/PauzeControl/Transfers`; an existing file is not overwritten.
- **Audio:** view available input/output devices and choose the Mac's default device.

The v1.6.0 Device Studio operations require a matching v1.6.0 Mac agent and Android build. The v1.5.0 APK cannot call the new endpoints.

## Troubleshooting

**Android shows the Mac as offline**
- Make sure Tailscale is connected on both devices.
- Check the Mac Tailscale IP/hostname and port `47777`; don't enter a URL path.
- Check that the Mac agent is running and re-enter the pairing secret if authentication fails.
- Make sure the devices' system clocks are reasonably accurate.

**Live Screen is black or reconnects**
- Grant Screen Recording access in macOS Privacy & Security.
- Restart the Mac agent after changing the permission.
- Try 720p/30 FPS before increasing quality.

**Live Screen audio is silent**
- Confirm you built and installed the matching live-audio-enabled Mac agent and Android app; the published v1.6.0 Beta 1 APK predates this feature.
- Check that **♫ ON** is displayed, the Android local mute is off, and the playback volume is raised.
- Play audio locally on the Mac and verify that the system output is not muted.
- Disconnect/reconnect the live session after enabling or disabling audio.

**Clipboard, file transfer, or audio options fail**
- Confirm that both the Android app and Mac agent are from v1.6.0 source.
- Confirm the profile selected in Device Studio is the intended Mac.
- Check the Mac agent's stderr log for the reported failure.

**The APK refuses to update**
- The installed and new APK must use the same signing certificate. Back up needed data first; uninstalling the old app removes its local data.

## Security notes

- PauzeControl is designed for a private Tailscale network. Do not forward port `47777` to the public internet.
- The application protocol uses HTTP over the private network and HMAC-authenticated requests; it does not add a separate application-layer TLS connection.
- Keep the pairing secret out of public logs, screenshots, issues, and messages.
- Clipboard transfer is manual, not continuous background monitoring.
- The restriction overlay is not a hardware security boundary. Someone with physical access may still shut down the Mac or use other means to regain control.

Use PauzeControl only on devices you own or are authorised to administer.

## Build status and release notes

- [Android build](https://github.com/PauzeDevs/PauzeControl/actions/workflows/build-android.yml)
- [macOS validation](https://github.com/PauzeDevs/PauzeControl/actions/workflows/build-macos.yml)
- [v1.6.0 beta notes](docs/RELEASE_NOTES_v1.6.0.md)
- [Build, signing, and verification guide](docs/BUILD_NOTES.md)

The CI workflows validate compilation and source structure. They do not replace testing on an actual Mac and Android phone. Clipboard behaviour, file transfer, macOS permissions, audio-device changes, Tailscale routing, and upgrade signing should be smoke-tested before treating the release as stable.

## Project layout

- `android/` — Android controller (Java + XML)
- `mac/` — native macOS agent (Swift)
- `docs/BUILD_NOTES.md` — build, installation, signing, and verification guide
- `docs/RELEASE_NOTES_v1.6.0.md` — v1.6.0 beta changes
- `docs/LIVE_SCREEN_AUDIO.md` — live system-audio protocol and manual test plan
- `.github/workflows/` — CI and release workflows

## License

Copyright © 2026 PauzeDevs. All rights reserved.

PauzeControl is source-available and **not open-source** under the repository's current terms. See [LICENSE](LICENSE) for the governing conditions. No permission is granted to redistribute, repackage, sell, sublicense, or publish derivative builds without prior written permission from PauzeDevs.

---

<p align="center"><sub>© 2026 PauzeDevs · PauzeControl</sub></p>
