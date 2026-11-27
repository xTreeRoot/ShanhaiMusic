import MBFoundation
import SwiftUI
#if os(iOS)
import UIKit
#endif

/// 封面与歌词共用同一舞台和控制区；紧凑设备及辅助字体可滚动查看全部操作。
struct NowPlayingView: View {
    let playback: PlaybackModel
    let demo: Bool
    var interactionSource: (any InteractionSource)? = nil
    var librarySource: (any LibrarySource)? = nil
    var pluginSource: PluginSource? = nil
    var accountRevision = 0
    var downloads: DownloadModel? = nil
    var preferences: PreferencesModel? = nil
    let watchVideo: (() -> Void)?
    @Environment(\.backgroundSkinImage) private var skinImage
    @Environment(\.backgroundSkinStyle) private var skinStyle
    @Environment(\.skinAccent) private var skinAccent
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var textSize
    @State private var hotComments: [TrackComment] = []
    @State private var hotTrackID: String?
    @State private var wideSection = WidePlayerSection.lyrics
    @State private var modeNotice: String?
    @State private var noticeRevision = 0
    @State private var showingQueue = false
    @State private var showingCaptions = false
    @State private var previewMilliseconds = 0.0
    @State private var scrubbing = false
    @State private var scrubbingSelection: Int?
    @State private var showingComments = false
    @State private var showingTrackActions = false
    @State private var queuedCreator = false
    @State private var creatorSupported = false
    @State private var creatorDestination: CreatorDestination?
    private struct CreatorDestination: Identifiable { let id: String; let source: (any LibrarySource)?; let plugin: PluginSource? }

    var body: some View {
        GeometryReader { geometry in
            if let track = playback.queue.current {
                Group {
                    if geometry.size.width > geometry.size.height && geometry.size.width > 600 && !textSize.isAccessibilitySize {
                        widePlayer(track: track)
                    } else if textSize.isAccessibilitySize {
                        ScrollView { player(track: track, viewportHeight: max(900, geometry.size.height)).frame(minHeight: max(900, geometry.size.height)) }
                    } else { player(track: track, viewportHeight: geometry.size.height) }
                }.padding(.horizontal, geometry.size.width > geometry.size.height ? 16 : 24).padding(.top, playerTopInset).padding(.bottom, 2)
            } else { ContentUnavailableView("播放队列为空", systemImage: "music.note.list") }
        }
        .background {
            // 同一个背景覆盖安全区和内容区，雾气不再只给中间区域叠色。
            ZStack {
                if skinImage == nil || !skinStyle.appliesToPlayer { MBTheme.background }
                if let track = playback.queue.current, preferences?.values.fog == true {
                    PlaybackFog(track: track, bass: playback.bassLevel)
                }
            }.ignoresSafeArea().allowsHitTesting(false).accessibilityHidden(true)
        }
        .modifier(SkinSurface(defaultColor: MBTheme.background, enabled: skinStyle.appliesToPlayer))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("music-player-root")
        .accessibilityValue(skinImage != nil && skinStyle.appliesToPlayer ? "自定义播放器背景" : "默认播放器背景")
        .overlay(alignment: .top) {
            if let modeNotice {
                Text(modeNotice).font(.subheadline.weight(.semibold)).padding(.horizontal, 18).padding(.vertical, 10)
                    .background(.regularMaterial, in: Capsule()).padding(.top, 54).allowsHitTesting(false)
                    .accessibilityIdentifier("playback-mode-notice")
            }
        }
        .tint(skinAccent)
        .sheet(isPresented: $showingQueue) { QueueView(playback: playback, hint: "待播清单") }
        .sheet(isPresented: $showingComments) {
            if let interactionSource, let track = playback.queue.current { CommentsView(source: interactionSource, track: track).id("\(track.id):\(accountRevision):\(interactionSource != nil)") }
        }
        .sheet(isPresented: $showingTrackActions, onDismiss: {
            if queuedCreator, let track = playback.queue.current { queuedCreator = false; creatorAction(track)?() }
        }) {
            if let track = playback.queue.current {
                PlayerTrackActionsSheet(track: track, downloads: downloads, preferences: preferences, pluginSource: pluginSource, openCreator: creatorAction(track).map { _ in { queuedCreator = true } })
            }
        }
        .sheet(item: $creatorDestination) { destination in
            NavigationStack {
                Group {
                    if let plugin = destination.plugin, let track = playback.queue.current {
                        PluginCatalogPage(source: plugin, request: .entity(.creator(id: destination.id, name: track.artist)), select: selectCreatorTrack, menu: creatorTrackMenu)
                    } else if let source = destination.source {
                        LibraryPageView(source: source, route: .creator(destination.id), select: selectCreatorTrack, menu: creatorTrackMenu)
                    }
                }
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { creatorDestination = nil } } }
            }.id(destination.id)
        }
        .task(id: (playback.queue.current?.id ?? "") + ":" + String(preferences?.values.hotComments ?? false) + ":" + String(interactionSource != nil) + ":" + String(accountRevision)) {
            hotComments = []; hotTrackID = nil
            guard preferences?.values.hotComments == true, let track = playback.queue.current, let interactionSource else { return }
            do {
                let page = try await interactionSource.comments(trackID: track.sourceTrackID, page: 1)
                guard !Task.isCancelled, playback.queue.current?.id == track.id else { return }
                hotComments = page.comments; hotTrackID = track.id
            } catch { /* 热评失败不打断歌词或播放。 */ }
        }
        .task(id: pluginSource?.manifest.id) {
            creatorSupported = false
            if let pluginSource {
                let supported = (try? await pluginSource.supports("artist")) == true
                if !Task.isCancelled { creatorSupported = supported }
            }
        }
        .onChange(of: playback.selectionRevision) { _, _ in
            scrubbing = false; scrubbingSelection = nil
            previewMilliseconds = demo ? 0 : Double(playback.positionMilliseconds)
        }
        .onChange(of: playback.positionMilliseconds) { _, value in if !scrubbing && !demo { previewMilliseconds = Double(value) } }
        .onAppear { if !demo { previewMilliseconds = Double(playback.positionMilliseconds) } }
        #if os(iOS)
        .onAppear { UIDevice.current.beginGeneratingDeviceOrientationNotifications() }
        .onDisappear { UIDevice.current.endGeneratingDeviceOrientationNotifications() }
        .onReceive(NotificationCenter.default.publisher(for: UIDevice.orientationDidChangeNotification)) { _ in MobileOrientation.followDevice() }
        #endif
    }

    private func player(track: Track, viewportHeight: CGFloat) -> some View {
        VStack(spacing: 10) {
            header(track: track)
            if !showingCaptions { Color.clear.frame(height: viewportHeight < 650 ? 12 : 32).accessibilityHidden(true) }
            if showingCaptions {
                HStack(alignment: .center, spacing: 12) {
                    Button { withAnimation(.easeInOut(duration: 0.2)) { showingCaptions = false } } label: {
                        ArtworkView(track: track, size: 60)
                    }.buttonStyle(.plain).accessibilityLabel("返回封面播放页")
                        .accessibilityIdentifier("player-lyrics-cover")
                    metadata(track: track, compact: true)
                }.accessibilityElement(children: .contain).accessibilityIdentifier("player-lyrics-header")
            }
            GeometryReader { region in
                let side = max(100, min(region.size.width, region.size.height, 440))
                Group {
                    if showingCaptions, let comment = quietComment {
                        Button { showingComments = true } label: {
                            VStack(alignment: .leading, spacing: 18) {
                                Label("热评", systemImage: "bubble.left").font(.caption).foregroundStyle(.secondary)
                                Text(comment.text).font(.title2.weight(.semibold)).multilineTextAlignment(.leading)
                                Text("\(comment.author) · \(comment.likes) 赞").font(.caption).foregroundStyle(.secondary)
                            }.padding(12).frame(maxWidth: .infinity, alignment: .leading)
                        }.buttonStyle(.plain).accessibilityLabel("查看热评 \(comment.text)")
                    } else if showingCaptions {
                        CaptionView(timeline: demo ? DemoCatalog.timeline : playback.timeline,
                                    positionMilliseconds: demo ? Int64(previewMilliseconds) : playback.positionMilliseconds,
                                    message: playback.captionMessage, viewportHeight: region.size.height, seek: {
                                        if demo { previewMilliseconds = Double($0) } else { playback.seek(to: $0) }
                                    }).id(track.id)
                    } else {
                        Button { withAnimation(.easeInOut(duration: 0.2)) { showingCaptions = true } } label: {
                            Group {
                                if let style = preferences?.values.playerStyle, style != .cover {
                                    RetroArtworkView(track: track, style: style, positionMilliseconds: playback.positionMilliseconds,
                                                     durationMilliseconds: playback.durationMilliseconds, playing: playback.state == .playing, size: side)
                                } else { ArtworkView(track: track, size: side).shadow(color: .black.opacity(0.15), radius: 18, y: 12) }
                            }.contentShape(Rectangle()).accessibilityHidden(true)
                        }.buttonStyle(.plain).accessibilityLabel("查看歌词")
                            .accessibilityIdentifier("player-artwork-" + (preferences?.values.playerStyle.rawValue ?? "cover"))

                    }
                }.frame(width: region.size.width, height: region.size.height)
                    .contentShape(Rectangle())
                    .modifier(InteractiveTrackSwipe(selection: playback.selectionRevision, simultaneous: showingCaptions,
                        previousTrack: playback.neighboringTrack(forward: false), nextTrack: playback.neighboringTrack(forward: true), previous: playback.previous, next: playback.next))
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("music-player-swipe-stage")
            }.frame(height: showingCaptions ? nil : max(120, min(320, (viewportHeight - 330) * 0.55)))
                .frame(minHeight: showingCaptions ? 130 : nil)
            if !showingCaptions {
                metadata(track: track)
                CaptionView(timeline: demo ? DemoCatalog.timeline : playback.timeline,
                                positionMilliseconds: demo ? Int64(previewMilliseconds) : playback.positionMilliseconds,
                                message: playback.captionMessage, viewportHeight: 96, compact: true, seek: {
                                    if demo { previewMilliseconds = Double($0) } else { playback.seek(to: $0) }
                                }).id(track.id)
                    .frame(height: 96).accessibilityIdentifier("player-inline-lyrics")
                Spacer(minLength: 0)
            }
            VStack(spacing: 10) {
                if let message = playback.errorMessage, !demo { Text(LocalizedStringKey(message)).font(.caption).foregroundStyle(.red).lineLimit(2) }
                if let station = playback.station { HStack { Label(station.title, systemImage: "radio"); Spacer(); Button("退出电台") { playback.endStation() } }.font(.caption) }
                if let message = playback.persistenceMessage { Text(LocalizedStringKey(message)).font(.caption).foregroundStyle(.secondary) }
                progress
                controls
                playerActions(track: track)
            }.frame(maxWidth: 540).frame(maxWidth: .infinity).accessibilityElement(children: .contain).accessibilityIdentifier("player-controls")
        }
    }

    private func announceMode(_ message: String) {
        modeNotice = message; noticeRevision += 1
        let revision = noticeRevision
        Task {
            try? await Task.sleep(for: .seconds(2))
            if noticeRevision == revision { modeNotice = nil }
        }
    }

    private var playerTopInset: CGFloat {
        #if os(iOS)
        // iPad 窗口模式的系统窗口按钮覆盖左上角；给自定义播放器控制区留出标题栏。
        UIDevice.current.userInterfaceIdiom == .pad ? 32 : 4
        #else
        4
        #endif
    }
    private func header(track: Track) -> some View {
        HStack(spacing: 8) {
            Button { dismiss() } label: { Image(systemName: "chevron.down").frame(width: 44, height: 44).contentShape(Rectangle()) }
                .buttonStyle(.plain).accessibilityLabel("收起")
            Spacer(minLength: 0)
            Text(LocalizedStringKey(demo ? "界面演示" : "正在播放")).font(.subheadline.weight(.semibold)).foregroundStyle(.secondary)
            Spacer(minLength: 0)
            if track.localFileID == nil, let watchVideo {
                Button("观看当前视频", systemImage: "play.rectangle", action: watchVideo)
                    .labelStyle(.iconOnly).frame(width: 44, height: 44)
            }
            #if os(iOS)
            Button("横屏播放", systemImage: "arrow.up.left.and.arrow.down.right") { MobileOrientation.player(landscape: true) }.labelStyle(.iconOnly).frame(width: 44, height: 44)
            #endif
            styleMenu

        }
    }
    private func creatorAction(_ track: Track) -> (() -> Void)? {
        guard let id = track.artistID, !id.isEmpty else { return nil }
        guard let pluginSource else {
            guard librarySource != nil else { return nil }
            return { creatorDestination = CreatorDestination(id: id, source: librarySource, plugin: nil) }
        }
        guard creatorSupported else { return nil }
        return { creatorDestination = CreatorDestination(id: id, source: librarySource, plugin: pluginSource) }
    }
    private func selectCreatorTrack(_ tracks: [Track], _ index: Int) {
        creatorDestination = nil; playback.select(tracks, index: index, autoplay: !demo)
    }
    private func creatorTrackMenu(_ track: Track) -> AnyView {
        if let downloads { AnyView(TrackContextMenu(track: track, playback: playback, downloads: downloads)) }
        else { AnyView(EmptyView()) }
    }
    private func metadata(track: Track, compact: Bool = false) -> some View {
        PlayerTrackMetadata(track: track, compact: compact, source: interactionSource,
                            openCreator: creatorAction(track), openComments: { showingComments = true },
                            more: { showingTrackActions = true }).id("\(track.id):\(accountRevision):\(interactionSource != nil)")
    }
    private func playerActions(track: Track) -> some View {
        HStack {
            Button { showingCaptions.toggle(); announceMode(showingCaptions ? "已切换到歌词" : "已切换到封面") } label: {
                actionLabel(icon: "quote.bubble", active: showingCaptions)
            }.accessibilityLabel("字幕").accessibilityValue(Text(showingCaptions ? "开启" : "关闭"))
            Spacer(minLength: 0)
            #if os(iOS)
            AudioRoutePicker(onPresent: { announceMode("音频输出") }).frame(width: 60, height: 48)
                .accessibilityLabel("音频输出").accessibilityIdentifier("player-audio-output")
            Spacer(minLength: 0)
            #endif
            Button { showingQueue = true } label: { actionLabel(icon: "list.bullet") }.accessibilityLabel("队列").accessibilityValue("\(playback.queue.tracks.count)")
        }.foregroundStyle(.primary)
    }
    private func actionLabel(icon: String, active: Bool = false) -> some View {
        Image(systemName: icon).font(.title3)
            .frame(minWidth: 60, minHeight: 48)
            .background(active ? skinAccent.opacity(0.12) : .clear, in: RoundedRectangle(cornerRadius: 12))
    }
    private func widePlayer(track: Track) -> some View {
        VStack(spacing: 4) {
            HStack(spacing: 8) {
                Button { dismiss() } label: { Image(systemName: "chevron.down").frame(width: 44, height: 44).contentShape(Rectangle()) }
                .buttonStyle(.plain).accessibilityLabel("收起")
                ForEach(WidePlayerSection.allCases) { section in
                    Button { wideSection = section } label: {
                        Text(section.title).font(.subheadline.weight(.semibold)).padding(.horizontal, 10).frame(minHeight: 40)
                            .background(wideSection == section ? skinAccent.opacity(0.13) : .clear, in: Capsule())
                    }.buttonStyle(.plain).accessibilityIdentifier("wide-player-" + section.rawValue)
                        .accessibilityAddTraits(wideSection == section ? .isSelected : [])
                }
                Spacer(minLength: 0)
                if track.localFileID == nil, let watchVideo {
                    Button("观看当前视频", systemImage: "play.rectangle", action: watchVideo).labelStyle(.iconOnly).frame(width: 44, height: 44)
                }
                styleMenu
                #if os(iOS)
                Button("竖屏播放", systemImage: "arrow.down.right.and.arrow.up.left") { MobileOrientation.player(landscape: false) }.labelStyle(.iconOnly).frame(width: 44, height: 44)
                AudioRoutePicker().frame(width: 44, height: 44).accessibilityLabel("音频输出")
                #endif
            }
            HStack(spacing: 24) {
                if wideSection == .lyrics || wideSection == .comments {
                    GeometryReader { area in
                        let side = max(60, min(area.size.width, area.size.height))
                        ArtworkView(track: track, size: side).frame(maxWidth: .infinity, maxHeight: .infinity)
                    }.frame(maxWidth: 280)
                        .contentShape(Rectangle())
                        .modifier(InteractiveTrackSwipe(selection: playback.selectionRevision,
                            previousTrack: playback.neighboringTrack(forward: false), nextTrack: playback.neighboringTrack(forward: true), previous: playback.previous, next: playback.next))
                }
                WidePlayerStage(playback: playback, demo: demo, section: wideSection, interactionSource: interactionSource,
                                accountRevision: accountRevision, position: demo ? Int64(previewMilliseconds) : playback.positionMilliseconds)
            }.frame(maxHeight: .infinity)
            HStack(spacing: 20) {
                metadata(track: track, compact: true).frame(maxWidth: 380)
                VStack(spacing: 0) {
                    if let message = playback.errorMessage, !demo { Text(message).font(.caption).foregroundStyle(.red).lineLimit(1) }
                    progress
                }.frame(maxWidth: .infinity)
                controls.frame(width: 260)
            }.accessibilityElement(children: .contain).accessibilityIdentifier("player-controls")
        }
    }

    @Environment(\.offlineLibrary) private var library

    @ViewBuilder private var styleMenu: some View {
        if let preferences {
            Menu {
                Menu("播放器样式") {
                    ForEach(PlayerVisualStyle.allCases) { style in
                        Button {
                            preferences.values.playerStyle = style
                            showingCaptions = false
                        } label: {
                            if !showingCaptions, preferences.values.playerStyle == style {
                                Label(LocalizedStringKey(style.title), systemImage: "checkmark")
                            } else { Text(LocalizedStringKey(style.title)) }
                        }
                    }
                }
                Menu("歌词舞台") {
                    ForEach(LyricVisualStyle.allCases) { style in
                        Button {
                            preferences.values.lyricStyle = style
                            showingCaptions = true
                        } label: {
                            if showingCaptions, preferences.values.lyricStyle == style {
                                Label(LocalizedStringKey(style.title), systemImage: "checkmark")
                            } else { Text(LocalizedStringKey(style.title)) }
                        }
                    }
                }
            } label: { Image(systemName: "paintbrush").frame(width: 44, height: 44) }.accessibilityLabel("播放界面样式")
        }
    }
    private var quietComment: TrackComment? {
        guard preferences?.values.hotComments == true, hotTrackID == playback.queue.current?.id else { return nil }
        return QuietComments.comment(at: demo ? Int64(previewMilliseconds) : playback.positionMilliseconds,
            timeline: demo ? DemoCatalog.timeline : playback.timeline, duration: playback.durationMilliseconds, comments: hotComments)
    }
    private var progress: some View {
        VStack(spacing: 0) {
            PlaybackSeekBar(value: Binding(get: { min(max(0, previewMilliseconds), Double(max(1, playback.durationMilliseconds))) }, set: { previewMilliseconds = $0 }), in: 0...Double(max(1, playback.durationMilliseconds))) { editing in
                scrubbing = editing
                if editing { scrubbingSelection = playback.selectionRevision }
                else {
                    if !demo, scrubbingSelection == playback.selectionRevision { playback.seek(to: Int64(previewMilliseconds)) }
                    scrubbingSelection = nil
                }
            }.overlay { if preferences?.values.smoke == true { ProgressSmoke(progress: previewMilliseconds / Double(max(1, playback.durationMilliseconds)), position: Int64(previewMilliseconds), playing: playback.state == .playing) } }
                .accessibilityLabel(Text(demo ? "字幕时间轴预览" : "播放进度"))
            HStack { Text(TimeLabel.string(milliseconds: Int64(previewMilliseconds))); Spacer(); Text(TimeLabel.string(milliseconds: playback.durationMilliseconds)) }.font(.caption.monospacedDigit()).foregroundStyle(.secondary)
        }
    }
    private var controls: some View {
        HStack {
            Button { playback.toggleShuffle(); announceMode(playback.queue.shuffled ? "已切换为随机播放" : "已切换为" + playback.queue.repeatMode.title) } label: {
                Image(systemName: "shuffle").frame(minWidth: 56, minHeight: 48)
            }.accessibilityLabel("随机播放")
                .tint(playback.queue.shuffled ? skinAccent : .secondary).accessibilityValue(Text(playback.queue.shuffled ? "开启" : "关闭"))
            Spacer(minLength: 4)
            Button("上一首", systemImage: "backward.end.fill") { playback.previous() }.labelStyle(.iconOnly).font(.title2).frame(width: 44, height: 52)
            Spacer(minLength: 4)
            Button { playback.toggle() } label: {
                Group { if playback.state == .loading { ProgressView() } else { Image(systemName: playback.state == .playing ? "pause.fill" : "play.fill").font(.system(size: 34, weight: .semibold)) } }.frame(width: 64, height: 64)
            }.buttonStyle(.plain).disabled(demo).accessibilityLabel(Text(playback.state == .playing ? "暂停" : "播放"))
            Spacer(minLength: 4)
            Button("下一首", systemImage: "forward.end.fill") { playback.next() }.labelStyle(.iconOnly).font(.title2).frame(width: 44, height: 52)
            Spacer(minLength: 4)
            Button { playback.cycleRepeat(); announceMode("已切换为" + playback.queue.repeatMode.title) } label: {
                Image(systemName: playback.queue.repeatMode == .one ? "repeat.1" : "repeat").frame(minWidth: 56, minHeight: 48)
            }.accessibilityLabel(LocalizedStringKey(playback.queue.repeatMode.title)).tint(playback.queue.repeatMode == .off ? .secondary : skinAccent)
        }.foregroundStyle(.primary)
    }
}

struct QueueView: View {
    let playback: PlaybackModel
    var hint: String? = nil
    @State private var showingHint = true
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                ForEach(Array(playback.queue.tracks.enumerated()), id: \.offset) { index, track in
                    Button { playback.jump(to: index, intent: .transport); dismiss() } label: {
                        TrackRow(track: track, selected: index == playback.queue.index)
                    }.buttonStyle(.plain).accessibilityLabel("队列选择 \(track.title)")
                }
                .onDelete { playback.removeQueue($0) }
                .onMove { playback.moveQueue($0, to: $1) }
                QueuePageFooter(playback: playback)
            }
            .modifier(PaginationViewport())
            .overlay {
                if playback.queue.tracks.isEmpty { ContentUnavailableView("播放队列为空", systemImage: "music.note.list") }
            }
            .overlay(alignment: .top) {
                if showingHint, let hint {
                    Text(LocalizedStringKey(hint)).font(.subheadline.weight(.semibold))
                        .padding(.horizontal, 18).padding(.vertical, 10).background(.regularMaterial, in: Capsule())
                        .allowsHitTesting(false).accessibilityIdentifier("player-action-notice")
                }
            }
            .task { try? await Task.sleep(for: .seconds(2)); showingHint = false }
            .navigationTitle("播放队列 · \(playback.queue.tracks.count)")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
            #if os(iOS)
            .toolbar { ToolbarItem(placement: .primaryAction) { EditButton() } }
            #endif
        }
    }
}

struct QueuePageFooter: View {
    let playback: PlaybackModel

    var body: some View {
        if let page = playback.nextQueuePageID {
            if playback.queuePageFailed {
                Button("重试") { playback.loadNextQueuePage() }
            } else {
                PaginationFooter(page: page, loading: playback.isLoadingQueuePage) {
                    playback.loadNextQueuePage()
                }
            }
        }
    }
}

struct PlaybackOrderControls: View {
    @Environment(\.skinAccent) private var skinAccent
    let playback: PlaybackModel
    var body: some View {
        HStack {
            Button("随机播放", systemImage: "shuffle") { playback.toggleShuffle() }
                .tint(playback.queue.shuffled ? skinAccent : .secondary)
                .accessibilityValue(Text(playback.queue.shuffled ? "开启" : "关闭"))
            Spacer()
            Button(LocalizedStringKey(playback.queue.repeatMode.title), systemImage: playback.queue.repeatMode == .one ? "repeat.1" : "repeat") { playback.cycleRepeat() }
                .tint(playback.queue.repeatMode == .off ? .secondary : skinAccent)
        }.font(.subheadline)
    }
}
