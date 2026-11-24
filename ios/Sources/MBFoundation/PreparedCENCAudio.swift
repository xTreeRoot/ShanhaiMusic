import CryptoKit
import Foundation

/// 可重新获取的播放缓存，与插件账号、资料库和下载目录分开。
actor PreparedCENCAudio {
    static let shared = PreparedCENCAudio()
    private let directory = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("MB-CENC-Audio", isDirectory: true)
    private var pending: [String: Task<URL, Error>] = [:]

    func prepare(url: URL, headers: [String: String], key: Data, fetch: @escaping @Sendable () async throws -> Data) async throws -> URL {
        try Task.checkCancellation()
        let identity = url.absoluteString + key.base64EncodedString() + headers.sorted(by: { $0.key < $1.key }).map { $0.key + $0.value }.joined()
        let name = SHA256.hash(data: Data(identity.utf8)).map { String(format: "%02x", $0) }.joined()
        let target = directory.appendingPathComponent(name + ".m4a")
        if FileManager.default.fileExists(atPath: target.path) {
            try FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: target.path)
            return target
        }
        if let running = pending[name] { return try await running.value }
        let directory = directory
        let job = Task.detached(priority: .userInitiated) {
            let bytes = try await fetch()
            try Task.checkCancellation()
            let decoded = try CENCAudio.decode(bytes, key: key)
            try Task.checkCancellation()
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            #if os(iOS)
            try decoded.write(to: target, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            #else
            try decoded.write(to: target, options: .atomic)
            #endif
            return target
        }
        pending[name] = job
        defer { pending[name] = nil }
        let file = try await withTaskCancellationHandler { try await job.value } onCancel: { job.cancel() }
        try Task.checkCancellation()
        prune(except: file)
        return file
    }
    private func prune(except current: URL) {
        guard let files = try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.contentModificationDateKey, .fileSizeKey]) else { return }
        let rows = files.compactMap { file -> (URL, Date, Int)? in
            guard file.pathExtension == "m4a", let value = try? file.resourceValues(forKeys: [.contentModificationDateKey, .fileSizeKey]) else { return nil }
            return (file, value.contentModificationDate ?? .distantPast, value.fileSize ?? 0)
        }.sorted { $0.1 < $1.1 }
        var bytes = rows.reduce(0) { $0 + $1.2 }
        for row in rows where row.0 != current && bytes > 256 << 20 {
            // 刚生成或正在预载的文件保留；缓存清理从不访问应用的数据目录。
            guard Date().timeIntervalSince(row.1) > 3600 else { continue }
            if (try? FileManager.default.removeItem(at: row.0)) != nil { bytes -= row.2 }
        }
    }
}
