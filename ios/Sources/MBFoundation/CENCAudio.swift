import CommonCrypto
import Foundation

/// ISO BMFF 非分片音频的 CENC AES-CTR 解码。密钥仅由信息源本次授权提供。
/// 保留原有时间表、文本轨道和偏移，不推导密钥，不扩展授权音频的长度。
enum CENCAudio {
    private struct Box {
        let type: String
        let start: Int
        let body: Int
        let end: Int
    }
    private struct ChunkRun { let first: Int; let count: Int }
    private struct Sample { let offset: Int; let size: Int }
    private static func invalid() -> PluginFailure { .execution("Unsupported or invalid CENC audio") }

    static func key(_ hex: String) throws -> Data {
        guard hex.utf8.count == 32, hex.utf8.allSatisfy({ (48...57).contains($0) || (65...70).contains($0) || (97...102).contains($0) }) else { throw invalid() }
        var result = Data()
        var at = hex.startIndex
        while at < hex.endIndex {
            let end = hex.index(at, offsetBy: 2)
            guard let value = UInt8(hex[at..<end], radix: 16) else { throw invalid() }
            result.append(value); at = end
        }
        return result
    }
    private static func integer(_ data: Data, _ at: Int, _ width: Int, end: Int) throws -> Int {
        guard width > 0, at >= 0, at <= end, width <= end - at, end <= data.count else { throw invalid() }
        var value = 0
        for byte in data[at..<at + width] {
            guard value <= (Int.max - Int(byte)) / 256 else { throw invalid() }
            value = value * 256 + Int(byte)
        }
        return value
    }
    private static func boxes(_ data: Data, from: Int, to: Int) throws -> [Box] {
        guard from >= 0, from <= to, to <= data.count else { throw invalid() }
        var result: [Box] = [], at = from
        while at < to {
            var size = try integer(data, at, 4, end: to), header = 8
            guard to - at >= 8 else { throw invalid() }
            if size == 1 { size = try integer(data, at + 8, 8, end: to); header = 16 }
            if size == 0 { size = to - at }
            guard size >= header, size <= to - at else { throw invalid() }
            result.append(Box(type: String(decoding: data[at + 4..<at + 8], as: UTF8.self), start: at, body: at + header, end: at + size))
            at += size
        }
        return result
    }
    private static func one(_ rows: [Box], _ type: String) throws -> Box {
        let found = rows.filter { $0.type == type }
        guard found.count == 1, let result = found.first else { throw invalid() }
        return result
    }
    private static func children(_ data: Data, _ box: Box) throws -> [Box] { try boxes(data, from: box.body, to: box.end) }
    private static func word(_ data: Data, _ box: Box, _ offset: Int, width: Int = 4) throws -> Int {
        try integer(data, box.body + offset, width, end: box.end)
    }

    /// 根据 stsc/stco/co64/stsz 定位样本，允许清晰文本轨与音频交错排列。
    private static func samples(_ data: Data, tables: [Box], media: [Box]) throws -> [Sample] {
        let sizes = try one(tables, "stsz"), fixed = try word(data, sizes, 4), count = try word(data, sizes, 8)
        guard try word(data, sizes, 0) == 0, count > 0, count <= 1_000_000 else { throw invalid() }
        let lengths = try (0..<count).map { try fixed > 0 ? fixed : word(data, sizes, 12 + $0 * 4) }
        guard lengths.allSatisfy({ $0 > 0 }) else { throw invalid() }
        let offsets = tables.filter { ["stco", "co64"].contains($0.type) }
        guard offsets.count == 1, let offsets = offsets.first else { throw invalid() }
        let chunks = try word(data, offsets, 4), stride = offsets.type == "co64" ? 8 : 4
        guard try word(data, offsets, 0) == 0, chunks > 0, chunks <= count else { throw invalid() }
        let mapping = try one(tables, "stsc"), runCount = try word(data, mapping, 4)
        guard try word(data, mapping, 0) == 0, runCount > 0, runCount <= chunks else { throw invalid() }
        var runs: [ChunkRun] = []
        for i in 0..<runCount {
            let first = try word(data, mapping, 8 + i * 12), amount = try word(data, mapping, 12 + i * 12)
            guard first >= 1, first <= chunks, first > (runs.last?.first ?? 0), amount > 0, amount <= count,
                  try word(data, mapping, 16 + i * 12) == 1 else { throw invalid() }
            runs.append(ChunkRun(first: first, count: amount))
        }
        guard runs.first?.first == 1 else { throw invalid() }
        var result: [Sample] = [], sample = 0, run = 0, priorEnd = 0
        result.reserveCapacity(count)
        for chunk in 1...chunks {
            if run + 1 < runs.count, runs[run + 1].first == chunk { run += 1 }
            var offset = try word(data, offsets, 8 + (chunk - 1) * stride, width: stride)
            guard offset >= priorEnd, runs[run].count <= count - sample else { throw invalid() }
            for _ in 0..<runs[run].count {
                let size = lengths[sample]
                guard media.contains(where: { offset >= $0.body && offset <= $0.end && size <= $0.end - offset }) else { throw invalid() }
                result.append(Sample(offset: offset, size: size)); offset += size; sample += 1
            }
            priorEnd = offset
        }
        guard sample == count else { throw invalid() }
        return result
    }
    private static func transform(_ data: inout Data, sample: Sample, key: Data, iv: Data, spans: [Range<Int>]) throws {
        var cryptor: CCCryptorRef?
        let status = key.withUnsafeBytes { keyBytes in iv.withUnsafeBytes { ivBytes in
            CCCryptorCreateWithMode(CCOperation(kCCDecrypt), CCMode(kCCModeCTR), CCAlgorithm(kCCAlgorithmAES), CCPadding(ccNoPadding), ivBytes.baseAddress, keyBytes.baseAddress, key.count, nil, 0, 0, CCModeOptions(kCCModeOptionCTR_BE), &cryptor)
        } }
        guard status == kCCSuccess, let cryptor else { throw invalid() }
        defer { CCCryptorRelease(cryptor) }
        for span in spans where !span.isEmpty {
            guard span.lowerBound >= sample.offset, span.upperBound <= sample.offset + sample.size else { throw invalid() }
            var count = 0
            let status = data.withUnsafeMutableBytes { (bytes: UnsafeMutableRawBufferPointer) in
                let pointer = bytes.baseAddress!.advanced(by: span.lowerBound)
                return CCCryptorUpdate(cryptor, pointer, span.count, pointer, span.count, &count)
            }
            guard status == kCCSuccess, count == span.count else { throw invalid() }
        }
    }
    static func decode(_ input: Data, key: Data) throws -> Data {
        guard key.count == 16, input.count <= 128 << 20 else { throw invalid() }
        var data = input
        let top = try boxes(data, from: 0, to: data.count), movie = try one(top, "moov")
        let movieBoxes = try children(data, movie), media = top.filter { $0.type == "mdat" }
        guard !media.isEmpty, !top.contains(where: { $0.type == "moof" }), !movieBoxes.contains(where: { $0.type == "mvex" }) else { throw invalid() }
        var decoded = 0
        for track in movieBoxes where track.type == "trak" {
            let mdia = try one(children(data, track), "mdia"), mdiaChildren = try children(data, mdia)
            let handler = try one(mdiaChildren, "hdlr")
            guard try word(data, handler, 8) == 0x736f756e else { continue } // soun
            let minf = try one(mdiaChildren, "minf"), stbl = try one(children(data, minf), "stbl"), tables = try children(data, stbl)
            let descriptions = try one(tables, "stsd")
            guard try word(data, descriptions, 0) == 0, try word(data, descriptions, 4) == 1 else { throw invalid() }
            let entry = try one(boxes(data, from: descriptions.body + 8, to: descriptions.end), "enca")
            guard try word(data, entry, 8, width: 2) == 0 else { throw invalid() }
            let protection = try one(boxes(data, from: entry.body + 28, to: entry.end), "sinf"), protections = try children(data, protection)
            let format = try one(protections, "frma"), scheme = try one(protections, "schm")
            // AAC-LC/HE-AAC in MP4 is natively playable after restoring mp4a.
            guard try word(data, format, 0) == 0x6d703461, try word(data, scheme, 4) == 0x63656e63 else { throw invalid() }
            let schi = try one(protections, "schi"), defaults = try one(children(data, schi), "tenc")
            let ivSize = try word(data, defaults, 7, width: 1)
            guard try word(data, defaults, 0, width: 1) == 0, try word(data, defaults, 6, width: 1) == 1,
                  [8, 16].contains(ivSize), defaults.end - defaults.body >= 24 else { throw invalid() }
            let encryption = try one(tables, "senc"), flags = try word(data, encryption, 0)
            let samples = try samples(data, tables: tables, media: media)
            guard [0, 2].contains(flags), try word(data, encryption, 4) == samples.count else { throw invalid() }
            var cursor = encryption.body + 8
            for (index, sample) in samples.enumerated() {
                if index % 128 == 0 { try Task.checkCancellation() }
                guard cursor <= encryption.end, ivSize <= encryption.end - cursor else { throw invalid() }
                var iv = Data(data[cursor..<cursor + ivSize]); iv.append(Data(repeating: 0, count: 16 - ivSize)); cursor += ivSize
                var spans: [Range<Int>] = []
                if flags == 2 {
                    let count = try integer(data, cursor, 2, end: encryption.end); cursor += 2
                    var offset = sample.offset
                    if count == 0 { spans = [offset..<offset + sample.size] }
                    for _ in 0..<count {
                        let clear = try integer(data, cursor, 2, end: encryption.end), encrypted = try integer(data, cursor + 2, 4, end: encryption.end); cursor += 6
                        guard clear <= sample.offset + sample.size - offset else { throw invalid() }; offset += clear
                        guard encrypted <= sample.offset + sample.size - offset else { throw invalid() }
                        spans.append(offset..<offset + encrypted); offset += encrypted
                    }
                    guard count == 0 || offset == sample.offset + sample.size else { throw invalid() }
                } else { spans = [sample.offset..<sample.offset + sample.size] }
                try transform(&data, sample: sample, key: key, iv: iv, spans: spans)
            }
            guard cursor == encryption.end else { throw invalid() }
            data.replaceSubrange(entry.start + 4..<entry.start + 8, with: Data("mp4a".utf8))
            for box in [protection] + tables.filter({ ["senc", "saiz", "saio"].contains($0.type) }) {
                data.replaceSubrange(box.start + 4..<box.start + 8, with: Data("free".utf8))
            }
            decoded += 1
        }
        guard decoded == 1 else { throw invalid() }
        return data
    }
}
