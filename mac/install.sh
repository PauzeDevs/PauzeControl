#!/bin/bash
# PauzeControl — Copyright (c) 2026 PauzeDevs. All rights reserved.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="${SCRIPT_DIR}/.build/release"
SOURCE_BINARY="${BUILD_DIR}/PauzeControl"

INSTALL_ROOT="${HOME}/.local/PauzeControl"
APP_BUNDLE="${INSTALL_ROOT}/PauzeControl.app"
APP_CONTENTS="${APP_BUNDLE}/Contents"
APP_MACOS="${APP_CONTENTS}/MacOS"
APP_RESOURCES="${APP_CONTENTS}/Resources"

LAUNCH_AGENTS="${HOME}/Library/LaunchAgents"
PLIST="${LAUNCH_AGENTS}/com.pauze.control.plist"

INFO_PLIST="${SCRIPT_DIR}/PauzeControl-Info.plist"

if [[ ! -x "${SOURCE_BINARY}" ]]; then
    echo "Build first with: swift build -c release"
    exit 1
fi

if [[ ! -f "${INFO_PLIST}" ]]; then
    echo "Missing ${INFO_PLIST}"
    exit 1
fi

mkdir -p     "${APP_MACOS}"     "${APP_RESOURCES}"     "${LAUNCH_AGENTS}"     "${HOME}/Library/Logs/PauzeControl"

cp "${SOURCE_BINARY}"     "${APP_MACOS}/PauzeControl"

cp "${INFO_PLIST}"     "${APP_CONTENTS}/Info.plist"

chmod 755     "${APP_MACOS}/PauzeControl"

cat > "${PLIST}" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.pauze.control</string>

    <key>ProgramArguments</key>
    <array>
        <string>${APP_MACOS}/PauzeControl</string>
    </array>

    <key>RunAtLoad</key>
    <true/>

    <key>KeepAlive</key>
    <true/>

    <key>ProcessType</key>
    <string>Interactive</string>

    <key>LimitLoadToSessionType</key>
    <string>Aqua</string>

    <key>StandardOutPath</key>
    <string>${HOME}/Library/Logs/PauzeControl/stdout.log</string>

    <key>StandardErrorPath</key>
    <string>${HOME}/Library/Logs/PauzeControl/stderr.log</string>
</dict>
</plist>
EOF

launchctl bootout     "gui/$(id -u)"     "${PLIST}"     2>/dev/null || true

launchctl bootstrap     "gui/$(id -u)"     "${PLIST}"

launchctl kickstart     -k     "gui/$(id -u)/com.pauze.control"

echo "PauzeControl installed as a macOS app bundle."
echo "App: ${APP_BUNDLE}"
echo "Pairing secret:"
"${APP_MACOS}/PauzeControl" --print-token
