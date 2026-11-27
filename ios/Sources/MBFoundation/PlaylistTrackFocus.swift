import Foundation

/// 每次进入列表只定位一次；目标来自播放器，播放和暂停使用相同的来源身份。
public struct PlaylistTrackFocus: Sendable {
    public let target: Track?
    public private(set) var finished: Bool

    public init(current: Track? = nil) {
        target = current
        finished = current == nil
    }

    public mutating func locate(in tracks: [Track], hasMore: Bool) -> Int? {
        guard !finished, let target else { return nil }
        if let index = tracks.firstIndex(where: { $0.sourceID == target.sourceID && $0.sourceTrackID == target.sourceTrackID }) {
            finished = true
            return index
        }
        if !hasMore { finished = true }
        return nil
    }

    public mutating func stop() { finished = true }
}
