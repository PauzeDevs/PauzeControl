# PauzeControl v1.3.0 — Complete Build Notes

These instructions build the Android controller and macOS agent from source. They also cover producing and verifying a **signed Android release APK**.

## Release identity

- Android application ID: com.pauze.control
- Release version name: 1.3.0
- Release version code: 2
- Android minimum API: 26 (Android 8.0)
- Android target/compile API: 35
- Java toolchain: 17
- Gradle: 8.9
- macOS deployment minimum: macOS 14

A successful debug build is not a signed release build. For distribution, use the release signing steps below and preserve the same signing keystore for all future updates.

## Android debug build

### Prerequisites

Install JDK 17, Android SDK Platform 35, and an Android SDK Build Tools version containing apksigner. Install Gradle 8.9 and configure ANDROID_HOME (or ANDROID_SDK_ROOT) to the Android SDK directory.

Verify the tools:

```sh
java -version
gradle --version
adb version
```

Gradle 8.9 should be reported. This repository does not currently commit a Gradle wrapper, so use an installed Gradle 8.9 or add a wrapper as a separately reviewed change.

### Build

From the repository root:

```sh
cd android
gradle clean assembleDebug --no-daemon
```

Output:

`android/app/build/outputs/apk/debug/app-debug.apk`

To install on a connected device with USB debugging enabled:

```sh
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

A debug APK is intended for development and testing, not as the stable distribution artifact.

## Android signed release build

### 1. Create the long-lived release keystore

Create a private directory and keep the keystore out of the repository:

```sh
mkdir -p "$HOME/.keys"
chmod 700 "$HOME/.keys"
keytool -genkeypair -v \
  -keystore "$HOME/.keys/pauze-release.jks" \
  -alias pauzecontrol \
  -keyalg RSA -keysize 4096 -validity 10000
chmod 600 "$HOME/.keys/pauze-release.jks"
```

Record the keystore password, key alias, and key password in a secure password manager. Back up the keystore securely. **Losing this keystore prevents you from signing compatible updates for users of this release.** Never commit the keystore or passwords to Git.

For an existing installation, use the same signing key that signed that installed APK; a new key will not be accepted as an in-place update.

### 2. Configure local signing variables

Set these environment variables in the terminal used for Gradle:

- ANDROID_KEYSTORE_PATH — absolute path to the JKS file
- ANDROID_KEYSTORE_PASSWORD — keystore password
- ANDROID_KEY_ALIAS — the alias, for example pauzecontrol
- ANDROID_KEY_PASSWORD — private key password

Example (replace password placeholders locally; do not commit them):

```sh
export ANDROID_KEYSTORE_PATH="$HOME/.keys/pauze-release.jks"
export ANDROID_KEYSTORE_PASSWORD='your-keystore-password'
export ANDROID_KEY_ALIAS='pauzecontrol'
export ANDROID_KEY_PASSWORD='your-key-password'
```

### 3. Build the signed APK

From the repository root:

```sh
cd android
gradle clean assembleRelease --no-daemon
```

When the signing variables are present, Gradle signs the release variant. Expected output:

`android/app/build/outputs/apk/release/app-release.apk`

If signing is not configured, Gradle can produce an unsigned release APK. Do not distribute that file as the stable release.

### 4. Verify the signature and calculate the checksum

From the repository root, identify the most recent Android Build Tools version and verify the APK:

```sh
APKSIGNER="$(find "$ANDROID_HOME/build-tools" -type f -name apksigner | sort -V | tail -n 1)"
test -x "$APKSIGNER"
"$APKSIGNER" verify --verbose --print-certs android/app/build/outputs/apk/release/app-release.apk
sha256sum android/app/build/outputs/apk/release/app-release.apk
```

Keep the SHA-256 checksum with the release notes. Before publishing, confirm the output path exists and signature verification exits successfully. Test installation and upgrade behavior on a physical Android device. An APK signature check does not replace device testing.

## Configure GitHub Actions release signing

The release workflow expects four **repository Actions secrets**. Configure them in GitHub: **Settings → Secrets and variables → Actions → Repository secrets**.

| Secret name | Value |
|---|---|
| ANDROID_KEYSTORE_BASE64 | Base64 encoding of the release keystore file |
| ANDROID_KEYSTORE_PASSWORD | Keystore password |
| ANDROID_KEY_ALIAS | Signing key alias |
| ANDROID_KEY_PASSWORD | Private key password |

On macOS, make the base64 value without line breaks:

```sh
base64 < "$HOME/.keys/pauze-release.jks" | tr -d '\n'
```

Copy that output directly into the ANDROID_KEYSTORE_BASE64 secret. Do not add the keystore itself to the repository or paste passwords into workflow YAML.

The CI workflow decodes the keystore into the runner's temporary directory, confirms the alias can be read, builds the release APK, runs apksigner verification, and uploads the verified APK as the PauzeControl-Android Actions artifact. When the workflow is triggered by a tag such as v1.3.0, it also attaches the APK to that GitHub Release.

If one of the secrets is missing or invalid, the workflow intentionally fails instead of quietly shipping an unsigned artifact. The repository maintainer must create/configure the release secrets; repository code cannot safely create or retrieve them.

## macOS agent build

### Prerequisites

- macOS 14 or later
- Xcode Command Line Tools and the Swift toolchain
- Screen Recording permission for the live stream
- Accessibility/Input Monitoring permission may be required for restriction/input blocking
- A connected Tailscale network for Android-to-Mac communication

Verify Swift is available:

```sh
swift --version
xcode-select -p
```

### Compile and install

From the repository root:

```sh
cd mac
swift package dump-package
swift build -c release
./install.sh
```

The install script places the app bundle and LaunchAgent in the current user's home directory. It prints the pairing secret as an explicit installation step. Keep this value private and enter it into the Android app's pairing field.

The macOS process is a native agent, not the Android APK. Build and install it on the Mac that will be controlled.

### Permissions and network

1. Open macOS System Settings → Privacy & Security.
2. Allow Screen Recording for PauzeControl if live screen capture is required.
3. Grant Accessibility/Input Monitoring only when required by the restriction functionality.
4. Restart PauzeControl after changing Screen Recording permission.
5. Confirm both devices are connected to Tailscale.

The agent accepts Tailscale IPv4 addresses in 100.64.0.0/10, Tailscale IPv6 addresses in fd7a:115c:a1e0::/48, plus loopback. It is not intended to be publicly exposed on port 47777.

## CI workflows

| Workflow | Purpose | Expected result |
|---|---|---|
| Build Android | Runs Gradle assembleDebug on pushes and pull requests | Debug APK artifact |
| Validate macOS | Runs swift package dump-package, swift build -c debug, per-file Swift parse, and Android tree presence check | Type-checked Swift package and syntax validation |
| Android APK | Builds a signed release APK on a v-prefixed tag or manually | Verified release APK artifact; GitHub Release attachment for v-prefixed tags |

The CI build validates source/buildability but cannot prove live screen capture, Tailscale routing, macOS privacy permissions, codec stability, or OS power commands work on every device. Complete the smoke tests below before calling a build production-stable.

## Release smoke-test checklist

- [ ] Android debug build completes.
- [ ] Signed release build completes.
- [ ] apksigner verification succeeds and the checksum is recorded.
- [ ] APK installs on a physical Android 8.0+ device.
- [ ] Existing installation upgrades successfully using the same signing key.
- [ ] Mac agent starts after installation and survives restart/login as expected.
- [ ] Pairing succeeds over Tailscale; connection fails from an unsupported network peer.
- [ ] Status telemetry refreshes and reports offline after loss of connectivity.
- [ ] H.264 streaming starts, stops, reconnects, and survives foreground/background transitions.
- [ ] Verify 144p, 240p, 360p, 480p, 720p/30, 720p/60, 1080p/30 and 1080p/60 where the Mac display and hardware support them.
- [ ] Test Screen Recording permission denied/granted and restart after granting.
- [ ] Verify each remote command deliberately, including restart/shutdown confirmation.
- [ ] Test malformed HTTP timestamps and Content-Length values in a controlled test environment.
- [ ] Review agent logs and confirm the pairing secret is not printed during normal startup.

## Troubleshooting

**Gradle cannot find the Android SDK:** set ANDROID_HOME/ANDROID_SDK_ROOT and install Platform 35 plus Android Build Tools.

**Release signing fails:** confirm that all four environment variables are set, the path is absolute, the key alias exists, and passwords correspond to the same keystore.

**GitHub release workflow stops at secret validation:** configure the repository Actions secrets listed above; there is no safe code fallback for missing signing credentials.

**APK update is rejected:** Android requires a matching signing certificate for in-place updates. Use the original keystore. If the APK was signed with a different key, it cannot update the installed package in place.

**Android cannot contact Mac:** verify Tailscale on both endpoints, the host field has no path component, the agent is listening on port 47777, and system clocks are accurate enough for the HMAC timestamp window.

## Reproducibility record

When distributing a release, record:

- Release tag and source commit SHA
- Android versionName and versionCode
- JDK, Gradle, compile SDK, and Build Tools versions
- CI workflow run URL and final status
- APK SHA-256
- Signing certificate digest (from apksigner output; never record the private key/password)
- Device/OS versions and smoke-test results

This repository currently defines v1.3.0 / versionCode 2. Update the versionCode for every future release that needs to install as an update.
