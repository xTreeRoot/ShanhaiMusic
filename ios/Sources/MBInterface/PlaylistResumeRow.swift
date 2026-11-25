import MBFoundation
import SwiftUI

/// 歌单顶部的整行续播入口，使用本机播放记录。
struct PlaylistResumeRow: View {
    let resume: PlaylistResume?
    let action: () async -> Bool
    @State private var working = false
    @State private var failed = false
    @State private var task: Task<Void, Never>?

    var body: some View {
        Button {
            failed = false
            working = true
            task = Task {
                let restored = await action()
                guard !Task.isCancelled else { return }
                failed = !restored
                working = false
            }
        } label: {
            HStack(spacing: 12) {
                Image(systemName: "play.circle.fill").font(.title2).foregroundStyle(Color.accentColor)
                VStack(alignment: .leading, spacing: 3) {
                    Text("上次播放").font(.subheadline.weight(.semibold))
                    if let resume, !failed {
                        let seconds = max(0, resume.position / 1000)
                        Text("\(resume.title) · \(seconds / 60):\(String(format: "%02lld", seconds % 60))")
                            .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    } else {
                        Text(LocalizedStringKey(failed ? "上次播放的歌曲暂不可用，点按重试" : "还没播放过"))
                            .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                }
                Spacer()
                if working { ProgressView().controlSize(.small) }
            }
            .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(resume == nil || working)
        .accessibilityIdentifier("playlist-resume")
        .onDisappear { task?.cancel(); task = nil; working = false }
    }
}
