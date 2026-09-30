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
                let response = self.handle(request)

                connection.send(
                    content: response,
                    completion: .contentProcessed { _ in
                        connection.cancel()
                    }
                )
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
                    inputBlockingEnabled: manager.inputBlockingEnabled,
                    serverPort: Int(port)
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
        case 401: reason = "Unauthorized"
        case 404: reason = "Not Found"
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
