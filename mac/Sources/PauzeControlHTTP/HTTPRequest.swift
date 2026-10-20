// PauzeControl — Copyright (c) 2026 PauzeDevs. All rights reserved.

import Foundation

/// Bounded HTTP/1.x request parser shared by the Mac agent and regression tests.
public struct HTTPRequest {
    public let method: String
    public let path: String
    public let headers: [String: String]
    public let body: String

    public static func parse(_ data: Data) -> HTTPRequest? {
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
