import MBFoundation
import ImageIO
import Foundation
import SwiftUI
import UniformTypeIdentifiers

@MainActor @Observable final class BackgroundSkin {
    private(set) var image: CGImage?
    private(set) var preview: CGImage?
    var previewOpacity = 0.25

    func beginPreview(_ candidate: CGImage, opacity: Double) {
        guard !busy else { return }
        previewOpacity = min(1, max(0, opacity)); preview = candidate; failed = false
    }
    func cancelPreview() { guard !busy else { return }; preview = nil; failed = false }
    func confirmPreview() async -> Double? {
        guard let candidate = preview, !busy else { return nil }
        let opacity = previewOpacity
        await apply(candidate)
        guard !failed else { return nil }
        preview = nil
        return opacity
    }
    private(set) var busy = false
    private(set) var failed = false
    private let file: URL

    init(directory: URL? = nil) {
        file = (directory ?? URL.applicationSupportDirectory.appendingPathComponent("MBMobile/background-skin"))
            .appendingPathComponent("background.jpg")
    }

    func restore() async {
        let file = file
        await update {
            FileManager.default.fileExists(atPath: file.path) ? try Self.thumbnail(from: file) : nil
        }
    }

    enum Input: Sendable { case file(URL), photo(Data), remote(URL) }

    /// 选择阶段只解码候选图，不改当前背景和磁盘文件。
    func prepare(_ input: Input) async -> CGImage? {
        guard !busy else { return nil }
        busy = true; failed = false
        defer { busy = false }
        do {
            let image: CGImage
            switch input {
            case let .file(source):
                image = try await Task.detached(priority: .utility) {
                    let accessing = source.startAccessingSecurityScopedResource()
                    defer { if accessing { source.stopAccessingSecurityScopedResource() } }
                    return try Self.thumbnail(from: source)
                }.value
            case let .photo(data): image = try await Task.detached(priority: .utility) { try Self.thumbnail(data: data) }.value
            case let .remote(url):
                guard let scheme = url.scheme?.lowercased(), ["http", "https"].contains(scheme),
                      url.host?.isEmpty == false, url.user == nil, url.password == nil else { throw CocoaError(.fileReadUnsupportedScheme) }
                var request = URLRequest(url: url); request.timeoutInterval = 30
                let (data, response) = try await NetworkClient.shared.data(for: request, maximumBytes: 32 * 1024 * 1024)
                guard let response = response as? HTTPURLResponse, (200..<300).contains(response.statusCode) else { throw CocoaError(.fileReadUnknown) }
                image = try await Task.detached(priority: .utility) { try Self.thumbnail(data: data) }.value
            }
            try Task.checkCancellation()
            return image
        } catch is CancellationError { return nil }
        catch CocoaError.userCancelled { return nil }
        catch { failed = true; return nil }
    }

    func apply(_ candidate: CGImage) async {
        let file = file
        await update {
            let data = NSMutableData()
            guard let output = CGImageDestinationCreateWithData(data, UTType.jpeg.identifier as CFString, 1, nil) else { throw CocoaError(.fileWriteUnknown) }
            CGImageDestinationAddImage(output, candidate, [kCGImageDestinationLossyCompressionQuality: 0.88] as CFDictionary)
            guard CGImageDestinationFinalize(output) else { throw CocoaError(.fileWriteUnknown) }
            var directory = file.deletingLastPathComponent()
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            var values = URLResourceValues(); values.isExcludedFromBackup = true
            try directory.setResourceValues(values)
            try (data as Data).write(to: file, options: .atomic)
            return candidate
        }
    }

    func clear() async {
        let file = file
        await update {
            do { try FileManager.default.removeItem(at: file) } catch CocoaError.fileNoSuchFile {}
            return nil
        }
    }

    private func update(_ operation: @escaping @Sendable () throws -> CGImage?) async {
        guard !busy else { return }
        busy = true; failed = false
        defer { busy = false }
        do { image = try await Task.detached(priority: .utility, operation: operation).value }
        catch CocoaError.userCancelled {} catch { failed = true }
    }

    private nonisolated static func thumbnail(data: Data) throws -> CGImage {
        guard !data.isEmpty, data.count <= 32 * 1024 * 1024,
              let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary) else { throw CocoaError(.fileReadCorruptFile) }
        return try thumbnail(source: source)
    }

    private nonisolated static func thumbnail(source: CGImageSource) throws -> CGImage {
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
            kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: 2560, kCGImageSourceShouldCacheImmediately: true,
        ] as CFDictionary) else { throw CocoaError(.fileReadCorruptFile) }
        return image
    }

    private nonisolated static func thumbnail(from file: URL) throws -> CGImage {
        let values = try file.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
        guard values.isRegularFile == true, let size = values.fileSize, size <= 32 * 1024 * 1024,
              let source = CGImageSourceCreateWithURL(file as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                  kCGImageSourceCreateThumbnailFromImageAlways: true,
                  kCGImageSourceCreateThumbnailWithTransform: true,
                  kCGImageSourceThumbnailMaxPixelSize: 2560,
                  kCGImageSourceShouldCacheImmediately: true,
              ] as CFDictionary) else { throw CocoaError(.fileReadCorruptFile) }
        return image
    }
}

extension EnvironmentValues {
    @Entry var backgroundSkinImage: CGImage? = nil
    @Entry var backgroundSkinOpacity: Double? = nil
}

struct SkinSurface: ViewModifier {
    var defaultColor: Color? = nil
    var list = false
    var base = MBTheme.background
    var enabled = true
    @Environment(\.backgroundSkinImage) private var image
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.backgroundSkinOpacity) private var opacity

    func body(content: Content) -> some View {
        content
            .scrollContentBackground(list && enabled && image != nil ? .hidden : .automatic)
            .background {
                if enabled, let image {
                    GeometryReader { geometry in
                        Image(decorative: image, scale: 1)
                            .resizable().scaledToFill()
                            .frame(width: geometry.size.width, height: geometry.size.height).clipped()
                            .opacity(opacity ?? (colorScheme == .dark ? 0.35 : 0.22))
                    }
                    .background(base)
                    .allowsHitTesting(false).accessibilityHidden(true)
                } else if let defaultColor { defaultColor }
            }
    }
}
