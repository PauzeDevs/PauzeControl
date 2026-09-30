// PauzeControl — Copyright (c) 2026 PauzeDevs. All rights reserved.

import Foundation
import Darwin

final class StateStore {
    private let fileURL: URL

    init() {
        let base = FileManager.default.urls(
            for: .applicationSupportDirectory,
            in: .userDomainMask
        )[0]

        let directory = base.appendingPathComponent(
            "PauzeControl",
            isDirectory: true
        )

        try? FileManager.default.createDirectory(
            at: directory,
            withIntermediateDirectories: true
        )

        fileURL = directory.appendingPathComponent("state.json")
    }

    var isRestricted: Bool {
        get {
            guard
                let data = try? Data(contentsOf: fileURL),
                let value = try? JSONSerialization.jsonObject(
                    with: data
                ) as? [String: Any]
            else {
                return false
            }

            return value["restricted"] as? Bool ?? false
        }

        set {
            guard let data = try? JSONSerialization.data(
                withJSONObject: ["restricted": newValue],
                options: []
            ) else {
                return
            }

            try? data.write(to: fileURL, options: [.atomic])

            fileURL.path.withCString { pointer in
                _ = chmod(pointer, 0o600)
            }
        }
    }
}
