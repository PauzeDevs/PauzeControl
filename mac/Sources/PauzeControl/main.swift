// PauzeControl — Copyright (c) 2026 PauzeDevs. All rights reserved.

import AppKit
import Foundation
import Darwin

if CommandLine.arguments.contains("--print-token") {
    do {
        print(try SecurityStore.shared.token())
    } catch {
        fputs(
            "Unable to load pairing secret: \(error.localizedDescription)\n",
            stderr
        )
        exit(EXIT_FAILURE)
    }

    exit(EXIT_SUCCESS)
}

let application = NSApplication.shared
let delegate = AppController()

application.delegate = delegate
application.run()
