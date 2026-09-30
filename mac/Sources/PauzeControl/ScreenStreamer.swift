// PauzeControl — Copyright (c) 2026 PauzeDevs. All rights reserved.

import AppKit
import CoreImage
import CoreMedia
import CoreVideo
import ScreenCaptureKit
import VideoToolbox
import QuartzCore

struct EncodedScreenFrame {
    let data: Data
    let width: Int
    let height: Int
    let ptsMicroseconds: Int64
    let keyFrame: Bool
}

final class ScreenStreamer: NSObject, SCStreamOutput, SCStreamDelegate {
    static let shared = ScreenStreamer()

    static let maxWidth = 1280
    static let maxHeight = 720
    static let maxFPS = 30
    static let targetAverageBitrate = 4_000_000

    private let queue = DispatchQueue(
        label: "com.pauze.control.screen",
        qos: .userInteractive
    )

    private let ciContext = CIContext()

    private var stream: SCStream?
    private var compressionSession: VTCompressionSession?
    private var resizePool: CVPixelBufferPool?

    private var encoderWidth = 0
    private var encoderHeight = 0
    private var outputWidth = 0
    private var outputHeight = 0

    private var subscribers: [
        UUID: (EncodedScreenFrame) -> Void
    ] = [:]

    private var lastFrameTime: CFTimeInterval = 0
    private var starting = false
    private var forceNextKeyframe = true

    private var retryWorkItem: DispatchWorkItem?
    private var captureAttempt = 0

    private override init() {
        super.init()
    }

    @discardableResult
    func subscribe(
        _ handler: @escaping (EncodedScreenFrame) -> Void
    ) -> UUID {
        let id = UUID()

        queue.async {
            self.subscribers[id] = handler
            self.forceNextKeyframe = true

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
        guard
            stream == nil,
            !starting,
            !subscribers.isEmpty
        else {
            return
        }

        if !CGPreflightScreenCaptureAccess() {
            print(
                "[PauzeControl] Screen Recording permission is required. " +
                "The app will request it now."
            )

            DispatchQueue.main.async { [weak self] in
                guard let self else { return }

                let granted =
                    CGRequestScreenCaptureAccess()

                self.queue.async {
                    guard !self.subscribers.isEmpty else {
                        self.starting = false
                        return
                    }

                    if granted &&
                        CGPreflightScreenCaptureAccess() {
                        self.captureAttempt = 0
                        self.starting = true
                        self.loadShareableContent()
                    } else {
                        self.starting = false

                        print(
                            "[PauzeControl] Screen Recording permission " +
                            "is not active yet. Grant it in System Settings, " +
                            "then restart PauzeControl."
                        )

                        self.scheduleCaptureRetry()
                    }
                }
            }

            return
        }

        starting = true
        captureAttempt = 0
        retryWorkItem?.cancel()
        retryWorkItem = nil

        loadShareableContent()
    }

    private func loadShareableContent() {
        guard !subscribers.isEmpty else {
            starting = false
            return
        }

        SCShareableContent.getExcludingDesktopWindows(
            false,
            onScreenWindowsOnly: false
        ) { [weak self] content, error in
            guard let self else { return }

            self.queue.async {
                guard !self.subscribers.isEmpty else {
                    self.starting = false
                    return
                }

                guard
                    let content,
                    !content.displays.isEmpty
                else {
                    self.captureAttempt += 1

                    print(
                        "[PauzeControl] No capturable display yet " +
                        "(attempt (self.captureAttempt)): " +
                        "(String(describing: error))"
                    )

                    self.starting = false
                    self.scheduleCaptureRetry()
                    return
                }

                let mainDisplayID =
                    CGMainDisplayID()

                let display =
                    content.displays.first(where: {
                        $0.displayID == mainDisplayID
                    })
                    ?? content.displays.first!

                self.captureAttempt = 0
                self.startStream(for: display)
            }
        }
    }

    private func startStream(
        for display: SCDisplay
    ) {
        let sourceWidth =
            max(Int(display.width), 2)

        let sourceHeight =
            max(Int(display.height), 2)

        let scale =
            min(
                1.0,
                min(
                    Double(Self.maxWidth) /
                        Double(sourceWidth),
                    Double(Self.maxHeight) /
                        Double(sourceHeight)
                )
            )

        outputWidth =
            min(
                makeEven(
                    max(
                        2,
                        Int(
                            Double(sourceWidth) *
                            scale
                        )
                    )
                ),
                Self.maxWidth
            )

        outputHeight =
            min(
                makeEven(
                    max(
                        2,
                        Int(
                            Double(sourceHeight) *
                            scale
                        )
                    )
                ),
                Self.maxHeight
            )

        createResizePoolIfNeeded()

        let filter =
            SCContentFilter(
                display: display,
                excludingApplications: [],
                exceptingWindows: []
            )

        let configuration =
            SCStreamConfiguration()

        configuration.width = outputWidth
        configuration.height = outputHeight
        configuration.scalesToFit = false
        configuration.preservesAspectRatio = true

        configuration.minimumFrameInterval =
            CMTime(
                value: 1,
                timescale: CMTimeScale(Self.maxFPS)
            )

        configuration.queueDepth = 3
        configuration.pixelFormat =
            kCVPixelFormatType_32BGRA
        configuration.showsCursor = true
        configuration.capturesAudio = false

        do {
            let newStream =
                SCStream(
                    filter: filter,
                    configuration: configuration,
                    delegate: self
                )

            try newStream.addStreamOutput(
                self,
                type: .screen,
                sampleHandlerQueue: queue
            )

            stream = newStream

            newStream.startCapture {
                [weak self] error in
                guard let self else { return }

                self.queue.async {
                    guard !self.subscribers.isEmpty else {
                        self.starting = false
                        return
                    }

                    if let error {
                        print(
                            "[PauzeControl] Screen capture failed: " +
                            "(error)"
                        )

                        self.stream = nil
                        self.invalidateEncoder()
                        self.starting = false
                        self.scheduleCaptureRetry()
                        return
                    }

                    self.starting = false

                    print(
                        "[PauzeControl] Screen capture started. " +
                        "Display (display.displayID): " +
                        "(sourceWidth)x(sourceHeight) -> " +
                        "(self.outputWidth)x(self.outputHeight) @ " +
                        "(Self.maxFPS) FPS H.264."
                    )
                }
            }
        } catch {
            print(
                "[PauzeControl] Unable to attach screen output: " +
                "(error)"
            )

            stream = nil
            invalidateEncoder()
            starting = false
            scheduleCaptureRetry()
        }
    }

    private func scheduleCaptureRetry() {
        guard !subscribers.isEmpty else {
            return
        }

        retryWorkItem?.cancel()

        let retryNumber =
            max(1, captureAttempt)

        let delay =
            min(
                5.0,
                max(
                    1.0,
                    pow(
                        1.35,
                        Double(
                            min(retryNumber - 1, 8)
                        )
                    )
                )
            )

        let workItem =
            DispatchWorkItem { [weak self] in
                guard let self else { return }

                self.queue.async {
                    guard
                        !self.subscribers.isEmpty,
                        self.stream == nil,
                        !self.starting
                    else {
                        return
                    }

                    self.startCapture()
                }
            }

        retryWorkItem = workItem

        queue.asyncAfter(
            deadline: .now() + delay,
            execute: workItem
        )
    }

    private func createResizePoolIfNeeded() {
        guard
            outputWidth > 0,
            outputHeight > 0
        else {
            resizePool = nil
            return
        }

        var pool:
            CVPixelBufferPool?

        let status =
            CVPixelBufferPoolCreate(
                kCFAllocatorDefault,
                [
                    kCVPixelBufferPoolMinimumBufferCountKey:
                        3
                ] as CFDictionary,
                [
                    kCVPixelBufferWidthKey:
                        outputWidth,
                    kCVPixelBufferHeightKey:
                        outputHeight,
                    kCVPixelBufferPixelFormatTypeKey:
                        Int(
                            kCVPixelFormatType_32BGRA
                        ),
                    kCVPixelBufferIOSurfacePropertiesKey:
                        [:] as CFDictionary
                ] as CFDictionary,
                &pool
            )

        guard status == kCVReturnSuccess else {
            resizePool = nil

            print(
                "[PauzeControl] Resize pool creation failed: " +
                "(status)"
            )

            return
        }

        resizePool = pool
    }

    private func pixelBufferForEncoding(
        _ source: CVPixelBuffer
    ) -> CVPixelBuffer? {
        let width =
            CVPixelBufferGetWidth(source)

        let height =
            CVPixelBufferGetHeight(source)

        if width == outputWidth,
           height == outputHeight {
            return source
        }

        guard let resizePool else {
            return nil
        }

        var target:
            CVPixelBuffer?

        let status =
            CVPixelBufferPoolCreatePixelBuffer(
                kCFAllocatorDefault,
                resizePool,
                &target
            )

        guard
            status == kCVReturnSuccess,
            let target
        else {
            return nil
        }

        let image =
            CIImage(
                cvPixelBuffer: source
            )

        ciContext.render(
            image,
            to: target,
            bounds:
                CGRect(
                    x: 0,
                    y: 0,
                    width: outputWidth,
                    height: outputHeight
                ),
            colorSpace:
                CGColorSpaceCreateDeviceRGB()
        )

        return target
    }

    private func stopCapture() {
        retryWorkItem?.cancel()
        retryWorkItem = nil
        captureAttempt = 0
        starting = false

        if let stream {
            self.stream = nil

            stream.stopCapture { error in
                if let error {
                    print(
                        "[PauzeControl] Screen capture stop error: " +
                        "(error)"
                    )
                }
            }
        }

        invalidateEncoder()

        lastFrameTime = 0
        outputWidth = 0
        outputHeight = 0
        resizePool = nil
    }

    private func invalidateEncoder() {
        if let compressionSession {
            VTCompressionSessionCompleteFrames(
                compressionSession,
                untilPresentationTimeStamp: .invalid
            )
            VTCompressionSessionInvalidate(
                compressionSession
            )
        }

        compressionSession = nil
        encoderWidth = 0
        encoderHeight = 0
        forceNextKeyframe = true
    }

    private func makeEncoder(
        width: Int,
        height: Int
    ) -> VTCompressionSession? {
        guard
            width > 0,
            height > 0,
            width <= Self.maxWidth,
            height <= Self.maxHeight
        else {
            return nil
        }

        var session:
            VTCompressionSession?

        let imageAttributes:
            CFDictionary =
            [
                kCVPixelBufferPixelFormatTypeKey:
                    Int(
                        kCVPixelFormatType_32BGRA
                    )
            ] as CFDictionary

        let status =
            VTCompressionSessionCreate(
                allocator: nil,
                width: Int32(width),
                height: Int32(height),
                codecType: kCMVideoCodecType_H264,
                encoderSpecification: nil,
                imageBufferAttributes:
                    imageAttributes,
                compressedDataAllocator: nil,
                outputCallback:
                    compressionOutputCallback,
                refcon:
                    Unmanaged.passUnretained(
                        self
                    ).toOpaque(),
                compressionSessionOut:
                    &session
            )

        guard
            status == noErr,
            let session
        else {
            print(
                "[PauzeControl] H.264 encoder creation failed: " +
                "(status)"
            )
            return nil
        }

        func set(
            _ key: CFString,
            _ value: CFTypeRef
        ) {
            let result =
                VTSessionSetProperty(
                    session,
                    key: key,
                    value: value
                )

            if result != noErr {
                print(
                    "[PauzeControl] H.264 property failed: " +
                    "(key) -> (result)"
                )
            }
        }

        set(
            kVTCompressionPropertyKey_RealTime,
            kCFBooleanTrue
        )

        set(
            kVTCompressionPropertyKey_AllowFrameReordering,
            kCFBooleanFalse
        )

        set(
            kVTCompressionPropertyKey_ProfileLevel,
            kVTProfileLevel_H264_Baseline_3_1
        )

        set(
            kVTCompressionPropertyKey_ExpectedFrameRate,
            NSNumber(value: Self.maxFPS)
        )

        set(
            kVTCompressionPropertyKey_MaxKeyFrameInterval,
            NSNumber(value: Self.maxFPS * 2)
        )

        set(
            kVTCompressionPropertyKey_MaxKeyFrameIntervalDuration,
            NSNumber(value: 2.0)
        )

        set(
            kVTCompressionPropertyKey_AverageBitRate,
            NSNumber(
                value:
                    Self.targetAverageBitrate
            )
        )

        let prepareStatus =
            VTCompressionSessionPrepareToEncodeFrames(
                session
            )

        guard prepareStatus == noErr else {
            print(
                "[PauzeControl] H.264 encoder prepare failed: " +
                "(prepareStatus)"
            )

            VTCompressionSessionInvalidate(
                session
            )

            return nil
        }

        encoderWidth = width
        encoderHeight = height

        return session
    }

    func stream(
        _ stream: SCStream,
        didOutputSampleBuffer sampleBuffer:
            CMSampleBuffer,
        of type: SCStreamOutputType
    ) {
        guard
            type == .screen,
            CMSampleBufferIsValid(sampleBuffer),
            let sourceBuffer =
                CMSampleBufferGetImageBuffer(
                    sampleBuffer
                )
        else {
            return
        }

        let now =
            CACurrentMediaTime()

        if lastFrameTime != 0,
           now - lastFrameTime <
               (1.0 / Double(Self.maxFPS)) {
            return
        }

        lastFrameTime = now

        guard
            let imageBuffer =
                pixelBufferForEncoding(
                    sourceBuffer
                )
        else {
            return
        }

        let width =
            CVPixelBufferGetWidth(imageBuffer)

        let height =
            CVPixelBufferGetHeight(imageBuffer)

        if compressionSession == nil ||
            width != encoderWidth ||
            height != encoderHeight {
            invalidateEncoder()

            compressionSession =
                makeEncoder(
                    width: width,
                    height: height
                )

            guard compressionSession != nil else {
                return
            }
        }

        guard
            let compressionSession
        else {
            return
        }

        var frameProperties:
            CFDictionary?

        if forceNextKeyframe {
            frameProperties =
                [
                    kVTEncodeFrameOptionKey_ForceKeyFrame:
                        kCFBooleanTrue
                ] as CFDictionary

            forceNextKeyframe = false
        }

        var infoFlags =
            VTEncodeInfoFlags()

        let status =
            VTCompressionSessionEncodeFrame(
                compressionSession,
                imageBuffer:
                    imageBuffer,
                presentationTimeStamp:
                    CMSampleBufferGetPresentationTimeStamp(
                        sampleBuffer
                    ),
                duration:
                    .invalid,
                frameProperties:
                    frameProperties,
                sourceFrameRefcon:
                    nil,
                infoFlagsOut:
                    &infoFlags
            )

        if status != noErr {
            print(
                "[PauzeControl] H.264 frame encode failed: " +
                "(status)"
            )
        }
    }

    func stream(
        _ stream: SCStream,
        didStopWithError error: Error
    ) {
        print(
            "[PauzeControl] Screen stream stopped: " +
            "(error)"
        )

        queue.async {
            guard self.stream === stream else {
                return
            }

            self.stream = nil
            self.invalidateEncoder()
            self.starting = false

            if !self.subscribers.isEmpty {
                self.scheduleCaptureRetry()
            }
        }
    }

    fileprivate func handleEncoded(
        _ sampleBuffer: CMSampleBuffer
    ) {
        guard
            CMSampleBufferIsValid(
                sampleBuffer
            ),
            let formatDescription =
                CMSampleBufferGetFormatDescription(
                    sampleBuffer
                ),
            let dataBuffer =
                CMSampleBufferGetDataBuffer(
                    sampleBuffer
                )
        else {
            return
        }

        let nalHeaderLength =
            h264NALHeaderLength(
                formatDescription:
                    formatDescription
            )

        var totalLength = 0
        var lengthAtOffset = 0
        var dataPointer:
            UnsafeMutablePointer<Int8>?

        let status =
            CMBlockBufferGetDataPointer(
                dataBuffer,
                atOffset: 0,
                lengthAtOffsetOut:
                    &lengthAtOffset,
                totalLengthOut:
                    &totalLength,
                dataPointerOut:
                    &dataPointer
            )

        guard
            status == kCMBlockBufferNoErr,
            totalLength > 0,
            let dataPointer
        else {
            return
        }

        let bytes =
            UnsafeMutableRawPointer(
                dataPointer
            ).assumingMemoryBound(
                to: UInt8.self
            )

        var accessUnit = Data()
        var offset = 0
        var containsIDR = false

        while offset + nalHeaderLength <= totalLength {
            var nalLength = 0

            for index in 0..<nalHeaderLength {
                nalLength =
                    (nalLength << 8) |
                    Int(
                        bytes[offset + index]
                    )
            }

            offset += nalHeaderLength

            guard
                nalLength > 0,
                offset + nalLength <= totalLength
            else {
                return
            }

            let nalType =
                Int(
                    bytes[offset] & 0x1F
                )

            if nalType == 5 {
                containsIDR = true
            }

            appendStartCode(
                to: &accessUnit
            )

            accessUnit.append(
                Data(
                    bytes:
                        bytes.advanced(
                            by: offset
                        ),
                    count: nalLength
                )
            )

            offset += nalLength
        }

        guard !accessUnit.isEmpty else {
            return
        }

        if containsIDR {
            var prefixed = Data()

            if let sps =
                h264ParameterSet(
                    formatDescription:
                        formatDescription,
                    index: 0
                ) {
                appendStartCode(
                    to: &prefixed
                )
                prefixed.append(sps)
            }

            if let pps =
                h264ParameterSet(
                    formatDescription:
                        formatDescription,
                    index: 1
                ) {
                appendStartCode(
                    to: &prefixed
                )
                prefixed.append(pps)
            }

            prefixed.append(
                accessUnit
            )

            accessUnit = prefixed
        }

        let pts =
            CMSampleBufferGetPresentationTimeStamp(
                sampleBuffer
            )

        let ptsMicroseconds: Int64

        if pts.isValid {
            ptsMicroseconds =
                Int64(
                    (
                        CMTimeGetSeconds(
                            pts
                        ) *
                        1_000_000.0
                    ).rounded()
                )
        } else {
            ptsMicroseconds =
                Int64(
                    (
                        CACurrentMediaTime() *
                        1_000_000.0
                    ).rounded()
                )
        }

        let frame =
            EncodedScreenFrame(
                data: accessUnit,
                width: encoderWidth,
                height: encoderHeight,
                ptsMicroseconds:
                    max(
                        0,
                        ptsMicroseconds
                    ),
                keyFrame: containsIDR
            )

        queue.async {
            guard !self.subscribers.isEmpty else {
                return
            }

            for subscriber in
                self.subscribers.values {
                subscriber(frame)
            }
        }
    }
}

private let compressionOutputCallback:
    VTCompressionOutputCallback = {
        refcon,
        _,
        status,
        _,
        sampleBuffer in

        guard
            status == noErr,
            let refcon,
            let sampleBuffer
        else {
            return
        }

        let streamer =
            Unmanaged<ScreenStreamer>
                .fromOpaque(
                    refcon
                )
                .takeUnretainedValue()

        streamer.handleEncoded(
            sampleBuffer
        )
    }

private func appendStartCode(
    to data: inout Data
) {
    data.append(contentsOf: [
        0x00,
        0x00,
        0x00,
        0x01
    ])
}

private func makeEven(
    _ value: Int
) -> Int {
    if value % 2 == 0 {
        return max(2, value)
    }

    return max(
        2,
        value - 1
    )
}

private func h264NALHeaderLength(
    formatDescription:
        CMFormatDescription
) -> Int {
    var pointer:
        UnsafePointer<UInt8>?

    var size = 0
    var count = 0
    var headerLength: Int32 = 4

    let status =
        CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
            formatDescription,
            parameterSetIndex: 0,
            parameterSetPointerOut:
                &pointer,
            parameterSetSizeOut:
                &size,
            parameterSetCountOut:
                &count,
            nalUnitHeaderLengthOut:
                &headerLength
        )

    guard
        status == noErr,
        headerLength >= 1,
        headerLength <= 4
    else {
        return 4
    }

    return Int(
        headerLength
    )
}

private func h264ParameterSet(
    formatDescription:
        CMFormatDescription,
    index: Int
) -> Data? {
    var pointer:
        UnsafePointer<UInt8>?

    var size = 0
    var count = 0
    var headerLength: Int32 = 4

    let status =
        CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
            formatDescription,
            parameterSetIndex: index,
            parameterSetPointerOut:
                &pointer,
            parameterSetSizeOut:
                &size,
            parameterSetCountOut:
                &count,
            nalUnitHeaderLengthOut:
                &headerLength
        )

    guard
        status == noErr,
        let pointer,
        size > 0
    else {
        return nil
    }

    return Data(
        bytes: pointer,
        count: size
    )
}
