import SwiftUI

struct MainView: View {
    @EnvironmentObject private var state: AppState

    @State private var showClearHistoryDialog = false
    @State private var preview: PreviewRequest?

    var body: some View {
        ZStack {
            // 与安卓端一致的浅色渐变背景
            LinearGradient(colors: state.palette.background, startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
            VStack(alignment: .leading, spacing: 16) {
                header
                if state.showingSettings {
                    SettingsView()
                        .transition(.opacity.combined(with: .move(edge: .trailing)))
                } else if !state.environmentInstalled {
                    SetupView()
                        .transition(.opacity)
                } else {
                    inputSection
                    if let batch = state.batch {
                        BatchCard(batch: batch)
                    } else if let task = state.currentTask {
                        TaskCard(task: task)
                    }
                    historySection
                }
                Spacer(minLength: 0)
            }
            .padding(20)
            .animation(.easeInOut(duration: 0.22), value: state.showingSettings)
        }
        .confirmationDialog("清空下载记录", isPresented: $showClearHistoryDialog, titleVisibility: .visible) {
            Button("仅清空记录", role: nil) { state.clearHistory(deleteFiles: false) }
            Button("清空并删除文件", role: .destructive) { state.clearHistory(deleteFiles: true) }
            Button("取消", role: .cancel) { }
        } message: {
            Text("只清空列表，还是连下载好的文件一起删除？删除后无法恢复。")
        }
        .alert("提示", isPresented: Binding(
            get: { state.errorMessage != nil },
            set: { if !$0 { state.errorMessage = nil } }
        )) {
            Button("知道了", role: .cancel) { state.errorMessage = nil }
        } message: {
            Text(state.errorMessage ?? "")
        }
        .sheet(item: $preview) { request in
            MediaPreviewSheet(files: request.files, index: request.index)
        }
    }

    private var header: some View {
        HStack(spacing: 10) {
            Image(systemName: "arrow.down.circle.fill")
                .font(.system(size: 26))
                .foregroundStyle(state.palette.primary)
            VStack(alignment: .leading, spacing: 2) {
                Text("秒存")
                    .font(.title2.bold())
                Text("抖音 / 快手 / 小红书 / 视频号 / B 站 / 微博 / TikTok / X / YouTube · 粘贴即可下载")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            // 与 Windows 版对齐：设置 / 换色 / 检查更新 三个按钮；设置页在主窗口内切换
            Button(state.showingSettings ? "返回" : "设置") {
                withAnimation(.easeInOut(duration: 0.22)) {
                    state.showingSettings.toggle()
                }
            }
                .font(.caption)
                .help(state.showingSettings ? "回到首页" : "打开设置（⌘,）")
            Button {
                state.toggleTheme()
            } label: {
                Text(state.theme == "green" ? "换橙色" : "换绿色")
                    .font(.caption)
            }
            .help("切换主题配色（浅橙 / 浅绿）")
            Button("检查更新") { state.checkForUpdate() }
                .font(.caption)
                .help("检查是否有新版本")
            engineBadge
        }
    }

    private var engineBadge: some View {
        let ready = state.enginesReady
        return Label(
            ready ? "引擎就绪" : (state.bridgeRunning ? "引擎检查中" : "引擎未启动"),
            systemImage: ready ? "checkmark.seal.fill" : "exclamationmark.triangle"
        )
        .font(.caption)
        .foregroundStyle(ready ? .green : .orange)
        .help(state.engines.map { "\($0.label) \($0.version)" }.joined(separator: "\n"))
    }

    private var inputSection: some View {
        let detectedLinks = LinkParser.allLinks(in: state.urlText)
        return VStack(alignment: .leading, spacing: 8) {
            Text("作品链接或分享文本")
                .font(.subheadline.weight(.medium))
            TextEditor(text: $state.urlText)
                .font(.body)
                .frame(height: 84)
                .padding(6)
                .background(Color(nsColor: .textBackgroundColor))
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .overlay(
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(Color.secondary.opacity(0.25))
                )
            Text("可一次粘贴多条链接：换行 / 逗号 / 分号分隔均可，自动去重、自动识别平台。")
                .font(.caption2)
                .foregroundStyle(.secondary)
            Text("主页 / 收藏页链接请用「批量下载」；部分平台需先在 设置 → Cookie 管理 登录。单次最多 50 条。")
                .font(.caption2)
                .foregroundStyle(.secondary)
            if detectedLinks.count >= 2 {
                let summary = Dictionary(grouping: detectedLinks, by: { LinkParser.platformLabel(for: $0) })
                    .map { "\($0.key) \($0.value.count)" }
                    .sorted()
                    .joined(separator: " · ")
                Text("已识别 \(detectedLinks.count) 条：\(summary)")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(state.palette.primary)
            }
            HStack {
                Button {
                    state.pasteFromClipboard()
                } label: {
                    Label("粘贴", systemImage: "doc.on.clipboard")
                }
                Button {
                    state.downloadFromInput()
                } label: {
                    Label(
                        detectedLinks.count >= 2 ? "下载 \(detectedLinks.count) 条" : "下载",
                        systemImage: "arrow.down.to.line"
                    )
                }
                .keyboardShortcut(.return, modifiers: [.command])
                .buttonStyle(ThemeGradientButtonStyle(
                    gradient: state.palette.gradient,
                    enabled: !state.urlText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ))
                .disabled(state.urlText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)

                // 批量下载（主页 / 收藏夹）：引擎用「页面内 fetch 重放」抓列表
                // （页面自己的请求带平台签名，天然合法），需要先在 Cookie 管理里登录对应平台
                Button {
                    state.downloadBatch(limit: 20)
                } label: {
                    Label("批量下载", systemImage: "square.stack.3d.down.right")
                }
                .help("主页 / 收藏夹链接：抓取该主页最近的 20 条作品并逐条下载（需先在「设置 → Cookie 管理」登录对应平台）")
                .disabled(state.urlText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)

                if state.currentTask?.isRunning == true && state.batch == nil {
                    Button(role: .destructive) {
                        state.cancelCurrentTask()
                    } label: {
                        Label("取消", systemImage: "xmark.circle")
                    }
                }
                Spacer()
                Button {
                    state.openDownloadFolder()
                } label: {
                    Label("打开下载目录", systemImage: "folder")
                }
            }
        }
    }

    private var historySection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("下载记录")
                    .font(.subheadline.weight(.medium))
                Spacer()
                Button("清空") { showClearHistoryDialog = true }
                    .font(.caption)
                    .disabled(state.history.isEmpty)
                    .help("清空下载记录（可选择是否删除文件）")
                Button {
                    state.loadHistory()
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .buttonStyle(.borderless)
                .help("刷新历史记录")
            }
            if state.history.isEmpty {
                Text("还没有下载记录")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 12)
            } else {
                ScrollView {
                    // 卡片之间不再画分隔线（与安卓端一致，靠卡片本身的间距区分）
                    LazyVStack(alignment: .leading, spacing: 6) {
                        ForEach(Array(state.history.enumerated()), id: \.element.id) { index, item in
                            historyRow(item, index: index)
                        }
                    }
                }
                .frame(minHeight: 140, maxHeight: .infinity)
            }
        }
    }

    private func historyRow(_ item: HistoryItem, index: Int) -> some View {
        let files = item.files.isEmpty && !item.file.isEmpty ? [item.file] : item.files
        let primary = item.file.isEmpty ? (files.first ?? "") : item.file
        return VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Text(item.platformName)
                    .font(.caption2.weight(.semibold))
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(Color.accentColor.opacity(0.15))
                    .clipShape(Capsule())
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.displayName)
                        .font(.callout)
                        .lineLimit(1)
                    Text(item.mediaInfo.isEmpty ? item.finishedAt : "\(item.finishedAt) · \(item.mediaInfo)")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                // 打开 / 显示 / 分享 / 删除（图标按钮；删除在最后，无确认，连同源文件一起删）
                if !primary.isEmpty {
                    cardIconButton("arrow.up.forward.square", help: "打开") {
                        NSWorkspace.shared.open(URL(fileURLWithPath: primary))
                    }
                    cardIconButton("folder", help: "在访达中显示") { state.reveal(file: primary) }
                    cardIconButton("square.and.arrow.up", help: "分享") {
                        state.share(files: files.isEmpty ? [primary] : files)
                    }
                }
                cardIconButton("trash", help: "删除这条记录及其文件", tint: .red) {
                    state.deleteHistoryItem(at: index)
                }
            }
            // 缩略图：图片点开可放大、视频/音频点开即播放（与安卓端一致）
            if !files.isEmpty {
                FileThumbStrip(
                    files: files,
                    onPreview: { fileIndex in
                        preview = PreviewRequest(files: files, index: fileIndex)
                    },
                    onShare: { file in
                        state.share(files: [file])
                    },
                    onDelete: { file in
                        state.deleteHistoryFile(at: index, file: file)
                    }
                )
            }
        }
        .padding(.vertical, 4)
    }

}

/// 卡片里的图标操作按钮（打开 / 在访达中显示 / 分享 / 删除），主界面与任务卡片共用
private func cardIconButton(
    _ systemName: String,
    help: String,
    tint: Color = .secondary,
    action: @escaping () -> Void
) -> some View {
    Button(action: action) {
        Image(systemName: systemName)
            .font(.system(size: 13))
            .foregroundStyle(tint)
            .frame(width: 22, height: 22)
            .contentShape(Rectangle())
    }
    .buttonStyle(.borderless)
    .help(help)
}

/// 多链接批量卡片：汇总 + 按链接分组（默认折叠），组内复用文件缩略图
struct BatchCard: View {
    let batch: BatchDownloadState
    @EnvironmentObject private var state: AppState
    @State private var expanded: Set<UUID> = []
    @State private var preview: PreviewRequest?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("批量下载").font(.headline)
                Text("共 \(batch.items.count) 条").font(.caption).foregroundStyle(.secondary)
                Spacer()
                if batch.finished {
                    if !allFiles.isEmpty {
                        cardIconButton("square.and.arrow.up", help: "分享全部文件") {
                            state.share(files: allFiles)
                        }
                        cardIconButton("trash", help: "删除这批下载及其文件", tint: .red) {
                            state.deleteBatchAll()
                        }
                    }
                    Button("关闭") { state.closeBatch() }.font(.caption)
                } else {
                    Button(role: .destructive) { state.cancelBatch() } label: {
                        Text("取消")
                    }
                    .font(.caption)
                }
            }
            ProgressView(value: overallProgress).tint(state.palette.primary)
            HStack(spacing: 12) {
                Text("成功 \(batch.succeeded)")
                    .font(.caption).foregroundStyle(.green)
                if batch.failed > 0 {
                    Text("失败 \(batch.failed)").font(.caption).foregroundStyle(.red)
                }
                if batch.cancelled > 0 {
                    Text("已取消 \(batch.cancelled)").font(.caption).foregroundStyle(.secondary)
                }
                if batch.skipped > 0 {
                    Text("已跳过 \(batch.skipped)").font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                if batch.items.contains(where: { $0.status == .success && !$0.files.isEmpty }) {
                    Button(expanded.count >= batch.items.count ? "收起全部" : "展开全部") {
                        if expanded.count >= batch.items.count {
                            expanded.removeAll()
                        } else {
                            expanded = Set(batch.items.map { $0.id })
                        }
                    }
                    .font(.caption)
                }
            }
            ForEach(Array(batch.items.enumerated()), id: \.element.id) { _, item in
                row(item)
            }
        }
        .padding(12)
        .background(Color(nsColor: .controlBackgroundColor))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(Color.secondary.opacity(0.15))
        )
        .shadow(color: .black.opacity(0.06), radius: 5, y: 2)
        .sheet(item: $preview) { request in
            MediaPreviewSheet(files: request.files, index: request.index)
        }
    }

    private var overallProgress: Double {
        let activeStatus = batch.items.indices.contains(batch.activeIndex)
            ? batch.items[batch.activeIndex].status
            : nil
        let current = activeStatus == .downloading ? (state.currentTask?.percent ?? 0) / 100 : 0
        return min(1, (Double(batch.doneCount) + current) / Double(max(1, batch.items.count)))
    }

    private var allFiles: [String] {
        batch.items.flatMap { $0.files }.filter { !$0.isEmpty }
    }

    @ViewBuilder
    private func row(_ item: BatchLinkItemState) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Text(item.platform)
                    .font(.caption2.weight(.semibold))
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(Color.accentColor.opacity(0.15))
                    .clipShape(Capsule())
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.title.isEmpty ? String(item.url.suffix(48)) : item.title)
                        .font(.caption)
                        .lineLimit(1)
                    Text(statusText(item))
                        .font(.caption2)
                        .foregroundStyle(statusColor(item))
                        .lineLimit(2)
                }
                Spacer()
                if item.status == .success && !item.files.isEmpty {
                    Text("\(item.files.count) 个文件")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                if item.status == .failed {
                    Button("复制") { copyToPasteboard(item.url) }
                        .font(.caption2)
                        .buttonStyle(.borderless)
                }
                if !item.files.isEmpty {
                    Button {
                        if expanded.contains(item.id) { expanded.remove(item.id) }
                        else { expanded.insert(item.id) }
                    } label: {
                        Image(systemName: expanded.contains(item.id) ? "chevron.up" : "chevron.down")
                            .font(.system(size: 11))
                    }
                    .buttonStyle(.borderless)
                    .help(expanded.contains(item.id) ? "收起" : "展开文件")
                }
            }
            if expanded.contains(item.id) && !item.files.isEmpty {
                FileThumbStrip(
                    files: item.files,
                    onPreview: { fileIndex in
                        preview = PreviewRequest(files: item.files, index: fileIndex)
                    },
                    onShare: { file in state.share(files: [file]) },
                    onDelete: { file in state.deleteBatchFile(file) }
                )
            }
        }
        .padding(.vertical, 3)
    }

    private func statusText(_ item: BatchLinkItemState) -> String {
        var text: String
        switch item.status {
        case .waiting:
            text = "等待"
        case .parsing:
            text = "解析中…"
        case .queued:
            text = "等待下载"
        case .downloading:
            if let percent = state.currentTask?.percent {
                text = "下载中 \(Int(percent.rounded()))%"
            } else {
                text = "下载中…"
            }
        case .success:
            text = "成功"
        case .failed:
            text = "失败：\(item.message.isEmpty ? "未知错误" : item.message)"
        case .skipped:
            text = item.message.isEmpty ? "已跳过" : item.message
        case .cancelled:
            text = "已取消"
        }
        let needsLogin = item.status == .failed &&
            (item.message.contains("登录") || item.message.lowercased().contains("cookie"))
        return needsLogin ? text + " · 请到 设置 → Cookie 管理 登录后重试" : text
    }

    private func statusColor(_ item: BatchLinkItemState) -> Color {
        switch item.status {
        case .failed: return .red
        case .success: return .green
        default: return .secondary
        }
    }

    private func copyToPasteboard(_ text: String) {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
    }
}

struct TaskCard: View {
    let task: DownloadTaskState
    @EnvironmentObject private var state: AppState
    @State private var preview: PreviewRequest?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(task.label.isEmpty ? "任务" : task.label)
                    .font(.headline)
                Text(task.stage)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
                Text(task.progressText)
                    .font(.caption.monospacedDigit())
            }
            if task.isRunning {
                if let percent = task.percent {
                    ProgressView(value: percent, total: 100).tint(state.palette.primary)
                } else {
                    ProgressView()
                        .progressViewStyle(.linear)
                        .tint(state.palette.primary)
                }
            } else if task.stage == "已完成" {
                ProgressView(value: 100, total: 100).tint(.green)
            }
            if !task.message.isEmpty {
                Text(task.message)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
            if !task.mediaInfo.isEmpty {
                Label(task.mediaInfo, systemImage: "film")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if !task.isRunning {
                let allFiles = task.files.isEmpty ? (task.file.map { [$0] } ?? []) : task.files
                let previewable = allFiles.filter { MediaFileKind.of($0).canPreview }
                let others = allFiles.filter { !MediaFileKind.of($0).canPreview }
                if !previewable.isEmpty {
                    // 图片 / 视频 / 音频 / PDF：缩略图，点一下即放大 / 播放（与安卓端一致）
                    VStack(alignment: .leading, spacing: 6) {
                        Text(allFiles.count > 1 ? "共 \(allFiles.count) 个文件 · 点缩略图可放大 / 播放" : "点缩略图可放大 / 播放")
                            .font(.caption.weight(.semibold))
                        FileThumbStrip(
                            files: previewable,
                            onPreview: { index in
                                preview = PreviewRequest(files: previewable, index: index)
                            },
                            onShare: { file in
                                state.share(files: [file])
                            },
                            onDelete: { file in
                                state.deleteCurrentTaskFile(file)
                            }
                        )
                    }
                }
                if !others.isEmpty {
                    // 不能预览的（安装包 / 压缩包 / 文本等）：保留原来的行样式，补上文件大小
                    VStack(alignment: .leading, spacing: 2) {
                        ForEach(Array(others.enumerated()), id: \.offset) { index, file in
                            HStack(spacing: 8) {
                                Text(Self.shortFileName(file, index: index))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                                Text(MediaThumbs.sizeText(file))
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                                Spacer(minLength: 4)
                                // 打开 / 分享：图标按钮（与记录卡片一致）
                                cardIconButton("arrow.up.forward.square", help: "打开") {
                                    NSWorkspace.shared.open(URL(fileURLWithPath: file))
                                }
                                cardIconButton("square.and.arrow.up", help: "分享") {
                                    state.share(files: [file])
                                }
                                cardIconButton("trash", help: "删除这个文件", tint: .red) {
                                    state.deleteCurrentTaskFile(file)
                                }
                            }
                        }
                    }
                }
                if let file = task.file {
                    HStack(spacing: 8) {
                        cardIconButton("folder", help: "在访达中显示") { state.reveal(file: file) }
                        cardIconButton("arrow.up.forward.square", help: "打开文件") {
                            NSWorkspace.shared.open(URL(fileURLWithPath: file))
                        }
                        cardIconButton("square.and.arrow.up", help: "分享") {
                            state.share(files: allFiles.isEmpty ? [file] : allFiles)
                        }
                        cardIconButton("trash", help: "删除这次下载及其文件", tint: .red) {
                            state.deleteCurrentTask()
                        }
                        Spacer()
                    }
                }
            }
        }
        .sheet(item: $preview) { request in
            MediaPreviewSheet(files: request.files, index: request.index)
        }
        .padding(12)
        .background(Color(nsColor: .controlBackgroundColor))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        // 卡片加一点阴影和边框，和安卓端的卡片风格对齐
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(Color.secondary.opacity(0.15))
        )
        .shadow(color: .black.opacity(0.06), radius: 5, y: 2)
    }

    /// 文件名太长时只显示尾部（序号 / 扩展名在后面，一眼能分辨）
    private static func shortFileName(_ path: String, index: Int) -> String {
        let name = (path as NSString).lastPathComponent
        if name.count <= 28 { return name }
        return "…" + String(name.suffix(26))
    }
}
