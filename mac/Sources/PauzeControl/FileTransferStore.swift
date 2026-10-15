// PauzeControl — Copyright (c) 2026 PauzeDevs. All rights reserved.
import Foundation

struct TransferFileInfo: Codable {
    let name: String
    let size: Int64
    let modifiedAt: Double
}
struct FilesListResponse: Codable { let ok: Bool; let files: [TransferFileInfo] }
struct UploadChunkResponse: Codable { let ok: Bool; let complete: Bool }
struct DownloadChunkResponse: Codable {
    let ok: Bool
    let filename: String
    let offset: Int
    let total: Int64
    let data: String
}

enum FileTransferStore {
    static let maximumFileBytes: Int64 = 50 * 1024 * 1024
    static let maximumChunkBytes = 24 * 1024
    private static let maximumStagedBytes: Int64 = 100 * 1024 * 1024
    private static let maximumStagingAge: TimeInterval = 24 * 60 * 60
    private static let lock = NSLock()
    private static let fm = FileManager.default

    private static var rootURL: URL {
        fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("PauzeControl", isDirectory: true)
            .appendingPathComponent("Transfers", isDirectory: true)
    }
    private static var stagingURL: URL { rootURL.appendingPathComponent(".staging", isDirectory: true) }

    private static func prepare() throws {
        try fm.createDirectory(at: rootURL, withIntermediateDirectories: true)
        try fm.createDirectory(at: stagingURL, withIntermediateDirectories: true)
    }

    /// Discard abandoned partial uploads and bound disk use by unfinished transfers.
    private static func cleanupStaging() throws -> Int64 {
        let urls = try fm.contentsOfDirectory(
            at: stagingURL,
            includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey],
            options: [.skipsHiddenFiles]
        )
        let cutoff = Date().addingTimeInterval(-maximumStagingAge)
        var remainingBytes: Int64 = 0
        for url in urls {
            let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey])
            guard values?.isRegularFile == true, url.pathExtension == "part" else {
                continue
            }
            if let modified = values?.contentModificationDate, modified < cutoff {
                try? fm.removeItem(at: url)
                continue
            }
            remainingBytes += Int64(max(0, values?.fileSize ?? 0))
        }
        return remainingBytes
    }

    private static func safeFilename(_ name: String) throws -> String {
        guard !name.isEmpty, name.utf8.count <= 255, name != ".", name != "..",
              !name.hasPrefix("."), !name.contains("/"), !name.contains("\\"),
              URL(fileURLWithPath: name).lastPathComponent == name,
              !name.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) else {
            throw NSError(domain: "PauzeControl.Files", code: 400,
                          userInfo: [NSLocalizedDescriptionKey: "Invalid file name. Use a visible filename without path separators."])
        }
        return name
    }

    private static func safeTransferID(_ value: String) throws -> String {
        guard UUID(uuidString: value) != nil else {
            throw NSError(domain: "PauzeControl.Files", code: 400,
                          userInfo: [NSLocalizedDescriptionKey: "Invalid upload transfer ID."])
        }
        return value.lowercased()
    }

    static func list() throws -> [TransferFileInfo] {
        try prepare()
        let urls = try fm.contentsOfDirectory(at: rootURL,
            includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey],
            options: [.skipsHiddenFiles])
        return urls.compactMap { url in
            guard url.deletingLastPathComponent().standardizedFileURL == rootURL.standardizedFileURL,
                  let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey]),
                  values.isRegularFile == true, let size = values.fileSize,
                  Int64(size) <= maximumFileBytes else { return nil }
            return TransferFileInfo(name: url.lastPathComponent, size: Int64(size),
                modifiedAt: values.contentModificationDate?.timeIntervalSince1970 ?? 0)
        }.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }

    static func appendChunk(transferID rawID: String, filename rawName: String,
                            offset: Int64, total: Int64, base64Data: String) throws -> Bool {
        let transferID = try safeTransferID(rawID)
        let filename = try safeFilename(rawName)
        guard offset >= 0, total >= 0, total <= maximumFileBytes, offset <= total,
              let chunk = Data(base64Encoded: base64Data), chunk.count <= maximumChunkBytes,
              Int64(chunk.count) <= total - offset else {
            throw NSError(domain: "PauzeControl.Files", code: 400,
                          userInfo: [NSLocalizedDescriptionKey: "Invalid upload size, offset or chunk. Files are limited to 50 MiB."])
        }
        lock.lock()
        defer { lock.unlock() }
        try prepare()
        let partial = stagingURL.appendingPathComponent(transferID + ".part")
        let destination = rootURL.appendingPathComponent(filename)
        if offset == 0 {
            let stagedBytes = try cleanupStaging()
            guard total <= maximumStagedBytes - min(stagedBytes, maximumStagedBytes) else {
                throw NSError(domain: "PauzeControl.Files", code: 413,
                              userInfo: [NSLocalizedDescriptionKey: "Temporary upload storage is full. Finish or retry later after abandoned uploads are cleaned up."])
            }
            guard !fm.fileExists(atPath: partial.path) else {
                throw NSError(domain: "PauzeControl.Files", code: 409,
                              userInfo: [NSLocalizedDescriptionKey: "An upload with this transfer ID is already in progress."])
            }
            guard !fm.fileExists(atPath: destination.path) else {
                throw NSError(domain: "PauzeControl.Files", code: 409,
                              userInfo: [NSLocalizedDescriptionKey: "A file with this name already exists; rename it to avoid overwriting."])
            }
            guard fm.createFile(atPath: partial.path, contents: Data()) else {
                throw NSError(domain: "PauzeControl.Files", code: 500,
                              userInfo: [NSLocalizedDescriptionKey: "Could not create the temporary upload file."])
            }
        } else if !fm.fileExists(atPath: partial.path) {
            throw NSError(domain: "PauzeControl.Files", code: 400,
                          userInfo: [NSLocalizedDescriptionKey: "Upload session expired or first chunk is missing."])
        }
        let attrs = try fm.attributesOfItem(atPath: partial.path)
        let currentSize = (attrs[.size] as? NSNumber)?.int64Value ?? -1
        guard currentSize == offset else {
            throw NSError(domain: "PauzeControl.Files", code: 400,
                          userInfo: [NSLocalizedDescriptionKey: "Upload chunks must be sent in order without gaps or duplicates."])
        }
        let handle = try FileHandle(forWritingTo: partial)
        do {
            try handle.seekToEnd()
            try handle.write(contentsOf: chunk)
            try handle.synchronize()
            try handle.close()
        } catch {
            try? handle.close()
            throw error
        }
        let newSize = offset + Int64(chunk.count)
        guard newSize <= total else {
            throw NSError(domain: "PauzeControl.Files", code: 400,
                          userInfo: [NSLocalizedDescriptionKey: "Upload exceeded the declared file size."])
        }
        if newSize == total {
            guard !fm.fileExists(atPath: destination.path) else {
                try? fm.removeItem(at: partial)
                throw NSError(domain: "PauzeControl.Files", code: 409,
                              userInfo: [NSLocalizedDescriptionKey: "A file with this name already exists."])
            }
            try fm.moveItem(at: partial, to: destination)
            return true
        }
        return false
    }

    static func readChunk(filename rawName: String, offset: Int64) throws -> (total: Int64, base64Data: String) {
        let filename = try safeFilename(rawName)
        guard offset >= 0 else {
            throw NSError(domain: "PauzeControl.Files", code: 400,
                          userInfo: [NSLocalizedDescriptionKey: "Invalid file offset."])
        }
        try prepare()
        let url = rootURL.appendingPathComponent(filename)
        guard url.deletingLastPathComponent().standardizedFileURL == rootURL.standardizedFileURL,
              let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey]),
              values.isRegularFile == true, let size = values.fileSize,
              Int64(size) <= maximumFileBytes, offset <= Int64(size) else {
            throw NSError(domain: "PauzeControl.Files", code: 404,
                          userInfo: [NSLocalizedDescriptionKey: "Transferred file was not found or exceeds the download limit."])
        }
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        try handle.seek(toOffset: UInt64(offset))
        let chunk = try handle.read(upToCount: maximumChunkBytes) ?? Data()
        return (Int64(size), chunk.base64EncodedString())
    }
}
