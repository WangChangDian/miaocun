import Foundation

/// 统一管理应用运行期需要的各类路径。
enum AppPaths {
    static let appName = "视频下载器"

    static var supportDirectory: URL {
        FileManager.default
            .homeDirectoryForCurrentUser
            .appendingPathComponent("Library/Application Support/\(appName)", isDirectory: true)
    }

    static var historyFile: URL { supportDirectory.appendingPathComponent("history.json") }
    static var settingsFile: URL { supportDirectory.appendingPathComponent("settings.json") }
    static var logDirectory: URL { supportDirectory.appendingPathComponent("logs", isDirectory: true) }

    /// App 内置资源根目录（打包后指向 Contents/Resources；开发运行回退到工程目录）。
    static var resourceRoot: URL {
        if let root = Bundle.main.resourceURL,
           FileManager.default.fileExists(atPath: root.appendingPathComponent("engine/bridge.py").path) {
            return root
        }
        // #filePath = <project>/app/Sources/VideoGrabber/Services/AppPaths.swift
        let source = URL(fileURLWithPath: #filePath)
        return source
            .deletingLastPathComponent()  // Services
            .deletingLastPathComponent()  // VideoGrabber
            .deletingLastPathComponent()  // Sources
            .deletingLastPathComponent()  // app
            .deletingLastPathComponent()  // 工程根目录
    }

    static var bridgeScript: URL { resourceRoot.appendingPathComponent("engine/bridge.py") }
    static var bootstrapScript: URL { resourceRoot.appendingPathComponent("scripts/bootstrap-engine.sh") }

    static var engineRoot: URL { supportDirectory.appendingPathComponent("engines", isDirectory: true) }
    static var browsersDirectory: URL { supportDirectory.appendingPathComponent("browsers", isDirectory: true) }

    /// 安装包内置的便携 Python（runtime 随包分发，用户下载后开箱即用，无需装任何环境）
    static var bundledRuntimePython: URL? {
        let candidate = resourceRoot.appendingPathComponent("runtime/python/bin/python3")
        return FileManager.default.isExecutableFile(atPath: candidate.path) ? candidate : nil
    }

    /// 引擎用哪个 Python：优先内置运行时；旧版安装（无 runtime）回退到首次初始化生成的 venv
    static var bridgePython: URL {
        bundledRuntimePython ?? engineRoot.appendingPathComponent("ytdlp/venv/bin/python3")
    }

    static var defaultDownloadDirectory: URL {
        FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Downloads/视频下载", isDirectory: true)
    }

    static func ensureDirectories() {
        for url in [supportDirectory, logDirectory] {
            try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        }
    }
}
