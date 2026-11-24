import CryptoKit
import Foundation

actor PluginSignInDiagnostics {
    enum Operation: String, Codable, Sendable {
        case signIn, account, signOut, clearLoginCache, signOutAndClearCache
    }
    enum Outcome: String, Codable, Sendable {
        case completed, accountReturned, noAccount, invalidAccount, failed, cancelled

        init(operation: Operation, result: PluginJSON) {
            guard operation == .account else { self = .completed; return }
            if result == .null { self = .noAccount }
            else if result["id"].string != nil && result["name"].string != nil { self = .accountReturned }
            else { self = .invalidAccount }
        }
    }
    struct StorageActivity: Codable, Sendable {
        var hits = 0
        var misses = 0
        var writes = 0
        var removals = 0
        var failures = 0
    }
    private struct Failure: Codable, Sendable {
        let type: String
        let code: Int

        init(_ error: any Error) {
            type = String(reflecting: Swift.type(of: error))
            code = (error as NSError).code
        }
    }
    private struct Entry: Codable, Sendable {
        let kind: String
        let host: String?
        let pathHash: String?
        let started: Date
        let durationMilliseconds: Int
        let status: Int?
        let failureType: String?
        let errorCode: Int?
        let serviceCodes: [Int]?
        let cookieHeaderPresent: Bool?
        let authorizationHeaderPresent: Bool?
        let setCookieCount: Int?
    }
    private struct Report: Codable, Sendable {
        let operation: Operation
        let pluginVersion: String?
        let started: Date
        let finished: Date
        let completed: Bool
        let outcome: Outcome
        let failure: Failure?
        let storage: StorageActivity?
        let entries: [Entry]
    }
    private actor History {
        static let shared = History()
        private let limit = 24
        private let byteLimit = 512 * 1024

        func append(_ report: Report, directory: URL) {
            guard (try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)) != nil else { return }
            if report.operation == .signIn {
                try? write(report, to: directory.appendingPathComponent("sign-in-diagnostics.json"))
            }
            let file = directory.appendingPathComponent("account-diagnostics.json")
            var reports = (try? read(file)) ?? []
            reports.append(report)
            try? write(Array(reports.sorted { $0.finished < $1.finished }.suffix(limit)), to: file)
        }
        private func read(_ file: URL) throws -> [Report] {
            let handle = try FileHandle(forReadingFrom: file)
            defer { try? handle.close() }
            let data = try handle.read(upToCount: byteLimit + 1) ?? Data()
            guard data.count <= byteLimit else { return [] }
            return try JSONDecoder().decode([Report].self, from: data)
        }
        private func write(_ value: some Encodable, to file: URL) throws {
            let data = try JSONEncoder().encode(value)
            #if os(iOS)
            try data.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            #else
            try data.write(to: file, options: .atomic)
            #endif
        }
    }
    private let started = Date()
    private let directory: URL
    private let operation: Operation
    private let pluginVersion: String?
    private var entries: [Entry] = []

    init(directory: URL, operation: Operation, pluginVersion: String?) {
        self.directory = directory; self.operation = operation; self.pluginVersion = pluginVersion
    }
    func record(_ event: PluginJSON, started: Date, result: PluginJSON?, error: (any Error)?) {
        guard let kind = event["kind"].string, ["fetch", "webSignIn", "oauthSignIn"].contains(kind) else { return }
        let fetch = kind == "fetch"
        let address = fetch ? event["url"].string : event["options"]["url"].string
        let url = address.flatMap(URL.init(string:))
        let failure = error.map(Failure.init)
        let codes = fetch ? Self.serviceCodes(result?["body"].string) : []
        let entry = Entry(kind: kind, host: url?.host.map { String($0.prefix(253)) }, pathHash: url.map { Self.pathHash($0.path) },
                          started: started, durationMilliseconds: Int(max(0, Date().timeIntervalSince(started)) * 1_000),
                          status: fetch ? result?["status"].integer : nil, failureType: failure?.type, errorCode: failure?.code,
                          serviceCodes: codes.isEmpty ? nil : codes,
                          cookieHeaderPresent: fetch ? Self.hasHeader("cookie", in: event) : nil,
                          authorizationHeaderPresent: fetch ? Self.hasHeader("authorization", in: event) : nil,
                          setCookieCount: fetch ? result?["setCookies"].array.count : nil)
        entries.append(entry)
        if entries.count > 32 { entries.removeFirst(entries.count - 32) }
    }
    func finish(_ outcome: Outcome, storage: StorageActivity? = nil, error: (any Error)? = nil) async {
        let report = Report(operation: operation, pluginVersion: pluginVersion, started: started, finished: Date(),
                            completed: outcome != .failed && outcome != .cancelled, outcome: outcome,
                            failure: error.map(Failure.init), storage: storage, entries: entries)
        await History.shared.append(report, directory: directory)
    }
    private nonisolated static func hasHeader(_ name: String, in event: PluginJSON) -> Bool {
        guard case .object(let headers) = event["headers"] else { return false }
        return headers.contains { $0.key.lowercased() == name && $0.value.string?.isEmpty == false }
    }
    nonisolated static func pathHash(_ path: String) -> String {
        SHA256.hash(data: Data(path.utf8)).map { String(format: "%02x", $0) }.joined()
    }
    /// 只提取约定字段里的有界整数错误码，其他响应字段永不写入诊断。
    private nonisolated static func serviceCodes(_ body: String?) -> [Int] {
        guard let body, body.utf8.count <= 2 << 20,
              let value = try? JSONDecoder().decode(PluginJSON.self, from: Data(body.utf8)) else { return [] }
        let fields = [value["code"], value["status_code"], value["error_code"],
                      value["status_info"]["status_code"], value["status_info"]["code"], value["data"]["error_code"]]
        return Array(Set(fields.compactMap { field -> Int? in
            guard let code = field.integer ?? field.string.flatMap(Int.init), (-2_147_483_648...2_147_483_647).contains(code) else { return nil }
            return code
        })).sorted()
    }
}
