import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var state: AppState
    @State private var manualCookieText: String = ""
    @State private var feedbackType: String = "SUGGESTION"
    @State private var feedbackContent: String = ""
    @State private var feedbackContact: String = ""
    @State private var feedbackWithLogs: Bool = false
    @State private var accountPhoneInput: String = ""
    @State private var smsCountdown: Int = 0
    @State private var smsTimer: Timer?
    @State private var accountCodeInput: String = ""

    // 抖音下载不需要 Cookie，就不在这张卡片里占一行（免得被误解成必须登录）
    // 顺序与 Windows 端保持一致
    private let platforms = [
        "douyin", "kuaishou", "xiaohongshu", "wechat_channels",
        "bilibili", "weibo", "tiktok", "instagram", "x", "youtube",
        // Vimeo 现在要登录态才给 web 客户端播放地址，Reddit / Twitch 有登录态成功率更高
        "ixigua", "reddit", "vimeo", "twitch", "weishi", "haokan",
        // 音乐会平台：QQ 音乐多数歌曲要登录；网易云未登录时常常只能下到试听片段
        "netease", "qqmusic",
    ]

    /// 验证码倒计时：60 秒内不让重复点（与 Windows / 安卓一致）
    private func startSmsCountdown() {
        smsCountdown = 60
        smsTimer?.invalidate()
        smsTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { timer in
            if smsCountdown <= 1 {
                timer.invalidate()
                smsCountdown = 0
            } else {
                smsCountdown -= 1
            }
        }
    }

    var body: some View {
        Group {
            Form {
                downloadSection
                browserSection
                networkSection
                loginStateSection
                feedbackSection
                themeSection
                engineSection
                aboutSection
            }
            .padding(.vertical, 4)
            .formStyle(.grouped)
            // 宽窗口下表单不无限拉伸，居中显示更规整（窄窗口时自动收窄）
            .frame(maxWidth: 720)
            .frame(maxWidth: .infinity, alignment: .center)
            .sheet(item: Binding(
                get: { state.manualCookiePlatform.map { PlatformWrapper(platform: $0) } },
                set: { if $0 == nil { state.manualCookiePlatform = nil } }
            )) { wrapper in
                manualCookieSheet(platform: wrapper.platform)
            }
            .sheet(item: Binding(
                get: { state.loginSession },
                set: { if $0 == nil { state.loginSession = nil } }
            )) { session in
                loginSheet(session: session)
            }
        }
        .onAppear {
            // 每次打开设置页都刷新账号登录态与云端列表（保持最新）
            state.refreshAccount()
            // SwiftUI 的 Form 会默认聚焦第一个输入框，导致设置页一打开就滚到中间；
            // 这里在出现后清除焦点，让页面停在顶部
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
                if let window = NSApp.keyWindow, window.isKeyWindow {
                    window.makeFirstResponder(nil)
                }
            }
        }
        // 设置窗口里点「检查更新」时，弹窗要弹在设置窗口自己身上（主窗口可能都没打开）；
        // 挂在外层 Group 上，避免和上面两个 sheet 抢同一个视图层
        .sheet(item: Binding(
            get: { state.settingsUpdateInfo },
            set: { if $0 == nil { state.settingsUpdateInfo = nil } }
        )) { info in
            UpdateSheet(
                info: info,
                onDownload: {
                    state.openUpdatePage(info)
                    state.settingsUpdateInfo = nil
                },
                onDismiss: { state.settingsUpdateInfo = nil }
            )
        }
        .alert("提示", isPresented: Binding(
            get: { state.noticeMessage != nil },
            set: { if !$0 { state.noticeMessage = nil } }
        )) {
            Button("好", role: .cancel) { state.noticeMessage = nil }
        } message: {
            Text(state.noticeMessage ?? "")
        }
        .alert("删除引擎与浏览器内核？", isPresented: $state.showPurgeConfirm) {
            Button("取消", role: .cancel) {}
            Button("删除", role: .destructive) { state.purgeEngines() }
        } message: {
            Text("将删除应用数据目录下的 engines/ 与 browsers/（约 800MB）。下次使用需要重新「开始初始化」。下载历史、设置和已下载的视频不受影响。")
        }
    }

    // MARK: - 下载

    // MARK: - 账号（手机号登录 + 云端 Cookie）

    /// 账号与云端登录态（并入「登录态管理」同一个卡片）
    /// 注意：@ViewBuilder 展开为多个独立 Form 行——Form 对"超高单行"会裁剪内容、
    /// 产生错位空白，之前把整块包成一个 VStack 导致底部文字被遮挡。
    @ViewBuilder
    private var accountBlock: some View {
        Text("账号 · 云端登录态同步")
            .font(.subheadline.weight(.semibold))
            .padding(.top, 2)

        if state.accountLoggedIn {
            HStack {
                Text("已登录：\(state.accountPhone)")
                Spacer()
                Button("退出登录") { state.logoutAccount() }
                    .controlSize(.small)
            }
            Text("登录态会加密保存在你的账号里，只有你自己能看到，随时可以一键清空。")
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            HStack {
                Button("拉取全部云端登录态到本地") {
                    state.pullAllCloudCookiesNewest(platforms: platforms)
                }
                .controlSize(.small)
                .help("每个平台取云端最新的一份（手机端 / 电脑端里更新的那个），手动补一次登录态")
                Button("同步本机登录态到云端") {
                    state.accountNotice = "正在同步本机 Cookie 到云端…"
                    for platform in platforms { state.uploadCookieToCloud(platform: platform) }
                }
                .controlSize(.small)
                Button("清空云端登录态", role: .destructive) { state.clearAllCloudCookies() }
                    .controlSize(.small)
            }
            Text("每个平台的「使用手机登录态 / 使用电脑端登录态」在选择登录态时可直接切换；当前使用的那一端会高亮。")
                .font(.caption2)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        } else {
            TextField("手机号", text: $accountPhoneInput)
                .textFieldStyle(.roundedBorder)
            HStack {
                TextField("验证码", text: $accountCodeInput)
                    .textFieldStyle(.roundedBorder)
                Button("获取验证码") { state.requestAccountSmsCode(phone: accountPhoneInput) }
                    .controlSize(.small)
                    .disabled(accountPhoneInput.count < 11)
                Button("登录") {
                    state.loginAccount(phone: accountPhoneInput, code: accountCodeInput)
                    accountCodeInput = ""
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.small)
                .disabled(accountPhoneInput.count < 11 || accountCodeInput.isEmpty)
            }
            Text("登录后：一端登录过的平台，其它端可以直接用，登录本机时自动回填各平台登录态；云端登录态加密保存、仅你可见、可一键清空。不登录也不影响现有功能。")
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        if let notice = state.accountNotice {
            Text(notice).font(.caption).foregroundStyle(.secondary)
        }
    }


    private var downloadSection: some View {
        Section("下载") {
            HStack {
                Text("保存目录")
                Spacer()
                Text(state.outDir)
                    .lineLimit(1)
                    .truncationMode(.middle)
                    .foregroundStyle(.secondary)
                Button("选择…") { state.chooseDownloadFolder() }
            }
            TextField("文件命名模板", text: $state.nameTemplate)
                .help("可用占位符：{author} 作者、{title} 标题，例如 {author}-{title}")
            Picker("清晰度上限", selection: $state.maxHeight) {
                Text("最高画质").tag(0)
                Text("≤1080P").tag(1080)
                Text("≤720P").tag(720)
            }
            .onChange(of: state.maxHeight) { _ in state.pushCloudSettings() }
            Text("只对走 yt-dlp 的平台生效（X / YouTube / B 站 / 微博 / TikTok）；抖音、快手、小红书、视频号由平台接口决定。")
                .font(.caption)
                .foregroundStyle(.secondary)
            Toggle("视频号省流量（H.265，同画质体积约减半）", isOn: $state.wxchannelsH265)
                .onChange(of: state.wxchannelsH265) { _ in state.pushCloudSettings() }
            Text("HEVC 在部分老播放器/剪辑软件里可能打不开，默认关闭。")
                .font(.caption)
                .foregroundStyle(.secondary)
            Toggle("下载完成后自动在访达中显示", isOn: $state.autoOpenFinder)
        }
    }

    // MARK: - 主题

    private var themeSection: some View {
        Section("主题") {
            HStack(spacing: 10) {
                ForEach([("orange", "浅橙色"), ("green", "浅绿色")], id: \.0) { key, label in
                    Button {
                        state.theme = key
                    } label: {
                        HStack(spacing: 6) {
                            Circle()
                                .fill(AppThemePalette.of(key).gradient[1])
                                .frame(width: 12, height: 12)
                            Text(label)
                        }
                    }
                    .buttonStyle(.bordered)
                    .tint(state.theme == key ? AppThemePalette.of(key).primary : nil)
                    .overlay(
                        RoundedRectangle(cornerRadius: 6)
                            .stroke(state.theme == key ? AppThemePalette.of(key).primary : Color.clear, lineWidth: 1.5)
                    )
                }
                Spacer()
            }
            Text("主题配色会同时影响主界面背景、下载按钮与进度条（与安卓端的两套配色一致）。")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    // MARK: - 浏览器内核

    private var browserSection: some View {
        Section("浏览器解析内核") {
            Picker("优先使用", selection: $state.browserEngine) {
                Text("自动（内置 → Chrome → Edge）").tag("auto")
                Text("内置内核（Chrome for Testing）").tag("chromium")
                Text("系统 Chrome").tag("chrome")
                Text("Microsoft Edge").tag("msedge")
            }
            ForEach(state.browsers) { browser in
                HStack {
                    Image(systemName: browser.ready ? "checkmark.circle.fill" : "xmark.circle")
                        .foregroundStyle(browser.ready ? .green : .secondary)
                    Text(browser.label)
                    Spacer()
                    Text(browser.detail)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            HStack {
                Button("检查状态") { state.refreshEngineStatus() }
                Button("安装 / 更新内置内核") { state.updateEngines() }
            }
            Text("内置内核随解析引擎一起安装（约 180MB，存放在应用数据目录），安装后不再依赖系统 Chrome；解析失败时会自动回退到系统浏览器。")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    // MARK: - 网络

    private var networkSection: some View {
        Section("网络与代理") {
            TextField("代理地址（可留空）", text: $state.proxy)
                .textFieldStyle(.roundedBorder)
                .help("例如 http://127.0.0.1:7890（Clash / Surge 等本地端口）。X 与 YouTube 需要先开代理/VPN。")
            Text("X 与 YouTube 在国内网络下需要代理：先在 Clash/Surge 里开好，再把本地 HTTP 端口填到这里（如 http://127.0.0.1:7890）。留空则使用系统代理设置。")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    // MARK: - Cookie 管理（全部在本机，登录自己的账号）

    private var loginStateSection: some View {
        Section("登录态管理") {
            Text("登录态只保存在本机；登录账号后可以加密同步到你的云端，多端共用。X / YouTube 必须登录后才能下载；微信视频号需要登录腾讯元宝；B 站 / 微博 / TikTok / Instagram 可选登录（清晰度和成功率更高）；抖音不需要登录。")
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            // 每个平台一行（对齐 Windows）：名称 + 状态 + 操作按钮
            ForEach(platforms, id: \.self) { platform in
                platformRow(platform)
            }

            VStack(alignment: .leading, spacing: 6) {
                Button("从系统浏览器导入全部登录态") { state.refreshCookies() }
                    .controlSize(.small)
                Text("「一键获取」打开内置浏览器登录（不需要钥匙串 / 完全磁盘访问权限）；「从系统浏览器导入」读取本机 Chrome / Edge / Brave / Safari 的登录态（Chrome 需钥匙串授权，Safari 需完全磁盘访问权限）。B 站 4K/HDR 只认电脑端浏览器登录过的整份登录态。")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Divider().padding(.vertical, 4)
            accountBlock
        }
    }

    /// 单个平台一行：上行「名称 + 本机状态」，下行「本机操作 + 云端切换」，
    /// 本机与云端登录态合并展示，不再两个列表重复出现平台名
    @ViewBuilder
    private func platformRow(_ platform: String) -> some View {
        let slots = state.cloudCookies[platform]
        let mobileExists = (slots?["mobile"] as? [String: Any])?["exists"] as? Bool ?? false
        let pcExists = (slots?["pc"] as? [String: Any])?["exists"] as? Bool ?? false
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Text(state.platformName(platform))
                    .font(.body.weight(.medium))
                if platform == "douyin" {
                    platformTag("下载免登录", color: .green)
                } else if platform == "x" || platform == "youtube" {
                    platformTag("必须登录", color: .orange)
                } else if platform == "wechat_channels" {
                    platformTag("需登录腾讯元宝", color: .orange)
                }
                Spacer(minLength: 6)
                Text(cookieStateText(platform))
                    .font(.caption)
                    .foregroundStyle(cookieStateColor(platform))
                    .lineLimit(1)
                    .truncationMode(.middle)
                    .help(cookieStateText(platform))
            }
            HStack(spacing: 8) {
                Group {
                    if isLoggedIn(platform) {
                        // 已登录：按钮降级为次要样式，避免看起来像还没登录
                        Button(state.cookieStatuses[platform]?.actionTitle ?? "重新登录") {
                            state.startCookieLogin(platform: platform)
                        }
                        .buttonStyle(.bordered)
                    } else {
                        Button(state.cookieStatuses[platform]?.actionTitle ?? "一键获取") {
                            state.startCookieLogin(platform: platform)
                        }
                        .buttonStyle(.borderedProminent)
                    }
                }
                .controlSize(.small)
                Button("手动粘贴") {
                    manualCookieText = ""
                    state.manualCookiePlatform = platform
                }
                .controlSize(.small)
                Button("测试登录态") { state.testCookie(platform: platform) }
                    .controlSize(.small)
                if state.accountLoggedIn {
                    cloudTypeButton(platform, type: "mobile")
                    cloudTypeButton(platform, type: "pc")
                    // 不选端：一键取云端最新的一份（登录后自动回填失败时手动补）
                    Button("拉取云端登录态") {
                        if let device = state.newestCloudDevice(platform) {
                            state.useCloudCookie(platform: platform, deviceType: device)
                        }
                    }
                    .controlSize(.small)
                    .disabled(state.newestCloudDevice(platform) == nil)
                    .help("拉取云端更新更晚的那一份登录态写进本机")
                    if mobileExists || pcExists {
                        Button("删除云端", role: .destructive) {
                            state.clearCloudCookie(platform: platform)
                        }
                        .controlSize(.small)
                    }
                }
                Spacer(minLength: 0)
                if isLoggedIn(platform) {
                    Button("清除", role: .destructive) { state.clearCookie(platform: platform) }
                        .controlSize(.small)
                }
            }
        }
        .padding(.vertical, 2)
        .fixedSize(horizontal: false, vertical: true)
    }

    /// 云端某一端（手机端 / 电脑端）的切换按钮；当前正在使用的那一端高亮
    @ViewBuilder
    private func cloudTypeButton(_ platform: String, type: String) -> some View {
        let slot = (state.cloudCookies[platform]?[type]) as? [String: Any]
        let exists = slot?["exists"] as? Bool ?? false
        let source = state.cookieStatuses[platform]?.source ?? ""
        let active = (type == "mobile" && source == "云端·手机端")
            || (type == "pc" && source == "云端·电脑端")
        let title = type == "mobile" ? "使用手机登录态" : "使用电脑端登录态"
        Group {
            if active {
                Button("✓ \(title)") {
                    state.useCloudCookie(platform: platform, deviceType: type)
                }
                .buttonStyle(.borderedProminent)
            } else {
                Button(title) {
                    state.useCloudCookie(platform: platform, deviceType: type)
                }
                .buttonStyle(.bordered)
            }
        }
        .controlSize(.small)
        .disabled(!exists)
        .help(exists
              ? "用这一端的登录态（来源：\((slot?["deviceName"] as? String) ?? "未知设备")）"
              : "云端还没有这一端的登录态：请在对应设备上登录后同步")
    }

    private func platformTag(_ text: String, color: Color) -> some View {
        Text(text)
            .font(.caption2)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(color.opacity(0.15))
            .clipShape(Capsule())
    }

    // MARK: - 提建议

    private var feedbackSection: some View {
        Section("提建议 / 联系官方") {
            Picker("类型", selection: $feedbackType) {
                Text("功能建议").tag("SUGGESTION")
                Text("问题反馈").tag("BUG")
                Text("其它").tag("OTHER")
            }
            .pickerStyle(.segmented)
            TextField("联系方式（可留空，便于我们回复你）", text: $feedbackContact)
                .textFieldStyle(.roundedBorder)
            TextEditor(text: $feedbackContent)
                .frame(height: 100)
                .padding(4)
                .overlay(RoundedRectangle(cornerRadius: 6).stroke(Color.secondary.opacity(0.3)))
                .overlay(alignment: .topLeading) {
                    if feedbackContent.isEmpty {
                        Text("写下你的建议或遇到的问题…（请尽量附上出问题的链接，方便我们复现）")
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 12)
                            .allowsHitTesting(false)
                    }
                }
            Toggle("附带运行日志（便于定位问题）", isOn: $feedbackWithLogs)
            HStack {
                if state.feedbackDone {
                    Label("已提交，感谢反馈", systemImage: "checkmark.circle.fill")
                        .font(.caption)
                        .foregroundStyle(.green)
                }
                Spacer()
                Button(state.feedbackSending ? "提交中…" : "提交") {
                    state.sendFeedback(
                        type: feedbackType,
                        content: feedbackContent,
                        contact: feedbackContact,
                        withLogs: feedbackWithLogs
                    )
                }
                .buttonStyle(.borderedProminent)
                .disabled(state.feedbackSending || feedbackContent.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
    }

    // MARK: - 引擎

    private var engineSection: some View {
        Section("解析引擎") {
            ForEach(state.engines) { engine in
                HStack {
                    Text(engine.label)
                    Spacer()
                    Text(engine.version)
                        .font(.caption.monospaced())
                        .foregroundStyle(.secondary)
                    Image(systemName: engine.ready ? "checkmark.circle.fill" : "xmark.circle")
                        .foregroundStyle(engine.ready ? .green : .red)
                }
            }
            HStack {
                Button("检查引擎状态") { state.refreshEngineStatus() }
                Button("打开应用数据目录") {
                    NSWorkspace.shared.open(AppPaths.supportDirectory)
                }
            }
            HStack {
                Button("删除引擎与浏览器内核…", role: .destructive) {
                    state.showPurgeConfirm = true
                }
                Text("释放约 800MB；历史记录与设置会保留")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var aboutSection: some View {
        Section("关于") {
            Text("秒存 \(AppState.appVersionName)（macOS）")
                .font(.callout.weight(.medium))
            VStack(alignment: .leading, spacing: 3) {
                Text("支持平台与能力")
                    .font(.caption.weight(.semibold))
                Text("粘贴链接即可下载。下面这些平台已内置专属解析（无水印 / 原始画质 / 自动合并）：")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                PlatformBadgeGrid()
                Text("""
                · 其它平台：微视 / 好看视频（通用解析 + 网页嗅探），以及 yt-dlp 支持的上千个站点（AcFun / 优酷 / 腾讯视频 / 爱奇艺 / 芒果 TV / SoundCloud…）
                · 主页 / 收藏夹批量下载（抖音 / 小红书，需在本机登录；最多 20 条）
                · 普通文件直链（PDF / 压缩包 / 文档 / 音视频）+ 网页资源嗅探
                · 下载：多线程分段加速（最多 8 段）、断点续传、多 CDN 重试
                · 画质：清晰度上限（最高 / ≤1080P / ≤720P）、视频号 H.265 省流量
                · 其它：命名模板、自定义保存目录、下载完自动在访达中显示
                """)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Text("与安卓端的差异：安卓为应用内登录 + 应用内更新、保存到相册，但读不到系统浏览器 Cookie（B 站 4K 可手动粘贴电脑端 Cookie，或登录账号后切「电脑端」）；电脑端可从系统浏览器一键导入 Cookie。两端都支持主页批量下载与多线程分段下载。")
                .font(.caption2)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            Text("仅用于个人学习与备份用途，请遵守各平台的服务条款。平台接口变化时，我们会更新引擎并发新版本，更新应用即可修复。")
                .font(.caption)
                .foregroundStyle(.secondary)
            HStack(spacing: 12) {
                Button("检查更新") { state.checkForUpdate(fromSettings: true) }
                    .controlSize(.small)
                Spacer()
            }
            // 合规入口：隐私政策 / 用户协议 / 开源声明
            HStack(spacing: 14) {
                ForEach([("隐私政策", "/privacy.html"), ("用户协议", "/terms.html"), ("开源声明", "/opensource.html")], id: \.0) { item in
                    Button(item.0) { state.openSitePage(item.1) }
                        .buttonStyle(.link)
                        .font(.caption)
                }
                Spacer()
            }
        }
    }

    // MARK: - Sheets

    /// 某平台是否算「已经登录过」（Cookie 已配置）
    private func isLoggedIn(_ platform: String) -> Bool {
        state.cookieStatuses[platform]?.hasCookie ?? false
    }

    private func cookieStateText(_ platform: String) -> String {
        let status = state.cookieStatuses[platform] ?? CookieStatus()
        return status.stateText(required: platform == "x" || platform == "youtube")
    }

    /// 状态文字颜色：过期提示橙、未登录提示次要色
    private func cookieStateColor(_ platform: String) -> Color {
        guard let status = state.cookieStatuses[platform], status.hasCookie else { return .secondary }
        if status.invalid { return .red }
        return status.isStale ? .orange : .green
    }

    private func manualCookieSheet(platform: String) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("手动粘贴\(state.platformName(platform)) Cookie")
                .font(.headline)
            Text("在浏览器中登录后，用开发者工具复制 Cookie 请求头（形如 name=value; name2=value2），粘贴到下方。")
                .font(.caption)
                .foregroundStyle(.secondary)
            TextEditor(text: $manualCookieText)
                .frame(height: 140)
                .padding(6)
                .overlay(RoundedRectangle(cornerRadius: 6).stroke(Color.secondary.opacity(0.3)))
            HStack {
                Spacer()
                Button("取消") { state.manualCookiePlatform = nil }
                Button("保存") {
                    state.saveManualCookie(platform: platform, cookie: manualCookieText)
                    manualCookieText = ""
                }
                .buttonStyle(.borderedProminent)
                .disabled(manualCookieText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .padding(20)
        .frame(width: 520)
    }

    private func loginSheet(session: LoginSession) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("正在获取\(state.platformName(session.platform)) Cookie")
                .font(.headline)
            Text(session.notice ?? "已打开内置浏览器窗口，请在其中登录你自己的账号（扫码或账号密码均可）。登录完成后回到这里点「完成」，Cookie 只会保存在本机。")
                .font(.callout)
                .foregroundStyle(session.notice == nil ? Color.secondary : Color.primary)
                .fixedSize(horizontal: false, vertical: true)
            if session.platform == "tiktok" || session.platform == "instagram" {
                Text("提示：用 Google / Facebook 账号登录可能被平台拦下（提示「浏览器可能不安全」）。请改用二维码登录（用手机 App 扫），或邮箱 / 手机号登录。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            HStack {
                Spacer()
                Button("取消") { state.finishCookieLogin(save: false) }
                Button(session.busy ? "保存中…" : "完成") { state.finishCookieLogin(save: true) }
                    .buttonStyle(.borderedProminent)
                    .disabled(session.busy)
            }
        }
        .padding(20)
        .frame(width: 460)
    }
}

private struct PlatformWrapper: Identifiable {
    let platform: String
    var id: String { platform }
}


// MARK: - 平台徽标（关于里展示支持的主流平台）

private struct PlatformBadge: Identifiable {
    let id = UUID()
    let short: String
    let name: String
    let color: Color
}

private let supportedPlatformBadges: [PlatformBadge] = [
    PlatformBadge(short: "抖", name: "抖音", color: Color(red: 0.09, green: 0.09, blue: 0.14)),
    PlatformBadge(short: "快", name: "快手", color: Color(red: 1.00, green: 0.29, blue: 0.02)),
    PlatformBadge(short: "红", name: "小红书", color: Color(red: 1.00, green: 0.14, blue: 0.26)),
    PlatformBadge(short: "视", name: "微信视频号", color: Color(red: 0.03, green: 0.76, blue: 0.38)),
    PlatformBadge(short: "B", name: "B 站", color: Color(red: 0.00, green: 0.63, blue: 0.84)),
    PlatformBadge(short: "博", name: "微博", color: Color(red: 0.90, green: 0.09, blue: 0.18)),
    PlatformBadge(short: "YT", name: "YouTube", color: Color(red: 1.00, green: 0.00, blue: 0.00)),
    PlatformBadge(short: "X", name: "X", color: Color(red: 0.08, green: 0.09, blue: 0.10)),
    PlatformBadge(short: "TT", name: "TikTok", color: Color(red: 0.01, green: 0.01, blue: 0.01)),
    PlatformBadge(short: "IG", name: "Instagram", color: Color(red: 0.76, green: 0.21, blue: 0.52)),
    PlatformBadge(short: "西", name: "西瓜视频", color: Color(red: 0.10, green: 0.70, blue: 0.58)),
    PlatformBadge(short: "R", name: "Reddit", color: Color(red: 1.00, green: 0.27, blue: 0.00)),
    PlatformBadge(short: "V", name: "Vimeo", color: Color(red: 0.10, green: 0.72, blue: 0.92)),
    PlatformBadge(short: "TW", name: "Twitch", color: Color(red: 0.57, green: 0.27, blue: 1.00)),
    PlatformBadge(short: "网", name: "网易云音乐", color: Color(red: 0.76, green: 0.05, blue: 0.05)),
    PlatformBadge(short: "QQ", name: "QQ 音乐", color: Color(red: 0.19, green: 0.76, blue: 0.49)),
]

private struct PlatformBadgeGrid: View {
    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 108), spacing: 6)], alignment: .leading, spacing: 6) {
            ForEach(supportedPlatformBadges) { badge in
                HStack(spacing: 6) {
                    Text(badge.short)
                        .font(.system(size: 9, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(width: 18, height: 18)
                        .background(badge.color, in: Circle())
                    Text(badge.name)
                        .font(.caption)
                        .lineLimit(1)
                }
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(Color.secondary.opacity(0.12), in: Capsule())
            }
        }
        .padding(.vertical, 2)
    }
}
