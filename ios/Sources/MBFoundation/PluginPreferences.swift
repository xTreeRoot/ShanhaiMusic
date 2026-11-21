import Foundation
import Security

/// 三端共享的插件偏好寄存器；逻辑时钟不依赖设备时间。
public struct PluginPreference<Value: Codable & Equatable & Sendable>: Codable, Equatable, Sendable {
    public var value: Value
    public var clock: Int64
    public var writer: String
    public init(value: Value, clock: Int64, writer: String) { self.value = value; self.clock = clock; self.writer = writer }
    fileprivate var valid: Bool { (1...PluginPreferences.maxClock).contains(clock) && PlatformDataCodec.decode(writer, size: 16) != nil }
    fileprivate func merged(_ other: Self) throws -> Self {
        guard clock != other.clock || writer != other.writer || value == other.value else { throw PlatformDataFailure.invalid }
        return other.clock > clock || other.clock == clock && other.writer > writer ? other : self
    }
}

/// 名称空串表示恢复默认，空排序表示显式清空；未安装插件的偏好继续保留。
public struct PluginPreferences: Codable, Equatable, Sendable {
    public static let maxClock: Int64 = 9_007_199_254_740_991
    public var version = 1
    public var names: [String: PluginPreference<String>] = [:]
    public var enabled: [String: PluginPreference<Bool>] = [:]
    public var order: PluginPreference<[String]>?
    public init() {}
    public var isEmpty: Bool { names.isEmpty && enabled.isEmpty && order == nil }
    public var maxObservedClock: Int64 { (names.values.map(\.clock) + enabled.values.map(\.clock) + [order?.clock ?? 0]).max() ?? 0 }

    public static func newWriter() -> String {
        var bytes = [UInt8](repeating: 0, count: 16)
        precondition(SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess)
        return PlatformDataCodec.encode(Data(bytes))
    }
    private static func validID(_ id: String) -> Bool {
        !id.contains("..") && id.range(of: #"\A[A-Za-z0-9][A-Za-z0-9_.-]{0,127}\z"#, options: .regularExpression) != nil
    }
    public func validated() throws -> Self {
        guard version == 1, names.count <= 512, enabled.count <= 512 else { throw PlatformDataFailure.invalid }
        var ids = Set<String>()
        for (id, entry) in names {
            guard Self.validID(id), entry.valid, entry.value.unicodeScalars.count <= 60, entry.value.utf8.count <= 240,
                  !entry.value.unicodeScalars.contains(where: { $0.properties.generalCategory == .control }) else { throw PlatformDataFailure.invalid }
            ids.insert(id)
        }
        for (id, entry) in enabled {
            guard Self.validID(id), entry.valid else { throw PlatformDataFailure.invalid }; ids.insert(id)
        }
        if let order {
            guard order.valid, order.value.count <= 512, Set(order.value).count == order.value.count,
                  order.value.allSatisfy(Self.validID) else { throw PlatformDataFailure.invalid }
            ids.formUnion(order.value)
        }
        guard ids.count <= 512 else { throw PlatformDataFailure.invalid }; return self
    }
    public func merged(with other: Self) throws -> Self {
        _ = try validated(); _ = try other.validated()
        var result = self
        for (id, right) in other.names { result.names[id] = try result.names[id]?.merged(right) ?? right }
        for (id, right) in other.enabled { result.enabled[id] = try result.enabled[id]?.merged(right) ?? right }
        if let right = other.order { result.order = try result.order?.merged(right) ?? right }
        return try result.validated()
    }
    public func withName(id: String, name: String, writer: String) throws -> Self {
        var result = self; result.names[id] = PluginPreference(value: name, clock: maxObservedClock + 1, writer: writer); return try result.validated()
    }
    public func withEnabled(id: String, enabled: Bool, writer: String) throws -> Self {
        var result = self; result.enabled[id] = PluginPreference(value: enabled, clock: maxObservedClock + 1, writer: writer); return try result.validated()
    }
    public func withOrder(_ ids: [String], writer: String) throws -> Self {
        var result = self; result.order = PluginPreference(value: ids, clock: maxObservedClock + 1, writer: writer); return try result.validated()
    }
}
