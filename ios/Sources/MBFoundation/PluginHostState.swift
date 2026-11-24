import CommonCrypto
import CryptoKit
import Foundation

/// 仅在插件 actor 上调用，同步存储符合桌面合同；文件路径始终限制在插件目录内。
final class PluginHostState {
    let root: URL
    let data: URL
    let manifest: PluginManifest
    var settings: [String: String]
    var storage: [String: PluginJSON]
    var prompt: (@Sendable (SignInPrompt) -> Void)?
    init(root: URL, data: URL, manifest: PluginManifest, settings: [String: String]) throws {
        self.root = root.resolvingSymlinksInPath(); self.data = data; self.manifest = manifest; self.settings = settings
        let file = data.appendingPathComponent("storage.json")
        storage = FileManager.default.fileExists(atPath: file.path) ? try JSONDecoder().decode([String: PluginJSON].self, from: Data(contentsOf: file)) : [:]
    }
    /// 仅清理本插件的宿主登录数据和缓存，不删除设置、资料库、下载或插件目录。
    func clearLoginStorage() throws {
        try FileManager.default.createDirectory(at: data, withIntermediateDirectories: true)
        #if os(iOS)
        try Data("{}".utf8).write(to: data.appendingPathComponent("storage.json"), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
        try Data("{}".utf8).write(to: data.appendingPathComponent("storage.json"), options: .atomic)
        #endif
        storage = [:]; prompt = nil
    }
    func call(_ operation: String, _ values: [PluginJSON]) throws -> PluginJSON {
        let first = values.first?.string ?? ""
        switch operation {
        case "module":
            let file = root.appendingPathComponent(first).resolvingSymlinksInPath()
            guard file.path.hasPrefix(root.path + "/"), !first.hasPrefix("/"), file.pathExtension == "js" else { throw PluginFailure.invalid("Module outside plugin directory") }
            let bytes = try Data(contentsOf: file)
            guard bytes.count <= 4 * 1_024 * 1_024, let text = String(data: bytes, encoding: .utf8) else { throw PluginFailure.invalid("Invalid module") }
            return .string(text)
        case "storage.get": return storage[first] ?? .null
        case "storage.set", "storage.remove":
            var updated = storage
            if operation == "storage.remove" { updated[first] = nil } else { updated[first] = values.count > 1 ? values[1] : .null }
            let bytes = try JSONEncoder().encode(updated)
            guard bytes.count <= 16 * 1_024 * 1_024 else { throw PluginFailure.invalid("Storage limit exceeded") }
            try FileManager.default.createDirectory(at: data, withIntermediateDirectories: true)
            #if os(iOS)
            try bytes.write(to: data.appendingPathComponent("storage.json"), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            #else
            try bytes.write(to: data.appendingPathComponent("storage.json"), options: .atomic)
            #endif
            storage = updated; return .null
        case "setting": return .string(settings[first] ?? "")
        case "language": return .string(AppLocalization.languageCode == "zh-Hans" ? "zh-CN" : "en")
        case "t":
            let language = AppLocalization.languageCode == "zh-Hans" ? "zh-CN" : "en"
            let file = root.appendingPathComponent("locales/\(language).json").resolvingSymlinksInPath()
            guard file.path.hasPrefix(root.path + "/"), FileManager.default.fileExists(atPath: file.path) else { return .string(first) }
            let translations = try JSONDecoder().decode([String: String].self, from: Data(contentsOf: file))
            return .string(translations[first] ?? first)
        case "md5": return .string(Insecure.MD5.hash(data: Data(first.utf8)).map { String(format: "%02x", $0) }.joined())
        case "aes":
            let key = Data(first.utf8), text = Data((values.count > 1 ? values[1].string ?? "" : "").utf8)
            guard [16, 24, 32].contains(key.count) else { throw PluginFailure.invalid("Invalid AES key length") }
            var output = Data(count: text.count + kCCBlockSizeAES128), count = 0
            let capacity = output.count
            let status = output.withUnsafeMutableBytes { out in key.withUnsafeBytes { k in text.withUnsafeBytes { input in
                CCCrypt(CCOperation(kCCEncrypt), CCAlgorithm(kCCAlgorithmAES), CCOptions(kCCOptionECBMode | kCCOptionPKCS7Padding), k.baseAddress, key.count, nil, input.baseAddress, text.count, out.baseAddress, capacity, &count)
            } } }
            guard status == kCCSuccess else { throw PluginFailure.invalid("AES operation failed") }
            return .string(output.prefix(count).map { String(format: "%02x", $0) }.joined())
        case "prompt":
            guard let value = values.first, case .object = value else { throw PluginFailure.invalid("Invalid sign-in prompt") }
            var url: URL?
            // 桌面合同允许生成二维码前和确认后只发送文字，不伪造二维码地址。
            if value["qr"] != .null, value["qr"] != .string("") {
                guard let text = value["qr"].string, let address = URL(string: text),
                      ["http", "https"].contains(address.scheme ?? ""),
                      let host = address.host, !host.isEmpty, address.user == nil else { throw PluginFailure.invalid("Invalid sign-in prompt") }
                url = address
            }
            prompt?(SignInPrompt(qrURL: url, message: value["text"].string ?? "")); return .null
        case "url":
            guard let url = URL(string: first, relativeTo: values.count > 1 ? values[1].string.flatMap(URL.init(string:)) : nil)?.absoluteURL, let parts = URLComponents(url: url, resolvingAgainstBaseURL: true), let scheme = parts.scheme else { throw PluginFailure.invalid("Invalid URL") }
            return .object(["href": .string(url.absoluteString), "protocol": .string(scheme + ":"), "host": .string((parts.host ?? "") + (parts.port.map { ":\($0)" } ?? "")), "hostname": .string(parts.host ?? ""), "pathname": .string(parts.percentEncodedPath), "search": .string(parts.percentEncodedQuery.map { "?" + $0 } ?? ""), "hash": .string(parts.percentEncodedFragment.map { "#" + $0 } ?? "")])
        case "base64.decode":
            guard let bytes = Data(base64Encoded: first) else { throw PluginFailure.invalid("Invalid base64") }
            return .string(String(bytes.map { Character(UnicodeScalar(Int($0))!) }))
        case "base64.encode":
            guard first.unicodeScalars.allSatisfy({ $0.value <= 255 }) else { throw PluginFailure.invalid("Invalid binary string") }
            return .string(Data(first.unicodeScalars.map { UInt8($0.value) }).base64EncodedString())
        case "console": return .null
        default: throw PluginFailure.unavailable
        }
    }
}

extension PluginHostState {
    func headers(for url: URL, kind: String) throws -> [String: String] {
        guard ["http", "https"].contains(url.scheme ?? ""), let host = url.host?.lowercased() else { return [:] }
        var result: [String: String] = [:]
        for rule in manifest.requestHeaders ?? [] {
            guard rule.kinds == nil || rule.kinds!.isEmpty || rule.kinds!.contains(kind) else { continue }
            let matches = rule.hosts.contains { pattern in
                if pattern.hasPrefix("{setting:"), pattern.hasSuffix("}") {
                    let key = String(pattern.dropFirst(9).dropLast())
                    let value = settings[key] ?? ""
                    let server = URL(string: value.contains("://") ? value : "http://" + value)?.host?.lowercased()
                    return server == host && manifest.permissions?.reaches(host) == true
                }
                let pattern = pattern.lowercased()
                return pattern == host || pattern.hasPrefix("*.") && host.hasSuffix(String(pattern.dropFirst()))
            }
            guard matches else { continue }
            for (name, template) in rule.headers where !result.keys.contains(where: { $0.lowercased() == name.lowercased() }) {
                let expression = try NSRegularExpression(pattern: #"\{(setting|storage):([^{}]+)\}"#)
                let matches = expression.matches(in: template, range: NSRange(template.startIndex..., in: template))
                var value = template, valid = true
                for match in matches.reversed() {
                    guard let typeRange = Range(match.range(at: 1), in: template), let keyRange = Range(match.range(at: 2), in: template), let range = Range(match.range, in: value) else { valid = false; break }
                    let key = String(template[keyRange]), type = String(template[typeRange])
                    let replacement: String
                    if type == "setting" { replacement = settings[key] ?? "" }
                    else if let stored = storage[key], stored != .null { replacement = try stored.string ?? stored.encoded() }
                    else { replacement = "" }
                    if replacement.isEmpty { valid = false; break }
                    value.replaceSubrange(range, with: replacement)
                }
                if valid && !value.contains(where: { "\r\n".contains($0) }) { result[name] = value }
            }
        }
        return result
    }
}
