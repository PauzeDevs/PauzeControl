// PauzeControl — Copyright (c) 2026 PauzeDevs. All rights reserved.
import CoreAudio
import Foundation

struct AudioDeviceInfo: Codable {
    let id: UInt32
    let name: String
    let supportsInput: Bool
    let supportsOutput: Bool
    let isDefaultInput: Bool
    let isDefaultOutput: Bool
}
struct AudioDevicesResponse: Codable { let ok: Bool; let devices: [AudioDeviceInfo] }
struct AudioDeviceChangeResponse: Codable {
    let ok: Bool
    let direction: String
    let deviceID: UInt32
}

enum AudioDeviceService {
    static func list() throws -> [AudioDeviceInfo] {
        let ids = try deviceIDs()
        let defaultInput = try defaultDevice(selector: kAudioHardwarePropertyDefaultInputDevice)
        let defaultOutput = try defaultDevice(selector: kAudioHardwarePropertyDefaultOutputDevice)
        var devices: [AudioDeviceInfo] = []
        for id in ids {
            let input = channelCount(id, scope: kAudioDevicePropertyScopeInput) > 0
            let output = channelCount(id, scope: kAudioDevicePropertyScopeOutput) > 0
            guard input || output else { continue }
            devices.append(AudioDeviceInfo(id: id, name: deviceName(id) ?? "Audio device \(id)",
                supportsInput: input, supportsOutput: output,
                isDefaultInput: id == defaultInput, isDefaultOutput: id == defaultOutput))
        }
        return devices.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    static func setDefaultDevice(id: UInt32, direction: String) throws {
        guard direction == "input" || direction == "output" else {
            throw NSError(domain: "PauzeControl.Audio", code: 400,
                          userInfo: [NSLocalizedDescriptionKey: "Direction must be input or output."])
        }
        let devices = try list()
        guard let device = devices.first(where: { $0.id == id }),
              (direction == "input" ? device.supportsInput : device.supportsOutput) else {
            throw NSError(domain: "PauzeControl.Audio", code: 400,
                          userInfo: [NSLocalizedDescriptionKey: "The selected device does not support that audio direction."])
        }
        let selector = direction == "input" ? kAudioHardwarePropertyDefaultInputDevice : kAudioHardwarePropertyDefaultOutputDevice
        var address = AudioObjectPropertyAddress(mSelector: selector,
            mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        var selected = AudioDeviceID(id)
        let status = withUnsafeMutablePointer(to: &selected) { pointer in
            AudioObjectSetPropertyData(AudioObjectID(kAudioObjectSystemObject), &address, 0, nil,
                UInt32(MemoryLayout<AudioDeviceID>.size), UnsafeRawPointer(pointer))
        }
        guard status == noErr else {
            throw NSError(domain: "PauzeControl.Audio", code: Int(status),
                          userInfo: [NSLocalizedDescriptionKey: "macOS could not change the default audio device (Core Audio error \(status))."])
        }
    }

    private static func deviceIDs() throws -> [AudioDeviceID] {
        var address = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyDevices,
            mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        var size: UInt32 = 0
        let sizeStatus = AudioObjectGetPropertyDataSize(AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, &size)
        guard sizeStatus == noErr else {
            throw NSError(domain: "PauzeControl.Audio", code: Int(sizeStatus),
                          userInfo: [NSLocalizedDescriptionKey: "Core Audio could not enumerate devices."])
        }
        let count = Int(size) / MemoryLayout<AudioDeviceID>.size
        guard count > 0 else { return [] }
        var values = [AudioDeviceID](repeating: 0, count: count)
        let status = values.withUnsafeMutableBufferPointer { buffer in
            AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, &size,
                UnsafeMutableRawPointer(buffer.baseAddress!))
        }
        guard status == noErr else {
            throw NSError(domain: "PauzeControl.Audio", code: Int(status),
                          userInfo: [NSLocalizedDescriptionKey: "Core Audio could not read the device list."])
        }
        return values
    }

    private static func defaultDevice(selector: AudioObjectPropertySelector) throws -> AudioDeviceID {
        var address = AudioObjectPropertyAddress(mSelector: selector,
            mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        var device: AudioDeviceID = 0
        var size = UInt32(MemoryLayout<AudioDeviceID>.size)
        let status = withUnsafeMutablePointer(to: &device) { pointer in
            AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, &size,
                UnsafeMutableRawPointer(pointer))
        }
        guard status == noErr else {
            throw NSError(domain: "PauzeControl.Audio", code: Int(status),
                          userInfo: [NSLocalizedDescriptionKey: "Could not read the current default audio device."])
        }
        return device
    }

    private static func deviceName(_ id: AudioDeviceID) -> String? {
        var address = AudioObjectPropertyAddress(mSelector: kAudioObjectPropertyName,
            mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        var name: CFString = "" as CFString
        var size = UInt32(MemoryLayout<CFString>.size)
        let status = withUnsafeMutablePointer(to: &name) { pointer in
            AudioObjectGetPropertyData(id, &address, 0, nil, &size, UnsafeMutableRawPointer(pointer))
        }
        return status == noErr ? name as String : nil
    }

    private static func channelCount(_ id: AudioDeviceID, scope: AudioObjectPropertyScope) -> UInt32 {
        var address = AudioObjectPropertyAddress(mSelector: kAudioDevicePropertyStreamConfiguration,
            mScope: scope, mElement: kAudioObjectPropertyElementMain)
        var size: UInt32 = 0
        guard AudioObjectGetPropertyDataSize(id, &address, 0, nil, &size) == noErr,
              size >= UInt32(MemoryLayout<AudioBufferList>.size) else { return 0 }
        let memory = UnsafeMutableRawPointer.allocate(byteCount: Int(size),
            alignment: MemoryLayout<AudioBufferList>.alignment)
        defer { memory.deallocate() }
        guard AudioObjectGetPropertyData(id, &address, 0, nil, &size, memory) == noErr else { return 0 }
        let list = UnsafeMutableAudioBufferListPointer(memory.assumingMemoryBound(to: AudioBufferList.self))
        return list.reduce(UInt32(0)) { $0 + $1.mNumberChannels }
    }
}
