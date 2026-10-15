// PauzeControl — Copyright (c) 2026 PauzeDevs. All rights reserved.
import AppKit
import Foundation

struct ClipboardResponse: Codable {
    let ok: Bool
    let text: String
}

enum ClipboardService {
    private static let maxBytes = 24 * 1024

    static func readText() throws -> String {
        let value = NSPasteboard.general.string(forType: .string) ?? ""
        guard value.utf8.count <= maxBytes else {
            throw NSError(domain: "PauzeControl.Clipboard", code: 413,
                          userInfo: [NSLocalizedDescriptionKey: "Clipboard text exceeds the 24 KiB transfer limit."])
        }
        return value
    }

    static func writeText(_ value: String) throws {
        guard value.utf8.count <= maxBytes else {
            throw NSError(domain: "PauzeControl.Clipboard", code: 413,
                          userInfo: [NSLocalizedDescriptionKey: "Clipboard text must be 24 KiB or smaller."])
        }
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        guard pasteboard.setString(value, forType: .string) else {
            throw NSError(domain: "PauzeControl.Clipboard", code: 500,
                          userInfo: [NSLocalizedDescriptionKey: "macOS could not update the clipboard."])
        }
    }
}
