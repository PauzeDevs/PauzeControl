// PauzeControl — Copyright (c) 2026 PauzeDevs. All rights reserved.

import AppKit

final class AppController: NSObject, NSApplicationDelegate {
    private var manager: RestrictionManager!
    private var server: ControlServer!
    private var selfHealTimer: Timer?

    func applicationDidFinishLaunching(
        _ notification: Notification
    ) {
        NSApp.setActivationPolicy(.accessory)

        manager = RestrictionManager()
        manager.start()
        startServerIfNeeded()

        // Recover both restriction UI/input blocking and the network listener.
        // A failed NWListener does not necessarily terminate the LaunchAgent,
        // so KeepAlive alone cannot be relied on to restart this process.
        selfHealTimer = Timer.scheduledTimer(
            withTimeInterval: 20,
            repeats: true
        ) { [weak self] _ in
            guard let self else { return }
            if self.manager.restricted {
                self.manager.start()
            }
            if self.server?.isListening != true {
                self.startServerIfNeeded()
            }
        }
    }

    private func startServerIfNeeded() {
        if let current = server {
            guard !current.isListening else { return }
            do {
                try current.start()
            } catch {
                NSLog("[PauzeControl] server retry failed: \(error.localizedDescription)")
            }
            return
        }

        do {
            let token = try SecurityStore.shared.token()
            NSLog("[PauzeControl] secure pairing secret loaded.")
            let newServer = ControlServer(token: token, manager: manager)
            server = newServer
            try newServer.start()
        } catch {
            NSLog("[PauzeControl] startup failed: \(error.localizedDescription)")
        }
    }

    func applicationShouldTerminateAfterLastWindowClosed(
        _ sender: NSApplication
    ) -> Bool {
        false
    }

    func applicationWillTerminate(
        _ notification: Notification
    ) {
        selfHealTimer?.invalidate()
        selfHealTimer = nil
    }
}
