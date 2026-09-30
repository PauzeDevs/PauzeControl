// PauzeControl — Copyright (c) 2026 PauzeDevs. All rights reserved.

import AppKit
import CoreGraphics

final class RestrictionManager: NSObject {
    private let stateStore: StateStore
    private var windows: [NSWindow] = []
    private var eventTap: CFMachPort?
    private var eventSource: CFRunLoopSource?

    private(set) var restricted = false
    private(set) var inputBlockingEnabled = false

    init(stateStore: StateStore = StateStore()) {
        self.stateStore = stateStore
        self.restricted = stateStore.isRestricted
        super.init()
    }

    func start() {
        guard restricted else { return }
        activate()
    }

    func restrict() -> CommandResponse {
        DispatchQueue.main.sync {
            restricted = true
            stateStore.isRestricted = true
            activate()
        }

        return response("RESTRICT")
    }

    func allow() -> CommandResponse {
        DispatchQueue.main.sync {
            restricted = false
            stateStore.isRestricted = false
            deactivate()
        }

        return response("ALLOW")
    }

    private func response(_ command: String) -> CommandResponse {
        CommandResponse(
            ok: true,
            command: command,
            restricted: restricted,
            inputBlockingEnabled: inputBlockingEnabled
        )
    }

    private func activate() {
        if windows.isEmpty {
            createWindows()
        }

        installEventTapIfNeeded()
        NSApp.activate(ignoringOtherApps: true)

        windows.forEach {
            $0.orderFrontRegardless()
            $0.makeKey()
        }
    }

    private func deactivate() {
        windows.forEach { $0.orderOut(nil) }
        windows.removeAll()
        removeEventTap()
    }

    private func createWindows() {
        for screen in NSScreen.screens {
            let panel = NSPanel(
                contentRect: screen.frame,
                styleMask: [.borderless],
                backing: .buffered,
                defer: false,
                screen: screen
            )

            panel.level = .screenSaver
            panel.isOpaque = true
            panel.backgroundColor = .black
            panel.hasShadow = false
            panel.hidesOnDeactivate = false
            panel.ignoresMouseEvents = false
            panel.collectionBehavior = [
                .canJoinAllSpaces,
                .stationary,
                .fullScreenAuxiliary
            ]
            panel.isReleasedWhenClosed = false
            panel.contentView = RestrictionView(
                frame: NSRect(
                    origin: .zero,
                    size: screen.frame.size
                )
            )

            windows.append(panel)
        }
    }

    private func installEventTapIfNeeded() {
        guard eventTap == nil else { return }

        let mask =
            (CGEventMask(1) << CGEventType.keyDown.rawValue) |
            (CGEventMask(1) << CGEventType.keyUp.rawValue) |
            (CGEventMask(1) << CGEventType.flagsChanged.rawValue) |
            (CGEventMask(1) << CGEventType.leftMouseDown.rawValue) |
            (CGEventMask(1) << CGEventType.leftMouseUp.rawValue) |
            (CGEventMask(1) << CGEventType.rightMouseDown.rawValue) |
            (CGEventMask(1) << CGEventType.rightMouseUp.rawValue) |
            (CGEventMask(1) << CGEventType.otherMouseDown.rawValue) |
            (CGEventMask(1) << CGEventType.otherMouseUp.rawValue) |
            (CGEventMask(1) << CGEventType.leftMouseDragged.rawValue) |
            (CGEventMask(1) << CGEventType.rightMouseDragged.rawValue) |
            (CGEventMask(1) << CGEventType.otherMouseDragged.rawValue) |
            (CGEventMask(1) << CGEventType.scrollWheel.rawValue)

        let context = UnsafeMutableRawPointer(
            Unmanaged.passUnretained(self).toOpaque()
        )

        guard let tap = CGEvent.tapCreate(
            tap: .cgSessionEventTap,
            place: .headInsertEventTap,
            options: .defaultTap,
            eventsOfInterest: mask,
            callback: restrictionEventTapCallback,
            userInfo: context
        ) else {
            inputBlockingEnabled = false
            return
        }

        eventTap = tap
        inputBlockingEnabled = true

        eventSource = CFMachPortCreateRunLoopSource(
            kCFAllocatorDefault,
            tap,
            0
        )

        if let source = eventSource {
            CFRunLoopAddSource(
                CFRunLoopGetMain(),
                source,
                .commonModes
            )
        }

        CGEvent.tapEnable(tap: tap, enable: true)
    }

    private func removeEventTap() {
        if let tap = eventTap {
            CGEvent.tapEnable(tap: tap, enable: false)
        }

        if let source = eventSource {
            CFRunLoopRemoveSource(
                CFRunLoopGetMain(),
                source,
                .commonModes
            )
        }

        eventSource = nil
        eventTap = nil
        inputBlockingEnabled = false
    }

    fileprivate func reenableEventTap() {
        guard let tap = eventTap else { return }
        CGEvent.tapEnable(tap: tap, enable: true)
    }
}

private final class RestrictionView: NSView {
    override func draw(_ dirtyRect: NSRect) {
        NSColor.black.setFill()
        dirtyRect.fill()

        let title = "🔒  MAC RESTRICTED"
        let subtitle = "Access is temporarily restricted."
        let detail = "Authorized from PauzeControl."

        let titleAttributes: [NSAttributedString.Key: Any] = [
            .font: NSFont.systemFont(ofSize: 42, weight: .bold),
            .foregroundColor: NSColor.white
        ]

        let subtitleAttributes: [NSAttributedString.Key: Any] = [
            .font: NSFont.systemFont(ofSize: 24, weight: .medium),
            .foregroundColor: NSColor.white.withAlphaComponent(0.9)
        ]

        let detailAttributes: [NSAttributedString.Key: Any] = [
            .font: NSFont.systemFont(ofSize: 17),
            .foregroundColor: NSColor.white.withAlphaComponent(0.55)
        ]

        drawCentered(
            title,
            y: bounds.midY + 30,
            attributes: titleAttributes
        )

        drawCentered(
            subtitle,
            y: bounds.midY - 20,
            attributes: subtitleAttributes
        )

        drawCentered(
            detail,
            y: bounds.midY - 55,
            attributes: detailAttributes
        )
    }

    private func drawCentered(
        _ text: String,
        y: CGFloat,
        attributes: [NSAttributedString.Key: Any]
    ) {
        let size = (text as NSString).size(withAttributes: attributes)

        (text as NSString).draw(
            at: NSPoint(
                x: bounds.midX - size.width / 2,
                y: y
            ),
            withAttributes: attributes
        )
    }
}

private func restrictionEventTapCallback(
    proxy: CGEventTapProxy,
    type: CGEventType,
    event: CGEvent,
    userInfo: UnsafeMutableRawPointer?
) -> Unmanaged<CGEvent>? {
    guard let userInfo else {
        return Unmanaged.passUnretained(event)
    }

    let manager = Unmanaged<RestrictionManager>
        .fromOpaque(userInfo)
        .takeUnretainedValue()

    if type == .tapDisabledByTimeout ||
       type == .tapDisabledByUserInput {
        manager.reenableEventTap()
        return nil
    }

    return manager.restricted
        ? nil
        : Unmanaged.passUnretained(event)
}
