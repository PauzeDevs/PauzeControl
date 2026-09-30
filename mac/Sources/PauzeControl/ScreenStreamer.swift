// PauzeControl — Copyright (c) 2026 PauzeDevs. All rights reserved.

import AppKit
import CoreImage
import CoreMedia
import CoreVideo
import ScreenCaptureKit

final class ScreenStreamer: NSObject, SCStreamOutput, SCStreamDelegate {
    static let shared = ScreenStreamer()

    static let maxWidth = 1280
    static let maxHeight = 720
    static let maxFPS = 30

    private let queue = DispatchQueue(
        label: "com.pauze.control.screen",
        qos: .userInteractive
    )

    private var stream: SCStream?
    private var subscribers: [
        UUID: (Data, Int, Int) -> Void
    ] = [:]

    private var lastFrameTime: CFTimeInterval = 0
    private var starting = false

    private override init() {
        super.init()
    }

    @discardableResult
    func subscribe(
        _ handler: @escaping (Data, Int, Int) -> Void
    ) -> UUID {
        let id = UUID()

        queue.async {
            self.subscribers[id] = handler

            if self.subscribers.count == 1 {
                self.startCapture()
            }
        }

        return id
    }

    func unsubscribe(_ id: UUID) {
        queue.async {
            self.subscribers.removeValue(forKey: id)

            if self.subscribers.isEmpty {
                self.stopCapture()
            }
        }
    }

    private func startCapture() {
        guard stream == nil, !starting else {
            return
        }

        guard CGPreflightScreenCaptureAccess() else {
            print("[PauzeControl] Screen Recording permission is not granted.")
            return
        }

        starting = true

        SCShareableContent.getExcludingDesktopWindows(
            false,
            onScreenWindowsOnly: true
        ) { [weak self] content, error in
            guard let self else { return }

            self.queue.async {
                defer { self.starting = false }

                guard let display = content?.displays.first else {
                    print(
                        "[PauzeControl] Unable to locate a capturable display: " +
                        "(String(describing: error))"
                    )
                    return
                }

                let filter = SCContentFilter(
                    display: display,
                    excludingWindows: []
                )

                let configuration = SCStreamConfiguration()

                // Hard ceiling: 1280x720 at 30 FPS.
                configuration.width = Self.maxWidth
                configuration.height = Self.maxHeight
                configuration.scalesToFit = true
                configuration.preservesAspectRatio = true

                configuration.minimumFrameInterval = CMTime(
                    value: 1,
                    timescale: CMTimeScale(Self.maxFPS)
                )

                configuration.queueDepth = 1
                configuration.pixelFormat =
                    kCVPixelFormatType_32BGRA
                configuration.showsCursor = true
                configuration.capturesAudio = false

                do {
                    let stream = SCStream(
                        filter: filter,
                        configuration: configuration,
                        delegate: self
                    )

                    try stream.addStreamOutput(
                        self,
                        type: .screen,
                        sampleHandlerQueue: self.queue
                    )

                    self.stream = stream

                    stream.startCapture { error in
                        if let error {
                            print(
                                "[PauzeControl] Screen capture failed: (error)"
                            )
                            self.queue.async {
                                self.stream = nil
                            }
                        } else {
                            print(
                                "[PauzeControl] Screen capture started at " +
                                "(Self.maxWidth)x(Self.maxHeight) " +
                                "maximum (Self.maxFPS) FPS."
                            )
                        }
                    }
                } catch {
                    print(
                        "[PauzeControl] Unable to attach screen output: (error)"
                    )
                    self.stream = nil
                }
            }
        }
    }

    private func stopCapture() {
        guard let stream else {
            return
        }

        self.stream = nil

        stream.stopCapture { error in
            if let error {
                print(
                    "[PauzeControl] Screen capture stop error: (error)"
                )
            }
        }
    }

    func stream(
        _ stream: SCStream,
        didOutputSampleBuffer sampleBuffer: CMSampleBuffer,
        of type: SCStreamOutputType
    ) {
        guard type == .screen else {
            return
        }

        guard CMSampleBufferIsValid(sampleBuffer),
              let imageBuffer = CMSampleBufferGetImageBuffer(sampleBuffer)
        else {
            return
        }

        let now = CACurrentMediaTime()

        // Secondary software ceiling so a caller can never make this
        // pipeline emit more than 30 JPEG frames per second.
        if lastFrameTime != 0,
           now - lastFrameTime < (1.0 / Double(Self.maxFPS)) {
            return
        }

        lastFrameTime = now

        autoreleasepool {
            let ciImage = CIImage(cvPixelBuffer: imageBuffer)
            let context = CIContext(options: [
                .cacheIntermediates: false
            ])

            guard let cgImage = context.createCGImage(
                ciImage,
                from: ciImage.extent
            ) else {
                return
            }

            let bitmap = NSBitmapImageRep(cgImage: cgImage)

            guard let jpeg = bitmap.representation(
                using: .jpeg,
                properties: [
                    .compressionFactor: 0.52
                ]
            ) else {
                return
            }

            let width = cgImage.width
            let height = cgImage.height

            let currentSubscribers = subscribers.values

            for subscriber in currentSubscribers {
                subscriber(jpeg, width, height)
            }
        }
    }

    func stream(
        _ stream: SCStream,
        didStopWithError error: Error
    ) {
        print(
            "[PauzeControl] Screen stream stopped: (error)"
        )

        queue.async {
            self.stream = nil

            if !self.subscribers.isEmpty {
                self.startCapture()
            }
        }
    }
}
