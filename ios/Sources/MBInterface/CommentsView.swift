import MBFoundation
import SwiftUI

struct CommentsView: View {
    @State private var model: InteractionModel
    private let track: Track
    @Environment(\.dismiss) private var dismiss
    init(source: any InteractionSource, track: Track) { self.track = track; _model = State(initialValue: InteractionModel(source: source, track: track)) }
    var body: some View {
        NavigationStack {
            TrackCommentsList(model: model, track: track)
                .navigationTitle("评论与点赞")
                #if os(iOS)
                .navigationBarTitleDisplayMode(.inline)
                #endif
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
                .task { await model.refresh(); await model.loadLike() }
        }
    }
}

struct TrackLikeButton: View {
    let model: InteractionModel
    var body: some View {
        if model.capabilities.likes {
            Button { Task { await model.toggleLike() } } label: {
                HStack {
                    Label(LocalizedStringKey(model.liked ? "已点赞" : "点赞视频"), systemImage: model.liked ? "hand.thumbsup.fill" : "hand.thumbsup")
                    Spacer()
                    if model.isChangingLike { ProgressView() }
                }.frame(minHeight: 44)
            }.disabled(model.isChangingLike)
            if let error = model.likeError { Text(LocalizedStringKey(error)).font(.caption).foregroundStyle(.red) }
        }
    }
}

struct TrackCommentsList: View {
    let model: InteractionModel
    var track: Track? = nil
    var blendsWithPlayer = false
    @State private var draft = ""
    @FocusState private var writing: Bool

    var body: some View {
            List {
                if let track {
                    Section {
                        TrackRow(track: track)
                        TrackLikeButton(model: model)
                    }.listRowBackground(blendsWithPlayer ? Color.clear : nil)
                }
                if model.capabilities.comments {
                    Section {
                        ForEach(model.comments) { comment in
                            HStack(alignment: .top, spacing: 12) {
                                PersonArtwork(name: comment.author, url: comment.avatarURL).frame(width: 34, height: 34)
                                VStack(alignment: .leading, spacing: 8) {
                                    HStack(alignment: .top) {
                                        VStack(alignment: .leading, spacing: 3) {
                                            Text(comment.author).font(.subheadline.weight(.semibold))
                                            Text(comment.date, style: .date).font(.caption2).foregroundStyle(.secondary)
                                        }
                                        Spacer(minLength: 6)
                                        if model.commentLikes {
                                            Button { Task { await model.toggleCommentLike(comment) } } label: {
                                                Label("\(comment.likes)", systemImage: comment.liked ? "hand.thumbsup.fill" : "hand.thumbsup").font(.caption).frame(minWidth: 44, minHeight: 44)
                                            }.buttonStyle(.borderless).disabled(model.changingComments.contains(comment.id)).accessibilityLabel("点赞评论 \(comment.author)")
                                        } else { Text("\(comment.likes) 赞").font(.caption).foregroundStyle(.secondary) }
                                    }
                                    Text(comment.text).font(.body).lineSpacing(4).textSelection(.enabled)
                                }
                            }.padding(.vertical, 10).listRowSeparator(.hidden).listRowBackground(blendsWithPlayer ? Color.clear : nil)
                        }
                        if model.isLoading { ProgressView().frame(maxWidth: .infinity).padding().listRowBackground(blendsWithPlayer ? Color.clear : nil) }
                        else if model.comments.isEmpty && model.errorMessage == nil { ContentUnavailableView("暂无评论", systemImage: "bubble.left.and.bubble.right").listRowBackground(blendsWithPlayer ? Color.clear : nil) }
                        if model.hasMore { PaginationFooter(page: "\(model.paginationGeneration):\(model.nextPage)", loading: model.isLoading, blocked: model.errorMessage != nil || model.isPosting) { await model.loadMore() }.listRowBackground(blendsWithPlayer ? Color.clear : nil) }
                    } header: {
                        if track != nil { Text("评论 · \(model.total)") }
                    }.listRowBackground(blendsWithPlayer ? Color.clear : nil)
                }
                if let message = model.errorMessage {
                    Text(LocalizedStringKey(message)).font(.subheadline).foregroundStyle(.red).listRowBackground(blendsWithPlayer ? Color.clear : nil)
                    Button("重试加载评论") { Task { if model.hasMore { await model.loadMore() } else { await model.refresh() } } }.frame(minHeight: 44).listRowBackground(blendsWithPlayer ? Color.clear : nil)
                }
            }.scrollContentBackground(blendsWithPlayer ? .hidden : .automatic).listStyle(.plain).modifier(PaginationViewport()).scrollDismissesKeyboard(.interactively)
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if model.capabilities.posting {
                    HStack(alignment: .bottom, spacing: 12) {
                        TextField("发送评论，需要登录", text: $draft, axis: .vertical).lineLimit(1...5).focused($writing)
                            .padding(12).background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 20)).disabled(model.isPosting)
                        Button {
                            let text = draft
                            Task { if await model.post(text) { draft = ""; writing = false } }
                        } label: {
                            Group { if model.isPosting { ProgressView() } else { Image(systemName: "arrow.up").font(.headline) } }.frame(width: 44, height: 44)
                        }.buttonStyle(.borderedProminent).clipShape(Circle()).accessibilityLabel("发送")
                            .disabled(model.isPosting || model.isLoading || draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }.padding(.horizontal, 16).padding(.vertical, 10).background(.bar)
                }
            }
            .refreshable { await model.refresh() }
    }
}

struct PersonArtwork: View {
    @Environment(\.skinAccent) private var skinAccent
    let name: String
    let url: URL?
    var headers: [String: String] = [:]
    var sourceID = "avatar"
    private func artwork(_ url: URL) -> Track {
        var track = Track(sourceID: sourceID, sourceTrackID: url.absoluteString, title: name, artist: "", artworkURL: url, durationMilliseconds: 0)
        track.artworkHeaders = headers; return track
    }
    var body: some View {
        Group {
            if let url { RemoteArtwork(track: artwork(url)) }
            else { Circle().fill(skinAccent.opacity(0.1)).overlay { Text(String(name.prefix(1))).font(.subheadline.weight(.semibold)).foregroundStyle(skinAccent) } }
        }.clipShape(Circle()).accessibilityHidden(true)
    }
}
