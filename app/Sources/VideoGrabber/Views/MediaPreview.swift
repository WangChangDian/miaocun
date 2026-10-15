import SwiftUI
import AppKit
import AVFoundation
import AVKit
import PDFKit

/// 下载结果的文件类型：决定卡片里用什么方式展示（缩略图 / 图标 / 普通行）
enum MediaFileKind {
    case image, video, audio, pdf, other

    static func of(_ path: String) -> MediaFileKind {
        switch (path as NSString).pathExtension.lowercased() {
        case "jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "avif", "bmp":
            return .image
        case "mp4", "mov", "mkv", "webm", "m4v", "3gp", "avi", "ts":
            return .video
        case "mp3", "m4a", "aac", "flac", "wav", "opus", "ogg":
            return .audio
        case "pdf":
            return .pdf
        default:
            return .other
        }
    }

    var canPreview: Bool { self != .other }
}

/// 缩略图 + 单个文件大小（与安卓端一致：图片/视频/PDF 出缩略图，音频出音符图标）
enum MediaThumbs {
    private static let cache = NSCache<NSString, NSImage>()

    static func sizeBytes(_ path: String) -> Int64 {
        (try? FileManager.default.attributesOfItem(atPath: path)[.size] as? Int64) ?? 0
    }

    static func sizeText(_ path: String) -> String {
        ByteCountFormatter.string(fromByteCount: sizeBytes(path), countStyle: .file)
    }

    static func thumbnail(for path: String, kind: MediaFileKind) -> NSImage? {
        if !kind.canPreview { return nil }
        let key = "\(kind)-\(path)" as NSString
        if let hit = cache.object(forKey: key) { return hit }
        let image = load(path: path, kind: kind)
        if let image { cache.setObject(image, forKey: key) }
        return image
    }

    private static func load(path: String, kind: MediaFileKind) -> NSImage? {
        switch kind {
        case .image:
            guard let image = NSImage(contentsOfFile: path) else { return nil }
            return resized(image, maxPx: 360)
        case .video:
            return videoFrame(path: path)
        case .pdf:
            guard let document = PDFDocument(url: URL(fileURLWithPath: path)),
                  let page = document.page(at: 0) else { return nil }
            let bounds = page.bounds(for: .cropBox)
            let width: CGFloat = 200
            let height = width * max(bounds.height, 1) / max(bounds.width, 1)
            return page.thumbnail(of: NSSize(width: width, height: height), for: .cropBox)
        default:
            return nil
        }
    }

    private static func videoFrame(path: String) -> NSImage? {
        let asset = AVURLAsset(url: URL(fileURLWithPath: path))
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: 360, height: 360)
        // 取 0.5 秒处的帧：第一帧常常是黑屏
        let time = CMTime(seconds: 0.5, preferredTimescale: 600)
        guard let cgImage = try? generator.copyCGImage(at: time, actualTime: nil) else { return nil }
        return NSImage(cgImage: cgImage, size: NSSize(width: cgImage.width, height: cgImage.height))
    }

    private static func resized(_ image: NSImage, maxPx: CGFloat) -> NSImage {
        let size = image.size
        guard size.width > 0, size.height > 0 else { return image }
        let scale = min(1, maxPx / max(size.width, size.height))
        if scale >= 1 { return image }
        let target = NSSize(width: size.width * scale, height: size.height * scale)
        let output = NSImage(size: target)
        output.lockFocus()
        image.draw(in: NSRect(origin: .zero, size: target),
                   from: NSRect(origin: .zero, size: size),
                   operation: .copy,
                   fraction: 1)
        output.unlockFocus()
        return output
    }
}

/// 卡片里的缩略图条：点缩略图放大预览（图片可翻页、视频/音频即点即播、PDF 直接翻看）
struct FileThumbStrip: View {
    let files: [String]
    var onPreview: (Int) -> Void
    var onShare: (String) -> Void
    /// 传 nil 时不显示删除按钮（如「刚下载完」的临时任务卡片；历史记录卡片会传）
    var onDelete: ((String) -> Void)? = nil

    var body: some View {
        ScrollView(.horizontal, showsIndicators: files.count > 3) {
            HStack(alignment: .top, spacing: 10) {
                ForEach(Array(files.enumerated()), id: \.offset) { index, file in
                    if let onDelete {
                        ThumbTile(path: file, index: index) {
                            onPreview(index)
                        } onShare: {
                            onShare(file)
                        } onDelete: {
                            onDelete(file)
                        }
                    } else {
                        ThumbTile(path: file, index: index) {
                            onPreview(index)
                        } onShare: {
                            onShare(file)
                        }
                    }
                }
            }
            .padding(.vertical, 2)
        }
    }
}

private struct ThumbTile: View {
    let path: String
    let index: Int
    var onOpen: () -> Void
    var onShare: () -> Void
    var onDelete: (() -> Void)?

    @State private var thumbnail: NSImage?
    @State private var loading = false

    private var kind: MediaFileKind { MediaFileKind.of(path) }

    /// 文件格式 · 大小（如 MP4 · 226 KB）
    private var formatSizeText: String {
        let ext = (path as NSString).pathExtension.uppercased()
        let size = MediaThumbs.sizeText(path)
        return ext.isEmpty ? size : "\(ext) · \(size)"
    }

    private func tileIcon(
        _ name: String,
        help: String,
        tint: Color = .secondary,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: name)
                .font(.system(size: 11))
                .foregroundStyle(tint)
                .frame(width: 20, height: 20)
                .contentShape(Rectangle())
        }
        .buttonStyle(.borderless)
        .help(help)
    }

    var body: some View {
        VStack(spacing: 3) {
            ZStack {
                RoundedRectangle(cornerRadius: 10)
                    .fill(Color.secondary.opacity(0.14))
                if let thumbnail {
                    Image(nsImage: thumbnail)
                        .resizable()
                        .aspectRatio(contentMode: .fill)
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                } else {
                    switch kind {
                    case .audio:
                        Image(systemName: "music.note")
                            .font(.system(size: 26))
                            .foregroundStyle(.secondary)
                    case .pdf:
                        Text("PDF").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                    case .other:
                        Image(systemName: "doc")
                            .font(.system(size: 24))
                            .foregroundStyle(.secondary)
                    default:
                        Image(systemName: "photo")
                            .font(.system(size: 24))
                            .foregroundStyle(.secondary)
                    }
                }
                if kind == .video || kind == .audio {
                    // 播放角标：点一下直接播放，不再弹「选择应用」
                    Circle()
                        .fill(Color.black.opacity(0.45))
                        .frame(width: 30, height: 30)
                        .overlay(
                            Image(systemName: "play.fill")
                                .font(.system(size: 13))
                                .foregroundStyle(.white)
                                .padding(.leading, 2)
                        )
                }
            }
            .frame(width: 86, height: 86)
            // 裁剪：fill 模式下竖版视频会撑出 86×86 的框，不裁剪就会盖住下面的「格式 · 大小」和按钮
            .clipShape(RoundedRectangle(cornerRadius: 10))
            // 细边框：缩略图边界更清楚（与安卓端一致）
            .overlay(
                RoundedRectangle(cornerRadius: 10)
                    .stroke(Color.secondary.opacity(0.35), lineWidth: 0.8)
            )
            .contentShape(Rectangle())
            .onTapGesture { onOpen() }
            .contextMenu {
                Button("打开") { NSWorkspace.shared.open(URL(fileURLWithPath: path)) }
                Button("在访达中显示") {
                    NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: path)])
                }
                Button("分享") { onShare() }
            }
            .help((path as NSString).lastPathComponent)

            // 文件格式 · 大小（如 MP4 · 226 KB）
            Text(formatSizeText)
                .font(.system(size: 10))
                .foregroundStyle(.secondary)
                .lineLimit(1)

            // 打开 / 分享 / 删除（图标按钮，居中；删除在文件的下面）
            HStack(spacing: 6) {
                tileIcon("arrow.up.forward.square", help: "打开") {
                    NSWorkspace.shared.open(URL(fileURLWithPath: path))
                }
                tileIcon("square.and.arrow.up", help: "分享") { onShare() }
                if let onDelete {
                    tileIcon("trash", help: "删除这个文件", tint: .red) { onDelete() }
                }
            }
            .padding(.top, 1)
        }
        .frame(width: 86)
        .task(id: path) {
            if thumbnail != nil || loading { return }
            loading = true
            let target = path
            let kind = self.kind
            let image = await Task.detached(priority: .utility) {
                MediaThumbs.thumbnail(for: target, kind: kind)
            }.value
            thumbnail = image
            loading = false
        }
    }
}

/// 预览请求：点缩略图时带上「这一组文件 + 从第几个开始」
struct PreviewRequest: Identifiable {
    let id = UUID()
    let files: [String]
    let index: Int
}

/// 放大预览窗口：图片可缩放/翻页，视频与音频进来即播，PDF 直接翻看
struct MediaPreviewSheet: View {
    let files: [String]
    @State private var index: Int
    @Environment(\.dismiss) private var dismiss

    init(files: [String], index: Int) {
        self.files = files
        _index = State(initialValue: index)
    }

    private var currentPath: String { files.indices.contains(index) ? files[index] : "" }
    private var kind: MediaFileKind { MediaFileKind.of(currentPath) }
    private var imageIndexes: [Int] {
        files.indices.filter { MediaFileKind.of(files[$0]) == .image }
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                Text((currentPath as NSString).lastPathComponent)
                    .font(.headline)
                    .lineLimit(1)
                Text(MediaThumbs.sizeText(currentPath))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
                if !currentPath.isEmpty {
                    Button("在访达中显示") {
                        NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: currentPath)])
                    }
                    Button("打开") { NSWorkspace.shared.open(URL(fileURLWithPath: currentPath)) }
                }
                Button("关闭") { dismiss() }
                    .keyboardShortcut(.cancelAction)
            }
            .padding(12)
            Divider()
            content
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Color.black.opacity(0.92))
        }
        .frame(minWidth: 760, minHeight: 560)
    }

    @ViewBuilder
    private var content: some View {
        switch kind {
        case .image:
            ZStack {
                if let image = NSImage(contentsOfFile: currentPath) {
                    ZoomableImage(image: image)
                        .padding(12)
                } else {
                    Text("无法打开这张图片").foregroundStyle(.white)
                }
                if imageIndexes.count > 1 {
                    HStack {
                        Button { step(-1) } label: { Image(systemName: "chevron.left.circle.fill").font(.system(size: 30)) }
                            .buttonStyle(.plain)
                            .foregroundStyle(.white.opacity(0.8))
                        Spacer()
                        Button { step(1) } label: { Image(systemName: "chevron.right.circle.fill").font(.system(size: 30)) }
                            .buttonStyle(.plain)
                            .foregroundStyle(.white.opacity(0.8))
                    }
                    .padding(.horizontal, 16)
                    Text("\((imageIndexes.firstIndex(of: index) ?? 0) + 1)/\(imageIndexes.count)")
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.8))
                        .padding(8)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
                }
            }
        case .video, .audio:
            PlayerView(path: currentPath)
        case .pdf:
            PDFKitView(path: currentPath)
        default:
            VStack(spacing: 10) {
                Image(systemName: "doc").font(.system(size: 40)).foregroundStyle(.white)
                Text("这类文件没有内置预览，点顶部「打开」用系统应用查看").foregroundStyle(.white.opacity(0.8))
            }
        }
    }

    private func step(_ delta: Int) {
        guard let position = imageIndexes.firstIndex(of: index) else { return }
        let next = (position + delta + imageIndexes.count) % imageIndexes.count
        index = imageIndexes[next]
    }
}

/// 可双指缩放 / 拖动的图片
private struct ZoomableImage: View {
    let image: NSImage
    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1

    var body: some View {
        Image(nsImage: image)
            .resizable()
            .aspectRatio(contentMode: .fit)
            .scaleEffect(scale)
            .gesture(
                MagnificationGesture()
                    .onChanged { value in
                        scale = min(max(lastScale * value, 1), 6)
                    }
                    .onEnded { _ in
                        lastScale = scale
                        if scale < 1.02 { scale = 1; lastScale = 1 }
                    }
            )
            .onTapGesture(count: 2) {
                scale = scale > 1 ? 1 : 2.5
                lastScale = scale
            }
    }
}

/// 视频 / 音频播放（AVPlayerView，进来即播）
private struct PlayerView: NSViewRepresentable {
    let path: String

    func makeNSView(context: Context) -> AVPlayerView {
        let view = AVPlayerView()
        view.controlsStyle = .floating
        let player = AVPlayer(url: URL(fileURLWithPath: path))
        view.player = player
        player.play()
        return view
    }

    func updateNSView(_ view: AVPlayerView, context: Context) {
        if (view.player?.currentItem?.asset as? AVURLAsset)?.url.path != path {
            let player = AVPlayer(url: URL(fileURLWithPath: path))
            view.player = player
            player.play()
        }
    }

    static func dismantleNSView(_ view: AVPlayerView, coordinator: ()) {
        view.player?.pause()
        view.player = nil
    }
}

/// PDF 预览（PDFKit 自带翻页 / 缩放）
private struct PDFKitView: NSViewRepresentable {
    let path: String

    func makeNSView(context: Context) -> PDFView {
        let view = PDFView()
        view.autoScales = true
        view.displayMode = .singlePageContinuous
        view.document = PDFDocument(url: URL(fileURLWithPath: path))
        return view
    }

    func updateNSView(_ view: PDFView, context: Context) {
        if view.document?.documentURL?.path != path {
            view.document = PDFDocument(url: URL(fileURLWithPath: path))
        }
    }
}
