// PauzeControl — Copyright (c) 2026 PauzeDevs. All rights reserved.

import Foundation
import Network
import CryptoKit

final class ControlServer {
    private let port: UInt16
    private let token: String
    private let manager: RestrictionManager

    private let queue = DispatchQueue(
        label: "com.pauze.control.server",
        qos: .userInitiated,
        attributes: .concurrent
    )

    private var listener: NWListener?
    private var seenNonces: [String: Date] = [:]
    private let nonceLock = NSLock()

    private var screenClients: [UUID: ScreenClient] = [:]
    private let screenClientLock = NSLock()

    init(
        port: UInt16 = 47777,
        token: String,
        manager: RestrictionManager
    ) {
        self.port = port
        self.token = token
        self.manager = manager
    }

    func start() throws {
        guard let nwPort = NWEndpoint.Port(rawValue: port) else {
            throw NSError(
                domain: "PauzeControl.Server",
                code: 1,
                userInfo: [
                    NSLocalizedDescriptionKey:
                        "Invalid control port."
                ]
            )
        }

        let listener = try NWListener(
            using: .tcp,
            on: nwPort
        )

        listener.stateUpdateHandler = { state in
            switch state {
            case .ready:
                print(
                    "[PauzeControl] listening on port \(self.port)"
                )
            case .failed(let error):
                print(
                    "[PauzeControl] listener failed: \(error)"
                )
            default:
                break
            }
        }

        listener.newConnectionHandler = { [weak self] connection in
            self?.handle(connection)
        }

        self.listener = listener
        listener.start(queue: queue)
    }

    private static let maximumRequestDuration: TimeInterval = 10
    private static let maximumRequestBytes = 80 * 1024
    private static let maximumHeaderBytes = 16 * 1024
    private static let maximumBodyBytes = 64 * 1024

    private func handle(_ connection: NWConnection) {
        guard Self.isAllowedPeer(connection.endpoint) else {
            connection.cancel()
            return
        }

        let requestDeadline = DispatchWorkItem {
            connection.cancel()
        }
        queue.asyncAfter(
            deadline: .now() + Self.maximumRequestDuration,
            execute: requestDeadline
        )

        connection.stateUpdateHandler = {
            [weak self, weak connection] state in

            guard let self, let connection else { return }

            if case .ready = state {
                self.receive(
                    from: connection,
                    accumulated: Data(),
                    deadline: requestDeadline
                )
            }
        }

        connection.start(queue: queue)
    }

    private func receive(
        from connection: NWConnection,
        accumulated: Data,
        deadline: DispatchWorkItem
    ) {
        connection.receive(
            minimumIncompleteLength: 1,
            maximumLength: 65_536
        ) { [weak self] data, _, complete, error in

            guard let self else {
                deadline.cancel()
                connection.cancel()
                return
            }

            var buffer = accumulated
            if let data {
                buffer.append(data)
            }

            guard !Self.exceedsRequestLimits(buffer) else {
                deadline.cancel()
                connection.cancel()
                return
            }

            if let request = HTTPRequest.parse(buffer) {
                deadline.cancel()
                if request.method == "GET",
                   (request.path == "/v1/screen" || request.path.hasPrefix("/v1/screen?")) {
                    self.startScreenStream(
                        connection: connection,
                        request: request
                    )
                } else {
                    let response = self.handle(request)
                    connection.send(
                        content: response,
                        completion: .contentProcessed { _ in
                            connection.cancel()
                        }
                    )
                }
                return
            }

            if complete || error != nil {
                deadline.cancel()
                connection.cancel()
                return
            }

            self.receive(
                from: connection,
                accumulated: buffer,
                deadline: deadline
            )
        }
    }

    private static func exceedsRequestLimits(_ data: Data) -> Bool {
        guard data.count <= maximumRequestBytes else {
            return true
        }

        let delimiter = Data([13, 10, 13, 10])
        guard let separator = data.range(of: delimiter) else {
            return data.count > maximumHeaderBytes
        }

        let headerData = data[..<separator.lowerBound]
        guard headerData.count <= maximumHeaderBytes,
              let headerText = String(data: headerData, encoding: .utf8)
        else {
            return true
        }

        var parsedLength: Int?
        for line in headerText.components(separatedBy: "\r\n").dropFirst() {
            guard let colon = line.firstIndex(of: ":") else {
                continue
            }
            let name = line[..<colon]
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .lowercased()
            guard name == "content-length" else {
                continue
            }

            // Duplicated, malformed, negative, or excessive lengths are
            // rejected before request parsing performs any collection slice.
            guard parsedLength == nil,
                  let length = Int(line[line.index(after: colon)...]
                    .trimmingCharacters(in: .whitespacesAndNewlines)),
                  length >= 0,
                  length <= maximumBodyBytes
            else {
                return true
            }
            parsedLength = length
        }

        let receivedBodyLength = data.distance(
            from: separator.upperBound,
            to: data.endIndex
        )
        guard receivedBodyLength <= maximumBodyBytes else {
            return true
        }
        if let parsedLength {
            if receivedBodyLength > parsedLength {
                return true
            }
        } else if receivedBodyLength > 0 {
            return true
        }
        return false
    }

    private static func isAllowedPeer(_ endpoint: NWEndpoint) -> Bool {
        guard case let .hostPort(host, _) = endpoint else {
            return false
        }

        switch host {
        case .ipv4(let address):
            let bytes = Array(address.rawValue)
            guard bytes.count == 4 else { return false }
            // Tailscale IPv4 addresses use 100.64.0.0/10. Loopback is
            // retained for local diagnostics and same-Mac testing.
            return (bytes[0] == 100 && (64...127).contains(Int(bytes[1]))) ||
                bytes[0] == 127
        case .ipv6(let address):
            let bytes = Array(address.rawValue)
            guard bytes.count == 16 else { return false }
            let tailscalePrefix: [UInt8] = [0xfd, 0x7a, 0x11, 0x5c, 0xa1, 0xe0]
            let isTailscale = Array(bytes.prefix(6)) == tailscalePrefix
            let isLoopback = bytes.dropLast().allSatisfy { $0 == 0 } && bytes[15] == 1
            return isTailscale || isLoopback
        case .name:
            return false
        @unknown default:
            return false
        }
    }

    private func startScreenStream(
        connection: NWConnection,
        request: HTTPRequest
    ) {
        guard authenticate(request) else {
            let response = HTTPResponse.json(
                status: 401,
                body: ErrorResponse(
                    ok: false,
                    error: "Unauthorized"
                )
            )

            connection.send(
                content: response,
                completion: .contentProcessed { _ in
                    connection.cancel()
                }
            )

            return
        }

        // The audio option is part of the signed request path, so it cannot
        // be changed by an intermediary after authentication.
        let queryParts = request.path.split(
            separator: "?",
            maxSplits: 1,
            omittingEmptySubsequences: false
        )
        let audioEnabled = queryParts.count > 1 &&
            queryParts[1].split(separator: "&").contains("audio=1")

        // Authenticated private screen stream using the PZV1 packet wrapper.
        // Video-only clients continue to receive ordinary H.264 packets.
        var responseHeaders = [
            "HTTP/1.1 200 OK",
            "Content-Type: video/H264",
            "Cache-Control: no-cache, no-store, must-revalidate",
            "Pragma: no-cache",
            "Connection: close",
            "X-Pauze-Video-Codec: H264",
            "X-Pauze-Frame-Format: PZV1",
            "X-Pauze-Max-Width: \(ScreenStreamer.maxWidth)",
            "X-Pauze-Max-Height: \(ScreenStreamer.maxHeight)",
            "X-Pauze-Max-FPS: \(ScreenStreamer.maxFPS)"
        ]
        if audioEnabled {
            responseHeaders.append("X-Pauze-Audio-Codec: PCM_S16LE")
            responseHeaders.append("X-Pauze-Audio-Sample-Rate: \(ScreenStreamer.audioSampleRate)")
            responseHeaders.append("X-Pauze-Audio-Channels: 1")
        }
        responseHeaders.append("")
        responseHeaders.append("")
        let header = responseHeaders.joined(separator: "\r\n")

        let clientID = UUID()

        let client = ScreenClient(
            connection: connection,
            stream: ScreenStreamer.shared,
            audioEnabled: audioEnabled,
            onClose: { [weak self] in
                self?.removeScreenClient(clientID)
            }
        )

        screenClientLock.lock()
        screenClients[clientID] = client
        screenClientLock.unlock()

        connection.stateUpdateHandler = { [weak client] state in
            switch state {
            case .cancelled, .failed:
                client?.close()
            default:
                break
            }
        }

        connection.send(
            content: Data(header.utf8),
            completion: .contentProcessed { [weak client] error in
                guard let client else {
                    connection.cancel()
                    return
                }

                if error != nil {
                    client.close()
                    return
                }

                client.start()
            }
        )
    }

    private func removeScreenClient(_ id: UUID) {
        screenClientLock.lock()
        screenClients.removeValue(forKey: id)
        screenClientLock.unlock()
    }

    private func handle(_ request: HTTPRequest) -> Data {
        guard authenticate(request) else {
            return HTTPResponse.json(
                status: 401,
                body: ErrorResponse(
                    ok: false,
                    error: "Unauthorized"
                )
            )
        }

        switch (request.method, request.path) {
        case ("POST", "/v1/screen/quality"):
            guard
                let bodyData = request.body.data(using: .utf8),
                let object = try? JSONSerialization.jsonObject(with: bodyData) as? [String: Any],
                let quality = object["quality"] as? String
            else {
                return HTTPResponse.json(
                    status: 400,
                    body: ErrorResponse(
                        ok: false,
                        error: "Quality must be one of 144p, 240p, 360p, 480p, 720p or 1080p."
                    )
                )
            }

            let requestedFPS = (object["fps"] as? NSNumber)?.intValue
            let accepted = ScreenStreamer.shared.setQuality(
                quality,
                fps: requestedFPS
            )

            guard accepted else {
                return HTTPResponse.json(
                    status: 400,
                    body: ErrorResponse(
                        ok: false,
                        error: "Invalid quality/FPS combination. 60 FPS is available only for 720p and 1080p."
                    )
                )
            }

            return HTTPResponse.json(
                status: 200,
                body: ScreenQualityResponse(
                    ok: true,
                    quality: quality.lowercased(),
                    fps: requestedFPS ?? 0
                )
            )

        case ("POST", "/v1/screen/input"):
            guard
                let bodyData = request.body.data(using: .utf8),
                let object = try? JSONSerialization.jsonObject(with: bodyData) as? [String: Any],
                let action = object["action"] as? String
            else {
                return HTTPResponse.json(
                    status: 400,
                    body: RemoteInputResponse(ok: false, error: "Invalid remote input request.")
                )
            }

            do {
                try RemoteInput.perform(action: action, payload: object)
                return HTTPResponse.json(
                    status: 200,
                    body: RemoteInputResponse(ok: true, error: nil)
                )
            } catch {
                return HTTPResponse.json(
                    status: 400,
                    body: RemoteInputResponse(ok: false, error: error.localizedDescription)
                )
            }

        case ("GET", "/v1/clipboard"):
            do {
                return HTTPResponse.json(status: 200,
                    body: ClipboardResponse(ok: true, text: try ClipboardService.readText()))
            } catch {
                return HTTPResponse.json(status: 400,
                    body: ErrorResponse(ok: false, error: error.localizedDescription))
            }

        case ("POST", "/v1/clipboard"):
            guard let bodyData = request.body.data(using: .utf8),
                  let object = try? JSONSerialization.jsonObject(with: bodyData) as? [String: Any],
                  let text = object["text"] as? String else {
                return HTTPResponse.json(status: 400,
                    body: ErrorResponse(ok: false, error: "Clipboard text is required."))
            }
            do {
                try ClipboardService.writeText(text)
                return HTTPResponse.json(status: 200, body: ClipboardResponse(ok: true, text: ""))
            } catch {
                return HTTPResponse.json(status: 400,
                    body: ErrorResponse(ok: false, error: error.localizedDescription))
            }

        case ("GET", "/v1/files/list"):
            do {
                return HTTPResponse.json(status: 200,
                    body: FilesListResponse(ok: true, files: try FileTransferStore.list()))
            } catch {
                return HTTPResponse.json(status: 500,
                    body: ErrorResponse(ok: false, error: "Could not list transferred files."))
            }

        case ("POST", "/v1/files/upload-chunk"):
            guard let bodyData = request.body.data(using: .utf8),
                  let object = try? JSONSerialization.jsonObject(with: bodyData) as? [String: Any],
                  let transferID = object["transfer_id"] as? String,
                  let filename = object["filename"] as? String,
                  let offset = object["offset"] as? Int,
                  let total = object["total"] as? Int,
                  let base64 = object["data"] as? String else {
                return HTTPResponse.json(status: 400,
                    body: ErrorResponse(ok: false, error: "Invalid upload chunk request."))
            }
            do {
                let complete = try FileTransferStore.appendChunk(transferID: transferID,
                    filename: filename, offset: Int64(offset), total: Int64(total), base64Data: base64)
                return HTTPResponse.json(status: 200, body: UploadChunkResponse(ok: true, complete: complete))
            } catch {
                return HTTPResponse.json(status: 400,
                    body: ErrorResponse(ok: false, error: error.localizedDescription))
            }

        case ("POST", "/v1/files/download-chunk"):
            guard let bodyData = request.body.data(using: .utf8),
                  let object = try? JSONSerialization.jsonObject(with: bodyData) as? [String: Any],
                  let filename = object["filename"] as? String,
                  let offset = object["offset"] as? Int else {
                return HTTPResponse.json(status: 400,
                    body: ErrorResponse(ok: false, error: "Invalid download chunk request."))
            }
            do {
                let chunk = try FileTransferStore.readChunk(filename: filename, offset: Int64(offset))
                return HTTPResponse.json(status: 200,
                    body: DownloadChunkResponse(ok: true, filename: filename, offset: offset,
                        total: chunk.total, data: chunk.base64Data))
            } catch {
                return HTTPResponse.json(status: 400,
                    body: ErrorResponse(ok: false, error: error.localizedDescription))
            }

        case ("GET", "/v1/audio/devices"):
            do {
                return HTTPResponse.json(status: 200,
                    body: AudioDevicesResponse(ok: true, devices: try AudioDeviceService.list()))
            } catch {
                return HTTPResponse.json(status: 500,
                    body: ErrorResponse(ok: false, error: "Could not enumerate macOS audio devices: \(error.localizedDescription)"))
            }

        case ("POST", "/v1/audio/device"):
            guard let bodyData = request.body.data(using: .utf8),
                  let object = try? JSONSerialization.jsonObject(with: bodyData) as? [String: Any],
                  let idNumber = object["id"] as? NSNumber,
                  let direction = object["direction"] as? String else {
                return HTTPResponse.json(status: 400,
                    body: ErrorResponse(ok: false, error: "An audio device ID and direction are required."))
            }
            do {
                try AudioDeviceService.setDefaultDevice(id: idNumber.uint32Value, direction: direction)
                return HTTPResponse.json(status: 200,
                    body: AudioDeviceChangeResponse(ok: true, direction: direction, deviceID: idNumber.uint32Value))
            } catch {
                return HTTPResponse.json(status: 400,
                    body: ErrorResponse(ok: false, error: error.localizedDescription))
            }

        case ("GET", "/v1/status"):
            return HTTPResponse.json(
                status: 200,
                body: StatusResponse(
                    ok: true,
                    device: Host.current().localizedName ?? "Mac",
                    restricted: manager.restricted,
                    inputBlockingEnabled:
                        manager.inputBlockingEnabled,
                    serverPort: Int(port),
                    system: SystemService.snapshot()
                )
            )

        case ("POST", "/v1/restrict"):
            return HTTPResponse.json(
                status: 200,
                body: manager.restrict()
            )

        case ("POST", "/v1/allow"):
            return HTTPResponse.json(
                status: 200,
                body: manager.allow()
            )

        case ("POST", "/v1/lock"):
            return executeSystemCommand(
                name: "lock"
            ) {
                try SystemService.perform("lock")
            }

        case ("POST", "/v1/sleep"):
            return executeSystemCommand(
                name: "sleep"
            ) {
                try SystemService.perform("sleep")
            }

        case ("POST", "/v1/restart"):
            return executeSystemCommand(
                name: "restart"
            ) {
                try SystemService.perform("restart")
            }

        case ("POST", "/v1/shutdown"):
            return executeSystemCommand(
                name: "shutdown"
            ) {
                try SystemService.perform("shutdown")
            }

        case ("POST", "/v1/mute"):
            return executeSystemCommand(
                name: "mute"
            ) {
                try SystemService.perform("mute")
            }

        case ("POST", "/v1/unmute"):
            return executeSystemCommand(
                name: "unmute"
            ) {
                try SystemService.perform("unmute")
            }

        case ("POST", "/v1/volume"):
            guard
                let bodyData =
                    request.body.data(using: .utf8),
                let object =
                    try? JSONSerialization.jsonObject(
                        with: bodyData
                    ) as? [String: Any],
                let number =
                    object["volume"] as? NSNumber
            else {
                return HTTPResponse.json(
                    status: 400,
                    body: ErrorResponse(
                        ok: false,
                        error:
                            "Volume must be a number from 0 to 100."
                    )
                )
            }

            let volume =
                max(
                    0,
                    min(
                        100,
                        Int(number.doubleValue.rounded())
                    )
                )

            return executeSystemCommand(
                name: "volume"
            ) {
                try SystemService.perform(
                    "volume",
                    value: volume
                )
            }

        default:
            return HTTPResponse.json(
                status: 404,
                body: ErrorResponse(
                    ok: false,
                    error: "Not found"
                )
            )
        }
    }

private struct ScreenQualityResponse: Codable {
    let ok: Bool
    let quality: String
    let fps: Int
}

private struct RemoteInputResponse: Codable {
    let ok: Bool
    let error: String?
}

    private func executeSystemCommand(
        name: String,
        operation: () throws -> Void
    ) -> Data {
        do {
            try operation()

            return HTTPResponse.json(
                status: 200,
                body: CommandResponse(
                    ok: true,
                    command: name,
                    restricted: manager.restricted,
                    inputBlockingEnabled:
                        manager.inputBlockingEnabled
                )
            )
        } catch {
            return HTTPResponse.json(
                status: 500,
                body: ErrorResponse(
                    ok: false,
                    error: error.localizedDescription
                )
            )
        }
    }

    private func authenticate(_ request: HTTPRequest) -> Bool {
        guard
            let timestampString =
                request.headers["x-pauze-timestamp"],
            let timestamp = Int64(timestampString),
            let nonce = request.headers["x-pauze-nonce"],
            let signature =
                request.headers["x-pauze-signature"],
            !nonce.isEmpty,
            !signature.isEmpty
        else {
            return false
        }

        let now = Int64(
            Date().timeIntervalSince1970
        )

        // Compare bounds directly; subtracting an attacker-controlled Int64
        // and passing it to abs() can overflow and trap before authentication.
        guard timestamp >= now - 120 && timestamp <= now + 120 else {
            return false
        }

        let canonical = [
            String(timestamp),
            nonce,
            request.method.uppercased(),
            request.path,
            request.body
        ].joined(separator: "\n")

        let key = SymmetricKey(
            data: Data(token.utf8)
        )

        let digest = HMAC<SHA256>.authenticationCode(
            for: Data(canonical.utf8),
            using: key
        )

        let hexDigits = Array("0123456789abcdef")
        var expectedCharacters: [Character] = []
        expectedCharacters.reserveCapacity(64)
        for byte in digest {
            let value = Int(byte)
            expectedCharacters.append(hexDigits[value >> 4])
            expectedCharacters.append(hexDigits[value & 0x0F])
        }
        let expected = String(expectedCharacters)

        guard constantTimeEquals(
            signature.lowercased(),
            expected
        ) else {
            return false
        }

        // Only authenticated requests consume nonce-cache entries. Otherwise
        // anyone could grow the replay cache with arbitrary unsigned requests.
        return registerNonce(nonce)
    }

    private func registerNonce(_ nonce: String) -> Bool {
        nonceLock.lock()
        defer { nonceLock.unlock() }

        let cutoff = Date().addingTimeInterval(-180)

        seenNonces = seenNonces.filter {
            $0.value > cutoff
        }

        guard seenNonces[nonce] == nil else {
            return false
        }

        seenNonces[nonce] = Date()
        return true
    }

    private func constantTimeEquals(
        _ lhs: String,
        _ rhs: String
    ) -> Bool {
        let left = Array(lhs.utf8)
        let right = Array(rhs.utf8)

        guard left.count == right.count else {
            return false
        }

        var difference: UInt8 = 0

        for index in left.indices {
            difference |= left[index] ^ right[index]
        }

        return difference == 0
    }

}

private final class ScreenClient {
    private static let headerSize = 24
    private static let magic = Data([0x50, 0x5A, 0x56, 0x31])
    private static let maximumPendingAudioPackets = 12

    private let connection: NWConnection
    private let stream: ScreenStreamer
    private let audioEnabled: Bool
    private let onClose: () -> Void

    private let queue = DispatchQueue(
        label: "com.pauze.control.screen.client",
        qos: .userInteractive
    )

    private var subscriberID: UUID?
    private var pendingVideo: EncodedScreenFrame?
    private var pendingAudio: [EncodedScreenAudio] = []
    private var sending = false
    private var closed = false

    init(
        connection: NWConnection,
        stream: ScreenStreamer,
        audioEnabled: Bool,
        onClose: @escaping () -> Void
    ) {
        self.connection = connection
        self.stream = stream
        self.audioEnabled = audioEnabled
        self.onClose = onClose
    }

    func start() {
        let id = stream.subscribe(
            audioEnabled: audioEnabled,
            onFrame: { [weak self] frame in
                self?.enqueue(frame: frame)
            },
            onAudio: { [weak self] packet in
                self?.enqueue(audio: packet)
            }
        )

        queue.async {
            guard !self.closed else {
                self.stream.unsubscribe(id)
                return
            }

            self.subscriberID = id
        }
    }

    func close() {
        queue.async {
            guard !self.closed else {
                return
            }

            self.closed = true

            if let id = self.subscriberID {
                self.stream.unsubscribe(id)
                self.subscriberID = nil
            }

            self.pendingVideo = nil
            self.pendingAudio.removeAll(keepingCapacity: false)
            self.sending = false
            self.connection.cancel()
            self.onClose()
        }
    }

    private func enqueue(frame: EncodedScreenFrame) {
        queue.async {
            guard !self.closed else {
                return
            }

            // Keep the latest useful video frame; do not evict a pending
            // keyframe before the Android decoder has a chance to receive it.
            if let pending = self.pendingVideo,
               pending.keyFrame,
               !frame.keyFrame {
                return
            }

            self.pendingVideo = frame
            guard !self.sending else { return }
            self.sendNext()
        }
    }

    private func enqueue(audio packet: EncodedScreenAudio) {
        queue.async {
            guard !self.closed, self.audioEnabled else {
                return
            }

            // Audio is real-time data: bound memory and prefer recent chunks
            // when the network falls behind instead of building a long delay.
            if self.pendingAudio.count >= Self.maximumPendingAudioPackets {
                self.pendingAudio.removeFirst()
            }
            self.pendingAudio.append(packet)

            guard !self.sending else { return }
            self.sendNext()
        }
    }

    private func sendNext() {
        guard !closed else { return }

        let payload: Data
        if !pendingAudio.isEmpty {
            payload = makeAudioPacket(pendingAudio.removeFirst())
        } else if let frame = pendingVideo {
            pendingVideo = nil
            payload = makeVideoPacket(frame)
        } else {
            sending = false
            return
        }

        sending = true
        connection.send(
            content: payload,
            completion: .contentProcessed { [weak self] error in
                guard let self else { return }
                self.queue.async {
                    self.sending = false
                    if error != nil {
                        self.closed = true
                        if let id = self.subscriberID {
                            self.stream.unsubscribe(id)
                            self.subscriberID = nil
                        }
                        self.pendingVideo = nil
                        self.pendingAudio.removeAll(keepingCapacity: false)
                        self.connection.cancel()
                        self.onClose()
                        return
                    }
                    self.sendNext()
                }
            }
        )
    }

    private func makeVideoPacket(_ frame: EncodedScreenFrame) -> Data {
        // PZV1 header: magic[4], version[1], flags[1], headerLength[2],
        // payloadLength[4], ptsUs[8], width[2], height[2].
        var packet = Self.magic
        packet.append(0x01)
        packet.append(frame.keyFrame ? 0x01 : 0x00)
        appendUInt16(UInt16(Self.headerSize), to: &packet)
        appendUInt32(UInt32(frame.data.count), to: &packet)
        appendUInt64(UInt64(bitPattern: frame.ptsMicroseconds), to: &packet)
        appendUInt16(UInt16(max(0, min(frame.width, ScreenStreamer.maxWidth))), to: &packet)
        appendUInt16(UInt16(max(0, min(frame.height, ScreenStreamer.maxHeight))), to: &packet)
        packet.append(frame.data)
        return packet
    }

    private func makeAudioPacket(_ audio: EncodedScreenAudio) -> Data {
        // PZV1 flag 0x02 denotes mono signed 16-bit little-endian PCM audio.
        // Width and height are zero for audio packets; the shared timestamp
        // preserves ordering information across the two media types.
        var packet = Self.magic
        packet.append(0x01)
        packet.append(0x02)
        appendUInt16(UInt16(Self.headerSize), to: &packet)
        appendUInt32(UInt32(audio.data.count), to: &packet)
        appendUInt64(UInt64(bitPattern: audio.ptsMicroseconds), to: &packet)
        appendUInt16(0, to: &packet)
        appendUInt16(0, to: &packet)
        packet.append(audio.data)
        return packet
    }
}
private func appendUInt16(
    _ value: UInt16,
    to data: inout Data
) {
    data.append(UInt8(value >> 8))
    data.append(UInt8(value & 0xFF))
}

private func appendUInt32(
    _ value: UInt32,
    to data: inout Data
) {
    data.append(UInt8((value >> 24) & 0xFF))
    data.append(UInt8((value >> 16) & 0xFF))
    data.append(UInt8((value >> 8) & 0xFF))
    data.append(UInt8(value & 0xFF))
}

private func appendUInt64(
    _ value: UInt64,
    to data: inout Data
) {
    for shift in stride(from: 56, through: 0, by: -8) {
        data.append(UInt8((value >> UInt64(shift)) & 0xFF))
    }
}

private struct HTTPRequest {
    let method: String
    let path: String
    let headers: [String: String]
    let body: String

    static func parse(_ data: Data) -> HTTPRequest? {
        guard data.count <= 80 * 1024,
              let text = String(data: data, encoding: .utf8),
              let separator = text.range(of: "\r\n\r\n")
        else {
            return nil
        }

        let head = String(text[..<separator.lowerBound])
        let rawBody = String(text[separator.upperBound...])
        guard head.utf8.count <= 16 * 1024 else {
            return nil
        }
        let lines = head.components(separatedBy: "\r\n")

        guard let line = lines.first else {
            return nil
        }
        let requestParts = line.split(separator: " ", omittingEmptySubsequences: true)
        guard requestParts.count == 3,
              (requestParts[2] == "HTTP/1.1" || requestParts[2] == "HTTP/1.0")
        else {
            return nil
        }

        var headers: [String: String] = [:]
        for item in lines.dropFirst() {
            guard let colon = item.firstIndex(of: ":") else {
                continue
            }
            let key = item[..<colon]
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .lowercased()
            let value = item[item.index(after: colon)...]
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if key == "content-length", headers[key] != nil {
                return nil
            }
            headers[key] = value
        }

        let rawLength = headers["content-length"] ?? "0"
        guard let contentLength = Int(rawLength),
              contentLength >= 0,
              contentLength <= 64 * 1024,
              rawBody.utf8.count >= contentLength
        else {
            return nil
        }

        let bodyBytes = Array(rawBody.utf8.prefix(contentLength))
        guard let body = String(bytes: bodyBytes, encoding: .utf8) else {
            return nil
        }

        return HTTPRequest(
            method: String(requestParts[0]),
            path: String(requestParts[1]),
            headers: headers,
            body: body
        )
    }
}

private enum HTTPResponse {
    static func json<T: Encodable>(
        status: Int,
        body: T
    ) -> Data {
        let payload =
            (try? JSONEncoder().encode(body))
            ?? Data("{\"ok\":false}".utf8)

        let reason: String

        switch status {
        case 200: reason = "OK"
        case 400: reason = "Bad Request"
        case 401: reason = "Unauthorized"
        case 404: reason = "Not Found"
        case 500: reason = "Internal Server Error"
        default: reason = "Error"
        }

        let head = [
            "HTTP/1.1 \(status) \(reason)",
            "Content-Type: application/json; charset=utf-8",
            "Content-Length: \(payload.count)",
            "Connection: close",
            "",
            ""
        ].joined(separator: "\r\n")

        return Data(head.utf8) + payload
    }
}
