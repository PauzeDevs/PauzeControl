// PauzeControl — Copyright (c) 2026 PauzeDevs. All rights reserved.

import AppKit
import ApplicationServices
import CoreGraphics

/// Applies narrowly scoped, authenticated remote pointer and text input.
enum RemoteInput {
    static func perform(action: String, payload: [String: Any]) throws {
        let promptOptions = [
            kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true
        ] as CFDictionary
        guard AXIsProcessTrustedWithOptions(promptOptions) else {
            throw NSError(
                domain: "PauzeControl.RemoteInput",
                code: 1,
                userInfo: [NSLocalizedDescriptionKey: "Enable Accessibility for PauzeControl in System Settings to control the Mac."]
            )
        }

        switch action {
        case "click":
            let point = try screenPoint(payload)
            try postMouse(.mouseMoved, at: point, button: .left)
            try postMouse(.leftMouseDown, at: point, button: .left)
            try postMouse(.leftMouseUp, at: point, button: .left)
        case "drag":
            let start = try screenPoint(payload, xKey: "startX", yKey: "startY")
            let end = try screenPoint(payload, xKey: "x", yKey: "y")
            try postMouse(.mouseMoved, at: start, button: .left)
            try postMouse(.leftMouseDown, at: start, button: .left)
            try postMouse(.leftMouseDragged, at: end, button: .left)
            try postMouse(.leftMouseUp, at: end, button: .left)
        case "scroll":
            let point = try screenPoint(payload)
            guard let deltaY = payload["deltaY"] as? NSNumber,
                  let deltaX = payload["deltaX"] as? NSNumber,
                  (-2000...2000).contains(deltaY.intValue),
                  (-2000...2000).contains(deltaX.intValue),
                  let event = CGEvent(
                    scrollWheelEvent2Source: nil,
                    units: .pixel,
                    wheelCount: 2,
                    wheel1: Int32(deltaY.intValue),
                    wheel2: Int32(deltaX.intValue),
                    wheel3: 0
                  )
            else { throw invalidRequest() }
            event.location = point
            event.post(tap: .cghidEventTap)
        case "type":
            guard let text = payload["text"] as? String,
                  !text.isEmpty,
                  text.utf16.count <= 512
            else { throw invalidRequest() }
            try postText(text)
        default:
            throw invalidRequest()
        }
    }

    private static func screenPoint(
        _ payload: [String: Any],
        xKey: String = "x",
        yKey: String = "y"
    ) throws -> CGPoint {
        guard let x = payload[xKey] as? NSNumber,
              let y = payload[yKey] as? NSNumber,
              x.doubleValue.isFinite,
              y.doubleValue.isFinite,
              (0...1).contains(x.doubleValue),
              (0...1).contains(y.doubleValue)
        else { throw invalidRequest() }

        let bounds = CGDisplayBounds(CGMainDisplayID())
        return CGPoint(
            x: bounds.minX + CGFloat(x.doubleValue) * max(0, bounds.width - 1),
            y: bounds.minY + CGFloat(y.doubleValue) * max(0, bounds.height - 1)
        )
    }

    private static func postMouse(_ type: CGEventType, at point: CGPoint, button: CGMouseButton) throws {
        guard let event = CGEvent(mouseEventSource: nil, mouseType: type, mouseCursorPosition: point, mouseButton: button) else {
            throw NSError(
                domain: "PauzeControl.RemoteInput",
                code: 3,
                userInfo: [NSLocalizedDescriptionKey: "macOS could not create the requested mouse event."]
            )
        }
        event.post(tap: .cghidEventTap)
    }

    private static func postText(_ text: String) throws {
        var batch = ""
        for character in text {
            if batch.utf16.count + String(character).utf16.count > 20 {
                try postTextBatch(batch)
                batch = ""
            }
            batch.append(character)
        }
        if !batch.isEmpty { try postTextBatch(batch) }
    }

    private static func postTextBatch(_ text: String) throws {
        guard let down = CGEvent(keyboardEventSource: nil, virtualKey: 0, keyDown: true),
              let up = CGEvent(keyboardEventSource: nil, virtualKey: 0, keyDown: false) else {
            throw NSError(
                domain: "PauzeControl.RemoteInput",
                code: 4,
                userInfo: [NSLocalizedDescriptionKey: "macOS could not create the requested keyboard event."]
            )
        }
        let units = Array(text.utf16)
        units.withUnsafeBufferPointer { buffer in
            down.keyboardSetUnicodeString(stringLength: units.count, unicodeString: buffer.baseAddress)
            up.keyboardSetUnicodeString(stringLength: units.count, unicodeString: buffer.baseAddress)
        }
        down.post(tap: .cghidEventTap)
        up.post(tap: .cghidEventTap)
    }

    private static func invalidRequest() -> NSError {
        NSError(
            domain: "PauzeControl.RemoteInput",
            code: 2,
            userInfo: [NSLocalizedDescriptionKey: "Remote input request is invalid."]
        )
    }
}
