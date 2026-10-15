import AppKit
import Combine
import Foundation
import SwiftUI

@MainActor
final class AppState: ObservableObject {
    @Published var urlText: String = ""
    @Published var engines: [EngineInfo] = []
    @Published var currentTask: DownloadTaskState?
    @Published var history: [HistoryItem] = []
    @Published var bootstrapLog: [String] = []
    @Published var isBootstrapping = false
    @Published var bootstrapFinished = false
    @Published var errorMessage: String?
    @Published var bridgeRunning = false
    @Published var cookieStatuses: [String: CookieStatus] = [:]
    @Published var browsers: [BrowserInfo] = []
    @Published var loginSession: LoginSession?
    @Published var manualCookiePlatform: String?
    @Published var noticeMessage: String?
    @Published var showPurgeConfirm = false
    @Published var updateInfo: UpdateInfo?
    /// 设置页里「检查更新」的结果单独放一份：macOS 的弹窗是按窗口present的，
    /// 设置窗口和主窗口是两个窗口，共用一份状态会出现「设置页点了检查更新却什么都不弹」
    @Published var settingsUpdateInfo: UpdateInfo?
    /// 主窗口内「首页 / 设置」切换（与 Windows 端一致；不再用独立的设置窗口）
    @Published var showingSettings = false
    @Published var feedbackSending = false
    @Published var feedbackDone = false
    /// B2 账号与云端 Cookie
    @Published var accountPhone: String = ""
    @Published var accountNickname: String = ""
    @Published var accountLoggedIn: Bool = false
    @Published var accountNotice: String?
    @Published var cloudCookies: [String: [String: Any]] = [:]

    @AppStorage("out_dir") var outDir: String = AppPaths.defaultDownloadDirectory.path
    @AppStorage("name_template") var nameTemplate: String = "{author}-{title}"
    @AppStorage("cookie_browser") var cookieBrowser: String = "auto"
    @AppStorage("browser_engine") var browserEngine: String = "auto"
    @AppStorage("proxy") var proxy: String = ""
    @AppStorage("max_height") var maxHeight: Int = 0
    @AppStorage("wxchannels_h265") var wxchannelsH265: Bool = false
    @AppStorage("auto_open_finder") var autoOpenFinder: Bool = false
    /// 主题配色（与安卓端一致：浅橙 / 浅绿）
    @AppStorage("theme") var theme: String = "green"
    @AppStorage("server_url") var serverURL: String = "https://music.aiyun-jing.com:8443/downloader-api"

    /// 与后台约定的客户端版本号：构建时由 scripts/build-app.sh 从仓库根目录 VERSION 写入 Info.plist
    static let appVersionName = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0.0.0"
    static let appVersionCode = Int((Bundle.main.infoDictionary?["CFBundleVersion"] as? String) ?? "0") ?? 0

    private let bridge = EngineBridge()
    private var lastTaskId: String?
    /// 这次「检查更新」是从哪个窗口发起的（决定弹窗弹在哪个窗口）
    private var updateCheckFromSettings = false

    var enginesReady: Bool {
        !engines.isEmpty && engines.allSatisfy(\.ready)
    }

    /// 当前主题配色
    var palette: AppThemePalette { AppThemePalette.of(theme) }

    /// 主界面右上角的「换橙色 / 换绿色」按钮
    func toggleTheme() {
        theme = (theme == "green") ? "orange" : "green"
    }

    var environmentInstalled: Bool {
        FileManager.default.isExecutableFile(atPath: AppPaths.bridgePython.path)
    }

    init() {
        bridge.onEvent = { [weak self] event in
            self?.handle(event: event)
        }
        loadHistory()
    }

    // MARK: - 生命周期

    func onAppear() {
        AppPaths.ensureDirectories()
        try? FileManager.default.createDirectory(
            atPath: outDir,
            withIntermediateDirectories: true
        )
        if environmentInstalled {
            startBridgeIfNeeded()
            refreshEngineStatus()
            refreshCookieStatus()
            // 读取账号登录态与云端登录态列表（此前漏调用，导致重启后界面误显示"未登录"）
            refreshAccount()
            pushCloudSettings()
            checkForUpdate(silent: true)
            // 解析引擎随应用版本一起更新（内置运行时），客户端不再做引擎热更新
        }
    }

    // MARK: - 云控后台

    /// 把界面上的服务器配置同步给引擎进程（引擎以自己的 settings.json 为准）
    func pushCloudSettings() {
        startBridgeIfNeeded()
        bridge.send([
            "cmd": "set_settings",
            "settings": [
                "server_url": serverURL,
                // 画质偏好也要下发给引擎：引擎按 settings.json 里的值选流
                "max_height": maxHeight,
                "wxchannels_h265": wxchannelsH265,
            ]
        ])
    }

    func checkForUpdate(silent: Bool = false, fromSettings: Bool = false) {
        startBridgeIfNeeded()
        pushCloudSettings()
        updateCheckFromSettings = fromSettings
        if !silent {
            noticeMessage = "正在检查新版本…"
        }
        bridge.send(["cmd": "cloud_check_update", "platform": "macos"])
    }

    func sendFeedback(type: String, content: String, contact: String, withLogs: Bool) {
        startBridgeIfNeeded()
        pushCloudSettings()
        feedbackSending = true
        feedbackDone = false
        bridge.send([
            "cmd": "cloud_feedback",
            "type": type,
            "content": content,
            "contact": contact,
            "with_logs": withLogs
        ])
    }

    /// 打开官网页面（隐私政策 / 用户协议 / 开源声明）
    func openSitePage(_ path: String) {
        let base = serverURL.dropLast("/downloader-api".count)
        guard let url = URL(string: base + "/download" + path) else { return }
        NSWorkspace.shared.open(url)
    }

    func openUpdatePage(_ info: UpdateInfo? = nil) {
        let target = info ?? updateInfo ?? settingsUpdateInfo
        guard let target, let url = URL(string: target.downloadURL) else { return }
        NSWorkspace.shared.open(url)
    }

    func startBridgeIfNeeded() {
        guard environmentInstalled else { return }
        if !bridge.isRunning {
            do {
                try bridge.start()
                bridgeRunning = true
            } catch {
                bridgeRunning = false
                errorMessage = "桥接进程启动失败：\(error.localizedDescription)"
            }
        }
    }

    func refreshEngineStatus() {
        startBridgeIfNeeded()
        bridge.send(["cmd": "engine_status"])
    }

    // MARK: - 下载

    func download() {
        let text = urlText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        guard environmentInstalled else {
            errorMessage = "解析引擎尚未安装，请先完成初始化。"
            return
        }
        startBridgeIfNeeded()
        let id = bridge.send([
            "cmd": "download",
            "url": text,
            "out_dir": outDir,
            "name_template": nameTemplate,
            "browser_engine": browserEngine,
            "proxy": proxy,
        ])
        lastTaskId = id
        currentTask = DownloadTaskState(id: id)
    }

    func cancelCurrentTask() {
        guard let id = currentTask?.id else { return }
        bridge.send(["cmd": "cancel", "task_id": id])
    }

    /// 主页 / 收藏夹批量下载：浏览器滚动 + 拦截页面自身请求，逐条下载
    func downloadBatch(limit: Int = 20) {
        let text = urlText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        guard environmentInstalled else {
            errorMessage = "解析引擎尚未安装，请先完成初始化。"
            return
        }
        startBridgeIfNeeded()
        let id = bridge.send([
            "cmd": "batch",
            "url": text,
            "limit": limit,
            "out_dir": outDir,
            "name_template": nameTemplate,
            "browser_engine": browserEngine,
            "proxy": proxy,
        ])
        lastTaskId = id
        currentTask = DownloadTaskState(id: id)
    }

    func refreshCookies() {
        startBridgeIfNeeded()
        bridge.send(["cmd": "refresh_cookies", "browser": cookieBrowser])
    }

    func refreshCookieStatus() {
        startBridgeIfNeeded()
        bridge.send(["cmd": "cookie_status"])
    }

    func startCookieLogin(platform: String) {
        startBridgeIfNeeded()
        let id = bridge.send(["cmd": "cookie_login", "platform": platform])
        loginSession = LoginSession(id: id, platform: platform, notice: "正在打开浏览器窗口…")
    }

    /// 取消：直接关面板（浏览器窗口由引擎侧关闭）
    /// 完成：面板先留着，等引擎确认——浏览器里的登录态可能已经过期，引擎会拦下来
    func finishCookieLogin(save: Bool) {
        guard let session = loginSession else { return }
        bridge.send(["cmd": save ? "cookie_login_save" : "cookie_login_cancel", "task_id": session.id])
        if save {
            loginSession?.busy = true
            loginSession?.notice = "正在保存 Cookie…"
        } else {
            loginSession = nil
        }
    }

    func testCookie(platform: String) {
        startBridgeIfNeeded()
        bridge.send(["cmd": "cookie_test", "platform": platform])
    }

    func clearCookie(platform: String) {
        startBridgeIfNeeded()
        bridge.send(["cmd": "cookie_clear", "platform": platform])
    }

    func saveManualCookie(platform: String, cookie: String) {
        startBridgeIfNeeded()
        bridge.send(["cmd": "cookie_set", "platform": platform, "cookie": cookie])
        manualCookiePlatform = nil
    }

    // MARK: - 账号（手机号登录 + 云端 Cookie 同步）

    func requestAccountSmsCode(phone: String) {
        startBridgeIfNeeded()
        accountNotice = "验证码发送中…"
        bridge.send(["cmd": "account_sms_code", "phone": phone])
    }

    func loginAccount(phone: String, code: String) {
        startBridgeIfNeeded()
        accountNotice = "登录中…"
        bridge.send(["cmd": "account_login", "phone": phone, "code": code])
    }

    func logoutAccount() {
        startBridgeIfNeeded()
        bridge.send(["cmd": "account_logout"])
    }

    func refreshAccount() {
        startBridgeIfNeeded()
        bridge.send(["cmd": "account_status"])
        bridge.send(["cmd": "cookie_sync_status"])
    }

    /// 把本机某平台的 Cookie 上报到云端（加密存储）
    func uploadCookieToCloud(platform: String) {
        startBridgeIfNeeded()
        bridge.send(["cmd": "cookie_sync_upload", "platform": platform])
    }

    /// 使用云端某一端的 Cookie（会写入本机，之后下载即用它）
    func useCloudCookie(platform: String, deviceType: String) {
        startBridgeIfNeeded()
        bridge.send(["cmd": "cookie_sync_pull", "platform": platform, "deviceType": deviceType])
    }

    /// 云端两端都有时取更新时间更晚的那一端（时间相同或缺失时优先手机端）；都没有返回 nil
    func newestCloudDevice(_ platform: String) -> String? {
        let slots = cloudCookies[platform]
        let mobile = (slots?["mobile"] as? [String: Any])?["exists"] as? Bool ?? false
        let pc = (slots?["pc"] as? [String: Any])?["exists"] as? Bool ?? false
        guard mobile || pc else { return nil }
        guard mobile && pc else { return mobile ? "mobile" : "pc" }
        let mobileTime = ((slots?["mobile"] as? [String: Any])?["updatedAt"]).map { "\($0)" } ?? ""
        let pcTime = ((slots?["pc"] as? [String: Any])?["updatedAt"]).map { "\($0)" } ?? ""
        return pcTime > mobileTime ? "pc" : "mobile"
    }

    /// 拉取全部云端登录态到本地：每个平台取云端最新的一份（登录后自动回填失败时手动补）
    func pullAllCloudCookiesNewest(platforms: [String]) {
        startBridgeIfNeeded()
        var pulled = 0
        for platform in platforms {
            guard let device = newestCloudDevice(platform) else { continue }
            pulled += 1
            bridge.send(["cmd": "cookie_sync_pull", "platform": platform, "deviceType": device])
        }
        accountNotice = pulled > 0 ? "正在拉取 \(pulled) 个平台的云端登录态到本地…" : "云端还没有可拉取的登录态"
    }

    func clearCloudCookie(platform: String) {
        startBridgeIfNeeded()
        bridge.send(["cmd": "cookie_sync_clear", "platform": platform])
    }

    func clearAllCloudCookies() {
        startBridgeIfNeeded()
        bridge.send(["cmd": "cookie_sync_clear"])
    }

    func platformName(_ key: String) -> String {
        switch key {
        case "douyin": return "抖音"
        case "kuaishou": return "快手"
        case "xiaohongshu": return "小红书"
        case "wechat_channels": return "微信视频号"
        case "bilibili": return "B 站"
        case "weibo": return "微博"
        case "tiktok": return "TikTok"
        case "instagram": return "Instagram"
        case "ixigua": return "西瓜视频"
        case "weishi": return "微视"
        case "haokan": return "好看视频"
        case "reddit": return "Reddit"
        case "vimeo": return "Vimeo"
        case "twitch": return "Twitch"
        case "netease": return "网易云音乐"
        case "qqmusic": return "QQ 音乐"
        case "kugou": return "酷狗音乐"
        case "generic": return "通用解析"
        case "web": return "网页资源"
        case "file": return "文件"
        default: return key
        }
    }

    func updateEngines() {
        startBridgeIfNeeded()
        bridge.send(["cmd": "update_engines"])
    }

    /// 删除引擎与浏览器内核（约 800MB），恢复到未初始化状态；历史记录与设置保留。
    func purgeEngines() {
        bridge.stop()
        let fileManager = FileManager.default
        for url in [AppPaths.engineRoot, AppPaths.browsersDirectory] {
            try? fileManager.removeItem(at: url)
        }
        engines = []
        browsers = []
        bridgeRunning = false
        bootstrapLog = ["已删除解析引擎与浏览器内核（应用数据目录中的 engines/ 与 browsers/）。"]
        bootstrapFinished = true
        noticeMessage = "已释放引擎与浏览器内核占用（下载历史与设置保留）。"
    }

    func pasteFromClipboard() {
        if let text = NSPasteboard.general.string(forType: .string), !text.isEmpty {
            urlText = text
        }
    }

    // MARK: - 引擎初始化

    func runBootstrap(update: Bool = false) {
        guard !isBootstrapping else { return }
        isBootstrapping = true
        bootstrapFinished = false
        bootstrapLog = ["开始\(update ? "更新" : "初始化")解析引擎…"]

        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/bin/bash")
        process.arguments = [AppPaths.bootstrapScript.path] + (update ? ["--update"] : [])
        var environment = ProcessInfo.processInfo.environment
        environment["PYTHONUNBUFFERED"] = "1"
        process.environment = environment

        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = pipe
        pipe.fileHandleForReading.readabilityHandler = { [weak self] handle in
            let data = handle.availableData
            guard !data.isEmpty, let text = String(data: data, encoding: .utf8) else { return }
            let lines = text.split(whereSeparator: \.isNewline).map(String.init)
            DispatchQueue.main.async {
                self?.bootstrapLog.append(contentsOf: lines)
            }
        }
        process.terminationHandler = { [weak self] proc in
            DispatchQueue.main.async {
                self?.isBootstrapping = false
                self?.bootstrapFinished = true
                self?.bootstrapLog.append(
                    proc.terminationStatus == 0 ? "初始化完成 ✓" : "初始化失败（退出码 \(proc.terminationStatus)）"
                )
                self?.refreshEngineStatus()
            }
        }
        do {
            try process.run()
        } catch {
            isBootstrapping = false
            bootstrapLog.append("无法启动初始化脚本：\(error.localizedDescription)")
        }
    }

    // MARK: - 事件处理

    private func handle(event: [String: Any]) {
        let name = event["event"] as? String ?? ""
        let id = event["id"] as? String
        switch name {
        case "ready":
            bridgeRunning = true
        case "bridge_exit":
            bridgeRunning = false
        case "engine_status":
            updateEngines(from: event)
        case "cookie_status":
            updateCookieStatuses(event["cookies"] as? [String: [String: Any]] ?? [:])
        case "cookie_login_ready":
            loginSession?.notice = "请在弹出的浏览器窗口里完成登录，完成后回到这里点「完成」。"
        case "cookie_login_state":
            loginSession?.notice = (event["logged_in"] as? Bool ?? false)
                ? "浏览器里已经是登录状态，直接点「完成」即可；要换账号请先在窗口里退出登录。"
                : "浏览器里还没有登录，请在弹出的窗口里完成登录，然后回到这里点「完成」。"
        case "meta":
            if var task = currentTask, id == nil || id == task.id {
                task.platform = event["platform"] as? String ?? ""
                task.label = event["label"] as? String ?? task.label
                currentTask = task
            }
        case "stage":
            if var task = currentTask, id == nil || id == task.id {
                task.stage = event["stage"] as? String ?? task.stage
                currentTask = task
            }
        case "progress":
            if var task = currentTask, id == nil || id == task.id {
                task.percent = event["percent"] as? Double
                task.downloaded = (event["downloaded"] as? NSNumber)?.int64Value ?? task.downloaded
                task.total = (event["total"] as? NSNumber)?.int64Value ?? task.total
                task.speed = (event["speed"] as? NSNumber)?.doubleValue
                if task.stage == "解析中" || task.stage == "准备中" {
                    task.stage = "下载中"
                }
                currentTask = task
            }
        case "log":
            if var task = currentTask, id == nil || id == task.id {
                let message = event["message"] as? String ?? ""
                // 过滤「读取 xxx Cookie…」这类内部探测日志，避免干扰界面
                if !message.isEmpty, !message.hasPrefix("读取 ") {
                    task.message = message
                    currentTask = task
                }
            }
        case "done":
            handleDone(event: event)
        case "error":
            // 登录窗口里的"登录态已过期"拦截：面板留着，让用户先真正登录再点「完成」
            if (event["code"] as? String) == "LOGIN_EXPIRED", loginSession != nil {
                loginSession?.busy = false
                loginSession?.notice = event["message"] as? String ?? "浏览器里的登录态已经过期，请重新登录。"
                return
            }
            feedbackSending = false
            if var task = currentTask, id == nil || id == task.id {
                task.isRunning = false
                task.stage = "失败"
                task.message = event["message"] as? String ?? "下载失败"
                currentTask = task
                errorMessage = task.message
            } else if let message = event["message"] as? String {
                errorMessage = message
            }
        case "settings":
            break
        default:
            break
        }
    }

    private func handleDone(event: [String: Any]) {
        if let kind = event["kind"] as? String {
            switch kind {
            case "refresh_cookies":
                noticeMessage = "已从系统浏览器导入 Cookie。"
                refreshCookieStatus()
                return
            case "cookie_login":
                let platform = event["platform"] as? String ?? ""
                loginSession = nil
                noticeMessage = "已保存\(platformName(platform)) Cookie（\(event["count"] as? Int ?? 0) 条）。"
                if let statuses = event["cookie_status"] as? [String: [String: Any]] {
                    updateCookieStatuses(statuses)
                }
                return
            case "cookie_login_cancelled":
                loginSession = nil
                noticeMessage = "已取消登录。"
                return
            case "cookie_test":
                noticeMessage = event["message"] as? String ?? "检测完成"
                return
            case "cookie_set", "cookie_clear":
                if let statuses = event["cookie_status"] as? [String: [String: Any]] {
                    updateCookieStatuses(statuses)
                }
                noticeMessage = kind == "cookie_set" ? "已保存 Cookie。" : "已清除 Cookie。"
                return
            case "update_engines":
                bootstrapLog.append("解析引擎已更新 ✓")
                refreshEngineStatus()
                return
            case "account_sms_code":
                accountNotice = "验证码已发送到 \(event["phone"] as? String ?? "你的手机")（5 分钟内有效）"
                return
            case "account_login":
                accountLoggedIn = true
                accountPhone = event["phone"] as? String ?? ""
                accountNickname = event["nickname"] as? String ?? ""
                accountNotice = (event["created"] as? Bool ?? false)
                    ? "账号已创建并登录：\(accountPhone)（可在设置里同步云端 Cookie）"
                    : "已登录：\(accountPhone)"
                refreshCookieStatus()
                bridge.send(["cmd": "cookie_sync_status"])
                // 登录即回填：本机没有的平台自动用云端登录态（电脑端优先，手机端兜底）
                bridge.send(["cmd": "cookie_sync_autofill"])
                return
            case "account_logout":
                accountLoggedIn = false
                accountPhone = ""
                accountNickname = ""
                cloudCookies = [:]
                accountNotice = "已退出账号（云端 Cookie 仍保留，可重新登录后再管理）"
                return
            case "cookie_sync_autofill":
                if let statuses = event["cookies"] as? [String: [String: Any]] {
                    updateCookieStatuses(statuses)
                }
                cloudCookies = event["platforms"] as? [String: [String: Any]] ?? [:]
                let applied = event["applied"] as? [[String: Any]] ?? []
                if !applied.isEmpty {
                    accountNotice = "已自动回填 \(applied.count) 个平台的登录态（来自云端）"
                }
                return
            case "account_status":
                accountLoggedIn = event["loggedIn"] as? Bool ?? false
                accountPhone = event["phone"] as? String ?? ""
                accountNickname = event["nickname"] as? String ?? ""
                if let error = event["error"] as? String, !error.isEmpty {
                    accountNotice = "账号状态读取失败：\(error)"
                }
                return
            case "cookie_sync_status", "cookie_sync_clear":
                cloudCookies = event["platforms"] as? [String: [String: Any]] ?? [:]
                if kind == "cookie_sync_clear" {
                    accountNotice = "云端 Cookie 已删除"
                }
                return
            case "cookie_sync_upload":
                cloudCookies = event["platforms"] as? [String: [String: Any]] ?? [:]
                accountNotice = "已把本机 \(platformName(event["platform"] as? String ?? "")) Cookie 同步到云端（加密存储）"
                return
            case "cookie_sync_pull":
                if let statuses = event["cookies"] as? [String: [String: Any]] {
                    updateCookieStatuses(statuses)
                }
                let type = (event["deviceType"] as? String) == "mobile" ? "手机端" : "电脑端"
                accountNotice = "已改用云端·\(type) 的 \(platformName(event["platform"] as? String ?? "")) Cookie"
                return
            case "cloud_register":
                noticeMessage = "设备注册成功，云控已就绪。"
                return
            case "cloud_check_update":
                let hasUpdate = event["hasUpdate"] as? Bool ?? false
                if hasUpdate {
                    let info = UpdateInfo(
                        versionName: event["versionName"] as? String ?? "",
                        versionCode: event["versionCode"] as? Int ?? 0,
                        changelog: event["changelog"] as? String ?? "",
                        downloadURL: event["downloadUrl"] as? String ?? "",
                        force: event["force"] as? Bool ?? false,
                        fileSize: (event["fileSize"] as? NSNumber)?.int64Value ?? 0
                    )
                    // 弹在发起检查的那个窗口：主窗口用 updateInfo，设置窗口用 settingsUpdateInfo
                    if updateCheckFromSettings {
                        settingsUpdateInfo = info
                    } else {
                        updateInfo = info
                    }
                } else if updateCheckFromSettings || noticeMessage == "正在检查新版本…" {
                    // 没有新版本时也要给个反馈，否则点了「检查更新」像是没反应
                    noticeMessage = "当前已是最新版本（v\(Self.appVersionName)）。"
                }
                return
            case "cloud_feedback":
                feedbackSending = false
                feedbackDone = true
                noticeMessage = event["message"] as? String ?? "感谢反馈，我们会尽快处理！"
                return
            default:
                break
            }
        }
        if let kind = event["kind"] as? String, kind == "update_engines" {
            bootstrapLog.append("解析引擎已更新 ✓")
            refreshEngineStatus()
            return
        }
        guard let task = currentTask, event["id"] as? String == task.id || lastTaskId == event["id"] as? String else {
            loadHistory()
            return
        }
        var finished = task
        finished.isRunning = false
        finished.stage = "已完成"
        finished.percent = 100
        finished.file = event["file"] as? String
        finished.files = event["files"] as? [String] ?? []
        if let info = event["media_info"] as? [String: Any] {
            finished.mediaInfo = Self.describe(media: info)
        }
        currentTask = finished
        loadHistory()
        // 与安卓端一致：下载完成后清空输入框，方便直接粘贴下一条
        urlText = ""
        if autoOpenFinder, let file = finished.file {
            NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: file)])
        }
    }

    private func updateEngines(from event: [String: Any]) {
        guard let payload = event["engines"] as? [String: [String: Any]] else { return }
        var list: [EngineInfo] = []
        for key in ["ytdlp", "xhs", "ks"] {
            guard let item = payload[key] else { continue }
            list.append(
                EngineInfo(
                    id: key,
                    label: item["label"] as? String ?? key,
                    ready: item["ready"] as? Bool ?? false,
                    version: item["version"] as? String ?? ""
                )
            )
        }
        engines = list
        if let payload = event["browsers"] as? [String: [String: Any]] {
            browsers = BrowserInfo.from(payload)
        }
        // Cookie 状态统一由 cookie_status / cookie_login 事件刷新，这里不再单独维护
    }

    private func updateCookieStatuses(_ payload: [String: [String: Any]]) {
        var result: [String: CookieStatus] = [:]
        for (platform, item) in payload {
            result[platform] = CookieStatus(
                hasCookie: item["has_cookie"] as? Bool ?? false,
                count: item["count"] as? Int ?? 0,
                updatedAt: item["updated_at"] as? String ?? "",
                source: item["source"] as? String ?? "",
                invalid: item["invalid"] as? Bool ?? false
            )
        }
        cookieStatuses = result
    }

    // MARK: - 历史记录

    /// 清空下载记录（可选删除文件）
    func clearHistory(deleteFiles: Bool) {
        let path = AppPaths.historyFile
        var deleted = 0
        if deleteFiles, let data = try? Data(contentsOf: path),
           let items = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] {
            for item in items {
                let files = (item["files"] as? [String]) ?? []
                for file in files where FileManager.default.fileExists(atPath: file) {
                    if (try? FileManager.default.removeItem(atPath: file)) != nil { deleted += 1 }
                }
            }
        }
        try? Data("[]".utf8).write(to: path)
        let records = history.count
        loadHistory()
        noticeMessage = deleteFiles
            ? "已清空 \(records) 条记录，并删除 \(deleted) 个文件"
            : "已清空 \(records) 条记录（文件保留）"
    }

    /// 删除单条下载记录（连同其全部源文件，不弹确认）
    /// 注意：HistoryItem.id 是每次加载时生成的 UUID，不能用来定位磁盘记录，按索引删。
    func deleteHistoryItem(at index: Int) {
        let path = AppPaths.historyFile
        guard let data = try? Data(contentsOf: path),
              var items = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]],
              index >= 0, index < items.count else { return }
        let files = items[index]["files"] as? [String] ?? []
        var deleted = 0
        for file in files where !file.isEmpty && FileManager.default.fileExists(atPath: file) {
            if (try? FileManager.default.removeItem(atPath: file)) != nil { deleted += 1 }
        }
        items.remove(at: index)
        if let out = try? JSONSerialization.data(withJSONObject: items) {
            try? out.write(to: path)
        }
        loadHistory()
        noticeMessage = deleted > 0 ? "已删除记录，连同 \(deleted) 个文件" : "已删除记录"
    }

    /// 删除记录里的单个文件（文件从记录里移除；全部删光时整条记录一并删除，不弹确认）
    func deleteHistoryFile(at index: Int, file: String) {
        if FileManager.default.fileExists(atPath: file) {
            try? FileManager.default.removeItem(atPath: file)
        }
        let path = AppPaths.historyFile
        guard let data = try? Data(contentsOf: path),
              var items = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]],
              index >= 0, index < items.count else { return }
        var files = items[index]["files"] as? [String] ?? []
        files.removeAll { $0 == file }
        if files.isEmpty {
            items.remove(at: index)
        } else {
            items[index]["files"] = files
            if (items[index]["file"] as? String) == file {
                items[index]["file"] = files.first ?? ""
            }
        }
        if let out = try? JSONSerialization.data(withJSONObject: items) {
            try? out.write(to: path)
        }
        loadHistory()
        noticeMessage = "已删除文件"
    }

    /// 某个文件所在的 history.json 下标；找不到返回 nil（「刚下载完」卡片用它定位记录）
    private func historyIndexForFile(_ file: String) -> Int? {
        guard let data = try? Data(contentsOf: AppPaths.historyFile),
              let items = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return nil }
        return items.firstIndex { item in
            let files = item["files"] as? [String] ?? []
            return files.contains(file)
        }
    }

    /// 删除「刚下载完」卡片对应的记录（连同文件）并收起卡片
    func deleteCurrentTask() {
        guard let task = currentTask else { return }
        let primary = task.files.first ?? task.file ?? ""
        if let index = historyIndexForFile(primary) {
            deleteHistoryItem(at: index)
        }
        currentTask = nil
    }

    /// 删除卡片里的单个文件：同步更新卡片与对应记录；文件全删完时收起卡片
    func deleteCurrentTaskFile(_ file: String) {
        guard var task = currentTask else { return }
        if let index = historyIndexForFile(file) {
            deleteHistoryFile(at: index, file: file)
        }
        let remaining = task.files.filter { $0 != file }
        if task.file == file || task.file == nil {
            task.file = remaining.first
        }
        task.files = remaining
        currentTask = (remaining.isEmpty && task.file == nil) ? nil : task
    }

    func loadHistory() {
        guard let data = try? Data(contentsOf: AppPaths.historyFile),
              let raw = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]]
        else {
            history = []
            return
        }
        history = raw.prefix(50).map { item in
            let files = item["files"] as? [String] ?? []
            return HistoryItem(
                platform: item["platform"] as? String ?? "",
                title: item["title"] as? String ?? "",
                author: item["author"] as? String ?? "",
                file: files.first ?? "",
                finishedAt: item["finished_at"] as? String ?? "",
                mediaInfo: Self.describe(media: item["media_info"] as? [String: Any] ?? [:]),
                files: files
            )
        }
    }

    static func describe(media: [String: Any]) -> String {
        var parts: [String] = []
        if let width = media["width"] as? Int, let height = media["height"] as? Int {
            parts.append("\(width)×\(height)")
        }
        if let duration = media["duration"] as? Double, duration > 0 {
            let minutes = Int(duration) / 60
            let seconds = Int(duration) % 60
            parts.append(String(format: "%d:%02d", minutes, seconds))
        }
        if let size = media["size"] as? Int, size > 0 {
            parts.append(ByteCountFormatter.string(fromByteCount: Int64(size), countStyle: .file))
        }
        return parts.joined(separator: " · ")
    }

    /// 用系统分享面板分享文件（支持多选文件 / AirDrop / 邮件 / 已安装的 IM）
    func share(files: [String]) {
        let urls = files.filter { !$0.isEmpty }.map { URL(fileURLWithPath: $0) }
        guard !urls.isEmpty, let window = NSApp.keyWindow, let view = window.contentView else { return }
        let picker = NSSharingServicePicker(items: urls)
        picker.show(relativeTo: .zero, of: view, preferredEdge: .minY)
    }

    func reveal(file: String) {
        guard !file.isEmpty else { return }
        NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: file)])
    }

    func openDownloadFolder() {
        NSWorkspace.shared.open(URL(fileURLWithPath: outDir))
    }

    func chooseDownloadFolder() {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.canCreateDirectories = true
        panel.directoryURL = URL(fileURLWithPath: outDir)
        if panel.runModal() == .OK, let url = panel.url {
            outDir = url.path
        }
    }
}
