// PauzeControl — Copyright (c) 2026 PauzeDevs. All rights reserved.

import Foundation
import Security

final class SecurityStore {
    static let shared = SecurityStore()

    private let service = "com.pauze.control"
    private let account = "controller-secret"

    private init() {}

    func token() throws -> String {
        if let existing = try readToken() {
            return existing
        }

        var bytes = [UInt8](repeating: 0, count: 32)
        let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)

        guard status == errSecSuccess else {
            throw NSError(
                domain: "PauzeControl.Security",
                code: Int(status),
                userInfo: [
                    NSLocalizedDescriptionKey:
                        "Secure random generation failed."
                ]
            )
        }

        let value = bytes.map { String(format: "%02x", $0) }.joined()
        try writeToken(value)
        return value
    }

    private func readToken() throws -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]

        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)

        if status == errSecItemNotFound {
            return nil
        }

        guard status == errSecSuccess,
              let data = result as? Data,
              let value = String(data: data, encoding: .utf8)
        else {
            throw NSError(
                domain: "PauzeControl.Security",
                code: Int(status),
                userInfo: [
                    NSLocalizedDescriptionKey:
                        "Unable to read the macOS Keychain."
                ]
            )
        }

        return value
    }

    private func writeToken(_ value: String) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecValueData as String: Data(value.utf8)
        ]

        let status = SecItemAdd(query as CFDictionary, nil)

        guard status == errSecSuccess || status == errSecDuplicateItem else {
            throw NSError(
                domain: "PauzeControl.Security",
                code: Int(status),
                userInfo: [
                    NSLocalizedDescriptionKey:
                        "Unable to store the macOS Keychain secret."
                ]
            )
        }
    }
}
