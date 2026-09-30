// PauzeControl — Copyright (c) 2026 PauzeDevs. All rights reserved.

import Foundation

struct StatusResponse: Codable {
    let ok: Bool
    let device: String
    let restricted: Bool
    let inputBlockingEnabled: Bool
    let serverPort: Int

    enum CodingKeys: String, CodingKey {
        case ok, device, restricted
        case inputBlockingEnabled = "input_blocking_enabled"
        case serverPort = "server_port"
    }
}

struct CommandResponse: Codable {
    let ok: Bool
    let command: String
    let restricted: Bool
    let inputBlockingEnabled: Bool

    enum CodingKeys: String, CodingKey {
        case ok, command, restricted
        case inputBlockingEnabled = "input_blocking_enabled"
    }
}

struct ErrorResponse: Codable {
    let ok: Bool
    let error: String
}
