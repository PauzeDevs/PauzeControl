// PauzeControl — Copyright (c) 2026 PauzeDevs. All rights reserved.

import AppKit
import Foundation

if CommandLine.arguments.contains("--print-token") {
    do {
        print(try SecurityStore.shared.token())
    } catch {
        fputs(
            "Unable to load pairing secret: \(error.localizedDescription)\n",
            stderr
        )
        exit(1)
    }

    exit(0)
}

let application = NSApplication.shared
let delegate = AppController()

application.delegate = delegate
application.run()
