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
