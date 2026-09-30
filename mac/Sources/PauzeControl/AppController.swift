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

        do {
            let token = try SecurityStore.shared.token()

            print(
                "[PauzeControl] pairing secret: \(token)"
            )

            server = ControlServer(
                token: token,
                manager: manager
            )

            try server.start()
        } catch {
            NSLog(
                "[PauzeControl] startup failed: \(error.localizedDescription)"
            )
        }

        selfHealTimer = Timer.scheduledTimer(
            withTimeInterval: 20,
            repeats: true
        ) { [weak self] _ in
            guard
                let self,
                self.manager.restricted
            else {
                return
            }

            self.manager.start()
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
