#!/bin/bash
# PauzeControl — Copyright (c) 2026 PauzeDevs. All rights reserved.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="${SCRIPT_DIR}/.build/release"
SOURCE_BINARY="${BUILD_DIR}/PauzeControl"
INSTALL_DIR="${HOME}/.local/bin"
INSTALL_BINARY="${INSTALL_DIR}/PauzeControl"
LAUNCH_AGENTS="${HOME}/Library/LaunchAgents"
PLIST="${LAUNCH_AGENTS}/com.pauze.control.plist"

if [[ ! -x "${SOURCE_BINARY}" ]]; then
  echo "Build first with: swift build -c release"
  exit 1
fi

mkdir -p "${INSTALL_DIR}"
mkdir -p "${LAUNCH_AGENTS}"
mkdir -p "${HOME}/Library/Logs/PauzeControl"

cp "${SOURCE_BINARY}" "${INSTALL_BINARY}"
chmod 755 "${INSTALL_BINARY}"

cat > "${PLIST}" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.pauze.control</string>
    <key>ProgramArguments</key>
    <array>
        <string>${INSTALL_BINARY}</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>ProcessType</key>
    <string>Interactive</string>
    <key>StandardOutPath</key>
    <string>${HOME}/Library/Logs/PauzeControl/stdout.log</string>
    <key>StandardErrorPath</key>
    <string>${HOME}/Library/Logs/PauzeControl/stderr.log</string>
</dict>
</plist>
EOF

launchctl bootout "gui/$(id -u)" "${PLIST}" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "${PLIST}"
launchctl kickstart -k "gui/$(id -u)/com.pauze.control"

echo "PauzeControl installed."
echo "Pairing secret:"
"${INSTALL_BINARY}" --print-token
