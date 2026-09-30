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
        qos: .userInitiated
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

    private func handle(_ connection: NWConnection) {
        connection.stateUpdateHandler = {
            [weak self, weak connection] state in

            guard let self, let connection else { return }

            if case .ready = state {
                self.receive(
                    from: connection,
                    accumulated: Data()
                )
            }
        }

        connection.start(queue: queue)
    }

    private func receive(
        from connection: NWConnection,
        accumulated: Data
    ) {
        connection.receive(
            minimumIncompleteLength: 1,
            maximumLength: 65_536
        ) { [weak self] data, _, complete, error in

            guard let self else {
                connection.cancel()
                return
            }

            var buffer = accumulated

            if let data {
                buffer.append(data)
            }

            if let request = HTTPRequest.parse(buffer) {
                if request.method == "GET",
                   request.path == "/v1/screen" {
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
                connection.cancel()
                return
            }

            self.receive(
                from: connection,
                accumulated: buffer
            )
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

        // Authenticated private H.264 stream using the PZV1 packet wrapper.
        let header = [
            "HTTP/1.1 200 OK",
            "Content-Type: video/H264",
            "Cache-Control: no-cache, no-store, must-revalidate",
            "Pragma: no-cache",
            "Connection: close",
            "X-Pauze-Video-Codec: H264",
            "X-Pauze-Frame-Format: PZV1",
            "X-Pauze-Max-Width: \(ScreenStreamer.maxWidth)",
            "X-Pauze-Max-Height: \(ScreenStreamer.maxHeight)",
            "X-Pauze-Max-FPS: \(ScreenStreamer.maxFPS)",
            "",
            ""
        ].joined(separator: "\r\n")

        let clientID = UUID()

        let client = ScreenClient(
            connection: connection,
            stream: ScreenStreamer.shared,
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

        guard abs(now - timestamp) <= 120 else {
            return false
        }

        guard registerNonce(nonce) else {
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

        let expected = Data(digest)
            .map { String(format: "%02x", $0) }
            .joined()

        return constantTimeEquals(
            signature.lowercased(),
            expected
        )
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

    private let connection: NWConnection
    private let stream: ScreenStreamer
    private let onClose: () -> Void

    private let queue = DispatchQueue(
        label: "com.pauze.control.screen.client",
        qos: .userInteractive
    )

    private var subscriberID: UUID?
    private var pending: EncodedScreenFrame?
    private var sending = false
    private var closed = false

    init(
        connection: NWConnection,
        stream: ScreenStreamer,
        onClose: @escaping () -> Void
    ) {
        self.connection = connection
        self.stream = stream
        self.onClose = onClose
    }

    func start() {
        let id = stream.subscribe { [weak self] frame in
            self?.enqueue(frame: frame)
        }

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

            self.pending = nil
            self.sending = false
            self.connection.cancel()
            self.onClose()
        }
    }

    private func enqueue(
        frame: EncodedScreenFrame
    ) {
        queue.async {
            guard !self.closed else {
                return
            }

            // Keep the latest useful frame; do not evict a pending keyframe.
            if let pending = self.pending,
               pending.keyFrame,
               !frame.keyFrame {
                return
            }

            self.pending = frame

            guard !self.sending else {
                return
            }

            self.sendNext()
        }
    }

    private func sendNext() {
        guard !closed else {
            return
        }

        guard let frame = pending else {
            sending = false
            return
        }

        pending = nil
        sending = true

        connection.send(
            content: makePacket(frame),
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

                        self.pending = nil
                        self.connection.cancel()
                        self.onClose()
                        return
                    }

                    self.sendNext()
                }
            }
        )
    }

    private func makePacket(
        _ frame: EncodedScreenFrame
    ) -> Data {
        // 24-byte header:
        // magic[4], version[1], flags[1], headerLength[2],
        // payloadLength[4], ptsUs[8], width[2], height[2].
        var packet = Self.magic

        packet.append(0x01)
        packet.append(frame.keyFrame ? 0x01 : 0x00)

        appendUInt16(
            UInt16(Self.headerSize),
            to: &packet
        )

        appendUInt32(
            UInt32(frame.data.count),
            to: &packet
        )

        appendUInt64(
            UInt64(bitPattern: frame.ptsMicroseconds),
            to: &packet
        )

        appendUInt16(
            UInt16(max(0, min(frame.width, ScreenStreamer.maxWidth))),
            to: &packet
        )

        appendUInt16(
            UInt16(max(0, min(frame.height, ScreenStreamer.maxHeight))),
            to: &packet
        )

        packet.append(frame.data)
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
        guard let text = String(
            data: data,
            encoding: .utf8
        ) else {
            return nil
        }

        guard let separator = text.range(
            of: "\r\n\r\n"
        ) else {
            return nil
        }

        let head = String(text[..<separator.lowerBound])
        let rawBody = String(text[separator.upperBound...])
        let lines = head.components(separatedBy: "\r\n")

        guard
            let line = lines.first,
            let firstSpace = line.firstIndex(of: " "),
            let secondSpace = line[
                line.index(after: firstSpace)...
            ].firstIndex(of: " ")
        else {
            return nil
        }

        var headers: [String: String] = [:]

        for item in lines.dropFirst() {
            guard let colon = item.firstIndex(of: ":") else {
                continue
            }

            let key = item[..<colon]
                .trimmingCharacters(
                    in: .whitespacesAndNewlines
                )
                .lowercased()

            let value = item[item.index(after: colon)...]
                .trimmingCharacters(
                    in: .whitespacesAndNewlines
                )

            headers[key] = value
        }

        let contentLength =
            Int(headers["content-length"] ?? "0") ?? 0

        guard rawBody.utf8.count >= contentLength else {
            return nil
        }

        let bodyBytes = Array(
            rawBody.utf8.prefix(contentLength)
        )

        let body = String(
            bytes: bodyBytes,
            encoding: .utf8
        ) ?? ""

        return HTTPRequest(
            method: String(line[..<firstSpace]),
            path: String(
                line[
                    line.index(after: firstSpace)..<secondSpace
                ]
            ),
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
