import Foundation

struct EngineInfo: Identifiable, Equatable {
    let id: String
    let label: String
    let ready: Bool
    let version: String
}

struct CookieStatus: Equatable {
    var hasCookie: Bool = false
    var count: Int = 0
    var updatedAt: String = ""
    var source: String = ""
    /// 下载时被平台拒绝（引擎标记），或重新登录时发现浏览器里的会话已过期
    var invalid: Bool = false

    /// 距上次更新超过这么多天就提示「建议重新登录」（与 Android 端一致）
    static let staleDays = 30

    /// 上次更新时间距现在多少天；时间解析不了就返回 nil
    private var ageInDays: Int? {
        guard !updatedAt.isEmpty else { return nil }
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
        formatter.locale = Locale(identifier: "zh_CN")
        guard let date = formatter.date(from: updatedAt) else { return nil }
        return Int(Date().timeIntervalSince(date) / 86_400)
    }

    /// 已配置但很久没更新 —— 可能已失效
    var isStale: Bool {
        guard hasCookie, let days = ageInDays else { return false }
        return days >= Self.staleDays
    }

    /// 界面上的状态文案
    func stateText(required: Bool) -> String {
        guard hasCookie else {
            return required ? "未登录（必须登录后才能下载）" : "未配置（可选）"
        }
        if invalid {
            return "登录已失效，请重新登录"
        }
        let time = updatedAt.isEmpty ? "" : " · \(updatedAt)"
        let base = isStale
            ? "已登录（\(count) 条\(time)）· 建议重新登录"
            : "已登录 · \(count) 条\(time)"
        // 标出当前用的是哪一份 Cookie（本机登录 / 手动粘贴 / 云端·手机端 / 云端·电脑端）
        let origin = source.isEmpty ? "" : " · 当前：\(source)"
        return base + origin
    }

    /// 按钮文案：没配过「一键获取」，已有就「重新登录」
    var actionTitle: String { hasCookie ? "重新登录" : "一键获取" }
}

/// 版本更新信息
struct UpdateInfo: Identifiable, Equatable {
    var versionName: String = ""
    var versionCode: Int = 0
    var changelog: String = ""
    var downloadURL: String = ""
    var force: Bool = false
    /// 安装包大小（字节），后台发布时回填
    var fileSize: Int64 = 0

    var sizeText: String {
        guard fileSize > 0 else { return "" }
        return " · \(fileSize / 1024 / 1024)MB"
    }

    var id: String { "\(versionName)-\(versionCode)" }
}

struct BrowserInfo: Identifiable, Equatable {
    let id: String
    let label: String
    let ready: Bool
    let detail: String

    static func from(_ payload: [String: [String: Any]]) -> [BrowserInfo] {
        let names = ["chromium": "内置内核（Chrome for Testing）", "chrome": "系统 Chrome", "msedge": "Microsoft Edge"]
        return names.compactMap { key, label in
            guard let item = payload[key] else { return nil }
            return BrowserInfo(
                id: key,
                label: label,
                ready: item["ready"] as? Bool ?? false,
                detail: item["detail"] as? String ?? ""
            )
        }
    }
}

struct LoginSession: Identifiable, Equatable {
    let id: String
    let platform: String
    /// 面板上的提示文字：自检结果 / 正在保存 / 登录态已过期，由引擎事件更新
    var notice: String?
    /// 已点「完成」，等引擎确认保存结果（登录态过期时会被引擎拦下）
    var busy: Bool = false
}

struct DownloadTaskState: Identifiable, Equatable {
    let id: String
    var platform: String = ""
    var label: String = "解析中"
    var stage: String = "准备中"
    var percent: Double?
    var downloaded: Int64 = 0
    var total: Int64 = 0
    var speed: Double?
    var isRunning: Bool = true
    var message: String = ""
    var file: String?
    var files: [String] = []
    var mediaInfo: String = ""

    var progressText: String {
        if let percent {
            return String(format: "%.1f%%", percent)
        }
        if total > 0 {
            return "\(Self.sizeText(downloaded)) / \(Self.sizeText(total))"
        }
        return stage
    }

    static func sizeText(_ value: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: value, countStyle: .file)
    }
}

struct HistoryItem: Identifiable, Equatable {
    let id = UUID()
    var platform: String
    var title: String
    var author: String
    var file: String
    var finishedAt: String
    var mediaInfo: String = ""
    /// 这一条记录下载出的全部文件（图集 / 批量 / 多文件时都要展示缩略图）
    var files: [String] = []

    var displayName: String {
        if !author.isEmpty && !title.isEmpty { return "\(author) - \(title)" }
        return title.isEmpty ? (file as NSString).lastPathComponent : title
    }

    var platformName: String {
        switch platform {
        case "douyin": return "抖音"
        case "kuaishou": return "快手"
        case "xiaohongshu": return "小红书"
        case "x": return "X"
        case "youtube": return "YouTube"
        case "web": return "网页资源"
        case "file": return "文件"
        default: return platform
        }
    }
}

// MARK: - 多链接批量

/// 多链接批量：链接识别规则（与安卓 / Windows 端保持一致）
enum LinkParser {
    private static let pattern = #"https?://[^\s\u4e00-\u9fff，。！？；：、“”‘’《》【】（）()"'<>\\^`{|}]+"#
    private static let trailing = CharacterSet(charactersIn: ".,，。；;!！?？、)）】》>\"'")

    static func allLinks(in text: String, limit: Int = 50) -> [String] {
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return [] }
        let ns = text as NSString
        var seen = Set<String>()
        var result: [String] = []
        for match in regex.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            var url = ns.substring(with: match.range)
            while let last = url.unicodeScalars.last, trailing.contains(last) {
                url.removeLast()
            }
            guard !url.isEmpty, !seen.contains(url) else { continue }
            seen.insert(url)
            result.append(url)
            if result.count >= limit { break }
        }
        return result
    }

    /// 主页 / 收藏页链接：多链接批量里跳过（提示单独用「批量下载」）
    static func isHomepage(_ url: String) -> Bool {
        let lower = url.lowercased()
        return lower.contains("douyin.com/user/") || lower.contains("xiaohongshu.com/user/profile/")
    }

    static func platformLabel(for url: String) -> String {
        let lower = url.lowercased()
        let rules: [(String, String)] = [
            ("douyin.com", "抖音"), ("iesdouyin.com", "抖音"),
            ("kuaishou.com", "快手"), ("kuaishou.cn", "快手"),
            ("xhslink", "小红书"), ("xiaohongshu.com", "小红书"), ("rednote.com", "小红书"),
            ("weixin.qq.com", "微信视频号"),
            ("bilibili.com", "B 站"), ("b23.tv", "B 站"), ("bili2233.cn", "B 站"),
            ("weibo.com", "微博"), ("weibo.cn", "微博"),
            ("tiktok.com", "TikTok"), ("instagram.com", "Instagram"),
            ("x.com", "X"), ("twitter.com", "X"), ("t.co", "X"),
            ("youtube.com", "YouTube"), ("youtu.be", "YouTube"),
            ("ixigua.com", "西瓜视频"), ("reddit.com", "Reddit"), ("redd.it", "Reddit"),
            ("vimeo.com", "Vimeo"), ("twitch.tv", "Twitch"),
            ("music.163.com", "网易云音乐"), ("y.qq.com", "QQ 音乐"),
        ]
        for (host, label) in rules where lower.contains(host) { return label }
        return "直链 / 网页"
    }
}

enum BatchItemStatus: String {
    case waiting, parsing, queued, downloading, success, failed, skipped, cancelled

    var isTerminal: Bool {
        self == .success || self == .failed || self == .skipped || self == .cancelled
    }

    var label: String {
        switch self {
        case .waiting: return "等待"
        case .parsing: return "解析中…"
        case .queued: return "等待下载"
        case .downloading: return "下载中"
        case .success: return "成功"
        case .failed: return "失败"
        case .skipped: return "已跳过"
        case .cancelled: return "已取消"
        }
    }
}

struct BatchLinkItemState: Identifiable, Equatable {
    let id = UUID()
    let url: String
    let platform: String
    var title: String = ""
    var status: BatchItemStatus = .waiting
    var message: String = ""
    var files: [String] = []
    var mediaInfo: String = ""
}

struct BatchDownloadState: Equatable {
    let id: String
    var items: [BatchLinkItemState]
    var activeIndex: Int = -1
    var finished: Bool = false

    var succeeded: Int { items.filter { $0.status == .success }.count }
    var failed: Int { items.filter { $0.status == .failed }.count }
    var cancelled: Int { items.filter { $0.status == .cancelled }.count }
    var skipped: Int { items.filter { $0.status == .skipped }.count }
    var doneCount: Int { items.filter { $0.status.isTerminal }.count }
    var percent: Double { items.isEmpty ? 0 : Double(doneCount) / Double(items.count) }
}
