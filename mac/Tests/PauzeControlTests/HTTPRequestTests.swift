// PauzeControl — Copyright (c) 2026 PauzeDevs. All rights reserved.

import Foundation
import XCTest
@testable import PauzeControlHTTP

final class HTTPRequestTests: XCTestCase {
    private func parse(_ request: String) -> HTTPRequest? {
        HTTPRequest.parse(Data(request.utf8))
    }

    func testParsesRequestLineAndNormalizesHeaderNames() {
        let parsed = parse(
            "GET /v1/status HTTP/1.1\r\n" +
            "hOsT: macbook.local\r\n" +
            "X-Pauze-Timestamp: 1700000000\r\n" +
            "\r\n"
        )

        XCTAssertEqual(parsed?.method, "GET")
        XCTAssertEqual(parsed?.path, "/v1/status")
        XCTAssertEqual(parsed?.headers["host"], "macbook.local")
        XCTAssertEqual(parsed?.headers["x-pauze-timestamp"], "1700000000")
        XCTAssertEqual(parsed?.body, "")
    }

    func testParsesUtf8BodyUsingContentLengthInBytes() {
        let body = #"{"text":"नमस्ते 🌌"}"#
        let request =
            "POST /v1/clipboard HTTP/1.1\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: \(body.utf8.count)\r\n" +
            "\r\n" +
            body

        let parsed = parse(request)

        XCTAssertEqual(parsed?.method, "POST")
        XCTAssertEqual(parsed?.body, body)
    }

    func testRejectsNegativeContentLength() {
        XCTAssertNil(parse(
            "POST /v1/clipboard HTTP/1.1\r\n" +
            "Content-Length: -1\r\n\r\n"
        ))
    }

    func testRejectsNonNumericContentLength() {
        XCTAssertNil(parse(
            "POST /v1/clipboard HTTP/1.1\r\n" +
            "Content-Length: many\r\n\r\n"
        ))
    }

    func testRejectsDuplicateContentLengthRegardlessOfHeaderCase() {
        XCTAssertNil(parse(
            "POST /v1/clipboard HTTP/1.1\r\n" +
            "Content-Length: 0\r\n" +
            "content-length: 0\r\n\r\n"
        ))
    }

    func testRejectsContentLengthAboveBodyLimit() {
        XCTAssertNil(parse(
            "POST /v1/clipboard HTTP/1.1\r\n" +
            "Content-Length: 65537\r\n\r\n"
        ))
    }

    func testRejectsTruncatedBody() {
        XCTAssertNil(parse(
            "POST /v1/clipboard HTTP/1.1\r\n" +
            "Content-Length: 5\r\n\r\n{}"
        ))
    }

    func testRejectsOverlongHeaders() {
        let padding = String(repeating: "a", count: 16 * 1024)
        let request =
            "GET /v1/status HTTP/1.1\r\n" +
            "X-Padding: \(padding)\r\n\r\n"

        XCTAssertNil(parse(request))
    }

    func testRejectsRequestsAboveTotalByteLimit() {
        let request = Data(repeating: 0x41, count: 80 * 1024 + 1)
        XCTAssertNil(HTTPRequest.parse(request))
    }

    func testRejectsUnsupportedHttpVersionsAndMalformedRequestLines() {
        XCTAssertNil(parse("GET /v1/status HTTP/2.0\r\n\r\n"))
        XCTAssertNil(parse("GET\r\n\r\n"))
    }
}
