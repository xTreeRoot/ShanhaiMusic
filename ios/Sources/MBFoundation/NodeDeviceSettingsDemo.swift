#if DEBUG
import Foundation

/// UI 回归专用内存节点；使用同一加密备份合同，不连接真实服务器。
actor NodeDeviceSettingsDemo {
    let node: PlatformNode
    private var name = "本机测试设备"
    private var objects: [String: Data] = [:]
    private var reference: NodeDeviceBackupReference?
    private var source: String { PlatformDataCodec.encode(Data(repeating: 3, count: 16)).utf8.map { String(format: "%02x", $0) }.joined() }
    init(node: PlatformNode) { self.node = node }

    func reply(_ request: URLRequest) async throws -> (Data, URLResponse) {
        guard let url = request.url, url.host == node.origin.host else { throw PlatformDataFailure.invalid }
        var status = 200, data = Data()
        if request.httpMethod == "PATCH", url.path == "/v1/sync/device" {
            name = try JSONDecoder().decode([String: String].self, from: request.httpBody ?? Data())["name"] ?? ""
            status = 204
        } else {
            guard request.httpMethod == "GET" else { throw PlatformDataFailure.invalid }
            switch url.path {
            case "/v1/sync/devices":
                let current = node.grant.device.utf8.map { String(format: "%02x", $0) }.joined()
                data = try JSONSerialization.data(withJSONObject: ["devices": [
                    ["key": current, "name": name, "platform": "ios", "current": true, "updated_at": 0],
                    ["key": source, "name": "书房 Mac", "platform": "desktop", "current": false, "updated_at": 1795518000]]])
            case "/v1/sync/overview": data = Data(#"{"records":[]}"#.utf8)
            case "/v1/data/device-settings/" + source:
                if reference == nil { try await prepare() }
                var doc = NodeDeviceSettings(); doc.backup = reference; doc.app = ["quality": "lossless"]
                struct Payload: Encodable { let value: NodeDeviceSettings }
                data = try JSONEncoder().encode(PlatformDataCodec.seal(JSONEncoder().encode(Payload(value: doc)), recovery: node.grant.recovery,
                    space: node.grant.space, platform: "device-settings", key: source))
            default:
                guard let body = objects[url.path] else { throw PlatformDataFailure.invalid }
                data = body
            }
        }
        return (data, HTTPURLResponse(url: url, statusCode: status, httpVersion: nil, headerFields: ["Content-Type": "application/json", "ETag": "\"" + node.grant.device + "\""])!)
    }

    private func prepare() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("mb-node-demo-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let installer = PluginInstaller(directory: root.appendingPathComponent("host"))
        for (id, name) in [("preview-a", "示例插件 A"), ("preview-b", "示例插件 B")] {
            let code = root.appendingPathComponent("code-" + id)
            try FileManager.default.createDirectory(at: code, withIntermediateDirectories: true)
            try Data("{\"id\":\"\(id)\",\"name\":\"\(name)\",\"version\":\"1.0.0\",\"settings\":[{\"key\":\"mode\",\"type\":\"string\",\"label\":\"Mode\"}]}".utf8).write(to: code.appendingPathComponent("plugin.json"))
            try Data("export function search(){return {tracks:[]};}".utf8).write(to: code.appendingPathComponent("index.js"))
            let plugin = try await installer.install(installer.prepare(code))
            try await installer.saveSettings(["mode": "from-B"], for: plugin)
            try Data(#"{"fixture":"from-B"}"#.utf8).write(to: plugin.dataDirectory.appendingPathComponent("storage.json"))
            try await installer.rename(id, to: name)
        }
        let (plugins, entries) = try await installer.deviceBackupPlugins(to: root)
        let file = root.appendingPathComponent("device.zip")
        try await DeviceArchiveFile().write(DeviceArchiveManifest(platform: "desktop", plugins: plugins), entries: entries, to: file, scratch: root)
        let bytes = try Data(contentsOf: file), hash = try NodeDeviceBackupCoordinator.digest(file)
        let part = String(repeating: "a", count: 32), index = String(repeating: "b", count: 32)
        let metadata = try JSONSerialization.data(withJSONObject: ["version": 1, "parts": [part], "bytes": bytes.count, "sha256": hash])
        for (key, value) in [(part, bytes), (index, metadata)] {
            let payload = try JSONSerialization.data(withJSONObject: ["value": ["data": PlatformDataCodec.encode(value)]])
            objects["/v1/backups/" + source + "/" + key] = try JSONEncoder().encode(PlatformDataCodec.seal(payload, recovery: node.grant.recovery,
                space: node.grant.space, platform: "backup-" + source, key: key))
        }
        reference = NodeDeviceBackupReference(index: index, platform: "desktop", created: 1795518000, bytes: Int64(bytes.count), sha256: hash, plugins: plugins.count)
    }
}
#endif
