# PauzeControl Live Screen System Audio

This document covers the opt-in system-output audio feature on the live-audio development branch. It is not included in previously published v1.5.0 Stable or v1.6.0 Beta 1 APKs.

## Design

- Capture source: macOS ScreenCaptureKit system output, not the Mac microphone.
- Trigger: audio capture is off at the start of every Live Screen session and is requested only after the user explicitly enables it, causing the authenticated Android viewer to open `/v1/screen?audio=1`.
- Format: mono signed 16-bit little-endian PCM, 24,000 samples/second.
- Transport: the existing authenticated PZV1 screen TCP stream; video-only clients continue to use the original video packet format.
- Packet flags: video uses bit 0 for keyframes; audio uses flag `0x02`. Audio packets have zero width/height and carry a presentation timestamp in microseconds.
- Playback: Android `AudioTrack` consumes the PCM stream. The live viewer keeps a bounded queue and drops old audio chunks rather than letting latency or memory grow indefinitely.
- Controls: the audio button toggles system capture; long-press opens local Android playback volume and mute controls. These controls do not change the Mac's system volume.
- When the last audio-enabled viewer disconnects or turns audio off, ScreenCaptureKit is restarted without audio capture.

## Build both ends

Use this same source branch for both platforms; a mismatched Mac agent and Android app will not negotiate the same audio packet protocol.

### macOS

From the repository root on the Mac:

```sh
cd mac
swift package dump-package
swift build -c debug
```

For the installed agent, use the documented installer after a Release build:

```sh
swift build -c release
./install.sh
```

Keep the agent signed into the same trusted Tailscale network as the Android device. Grant macOS Screen Recording permission to the app/agent as prompted; restart the agent after changing permissions.

### Android

From the repository root:

```sh
cd android
gradle clean assembleDebug --no-daemon
```

The debug APK is written to `android/app/build/outputs/apk/debug/app-debug.apk` relative to the repository root. Use the matching build on Android and ensure it can connect to the Mac agent before testing audio.

## Manual test plan

CI compiles the source, but it does not exercise ScreenCaptureKit on a real Mac or AudioTrack on a physical Android device. Do not treat this feature as release-ready until these checks pass.

1. **Video-only baseline:** open Live Screen with audio off. Confirm H.264 video starts, quality changes still work, control mode still works, and this viewer does not request audio capture.
2. **Start audio:** play a local song or test tone on the Mac, enable audio in the viewer, and verify the phone produces the Mac's system output without capturing the microphone.
3. **Playback controls:** long-press the audio button, verify volume changes are local to Android, toggle mute, then unmute.
4. **Stop audio:** turn audio off while the stream stays open. Verify playback stops and video continues without reconnect loops.
5. **Lifecycle:** close/reopen Live Screen, background/foreground Android, manually reconnect, and briefly interrupt Tailscale. Verify recovery without stuck audio, duplicate playback workers, or excessive delay.
6. **Multiple viewers:** if testing with two Android viewers, verify audio remains active while one requests it and turns off after the last audio-enabled viewer disconnects.
7. **Resource check:** compare Activity Monitor CPU, memory, and energy impact for a 10-minute video-only session and a 10-minute video-plus-audio session. Record Mac model, macOS version, quality/FPS, and network type; do not claim zero impact without measurements.
8. **Failure paths:** deny Screen Recording permission, mute Mac system output, turn off Tailscale, and test a stale Android build. Confirm failures are understandable and audio-specific issues do not break video-only viewing.

## Privacy and performance

Audio is opt-in, and only system output is included. The app does not store captured audio to disk. The stream remains on the existing authenticated private-network path. Do not expose port 47777 to the public internet.

Audio capture, PCM conversion, packet scheduling, and playback add some CPU, network, battery, and thermal load. The implementation uses low-rate mono PCM and bounded buffers to limit extra work and stale-audio buildup, but real-device measurement is required before a stable release.
