import Foundation

public enum PluginFailure: LocalizedError, Sendable {
    case invalid(String), execution(String), unavailable
    case playback(TrackProblem, String)
    public var errorDescription: String? {
        switch self {
        case .invalid(let reason): AppLocalization.text("插件无效：%@", arguments: [reason])
        case .execution(let reason), .playback(_, let reason): AppLocalization.text("插件执行失败：%@", arguments: [reason])
        case .unavailable: AppLocalization.text("此插件需要 iOS 不提供的桌面宿主能力")
        }
    }
}

public struct PluginManifest: Codable, Sendable {
    public struct Permissions: Codable, Sendable {
        public var network: [String]?
        public var process: [String]?
        public func reaches(_ host: String) -> Bool {
            let host = host.lowercased()
            return (network ?? []).contains { pattern in
                let pattern = pattern.lowercased()
                return pattern == "*" || host == pattern || (pattern.hasPrefix("*.") && host.hasSuffix(String(pattern.dropFirst())))
            }
        }
    }
    public struct Setting: Codable, Identifiable, Sendable {
        public struct Option: Codable, Sendable { public let value: String; public let label: String }
        public let key: String
        public let label: String
        public let help: String?
        public let link: String?
        public let type: String?
        public let options: [Option]?
        public var id: String { key }
    }
    public struct Navigation: Codable, Sendable { public let title: String?; public let page: String?; public let builtin: String?; public let icon: String? }
    public struct HeaderRule: Codable, Sendable { public let hosts: [String]; public let kinds: [String]?; public let headers: [String: String] }
    public let id: String
    public let name: String?
    public let version: String?
    public let main: String?
    public let minAppVersion: String?
    public let playback: String?
    public let permissions: Permissions?
    public let settings: [Setting]?
    public let navigation: [Navigation]?
    public let requestHeaders: [HeaderRule]?
    public let signInMethods: [SignInMethod]?
    public var entry: String { main ?? "index.js" }
    public var displayName: String { name ?? id }
    public func validate(hostVersion: String = "0.1.28") throws {
        guard id.range(of: "^[A-Za-z0-9][A-Za-z0-9_.-]*$", options: .regularExpression) != nil, !id.contains("..") else { throw PluginFailure.invalid("Invalid plugin ID") }
        guard !entry.hasPrefix("/"), !entry.split(separator: "/").contains(".."), entry.hasSuffix(".js") else { throw PluginFailure.invalid("Invalid entry path") }
        if let minAppVersion {
            guard let required = Self.version(minAppVersion), let host = Self.version(hostVersion) else { throw PluginFailure.invalid("Invalid compatibility version") }
            if host.lexicographicallyPrecedes(required) { throw PluginFailure.invalid("Requires host \(minAppVersion)") }
        }
        let methods = signInMethods ?? []
        guard methods.count <= 16, Set(methods.map(\.id)).count == methods.count,
              methods.allSatisfy({ !$0.label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && $0.id.range(of: "^[A-Za-z0-9][A-Za-z0-9_.-]*$", options: .regularExpression) != nil }) else { throw PluginFailure.invalid("Invalid sign-in methods") }
        guard playback == nil || playback == "stream" else { throw PluginFailure.unavailable }
        if !(permissions?.process ?? []).isEmpty { throw PluginFailure.unavailable }
        for rule in requestHeaders ?? [] {
            guard !rule.hosts.isEmpty, !rule.headers.isEmpty, (rule.kinds ?? []).allSatisfy({ ["image", "audio"].contains($0) }) else { throw PluginFailure.invalid("Invalid requestHeaders") }
            for host in rule.hosts {
                if host.hasPrefix("{setting:"), host.hasSuffix("}"), host.count > 10 { continue }
                guard host != "*", !host.contains(where: { "{}/:".contains($0) }), permissions?.reaches(host) == true else { throw PluginFailure.invalid("Header host outside network permissions") }
            }
            guard rule.headers.keys.allSatisfy({ !["host", "content-length"].contains($0.lowercased()) }) else { throw PluginFailure.invalid("Invalid header name") }
        }
    }
    private static func version(_ value: String) -> [Int]? {
        let parts = value.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 3 else { return nil }
        let numbers = parts.compactMap { part -> Int? in
            guard !part.isEmpty, part.allSatisfy(\.isNumber), let number = Int(part), number >= 0 else { return nil }; return number
        }
        return numbers.count == 3 ? numbers : nil
    }
}

public enum PluginJSON: Codable, Equatable, Sendable {
    case null, bool(Bool), number(Double), string(String), array([PluginJSON]), object([String: PluginJSON])
    public init(from decoder: any Decoder) throws {
        let value = try decoder.singleValueContainer()
        if value.decodeNil() { self = .null }
        else if let bool = try? value.decode(Bool.self) { self = .bool(bool) }
        else if let number = try? value.decode(Double.self) { self = .number(number) }
        else if let string = try? value.decode(String.self) { self = .string(string) }
        else if let array = try? value.decode([PluginJSON].self) { self = .array(array) }
        else { self = .object(try value.decode([String: PluginJSON].self)) }
    }
    public func encode(to encoder: any Encoder) throws {
        var value = encoder.singleValueContainer()
        switch self {
        case .null: try value.encodeNil()
        case .bool(let bool): try value.encode(bool)
        case .number(let number): try value.encode(number)
        case .string(let string): try value.encode(string)
        case .array(let array): try value.encode(array)
        case .object(let object): try value.encode(object)
        }
    }
    public subscript(_ key: String) -> PluginJSON { if case .object(let values) = self { values[key] ?? .null } else { .null } }
    public var string: String? { if case .string(let value) = self { value } else { nil } }
    public var array: [PluginJSON] { if case .array(let value) = self { value } else { [] } }
    public var integer: Int? { if case .number(let value) = self, value.isFinite, value >= Double(Int.min), value < Double(Int.max) { Int(value) } else { nil } }
    public func encoded() throws -> String { String(decoding: try JSONEncoder().encode(self), as: UTF8.self) }
}
