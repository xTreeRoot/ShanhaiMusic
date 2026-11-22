import CoreImage.CIFilterBuiltins
import MBFoundation
import SwiftUI

struct AccountLibraryView: View {
    @Environment(\.sourceNames) private var sourceNames
    private var platformName: String { plugin.flatMap { sourceNames[$0.manifest.id] } ?? account.displayName }
    let account: AccountModel
    var plugin: PluginSource?
    let source: (any LibrarySource)?
    let playback: PlaybackModel
    let downloads: DownloadModel
    let select: ([Track], Int) -> Void
    let openSettings: () -> Void
    let openPlugins: () -> Void
    let openService: () -> Void
    @State private var showingSignIn = false
    @State private var path: [LibraryRoute] = []

    var body: some View {
        NavigationStack(path: $path) {
            List {
                Group {
                    Section {
                        HStack(spacing: 16) {
                            PersonArtwork(name: account.account?.name ?? "M", url: account.account?.avatarURL, headers: account.account?.avatarHeaders ?? [:], sourceID: account.displayName).frame(width: 64, height: 64)
                            VStack(alignment: .leading, spacing: 6) {
                                Text(account.account?.name ?? AppLocalization.text("我的音乐")).font(.title2.bold())
                                Text(platformName).font(.subheadline).foregroundStyle(.secondary)
                            }
                            Spacer(minLength: 0)
                        }.padding(.vertical, 18)
                        if account.account == nil, account.available {
                            Button("登录\(platformName)", systemImage: "qrcode.viewfinder") { showingSignIn = true; account.signIn() }
                                .buttonStyle(.borderedProminent).frame(minHeight: 44)
                        }
                        if let user = account.account, source != nil { NavigationLink("我的投稿", value: LibraryRoute.creator(user.id)) }
                        if let message = account.errorMessage { Text(LocalizedStringKey(message)).font(.caption).foregroundStyle(.red) }
                    }.listRowSeparator(.hidden)
                    if let plugin {
                        Section("我的内容") { PluginPersonalLinks(source: plugin, account: account.account, select: select, menu: { AnyView(TrackContextMenu(track: $0, playback: playback, downloads: downloads)) }) }
                    }
                    if source != nil {
                        Section("我的内容") {
                            NavigationLink(value: LibraryRoute.favorites) { Label("收藏夹", systemImage: "folder.fill") }
                            NavigationLink(value: LibraryRoute.liked) { Label("点赞的视频", systemImage: "heart.fill") }
                            NavigationLink(value: LibraryRoute.history) { Label("观看历史", systemImage: "clock.fill") }
                            NavigationLink(value: LibraryRoute.following) { Label("我的关注", systemImage: "person.2.fill") }
                        }
                    }
                    Section {
                        Button("设置", systemImage: "gearshape") { openSettings() }
                        Button("插件", systemImage: "puzzlepiece.extension") { openPlugins() }
                        Button("山海服务", systemImage: "externaldrive.connected.to.line.below") { openService() }
                            .accessibilityIdentifier("open-shanhai-service")
                    }
                    if account.account != nil {
                        Section {
                            HStack {
                                Button("退出登录", role: .destructive) { Task { await account.signOut() } }
                                if account.canClearLoginCache {
                                    Spacer(minLength: 12)
                                    Button("退出并清理缓存", role: .destructive) { Task { await account.signOut(clearCache: true) } }
                                }
                            }.buttonStyle(.plain).frame(minHeight: 44).disabled(account.isSigningOut)
                        }
                    }

                }.modifier(SkinRow())
            }
            .modifier(MusicGroupedList())
            // 导航容器外的迷你播放器不计入 List 的滚动内容边距。
            .contentMargins(.bottom, playback.queue.current == nil ? 0 : 76, for: .scrollContent)
            .navigationTitle("")
            .modifier(CompactRootNavigation())
            .toolbar { ToolbarItem(placement: .primaryAction) { Button("设置", systemImage: "gearshape") { openSettings() }.labelStyle(.iconOnly).accessibilityIdentifier("open-settings") } }
            .navigationDestination(for: LibraryRoute.self) { route in
                if let source {
                    LibraryPageView(source: source, route: route, select: select, menu: { track in
                        AnyView(TrackContextMenu(track: track, playback: playback, downloads: downloads, openCreator: { path.append(.creator($0)) }))
                    }).id(account.revision)
                }
            }
            .refreshable { await account.restore() }
        }
        .onChange(of: ObjectIdentifier(account)) { _, _ in showingSignIn = false; path = [] }
        .sheet(isPresented: $showingSignIn, onDismiss: { account.cancelSignIn() }) { SignInView(model: account) }
    }
}

private struct SignInView: View {
    let model: AccountModel
    @Environment(\.pluginUI) private var pluginUI
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                if model.choosingSignInMethod {
                    ForEach(model.signInMethods) { method in
                        Button(method.label) { model.chooseSignInMethod(method.id) }
                            .buttonStyle(.borderedProminent).frame(minHeight: 44)
                            .accessibilityIdentifier("sign-in-method-" + method.id)
                    }
                } else if let prompt = model.prompt {
                    if let url = prompt.qrURL, let image = qrImage(url.absoluteString) {
                        image.interpolation(.none).resizable().scaledToFit().frame(maxWidth: 270).padding(16).background(.white)
                            .accessibilityLabel("登录二维码")
                        ShareLink(item: image, preview: SharePreview("登录二维码", image: image)) {
                            Label("保存或分享二维码", systemImage: "square.and.arrow.up")
                        }
                    } else if model.isSigningIn { ProgressView() }
                    Text(LocalizedStringKey(prompt.message)).multilineTextAlignment(.center)
                } else if let request = pluginUI?.oauthBrowser {
                    Image(systemName: "safari").font(.system(size: 42)).foregroundStyle(.secondary)
                    Text("请在系统浏览器完成授权，完成后会返回山海").multilineTextAlignment(.center)
                    Button("打开浏览器") { Task { await pluginUI?.reopenOAuthBrowser(request.id) } }.buttonStyle(.plain)
                } else if model.isSigningIn { ProgressView("正在准备登录") }
                if let error = model.errorMessage {
                    Text(LocalizedStringKey(error)).foregroundStyle(.red)
                    Button("重新登录") { model.signIn() }.buttonStyle(.borderedProminent)
                }
            }
            .padding(28).frame(maxWidth: .infinity, maxHeight: .infinity)
            .navigationTitle("登录")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { model.cancelSignIn(); dismiss() } } }
        }
        .modifier(PluginSignInPresentation(service: pluginUI))
        .onChange(of: model.account) { _, value in if value != nil && !model.isSigningIn { dismiss() } }
    }
    private func qrImage(_ text: String) -> Image? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8); filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 10, y: 10)),
              let cgImage = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return Image(decorative: cgImage, scale: 1)
    }
}

struct LibraryPageView: View {
    @State private var model: LibraryModel
    let select: ([Track], Int) -> Void
    let menu: (Track) -> AnyView
    @State private var artworkPrefetch: Task<Void, Never>?
    init(source: any LibrarySource, route: LibraryRoute, select: @escaping ([Track], Int) -> Void, menu: @escaping (Track) -> AnyView = { _ in AnyView(EmptyView()) }) {
        _model = State(initialValue: LibraryModel(source: source, route: route)); self.select = select
        self.menu = menu
    }
    var body: some View {
        List {
            Group {
                ForEach(model.links) { link in
                    NavigationLink(value: link.route) {
                        HStack {
                            if let url = link.imageURL {
                                ArtworkView(track: Track(sourceID: model.sourceID, sourceTrackID: link.id, title: link.title, artist: "", artworkURL: url, durationMilliseconds: 0))
                            } else { Image(systemName: link.route.isCreator ? "person.crop.circle" : "folder").font(.title2).frame(width: 40) }
                            VStack(alignment: .leading, spacing: 4) {
                                Text(link.title)
                                if !link.subtitle.isEmpty { Text(link.subtitle).font(.caption).foregroundStyle(.secondary) }
                            }
                        }
                    }
                }
                if !model.tracks.isEmpty {
                    Button { select(model.tracks, 0) } label: { MusicActionLabel(title: "播放全部", symbol: "play.fill") }.buttonStyle(.borderedProminent)
                }
                ForEach(Array(model.tracks.enumerated()), id: \.element.id) { index, track in
                    HStack(spacing: 4) {
                        Button { select(model.tracks, index) } label: { TrackRow(track: track) }.buttonStyle(.plain).accessibilityLabel("播放 \(track.title)")
                            .modifier(TrackProblemAccessibility(track: track))
                        Menu { menu(track) } label: { Image(systemName: "ellipsis").frame(width: 44, height: 44) }.accessibilityLabel("曲目操作 \(track.title)")
                    }.contextMenu { menu(track) }
                }
                if let error = model.errorMessage {
                    Text(LocalizedStringKey(error)).foregroundStyle(.secondary)
                    Button("重试") { Task { await model.retry() } }
                }
                if model.isLoading { ProgressView().frame(maxWidth: .infinity) }
                else if model.tracks.isEmpty && model.links.isEmpty && model.errorMessage == nil {
                    ContentUnavailableView("暂无内容", systemImage: "tray")
                }
                if let cursor = model.nextCursor {
                    PaginationFooter(page: "\(model.paginationGeneration):\(cursor)", loading: model.isLoading, blocked: model.errorMessage != nil) {
                        await model.loadMore(); prefetchArtwork()
                    }
                }

            }.modifier(SkinRow())
        }
        .listStyle(.plain).modifier(SkinSurface(defaultColor: MBTheme.background, list: true))
        .modifier(PaginationViewport())
        .navigationTitle(Text(LocalizedStringKey(model.title)))
        .task { await model.restore(); prefetchArtwork() }
        .onDisappear { artworkPrefetch?.cancel() }
        .refreshable { await model.refresh(); prefetchArtwork() }
    }
    private func prefetchArtwork() {
        artworkPrefetch?.cancel()
        let images = model.tracks + model.links.map { link in
            Track(sourceID: model.sourceID, sourceTrackID: link.id, title: link.title, artist: "", artworkURL: link.imageURL, durationMilliseconds: 0)
        }
        artworkPrefetch = Task { await ArtworkCache.shared.prefetch(images) }
    }
}

private extension LibraryRoute {
    var isCreator: Bool { if case .creator = self { true } else { false } }
}
