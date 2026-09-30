# PauzeControl Security

The macOS secret is generated randomly and stored in the macOS Keychain. The Android copy is stored using an Android Keystore-backed AES-GCM key.

The secret itself is never sent in request headers. Each command is authenticated with HMAC-SHA256 over a timestamp, random nonce, HTTP method, path and body. The macOS agent rejects stale timestamps and reused nonces.

The intended network is a private Tailscale network. No public cloud control server is required.

The restriction overlay is a software restriction layer, not a hardware security boundary.
