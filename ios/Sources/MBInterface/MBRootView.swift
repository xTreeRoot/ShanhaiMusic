import MBFoundation
import SwiftUI

enum MBPage: Hashable { case discovery(DiscoveryCategory), video, library(LibraryRoute) }
private enum MBTab: Hashable { case discover, search, library, account }


/// 手机使用层级返回；平板保留发现侧栏。所有页面共用同一播放会话。
public struct MBRootView: View {
    @State private var selectedTab = MBTab.discover
    @State private var discoveryPagePosition: CGFloat?
    @Namespace private var playerTransition
    @State private var trackSearch: SearchModel
    @State private var showingSearchPlatforms = false
    @State private var modeNotice: String?
    @State private var modeNoticeID = UUID()
    @State private var searchTarget: String?
    @State private var playback: PlaybackModel
    @State private var video: VideoModel
    @State private var networkSettings = NetworkSettingsModel()
    @State private var suggestions: [String] = []
    @State private var recordedSelection = -1
    @State private var input = ""
    @State private var request: SearchRequest?
    @State private var path: [MBPage] = []
    @State private var searchPath: [MBPage] = []
    @State private var showingAppSettings = false
    @State private var showingPlayer = false
    @State private var showingQueue = false
    @State private var showingSettings = false
    @State private var showingService = false
    @State private var showingPlugins = false
    @State private var account: AccountModel
    @State private var accountPlatformID: String
    @State private var commentTrack: Track?
    @State private var resourceDetail: Track?
    @State private var systemPlayback = SystemPlaybackCoordinator()
    @State private var downloads: DownloadModel
    @State private var preferences = PreferencesModel()
    #if os(iOS)
    @State private var pictureInPicture = VideoPictureInPicture()
    #endif
    @State private var skin: BackgroundSkin
    @State private var localLibrary: LocalLibraryModel
    @State private var previewLibrary: DemoLibrary?
    @State private var columns: NavigationSplitViewVisibility = .all
    @FocusState private var inputFocused: Bool
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var source: any MusicSource
    @State private var selectedSourceID: String
    @State private var plugins: PluginModel
    @State private var platformData = PlatformDataModel()
    @State private var currentInteractions: (any InteractionSource)?
    @State private var interactionTrackID: String?
    @State private var currentLibrarySource: (any LibrarySource)?
    private let router: SourceRouter
    private let initialSource: any MusicSource
    private let initialSourceID: String
    private let demo: Bool
    private let demoTrackProblems: Bool
    private var search: SearchModel { trackSearch }
    private var skinAccent: Color { skin.preview == nil && skin.image == nil ? MBTheme.accent : (skin.preview != nil ? skin.previewStyle : preferences.values.backgroundStyle).accent.color }

    public init(source: any MusicSource, sourceID: String = "demo", demo: Bool = false) {
        let preview = demo ? DemoLibrary() : nil
        let router = SourceRouter()
        #if DEBUG
        let demoTrackProblems = demo && ProcessInfo.processInfo.arguments.contains("--plugin-track-problems")
        #else
        let demoTrackProblems = false
        #endif
        self.demoTrackProblems = demoTrackProblems
        self.router = router
        self.initialSource = source; self.initialSourceID = sourceID
        _selectedSourceID = State(initialValue: sourceID)
        _trackSearch = State(initialValue: SearchModel(source: source))
        var playbackSource: (any PlaybackSource)? = demo && !demoTrackProblems ? source as? any PlaybackSource : router
        var videoSource: (any VideoSource)? = demo ? source as? any VideoSource : router
        #if DEBUG
        if demo, let fixture = DemoVideoSource.fixture() { playbackSource = fixture; videoSource = fixture }
        #endif
        let playback = PlaybackModel(source: playbackSource, engine: NativeAudioEngine(), store: demo ? nil : AppPersistence.shared, mediaLibrary: demo ? nil : MediaLibrary.shared)
        let video = VideoModel(source: videoSource, store: demo ? nil : AppPersistence.shared)
        video.onPlaybackHoldChanged = { [weak playback] hold in playback?.holdForVideo(hold) }
        _playback = State(initialValue: playback)
        _video = State(initialValue: video)
        _source = State(initialValue: source)
        self.demo = demo
        _account = State(initialValue: AccountModel(source: source as? any AccountSource))
        _accountPlatformID = State(initialValue: sourceID)
        _downloads = State(initialValue: DownloadModel(source: demo ? source as? any PlaybackSource : router, library: preview?.media ?? .shared))
        _localLibrary = State(initialValue: LocalLibraryModel(store: preview?.store ?? .shared))
        _previewLibrary = State(initialValue: preview)
        _skin = State(initialValue: BackgroundSkin(directory: preview?.skinDirectory))
        let installer = preview.map { PluginInstaller(directory: $0.pluginDirectory) } ?? PluginInstaller()
        #if DEBUG
        if let preview, ProcessInfo.processInfo.arguments.contains("--plugin-check-demo") {
            _plugins = State(initialValue: PluginModel(installer: installer) { installer, repo in
                try await Task.sleep(for: .seconds(5))
                let fixture = try preview.pluginFixture()
                var pending = try await installer.prepare(repo == "fixture/failure" ? fixture.appendingPathComponent("missing.zip") : fixture)
                pending.releaseNotes = "优化推荐分类切换，保留完整首页内容。"
                return pending
            })
            return
        }
        #endif
        _plugins = State(initialValue: PluginModel(installer: installer))
    }

    private var tabContent: some View {
        TabView(selection: $selectedTab) {
            adaptiveNavigation
                .safeAreaInset(edge: .bottom, spacing: 0) { legacyMiniPlayer(for: .discover) }
                .tabItem { Label("发现", systemImage: "safari") }.tag(MBTab.discover)
            searchNavigation.safeAreaInset(edge: .bottom, spacing: 0) { legacyMiniPlayer(for: .search) }
                .tabItem { Label("搜索", systemImage: "magnifyingglass") }.tag(MBTab.search)
            DownloadsView(model: downloads, library: localLibrary, playback: playback)
                .safeAreaInset(edge: .bottom, spacing: 0) { legacyMiniPlayer(for: .library) }
                .tabItem { Label("资料库", systemImage: "music.note.list") }.tag(MBTab.library)
            AccountLibraryView(account: account, plugin: source as? PluginSource, source: source as? any LibrarySource, playback: playback, downloads: downloads, select: select, openSettings: { showingAppSettings = true }, openPlugins: { showingPlugins = true }, openService: { showingService = true })
                .safeAreaInset(edge: .bottom, spacing: 0) { legacyMiniPlayer(for: .account) }
                .tabItem { Label("我的", systemImage: "person.crop.circle") }.tag(MBTab.account)
        }
    }

    @ViewBuilder private var tabsWithPlayer: some View {
        #if os(iOS)
        if #available(iOS 26.1, *) {
            tabContent.tabViewBottomAccessory(isEnabled: playback.queue.current != nil && !video.isVisible) {
                miniPlayer(systemContainer: true)
            }
        } else { tabContent }
        #else
        tabContent
        #endif
    }

    @ViewBuilder private func legacyMiniPlayer(for tab: MBTab) -> some View {
        // 旧系统仅在当前标签的内容安全区挂载，保留唯一的封面转场来源。
        if selectedTab == tab {
            #if os(iOS)
            if #unavailable(iOS 26.1) { miniPlayer() }
            #else
            miniPlayer()
            #endif
        }
    }

    @ViewBuilder private func miniPlayer(systemContainer: Bool = false) -> some View {
        if playback.queue.current != nil && !video.isVisible {
            MiniPlayerView(playback: playback, systemContainer: systemContainer, transitionEnabled: preferences.values.growFromCover, transitionNamespace: playerTransition, openQueue: { showingQueue = true }) {
                if video.presentationMode == .video { video.open(); showVideo() }
                else { showingPlayer = true }
            }
        }
    }

    private var tabs: some View {
        tabsWithPlayer
            #if os(iOS)
            .statusBarHidden(video.isVisible && video.aspectRatio < 1)
            #endif
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if skin.preview != nil { BackgroundPreviewControls(skin: skin) {
                    let style = skin.previewStyle
                    if let opacity = await skin.confirmPreview() { preferences.values.backgroundOpacity = opacity; preferences.values.backgroundStyle = style }
                } }
            }
            .preferredColorScheme(preferences.values.appearance == .system ? nil : preferences.values.appearance == .dark ? .dark : .light)
            .playerPresentation(isPresented: $showingPlayer) { NowPlayingView(playback: playback, demo: demo, interactionSource: currentInteractions, librarySource: currentLibrarySource, accountRevision: account.revision, downloads: downloads, preferences: preferences, watchVideo: { video.watchCurrent() }).modifier(CoverTransitionDestination(enabled: preferences.values.growFromCover, namespace: playerTransition)) }
            .sheet(isPresented: $showingQueue) { QueueView(playback: playback) }
            .sheet(isPresented: $showingService) { ShanhaiServiceView(model: platformData, account: verifiedProfileAccount, platform: selectedSourceID) }
            .sheet(isPresented: $showingAppSettings) { SettingsView(preferences: preferences, network: networkSettings, plugins: plugins, skin: skin) }
            .sheet(item: $resourceDetail) { track in
                NavigationStack { ResourceDetailView(track: track, library: localLibrary)
                    .toolbar { ToolbarItem(placement: .cancellationAction) { Button("完成") { resourceDetail = nil } } }
                }
            }
            .sheet(item: $commentTrack) { track in
                if let interactions = currentInteractions { CommentsView(source: interactions, track: track).id(account.revision) }
            }
            .sheet(isPresented: $showingPlugins) {
                NavigationStack {
                    PluginsView(model: plugins).toolbar { ToolbarItem(placement: .cancellationAction) { Button("完成") { showingPlugins = false }.accessibilityIdentifier("dismiss-plugins") } }
                }.modifier(PluginPreparationPresentation(model: plugins))
            }
            .onOpenURL { url in
                if plugins.ui.handleOAuthCallback(url) { return }
                if let repo = PluginInstaller.repository(fromInstallURL: url) { showingPlugins = true; Task { await plugins.prepareGitHub(repo) } }
            }
            .sheet(isPresented: $showingSettings) { NetworkSettingsView(model: networkSettings) }
    }

    private var initializedTabs: some View {
        tabs
            .task { await skin.restore() }
            .task { if !demo { await platformData.restore() } }
            .environment(\.discoveryPreferencesModel, plugins)
            .task(id: "discovery:\(plugins.discoveryRevision):\(platformData.revision):\(scenePhase)") {
                if !demo, scenePhase == .active {
                    await platformData.syncDiscoveryPreferences(load: { try await plugins.loadDiscoveryPreferences() },
                        merge: { try await plugins.mergeDiscoveryPreferences($0) })
                }
            }
            .task(id: "plugins:\(plugins.revision):\(platformData.revision):\(scenePhase)") {
                if !demo, scenePhase == .active {
                    await platformData.syncPluginPreferences(load: { try await plugins.pluginPreferences() },
                        merge: { try await plugins.mergePluginPreferences($0) })
                }
            }
            .task(id: "\(selectedSourceID):\(accountPlatformID):\(account.revision):\(account.isSigningIn):\(account.isSigningOut):\(platformData.revision)") {
                if !demo { platformData.sync(verifiedProfileAccount, platform: selectedSourceID) }
            }
            .task {
                if demo { await router.register(initialSource, id: initialSourceID) }
                if !demo { await plugins.restore(); await registerPlugins() }
                if !demo { systemPlayback.connect(playback) }
                if !demo { await networkSettings.restore() }
                if !demo { await preferences.restore(); if let saved = preferences.values.selectedSourceID { selectedSourceID = saved }; playback.setVolume(preferences.values.volume); playback.setNormalization(preferences.values.normalization) }
                if !demo { systemPlayback.setLyricsEnabled(preferences.values.nowPlayingLyrics) }
                AppLocalization.use(preferences.values.language)
                await router.setQuality(preferences.values.quality)
                video.setQuality(preferences.values.videoQuality)
                await account.restore()
                if let previewLibrary {
                    await previewLibrary.prepare()
                    #if DEBUG
                    if ProcessInfo.processInfo.arguments.contains("--plugin-demo") {
                        do { await plugins.prepare(try previewLibrary.pluginFixture(includeTrackProblems: demoTrackProblems, refreshFixture: ProcessInfo.processInfo.arguments.contains("--refresh-fixture"), filterFixture: ProcessInfo.processInfo.arguments.contains("--filter-fixture"))) }
                        catch { plugins.reportError(error) }
                    }
                    #endif
                }
                await downloads.refresh()
                localLibrary.updateTracks(downloads.items)
                await localLibrary.restore()
                await video.restore()
                await playback.restore()
                for item in downloads.items { playback.updateLocalTrack(item.track) }
                video.updateTrack(playback.queue.current)
            }
            .task(id: selectedSourceID + ":" + String(plugins.revision) + ":" + String(preferences.isRestored)) {
                if demo || preferences.isRestored { await activateSource() }
            }
            .task(id: (playback.queue.current?.id ?? "") + ":" + String(plugins.revision)) {
                currentInteractions = nil
                interactionTrackID = nil
                currentLibrarySource = nil
                let track = playback.queue.current
                await registerPlugins()
                guard let track, let provider = await router.source(for: track), !Task.isCancelled,
                      playback.queue.current?.id == track.id else { return }
                currentLibrarySource = provider as? any LibrarySource
                if let interactions = provider as? any InteractionSource {
                    let available = await interactions.capabilities().available
                    guard !Task.isCancelled, playback.queue.current?.id == track.id else { return }
                    currentInteractions = available ? interactions : nil
                    interactionTrackID = available ? track.id : nil
                }
            }
    }

    private var observedTabs: some View {
        AnyView(initializedTabs)
            .modifier(AutomaticAppUpdate(preferences: preferences,
                enabled: !demo || ProcessInfo.processInfo.arguments.contains("--demo-startup-update"),
                canPresent: skin.preview == nil && !showingPlayer && !showingQueue && !showingAppSettings && !showingService && !showingPlugins && !showingSettings && resourceDetail == nil && commentTrack == nil && !account.isSigningIn && !account.choosingSignInMethod && !video.isVisible,
                model: demo && ProcessInfo.processInfo.arguments.contains("--demo-startup-update") ? DemoCatalog.startupUpdateModel() : nil))
            .onChange(of: selectedSourceID) { _, id in if demo || preferences.isRestored { preferences.values.selectedSourceID = id } }
            .onChange(of: skin.preview != nil) { _, previewing in
                if previewing {
                    showingAppSettings = false; showingService = false; showingPlugins = false; showingSettings = false
                    showingPlayer = false; showingQueue = false
                    selectedTab = .discover; path = []
                }
            }
            .onChange(of: selectedTab) { _, _ in inputFocused = false }
            .onChange(of: plugins.revision) { _, _ in video.invalidatePreload(); Task { await registerPlugins() } }
            .onChange(of: account.revision) { _, _ in video.invalidatePreload() }
            .onChange(of: playback.positionMilliseconds) { _, _ in syncVideoClock() }
            .onChange(of: playback.audioBuffering) { _, _ in syncVideoClock() }
            .onChange(of: video.isLoading) { _, _ in syncVideoClock() }
            .onChange(of: video.isBuffering) { _, _ in syncVideoClock() }
            .onChange(of: scenePhase) { _, phase in
                if phase != .active { Task { await playback.saveNow(); if !demo { await preferences.flush() } } }
            }
            .onChange(of: preferences.values) { _, _ in
                AppLocalization.use(preferences.values.language)
                if !demo { preferences.save(); playback.setVolume(preferences.values.volume); playback.setNormalization(preferences.values.normalization); systemPlayback.setLyricsEnabled(preferences.values.nowPlayingLyrics) }
            }
            .onChange(of: preferences.values.videoQuality) { _, quality in video.setQuality(quality) }
            .onChange(of: preferences.values.quality) { _, quality in
                playback.invalidatePreparedAudio()
                Task { await router.setQuality(quality); playback.invalidatePreparedAudio() }
            }
            .onChange(of: downloads.items) { _, items in
                localLibrary.updateTracks(items)
                for item in items { playback.updateLocalTrack(item.track) }
            }
    }

    private var playbackObservedTabs: some View {
        AnyView(observedTabs)
            .onChange(of: playback.state) { _, state in
                syncVideoClock()
                if state == .playing, let track = playback.queue.current {
                    Task { await localLibrary.recordPlay(track) }
                    if recordedSelection != playback.selectionRevision {
                        recordedSelection = playback.selectionRevision
                        Task { if let source = await router.source(for: track) as? any PlaybackRecorder { await source.played(track) } }
                    }
                }
            }
            .onDisappear { systemPlayback.disconnect() }
            .onChange(of: playback.selectionRevision) { _, _ in
                video.handleSelection(playback.queue.current, intent: playback.selectionIntent,
                                      positionMilliseconds: playback.positionMilliseconds, playing: playback.state == .playing)
                if playback.selectionIntent == .content, playback.queue.current != nil {
                    if video.isVisible { showingPlayer = false; showVideo() }
                    else { showingPlayer = true }
                }
            }
            .onChange(of: video.isVisible) { _, visible in
                syncVideoClock()
                if visible { showingPlayer = false; showVideo() }
                else { path.removeAll { $0 == .video }; searchPath.removeAll { $0 == .video } }
            }
            .onChange(of: path) { _, pages in
                if selectedTab == .discover, !pages.contains(.video), video.isVisible { minimizeVideo() }
            }
            .onChange(of: searchPath) { _, pages in
                if selectedTab == .search, !pages.contains(.video), video.isVisible { minimizeVideo() }
            }
    }

    private func syncVideoClock() {
        video.sync(positionMilliseconds: playback.positionMilliseconds, playing: playback.state == .playing && !playback.audioBuffering)
        if !video.isVisible && !video.isFloating { video.preloadCurrent() }
        let next = playback.queue.nextIndex(automatic: true).map { playback.queue.tracks[$0] }
        let eligible = playback.state == .playing && !playback.audioBuffering && playback.durationMilliseconds > 0 &&
            Double(playback.positionMilliseconds) / Double(playback.durationMilliseconds) >= 0.7
        video.preloadNext(next?.id == playback.queue.current?.id ? nil : next, enabled: eligible)
    }

    private var playbackSurface: some View {
        AnyView(playbackObservedTabs)
            .overlay {
                if video.isFloating && preferences.values.inAppVideoWindow {
                    FloatingVideoWindow(video: video, playback: playback) { video.open(); showVideo() }
                }
            }
            #if os(iOS)
            .environment(\.videoPictureInPicture, pictureInPicture)
            .onChange(of: preferences.values.systemVideoWindow, initial: true) { _, enabled in
                pictureInPicture.configure(enabled: enabled, video: video, playback: playback) { video.open(); showVideo() }
            }
            .onChange(of: scenePhase) { _, phase in
                if phase == .inactive && preferences.values.systemVideoWindow && (video.isVisible || video.isFloating) && playback.state == .playing {
                    pictureInPicture.start()
                }
            }
            #endif
            .onChange(of: preferences.values.inAppVideoWindow) { _, enabled in if !enabled && video.isFloating { video.browse() } }
            .overlay {
                if let modeNotice {
                    Text(LocalizedStringKey(modeNotice)).font(.subheadline.weight(.medium)).padding(.horizontal, 22).padding(.vertical, 14)
                        .foregroundStyle(Color.primary).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
                        .shadow(color: .black.opacity(0.1), radius: 6).allowsHitTesting(false)
                        .accessibilityIdentifier("playback-mode-toast").accessibilityAddTraits(.updatesFrequently)
                }
            }
            .task(id: modeNoticeID) { if modeNotice != nil { try? await Task.sleep(for: .milliseconds(1400)); if !Task.isCancelled { modeNotice = nil } } }
            .task(id: input + ":" + (searchTarget ?? selectedSourceID)) { await updateSuggestions() }
            .task(id: request?.id) { [request] in
                guard let request, !Task.isCancelled else { return }
                if request.more { await request.model.loadMore() }
                else { await request.model.search(request.query) }
            }
    }

    public var body: some View {
        AnyView(playbackSurface)
            .environment(\.sourceNames, sourceDisplayNames)
            .environment(\.pluginUI, plugins.ui)
            .modifier(PluginLinkPresentation(service: plugins.ui))
            .environment(\.offlineLibrary, localLibrary)
            .environment(\.stationPlayback, playback)
            .environment(\.videoQualityPreference, $preferences.values.videoQuality)
            .environment(\.lyricSparks, preferences.values.stageSparks)
            .environment(\.lyricVisualStyle, preferences.values.lyricStyle)
            .environment(\.locale, Locale(identifier: preferences.values.language.languageCode))
            .tint(skinAccent)
            .modifier(SkinCanvas())
            .environment(\.backgroundSkinImage, skin.preview ?? skin.image)
            .environment(\.backgroundSkinOpacity, skin.preview != nil ? skin.previewOpacity : preferences.values.backgroundOpacity)
            .environment(\.backgroundSkinStyle, skin.preview != nil ? skin.previewStyle : preferences.values.backgroundStyle)
            .environment(\.skinAccent, skinAccent)
    }

    private func updateSuggestions() async {
        suggestions = []
        guard inputFocused,
              !input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let source = source as? any SuggestionSource else { return }
        do {
            try await Task.sleep(for: .milliseconds(250))
            let result = try await source.suggestions(input)
            guard !Task.isCancelled else { return }
            suggestions = Array(Set(result)).sorted()
        } catch { /* 联想失败保留正常搜索。 */ }
    }

    @ViewBuilder private var adaptiveNavigation: some View {
        if usesSidebar {
            NavigationSplitView(columnVisibility: $columns) {
                List {
                    Group {
                        Section("发现") {
                            Button("首页", systemImage: "house") { video.browse(); path = []; input = "" }
                            ForEach(DiscoveryCategory.allCases) { category in
                                Button(LocalizedStringKey(category.title)) { video.browse(); path = [.discovery(category)] }
                            }
                        }
                        Section("播放器") {
                            Button("听歌模式", systemImage: "music.note") { chooseMode(.music) }
                            Button("视频模式", systemImage: "play.rectangle") { chooseMode(.video) }
                            Button("网络设置", systemImage: "gearshape") { showingSettings = true }
                        }

                    }.modifier(SkinRow())
                }
                .modifier(SkinSurface(defaultColor: MBTheme.grouped, list: true))
                .navigationTitle("山海")
                .navigationSplitViewColumnWidth(min: 180, ideal: 210, max: 260)
            } detail: { navigation }
        } else { navigation }
    }

    private var usesSidebar: Bool {
        #if os(iOS)
        UIDevice.current.userInterfaceIdiom == .pad && sizeClass == .regular
        #else
        sizeClass == .regular
        #endif
    }

    private var navigation: some View {
        GeometryReader { geometry in
        NavigationStack(path: $path) {
            DiscoveryPager(sources: sourceTabs, providers: discoveryProviders, selected: selectedSourceID,
                           switchSource: switchSource, pageProgress: { discoveryPagePosition = $0 }, videoMode: video.mode == .video, select: select, menu: trackMenu)
                // 分页容器会隔开子页面的 toolbar preference，导航栏皮肤需设置在实际导航根内容上。
                .modifier(SkinSurface(defaultColor: MBTheme.background))
                .navigationTitle("")
                .modifier(CompactRootNavigation())
                .toolbar {
                    ToolbarItem(placement: .principal) { SourceTabs(sources: sourceTabs, selected: selectedSourceID, select: switchSource, pagePosition: discoveryPagePosition).frame(width: max(100, geometry.size.width - 110)) }.discoveryBackground
                    toolbar
                }
                .navigationDestination(for: MBPage.self) { destination($0) }
        }.modifier(SkinSurface(defaultColor: MBTheme.background))
        }
    }

    private var searchNavigation: some View {
        NavigationStack(path: $searchPath) {
            VStack(spacing: 0) {
                searchBar
                ZStack(alignment: .topLeading) {
                VStack(spacing: 0) {
                if inputFocused && !suggestions.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack { ForEach(suggestions, id: \.self) { text in Button(text) { input = text; submit() }.buttonStyle(.bordered).frame(minHeight: 44) } }.padding(.horizontal, 20)
                    }
                }
                Group {
                    if search.query.isEmpty { searchLanding }
                    else { searchResults }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .contentShape(Rectangle())
                .scrollDismissesKeyboard(.immediately)
                .simultaneousGesture(TapGesture().onEnded { inputFocused = false })
                .simultaneousGesture(DragGesture(minimumDistance: 10).onChanged { _ in inputFocused = false })
                }
                if showingSearchPlatforms {
                    Color.clear.contentShape(Rectangle()).onTapGesture { showingSearchPlatforms = false }
                    SearchPlatformOptions(sources: sourceTabs, selected: searchTarget ?? selectedSourceID, choose: chooseSearchTarget)
                        .padding(.leading, 16).padding(.top, 4)
                }
                }
            }
            .defaultFocus($inputFocused, false)
            .onChange(of: inputFocused) { _, focused in if focused { showingSearchPlatforms = false } }
            .onAppear { inputFocused = false }
            .onDisappear { inputFocused = false; showingSearchPlatforms = false }
            .navigationTitle("")
            .modifier(CompactRootNavigation())
            .navigationDestination(for: MBPage.self) { destination($0) }
            .modifier(SkinSurface(defaultColor: MBTheme.background))
        }
        .modifier(SkinSurface(defaultColor: MBTheme.background))
        .onChange(of: input) { _, value in
            if value.trimmingCharacters(in: .whitespacesAndNewlines) != search.query {
                search.cancel()
                if value.isEmpty { request = SearchRequest(model: search, query: "") }
                else if request?.query != value { request = nil }
            }
        }
    }

    private var searchLanding: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                if !preferences.values.searchHistory.isEmpty {
                    HStack { Text("最近搜索").font(.headline); Spacer(); Button("全部清除") { preferences.values.searchHistory = [] }.font(.subheadline) }
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 120))], alignment: .leading, spacing: 10) {
                        ForEach(preferences.values.searchHistory, id: \.self) { term in
                            HStack(spacing: 0) {
                                Button { input = term; submit() } label: { Text(term).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading).padding(.leading, 12) }
                                Button { preferences.values.searchHistory.removeAll { $0 == term } } label: { Image(systemName: "xmark").font(.caption).frame(width: 44, height: 44) }.accessibilityLabel("删除搜索记录 \(term)")
                            }.buttonStyle(.plain).background(.quaternary, in: Capsule())
                        }
                    }
                }
                if source is any DiscoverySource {
                    MusicSectionHeading(title: "浏览音乐")
                    LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 14) {
                        ForEach(DiscoveryCategory.allCases) { category in
                            NavigationLink(value: MBPage.discovery(category)) {
                                VStack(alignment: .leading, spacing: 24) {
                                    Image(systemName: category == .chart ? "chart.bar.fill" : category == .fresh ? "sparkles" : category == .trending ? "flame.fill" : "headphones").font(.title2)
                                    Text(LocalizedStringKey(category.title)).font(.headline)
                                }.frame(maxWidth: .infinity, minHeight: 110, alignment: .leading).padding(16)
                                    .background(skinAccent.opacity(0.08), in: RoundedRectangle(cornerRadius: 16))
                            }.buttonStyle(.plain)
                        }
                    }
                }

            }.padding(20)
        }.modifier(SkinSurface(defaultColor: MBTheme.background))
            .scrollDismissesKeyboard(.immediately)
            .accessibilityIdentifier("search-landing")
    }

    @ViewBuilder private func destination(_ page: MBPage) -> some View {
        switch page {
        case .video:
            VideoPageView(video: video, playback: playback, interactionSource: interactionTrackID == playback.queue.current?.id ? currentInteractions : nil, accountRevision: account.revision, librarySource: source as? any LibrarySource, openMusic: {
                showingPlayer = true
                Task { await video.choose(.music) }
            }, browse: minimizeVideo, saveResource: { track in Task { await localLibrary.saveResource(track) } }, menu: trackMenu)
            #if os(iOS)
            .toolbar(.hidden, for: .navigationBar, .tabBar)
            .toolbarBackground(.hidden, for: .navigationBar)
            #else
            .navigationTitle("视频播放")
            #endif
        case .discovery(let category):
            if let discovery = source as? any DiscoverySource { DiscoveryPageView(source: discovery, category: category, videoMode: video.mode == .video, select: select, menu: trackMenu).toolbar { toolbar } }
        case .library(let route):
            if let provider = source as? any LibrarySource { LibraryPageView(source: provider, route: route, select: select, menu: trackMenu) }
        }
    }

    @ToolbarContentBuilder private var toolbar: some ToolbarContent {
        if selectedTab != .search {
        ToolbarItem(placement: .primaryAction) {
            Button {
                let next: PlaybackMode = video.mode == .music ? .video : .music
                chooseMode(next)
                modeNotice = next == .music ? "听歌模式" : "视频模式"; modeNoticeID = UUID()
            } label: {
                Label(LocalizedStringKey(video.mode == .music ? "听歌模式" : "视频模式"), systemImage: video.mode == .music ? "music.note" : "play.rectangle")
            }.buttonStyle(.plain).accessibilityLabel("切换播放模式").accessibilityValue(LocalizedStringKey(video.mode == .music ? "听歌模式" : "视频模式"))
        }.discoveryBackground
        }
    }

    @ViewBuilder private var searchResults: some View {
        if search.tracks.isEmpty && search.isLoading {
            ProgressView("正在搜索").frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let message = search.errorMessage, search.tracks.isEmpty {
            ContentUnavailableView {
                Label("搜索失败", systemImage: "wifi.exclamationmark")
            } description: { Text(LocalizedStringKey(message)) } actions: { Button("重试") { submit() }.buttonStyle(.borderedProminent) }
        } else if search.tracks.isEmpty && !search.hasMore { ContentUnavailableView.search(text: search.query) }
        else {
            List {
                Group {
                    ForEach(search.warnings, id: \.self) { Text($0).font(.caption).foregroundStyle(.secondary) }
                    ForEach(Array(search.tracks.enumerated()), id: \.element.id) { index, track in
                        HStack(spacing: 4) {
                            Button { selectSearch(search.tracks, index) } label: {
                                let selected = playback.queue.current?.id == track.id
                                VStack(alignment: .leading, spacing: 4) {
                                    TrackRow(track: track, selected: selected)
                                    Text("\(sourceTabs.first(where: { $0.id == track.sourceID })?.name ?? track.artist) · \(resourceTypeName(track.resourceType))")
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                            }
                                .buttonStyle(.plain).accessibilityLabel("选择 \(track.title)")
                                .modifier(TrackProblemAccessibility(track: track, enabled: video.mode == .music))
                            Menu { Button("保存到资料库", systemImage: "plus") { Task { await localLibrary.saveResource(track) } }; if track.resourceURL == nil { trackMenu(track) } else if let url = track.resourceURL { ShareLink(item: url) } } label: { Image(systemName: "ellipsis").frame(width: 44, height: 44) }.accessibilityLabel("曲目操作 \(track.title)")
                        }.contextMenu {
                            Button("保存到资料库", systemImage: "plus") { Task { await localLibrary.saveResource(track) } }
                            if track.resourceURL == nil { trackMenu(track) }
                            else if let url = track.resourceURL { ShareLink(item: url) }
                        }
                    }
                    if search.hasMore {
                        VStack(spacing: 8) {
                            if let message = search.errorMessage { Text(LocalizedStringKey(message)).font(.caption).foregroundStyle(.secondary) }
                            if search.errorMessage != nil {
                                Button("重试") { request = SearchRequest(model: search, query: search.query, more: true) }
                            }
                            PaginationFooter(page: "\(search.paginationGeneration):\(search.cursor)", loading: search.isLoading, blocked: search.errorMessage != nil) {
                                await search.loadMore()
                            }.id(ObjectIdentifier(search))
                        }.frame(maxWidth: .infinity).listRowSeparator(.hidden)
                    }

                }.modifier(SkinRow())
            }.listStyle(.plain).modifier(PaginationViewport()).modifier(SkinSurface(list: true))
        }
    }

    private var searchBar: some View {
        HStack(spacing: 10) {
            Button { inputFocused = false; showingSearchPlatforms.toggle() } label: {
                HStack(spacing: 3) { Text(searchTargetName).lineLimit(1); Image(systemName: "chevron.down").font(.caption2) }
                    .font(.subheadline).frame(maxWidth: 115)
            }.accessibilityIdentifier("search-platform-picker")
            TextField("搜索视频、音乐、UP 主", text: Binding(get: { input }, set: { input = $0 }))
                .accessibilityLabel("搜索视频、音乐、UP 主")
                .autocorrectionDisabled().focused($inputFocused)
                .submitLabel(.search)
                .onSubmit { submit() }
            if !input.isEmpty { Button("清空搜索", systemImage: "xmark.circle.fill") { input = "" }.labelStyle(.iconOnly).foregroundStyle(.secondary) }
            Button("搜索") { submit() }
                .disabled(input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                .accessibilityIdentifier("submit-search")
            if inputFocused {
                Button("完成") { inputFocused = false }
                    .accessibilityIdentifier("dismiss-search-keyboard")
            }
        }
        .padding(12).background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 12))
        .padding(.horizontal, 16).padding(.vertical, 8)
    }

    private func registerPlugins() async {
        if demo { await router.register(initialSource, id: initialSourceID) }
        await router.keep(Set(plugins.sources.keys).union(demo ? [initialSourceID] : []))
        for (id, plugin) in plugins.sources { await router.register(plugin, id: id) }
    }
    private var discoveryProviders: [String: any MusicSource] {
        var result: [String: any MusicSource] = plugins.sources
        if demo, result[initialSourceID] == nil { result[initialSourceID] = initialSource }
        return result
    }
    private var sourceDisplayNames: [String: String] {
        var names = Dictionary(plugins.installed.map { ($0.id, plugins.displayName($0)) }, uniquingKeysWith: { a, _ in a })
        if demo, names[initialSourceID] == nil { names[initialSourceID] = initialSource.displayName }
        return names
    }
    private var sourceTabs: [SourceTab] {
        var tabs = plugins.installed.compactMap { plugin in
            plugins.sources[plugin.id] == nil ? nil : SourceTab(id: plugin.id, name: plugins.displayName(plugin))
        }
        if demo, plugins.sources[initialSourceID] == nil {
            tabs.insert(SourceTab(id: initialSourceID, name: initialSource.displayName), at: 0)
        }
        return tabs
    }
    private func switchSource(_ id: String) {
        guard id != selectedSourceID else { return }
        platformData.cancelProfileSync()
        playback.didSwitchSource(to: id, pauseOtherSources: preferences.values.pauseOtherSourcesOnSwitch)
        showingPlayer = false; showingQueue = false; commentTrack = nil
        inputFocused = false
        search.cancel(); request = nil
        selectedSourceID = id
    }
    private func activateSource() async {
        let id = selectedSourceID
        let chosen: any MusicSource
        if let plugin = plugins.sources[id] { chosen = plugin }
        else if id == initialSourceID, demo { chosen = initialSource }
        else if let fallback = plugins.sources.keys.sorted().first { switchSource(fallback); return }
        else if demo { switchSource(initialSourceID); return }
        else if !id.isEmpty { switchSource(""); return }
        else { chosen = DisabledMusicSource() }
        account.cancelSignIn()
        let accountSource: (any AccountSource)?
        if let plugin = chosen as? PluginSource {
            accountSource = (try? await plugin.supports("signIn")) == true ? plugin : nil
        } else {
            accountSource = chosen as? any AccountSource
        }
        guard id == selectedSourceID, !Task.isCancelled else { return }
        trackSearch.cancel()
        source = chosen; path = []; searchPath = []; request = nil
        await rebuildSearch()
        account = AccountModel(source: accountSource)
        accountPlatformID = id
        await account.restore()
    }

    private var verifiedProfileAccount: SourceAccount? {
        guard selectedSourceID == accountPlatformID, !account.isSigningIn, !account.isSigningOut else { return nil }
        return account.account
    }

    private func chooseMode(_ mode: PlaybackMode) {
        Task { await video.choose(mode) }
    }
    private func minimizeVideo() {
        if preferences.values.inAppVideoWindow { video.minimize() } else { video.browse() }
    }
    private func showVideo() {
        if selectedTab == .search { if searchPath.last != .video { searchPath.append(.video) } }
        else { selectedTab = .discover; if path.last != .video { path.append(.video) } }
    }
    private func select(_ tracks: [Track], _ index: Int) { playback.select(tracks, index: index, autoplay: !demo) }
    private func trackMenu(_ track: Track) -> AnyView {
        AnyView(TrackContextMenu(track: track, playback: playback, downloads: downloads,
            openCreator: source is any LibrarySource && track.sourceID == selectedSourceID ? { id in
                if selectedTab == .search { searchPath.append(.library(.creator(id))) }
                else { path.append(.library(.creator(id))) }
            } : nil))
    }
    private var searchTargetName: String {
        if searchTarget == "all" { return AppLocalization.text("全平台搜索") }
        return sourceTabs.first { $0.id == (searchTarget ?? selectedSourceID) }?.name ?? source.displayName
    }
    private func chooseSearchTarget(_ id: String) {
        showingSearchPlatforms = false
        search.cancel(); request = nil; searchTarget = id
        Task { await rebuildSearch(); if !input.isEmpty { submit() } }
    }
    private func rebuildSearch() async {
        let target = searchTarget ?? selectedSourceID
        var providers: [SearchProvider] = []
        for tab in sourceTabs where target == "all" || target == tab.id {
            let provider: any MusicSource
            if let plugin = plugins.sources[tab.id] { provider = plugin }
            else if demo, tab.id == initialSourceID { provider = initialSource }
            else { continue }
            providers.append(SearchProvider(id: tab.id, name: tab.name, source: provider))
            if let plugin = provider as? PluginSource, (try? await plugin.supports("searchVideos")) == true {
                providers.append(SearchProvider(videoSource: plugin, name: tab.name))
            }
        }
        guard !Task.isCancelled, target == (searchTarget ?? selectedSourceID) else { return }
        trackSearch.cancel()
        trackSearch = SearchModel(source: UnifiedSearchSource(providers: providers))
    }
    private func selectSearch(_ tracks: [Track], _ index: Int) {
        if tracks[index].resourceURL != nil { resourceDetail = tracks[index]; return }
        Task {
            await video.choose(tracks[index].resourceType == "video" ? .video : .music)
            select(tracks, index)
        }
    }
    private func submit() {
        inputFocused = false; video.browse(); searchPath = []
        if demo {
            let term = input.trimmingCharacters(in: .whitespacesAndNewlines)
            if !term.isEmpty { preferences.values.searchHistory = Array(([term] + preferences.values.searchHistory.filter { $0 != term }).prefix(20)) }
        } else { preferences.recordSearch(input) }
        request = SearchRequest(model: search, query: input)
    }
}

private struct SearchRequest {
    let id = UUID()
    let model: SearchModel
    let query: String
    var more = false
}
