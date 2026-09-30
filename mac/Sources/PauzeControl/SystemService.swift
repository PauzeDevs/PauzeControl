// PauzeControl — Copyright (c) 2026 PauzeDevs. All rights reserved.

import Foundation

struct SystemSnapshot: Codable {
    let cpuUsagePercent: Double
    let memoryUsedGB: Double
    let memoryTotalGB: Double
    let diskFreeGB: Double
    let diskTotalGB: Double
    let batteryPercent: Int?
    let batteryCharging: Bool?
    let uptimeSeconds: Double
    let macOSVersion: String
    let outputVolume: Int
    let outputMuted: Bool

    enum CodingKeys: String, CodingKey {
        case cpuUsagePercent = "cpu_usage_percent"
        case memoryUsedGB = "memory_used_gb"
        case memoryTotalGB = "memory_total_gb"
        case diskFreeGB = "disk_free_gb"
        case diskTotalGB = "disk_total_gb"
        case batteryPercent = "battery_percent"
        case batteryCharging = "battery_charging"
        case uptimeSeconds = "uptime_seconds"
        case macOSVersion = "macos_version"
        case outputVolume = "output_volume"
        case outputMuted = "output_muted"
    }
}

struct ScreenPowerProfile {
    let fps: Int
    let bitrate: Int
    let name: String
}

enum SystemService {
    static func streamingProfile() -> ScreenPowerProfile {
        let current = battery()

        // Screen capture is the expensive part of the remote viewer. Keep
        // the idle agent cheap, and automatically reduce capture/encoding
        // cost while the Mac is running from battery power.
        guard current.charging == false else {
            return ScreenPowerProfile(
                fps: 30,
                bitrate: 4_000_000,
                name: "AC • 30 FPS"
            )
        }

        if let percent = current.percent, percent <= 15 {
            return ScreenPowerProfile(
                fps: 10,
                bitrate: 1_250_000,
                name: "Battery • 10 FPS • Saver"
            )
        }

        if let percent = current.percent, percent <= 30 {
            return ScreenPowerProfile(
                fps: 12,
                bitrate: 1_500_000,
                name: "Battery • 12 FPS"
            )
        }

        return ScreenPowerProfile(
            fps: 15,
            bitrate: 2_000_000,
            name: "Battery • 15 FPS"
        )
    }

    static func snapshot() -> SystemSnapshot {
        let memory = memory()
        let disk = disk()
        let battery = battery()
        let audio = audio()
        return SystemSnapshot(
            cpuUsagePercent: round(max(0, min(100, cpu())) * 10) / 10,
            memoryUsedGB: round(memory.used * 100) / 100,
            memoryTotalGB: round(memory.total * 100) / 100,
            diskFreeGB: round(disk.free * 10) / 10,
            diskTotalGB: round(disk.total * 10) / 10,
            batteryPercent: battery.percent,
            batteryCharging: battery.charging,
            uptimeSeconds: ProcessInfo.processInfo.systemUptime,
            macOSVersion: macOSVersion(),
            outputVolume: audio.volume,
            outputMuted: audio.muted
        )
    }

    static func perform(_ action: String, value: Int? = nil) throws {
        switch action {
        case "lock": try appleScript(#"tell application "System Events" to keystroke "q" using {control down, command down}"#)
        case "sleep": try run("/usr/bin/pmset", ["sleepnow"])
        case "restart": try appleScript(#"tell application "System Events" to restart"#)
        case "shutdown": try appleScript(#"tell application "System Events" to shut down"#)
        case "mute": try appleScript("set volume with output muted true")
        case "unmute": try appleScript("set volume with output muted false")
        case "volume":
            guard let value, (0...100).contains(value) else {
                throw NSError(domain: "PauzeControl.System", code: 400, userInfo: [NSLocalizedDescriptionKey: "Volume must be between 0 and 100."])
            }
            try appleScript("set volume output volume \(value)")
        default:
            throw NSError(domain: "PauzeControl.System", code: 404, userInfo: [NSLocalizedDescriptionKey: "Unknown system action."])
        }
    }

    private static func cpu() -> Double {
        guard let output = commandOutput("/bin/ps", ["-A", "-o", "%cpu="]) else { return 0 }
        let total = output.split(whereSeparator: { $0.isWhitespace }).compactMap { Double($0) }.reduce(0, +)
        let cores = Double(max(ProcessInfo.processInfo.activeProcessorCount, 1))
        return total / cores
    }

    private static func memory() -> (used: Double, total: Double) {
        let total = Double(ProcessInfo.processInfo.physicalMemory) / 1_073_741_824.0
        guard let output = commandOutput("/usr/bin/vm_stat", []) else { return (0, total) }
        func number(_ line: String) -> UInt64 {
            line.split(whereSeparator: { !$0.isNumber }).first { !$0.isEmpty && $0.allSatisfy { $0.isNumber } }.flatMap { UInt64($0) } ?? 0
        }
        let lines = output.components(separatedBy: .newlines)
        let pageSize: UInt64 = lines.first(where: { $0.contains("page size of") }).map(number) ?? 4096
        let active: UInt64 = lines.first(where: { $0.hasPrefix("Pages active:") }).map(number) ?? 0
        let wired: UInt64 = lines.first(where: { $0.hasPrefix("Pages wired down:") }).map(number) ?? 0
        let compressed: UInt64 = lines.first(where: { $0.hasPrefix("Pages occupied by compressor:") }).map(number) ?? 0
        let used = Double((active + wired + compressed) * pageSize) / 1_073_741_824.0
        return (min(max(used, 0), total), total)
    }

    private static func disk() -> (free: Double, total: Double) {
        let url = URL(fileURLWithPath: "/")
        guard let values = try? url.resourceValues(forKeys: [.volumeTotalCapacityKey, .volumeAvailableCapacityForImportantUsageKey]), let total = values.volumeTotalCapacity, let free = values.volumeAvailableCapacityForImportantUsage else { return (0, 0) }
        return (Double(free) / 1_073_741_824.0, Double(total) / 1_073_741_824.0)
    }

    private static func battery() -> (percent: Int?, charging: Bool?) {
        guard let output = commandOutput("/usr/bin/pmset", ["-g", "batt"]) else { return (nil, nil) }
        var digits = ""
        for character in output {
            if character.isNumber { digits.append(character) }
            else if character == "%" {
                if let value = Int(digits) {
                    let safe = max(0, min(100, value))
                    let lower = output.lowercased()
                    if lower.contains("discharging") { return (safe, false) }
                    if lower.contains("charging") || lower.contains("charged") { return (safe, true) }
                    return (safe, nil)
                }
                digits.removeAll(keepingCapacity: true)
            } else { digits.removeAll(keepingCapacity: true) }
        }
        return (nil, nil)
    }

    private static func audio() -> (volume: Int, muted: Bool) {
        let volume = Int(commandOutput("/usr/bin/osascript", ["-e", "output volume of (get volume settings)"])?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "") ?? 0
        let muted = commandOutput("/usr/bin/osascript", ["-e", "output muted of (get volume settings)"])?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == "true"
        return (max(0, min(100, volume)), muted)
    }

    private static func macOSVersion() -> String {
        let version = ProcessInfo.processInfo.operatingSystemVersion
        return "\(version.majorVersion).\(version.minorVersion).\(version.patchVersion)"
    }

    private static func appleScript(_ script: String) throws { try run("/usr/bin/osascript", ["-e", script]) }

    private static func commandOutput(_ executable: String, _ arguments: [String]) -> String? {
        let process = Process()
        let output = Pipe()
        process.executableURL = URL(fileURLWithPath: executable)
        process.arguments = arguments
        process.standardOutput = output
        process.standardError = Pipe()
        do { try process.run() } catch { return nil }
        process.waitUntilExit()
        guard process.terminationStatus == 0 else { return nil }
        return String(data: output.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8)
    }

    private static func run(_ executable: String, _ arguments: [String]) throws {
        let process = Process()
        let pipe = Pipe()
        process.executableURL = URL(fileURLWithPath: executable)
        process.arguments = arguments
        process.standardOutput = pipe
        process.standardError = pipe
        do { try process.run(); process.waitUntilExit() }
        catch { throw NSError(domain: "PauzeControl.System", code: 500, userInfo: [NSLocalizedDescriptionKey: "The requested Mac system action could not start."]) }
        guard process.terminationStatus == 0 else { throw NSError(domain: "PauzeControl.System", code: 500, userInfo: [NSLocalizedDescriptionKey: "macOS rejected the requested system action."]) }
    }
}
