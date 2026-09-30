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

    // Hardware-encoder ceiling. The active power profile decides the actual
    // resolution/FPS/bitrate for each streaming session.
    static let maxWidth = 1920
    static let maxHeight = 1080
    static let maxFPS = 60
    static let targetAverageBitrate = 8_000_000

    private let queue = DispatchQueue(label: "com.pauze.control.screen", qos: .userInitiated)
    private let ciContext = CIContext()
    private var stream: SCStream?
    private var compressionSession: VTCompressionSession?
    private var resizePool: CVPixelBufferPool?
    private var encoderWidth = 0
    private var encoderHeight = 0
    private var outputWidth = 0
    private var outputHeight = 0
    private var subscribers: [UUID: (EncodedScreenFrame) -> Void] = [:]
    private var lastFrameTime: CFTimeInterval = 0
    private var starting = false
    private var forceNextKeyframe = true
    private var retryWorkItem: DispatchWorkItem?
    private var captureAttempt = 0
    private var activeFPS = Self.maxFPS
    private var activeBitrate = Self.targetAverageBitrate
    private var activeProfileName = "AC • 1080p • 60 FPS"

    private override init() { super.init() }

    @discardableResult
    func subscribe(_ handler: @escaping (EncodedScreenFrame) -> Void) -> UUID {
        let id = UUID()
        queue.async {
            self.subscribers[id] = handler
            self.forceNextKeyframe = true
            if self.subscribers.count == 1 { self.startCapture() }
        }
        return id
    }

    func unsubscribe(_ id: UUID) {
        queue.async {
            self.subscribers.removeValue(forKey: id)
            if self.subscribers.isEmpty { self.stopCapture() }
        }
    }

    private func startCapture() {
        guard stream == nil, !starting, !subscribers.isEmpty else { return }
        if !CGPreflightScreenCaptureAccess() {
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                let granted = CGRequestScreenCaptureAccess()
                self.queue.async {
                    guard !self.subscribers.isEmpty else { self.starting = false; return }
                    if granted && CGPreflightScreenCaptureAccess() {
                        self.starting = true
                        self.loadShareableContent()
                    } else {
                        self.starting = false
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
        guard !subscribers.isEmpty else { starting = false; return }
        SCShareableContent.getExcludingDesktopWindows(false, onScreenWindowsOnly: false) { [weak self] content, _ in
            guard let self else { return }
            self.queue.async {
                guard !self.subscribers.isEmpty else { self.starting = false; return }
                guard let content, !content.displays.isEmpty else {
                    self.captureAttempt += 1
                    self.starting = false
                    self.scheduleCaptureRetry()
                    return
                }
                let mainID = CGMainDisplayID()
                let display = content.displays.first(where: { $0.displayID == mainID }) ?? content.displays.first!
                self.captureAttempt = 0
                self.startStream(for: display)
            }
        }
    }

    private func startStream(for display: SCDisplay) {
        let sourceWidth = max(Int(display.width), 2)
        let sourceHeight = max(Int(display.height), 2)
        let profile = SystemService.streamingProfile()
        activeFPS = min(profile.fps, Self.maxFPS)
        activeBitrate = profile.bitrate
        activeProfileName = profile.name

        let scale = min(1.0, min(Double(profile.maxWidth) / Double(sourceWidth), Double(profile.maxHeight) / Double(sourceHeight)))
        outputWidth = min(makeEven(max(2, Int(Double(sourceWidth) * scale))), profile.maxWidth)
        outputHeight = min(makeEven(max(2, Int(Double(sourceHeight) * scale))), profile.maxHeight)
        createResizePoolIfNeeded()

        print("[PauzeControl] Streaming profile: \(activeProfileName) • \(outputWidth)x\(outputHeight) • \(activeFPS) FPS • \(activeBitrate / 1_000_000) Mbps")

        let filter = SCContentFilter(display: display, excludingApplications: [], exceptingWindows: [])
        let configuration = SCStreamConfiguration()
        configuration.width = outputWidth
        configuration.height = outputHeight
        configuration.scalesToFit = false
        configuration.preservesAspectRatio = true
        configuration.minimumFrameInterval = CMTime(value: 1, timescale: CMTimeScale(activeFPS))
        // A small queue prevents frames from piling up when the device/network
        // cannot keep up. This reduces latency and memory pressure.
        configuration.queueDepth = 2
        configuration.pixelFormat = kCVPixelFormatType_32BGRA
        configuration.showsCursor = true
        configuration.capturesAudio = false

        do {
            let newStream = SCStream(filter: filter, configuration: configuration, delegate: self)
            try newStream.addStreamOutput(self, type: .screen, sampleHandlerQueue: queue)
            stream = newStream
            newStream.startCapture { [weak self] error in
                guard let self else { return }
                self.queue.async {
                    guard !self.subscribers.isEmpty else { self.starting = false; return }
                    if let error {
                        print("[PauzeControl] Screen capture failed: \(error)")
                        self.stream = nil
                        self.invalidateEncoder()
                        self.starting = false
                        self.scheduleCaptureRetry()
                        return
                    }
                    self.starting = false
                    print("[PauzeControl] Screen capture started: \(self.outputWidth)x\(self.outputHeight) @ \(self.activeFPS) FPS H.264 (\(self.activeProfileName)).")
                }
            }
        } catch {
            print("[PauzeControl] Unable to attach screen output: \(error)")
            stream = nil
            invalidateEncoder()
            starting = false
            scheduleCaptureRetry()
        }
    }

    private func scheduleCaptureRetry() {
        guard !subscribers.isEmpty else { return }
        retryWorkItem?.cancel()
        let retryNumber = max(1, captureAttempt)
        let delay = min(5.0, max(1.0, pow(1.35, Double(min(retryNumber - 1, 8)))))
        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }
            self.queue.async {
                guard !self.subscribers.isEmpty, self.stream == nil, !self.starting else { return }
                self.startCapture()
            }
        }
        retryWorkItem = work
        queue.asyncAfter(deadline: .now() + delay, execute: work)
    }

    private func createResizePoolIfNeeded() {
        guard outputWidth > 0, outputHeight > 0 else { resizePool = nil; return }
        var pool: CVPixelBufferPool?
        let status = CVPixelBufferPoolCreate(
            kCFAllocatorDefault,
            [kCVPixelBufferPoolMinimumBufferCountKey: 2] as CFDictionary,
            [
                kCVPixelBufferWidthKey: outputWidth,
                kCVPixelBufferHeightKey: outputHeight,
                kCVPixelBufferPixelFormatTypeKey: Int(kCVPixelFormatType_32BGRA),
                kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary
            ] as CFDictionary,
            &pool
        )
        guard status == kCVReturnSuccess else { resizePool = nil; return }
        resizePool = pool
    }

    private func pixelBufferForEncoding(_ source: CVPixelBuffer) -> CVPixelBuffer? {
        if CVPixelBufferGetWidth(source) == outputWidth && CVPixelBufferGetHeight(source) == outputHeight { return source }
        guard let resizePool else { return nil }
        var target: CVPixelBuffer?
        guard CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, resizePool, &target) == kCVReturnSuccess, let target else { return nil }
        ciContext.render(
            CIImage(cvPixelBuffer: source),
            to: target,
            bounds: CGRect(x: 0, y: 0, width: outputWidth, height: outputHeight),
            colorSpace: CGColorSpaceCreateDeviceRGB()
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
                if let error { print("[PauzeControl] Screen capture stop error: \(error)") }
            }
        }
        invalidateEncoder()
        lastFrameTime = 0
        outputWidth = 0
        outputHeight = 0
        resizePool = nil
        activeFPS = Self.maxFPS
        activeBitrate = Self.targetAverageBitrate
        activeProfileName = "AC • 1080p • 60 FPS"
    }

    private func invalidateEncoder() {
        if let compressionSession {
            VTCompressionSessionCompleteFrames(compressionSession, untilPresentationTimeStamp: .invalid)
            VTCompressionSessionInvalidate(compressionSession)
        }
        compressionSession = nil
        encoderWidth = 0
        encoderHeight = 0
        forceNextKeyframe = true
    }

    private func makeEncoder(width: Int, height: Int) -> VTCompressionSession? {
        guard width > 0, height > 0, width <= Self.maxWidth, height <= Self.maxHeight else { return nil }
        var session: VTCompressionSession?
        let imageAttributes = [kCVPixelBufferPixelFormatTypeKey: Int(kCVPixelFormatType_32BGRA)] as CFDictionary
        let status = VTCompressionSessionCreate(
            allocator: nil,
            width: Int32(width), height: Int32(height),
            codecType: kCMVideoCodecType_H264,
            encoderSpecification: nil,
            imageBufferAttributes: imageAttributes,
            compressedDataAllocator: nil,
            outputCallback: compressionOutputCallback,
            refcon: Unmanaged.passUnretained(self).toOpaque(),
            compressionSessionOut: &session
        )
        guard status == noErr, let session else { return nil }

        func set(_ key: CFString, _ value: CFTypeRef) {
            _ = VTSessionSetProperty(session, key: key, value: value)
        }
        set(kVTCompressionPropertyKey_RealTime, kCFBooleanTrue)
        set(kVTCompressionPropertyKey_AllowFrameReordering, kCFBooleanFalse)
        set(kVTCompressionPropertyKey_ProfileLevel, kVTProfileLevel_H264_Main_AutoLevel)
        set(kVTCompressionPropertyKey_ExpectedFrameRate, NSNumber(value: activeFPS))
        set(kVTCompressionPropertyKey_MaxKeyFrameInterval, NSNumber(value: activeFPS * 2))
        set(kVTCompressionPropertyKey_MaxKeyFrameIntervalDuration, NSNumber(value: 2.0))
        set(kVTCompressionPropertyKey_AverageBitRate, NSNumber(value: activeBitrate))
        set(kVTCompressionPropertyKey_DataRateLimits, [[activeBitrate / 8 * 2, 1]] as NSArray)

        guard VTCompressionSessionPrepareToEncodeFrames(session) == noErr else {
            VTCompressionSessionInvalidate(session)
            return nil
        }
        encoderWidth = width
        encoderHeight = height
        return session
    }

    func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .screen, CMSampleBufferIsValid(sampleBuffer), let sourceBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let now = CACurrentMediaTime()
        if lastFrameTime != 0 && now - lastFrameTime < (1.0 / Double(activeFPS)) { return }
        lastFrameTime = now
        guard let imageBuffer = pixelBufferForEncoding(sourceBuffer) else { return }
        let width = CVPixelBufferGetWidth(imageBuffer)
        let height = CVPixelBufferGetHeight(imageBuffer)
        if compressionSession == nil || width != encoderWidth || height != encoderHeight {
            invalidateEncoder()
            compressionSession = makeEncoder(width: width, height: height)
            guard compressionSession != nil else { return }
        }
        guard let compressionSession else { return }
        var frameProperties: CFDictionary?
        if forceNextKeyframe {
            frameProperties = [kVTEncodeFrameOptionKey_ForceKeyFrame: kCFBooleanTrue] as CFDictionary
            forceNextKeyframe = false
        }
        var infoFlags = VTEncodeInfoFlags()
        let status = VTCompressionSessionEncodeFrame(
            compressionSession,
            imageBuffer: imageBuffer,
            presentationTimeStamp: CMSampleBufferGetPresentationTimeStamp(sampleBuffer),
            duration: .invalid,
            frameProperties: frameProperties,
            sourceFrameRefcon: nil,
            infoFlagsOut: &infoFlags
        )
        if status != noErr { print("[PauzeControl] H.264 frame encode failed: \(status)") }
    }

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        print("[PauzeControl] Screen stream stopped: \(error)")
        queue.async {
            guard self.stream === stream else { return }
            self.stream = nil
            self.invalidateEncoder()
            self.starting = false
            if !self.subscribers.isEmpty { self.scheduleCaptureRetry() }
        }
    }

    fileprivate func handleEncoded(_ sampleBuffer: CMSampleBuffer) {
        guard CMSampleBufferIsValid(sampleBuffer), let format = CMSampleBufferGetFormatDescription(sampleBuffer), let dataBuffer = CMSampleBufferGetDataBuffer(sampleBuffer) else { return }
        let headerLength = h264NALHeaderLength(formatDescription: format)
        var totalLength = 0
        var lengthAtOffset = 0
        var dataPointer: UnsafeMutablePointer<Int8>?
        guard CMBlockBufferGetDataPointer(dataBuffer, atOffset: 0, lengthAtOffsetOut: &lengthAtOffset, totalLengthOut: &totalLength, dataPointerOut: &dataPointer) == kCMBlockBufferNoErr, totalLength > 0, let dataPointer else { return }
        let bytes = UnsafeMutableRawPointer(dataPointer).assumingMemoryBound(to: UInt8.self)
        var accessUnit = Data()
        var offset = 0
        var containsIDR = false
        while offset + headerLength <= totalLength {
            var nalLength = 0
            for index in 0..<headerLength { nalLength = (nalLength << 8) | Int(bytes[offset + index]) }
            offset += headerLength
            guard nalLength > 0, offset + nalLength <= totalLength else { return }
            if Int(bytes[offset] & 0x1F) == 5 { containsIDR = true }
            appendStartCode(to: &accessUnit)
            accessUnit.append(Data(bytes: bytes.advanced(by: offset), count: nalLength))
            offset += nalLength
        }
        guard !accessUnit.isEmpty else { return }
        if containsIDR {
            var prefixed = Data()
            for index in 0..<2 {
                if let parameter = h264ParameterSet(formatDescription: format, index: index) {
                    appendStartCode(to: &prefixed)
                    prefixed.append(parameter)
                }
            }
            prefixed.append(accessUnit)
            accessUnit = prefixed
        }
        let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        let ptsMicroseconds = pts.isValid ? Int64((CMTimeGetSeconds(pts) * 1_000_000).rounded()) : Int64((CACurrentMediaTime() * 1_000_000).rounded())
        let frame = EncodedScreenFrame(data: accessUnit, width: encoderWidth, height: encoderHeight, ptsMicroseconds: max(0, ptsMicroseconds), keyFrame: containsIDR)
        queue.async {
            guard !self.subscribers.isEmpty else { return }
            for subscriber in self.subscribers.values { subscriber(frame) }
        }
    }
}

private let compressionOutputCallback: VTCompressionOutputCallback = { refcon, _, status, _, sampleBuffer in
    guard status == noErr, let refcon, let sampleBuffer else { return }
    Unmanaged<ScreenStreamer>.fromOpaque(refcon).takeUnretainedValue().handleEncoded(sampleBuffer)
}

private func appendStartCode(to data: inout Data) { data.append(contentsOf: [0, 0, 0, 1]) }

private func makeEven(_ value: Int) -> Int { value % 2 == 0 ? max(2, value) : max(2, value - 1) }

private func h264NALHeaderLength(formatDescription: CMFormatDescription) -> Int {
    var pointer: UnsafePointer<UInt8>?
    var size = 0
    var count = 0
    var headerLength: Int32 = 4
    let status = CMVideoFormatDescriptionGetH264ParameterSetAtIndex(formatDescription, parameterSetIndex: 0, parameterSetPointerOut: &pointer, parameterSetSizeOut: &size, parameterSetCountOut: &count, nalUnitHeaderLengthOut: &headerLength)
    return status == noErr && headerLength >= 1 && headerLength <= 4 ? Int(headerLength) : 4
}

private func h264ParameterSet(formatDescription: CMFormatDescription, index: Int) -> Data? {
    var pointer: UnsafePointer<UInt8>?
    var size = 0
    var count = 0
    var headerLength: Int32 = 4
    let status = CMVideoFormatDescriptionGetH264ParameterSetAtIndex(formatDescription, parameterSetIndex: index, parameterSetPointerOut: &pointer, parameterSetSizeOut: &size, parameterSetCountOut: &count, nalUnitHeaderLengthOut: &headerLength)
    guard status == noErr, let pointer, size > 0 else { return nil }
    return Data(bytes: pointer, count: size)
}
