import Foundation

/// 与 Python 桥接进程通信：stdin 发送 JSON 指令，stdout 接收 JSON 事件。
final class EngineBridge {
    var onEvent: (([String: Any]) -> Void)?

    private var process: Process?
    private var inputHandle: FileHandle?
    private var buffer = Data()
    private let ioQueue = DispatchQueue(label: "videograbber.bridge.io")
    private var logHandle: FileHandle?

    var isRunning: Bool { process?.isRunning ?? false }

    func start() throws {
        guard !isRunning else { return }
        try AppPaths.ensureLogHandle(&logHandle)

        let task = Process()
        task.executableURL = AppPaths.bridgePython
        task.arguments = [AppPaths.bridgeScript.path]
        var environment = ProcessInfo.processInfo.environment
        // 自动继承 macOS 系统代理（Clash/Surge 等），X / YouTube 无需手动配置
        if let proxy = Self.systemProxy() {
            environment["HTTP_PROXY"] = proxy
            environment["HTTPS_PROXY"] = proxy
            environment["http_proxy"] = proxy
            environment["https_proxy"] = proxy
        }
        environment["PYTHONUNBUFFERED"] = "1"
        environment["PYTHONIOENCODING"] = "utf-8"
        environment["PYTHONDONTWRITEBYTECODE"] = "1"  // 不在应用包内写 __pycache__，保持签名完整
        task.environment = environment

        let stdinPipe = Pipe()
        let stdoutPipe = Pipe()
        let stderrPipe = Pipe()
        task.standardInput = stdinPipe
        task.standardOutput = stdoutPipe
        task.standardError = stderrPipe

        stdoutPipe.fileHandleForReading.readabilityHandler = { [weak self] handle in
            let data = handle.availableData
            guard !data.isEmpty else { return }
            self?.ioQueue.async { self?.consume(data) }
        }
        stderrPipe.fileHandleForReading.readabilityHandler = { [weak self] handle in
            let data = handle.availableData
            guard !data.isEmpty, let text = String(data: data, encoding: .utf8) else { return }
            self?.logHandle?.write(Data(text.utf8))
        }
        task.terminationHandler = { [weak self] proc in
            self?.process = nil
            self?.inputHandle = nil
            DispatchQueue.main.async {
                self?.onEvent?(["event": "bridge_exit", "code": Int(proc.terminationStatus)])
            }
        }

        try task.run()
        process = task
        inputHandle = stdinPipe.fileHandleForWriting
    }

    func stop() {
        inputHandle?.closeFile()
        process?.terminate()
        process = nil
        inputHandle = nil
    }

    @discardableResult
    func send(_ payload: [String: Any]) -> String {
        var message = payload
        if message["id"] == nil {
            message["id"] = UUID().uuidString
        }
        let id = message["id"] as? String ?? ""
        guard
            let handle = inputHandle,
            let data = try? JSONSerialization.data(withJSONObject: message),
            var line = String(data: data, encoding: .utf8)
        else {
            return id
        }
        line.append("\n")
        handle.write(Data(line.utf8))
        return id
    }

    /// 读取 macOS 系统 HTTP/HTTPS 代理（如 Clash 的 127.0.0.1:7897）
    static func systemProxy() -> String? {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/sbin/scutil")
        process.arguments = ["--proxy"]
        let pipe = Pipe()
        process.standardOutput = pipe
        guard (try? process.run()) != nil else { return nil }
        process.waitUntilExit()
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        let text = String(data: data, encoding: .utf8) ?? ""
        func value(_ key: String) -> String? {
            guard let range = text.range(of: "\(key) : ") else { return nil }
            return text[range.upperBound...].split(separator: "\n").first.map { String($0).trimmingCharacters(in: .whitespaces) }
        }
        let enabled = value("HTTPSEnable") == "1" || value("HTTPEnable") == "1"
        guard enabled, let host = value("HTTPSProxy") ?? value("HTTPProxy") else { return nil }
        let port = value("HTTPSPort") ?? value("HTTPPort") ?? "7890"
        return "http://\(host):\(port)"
    }

    private func consume(_ data: Data) {
        buffer.append(data)
        while let newline = buffer.firstIndex(of: 0x0A) {
            let lineData = buffer.subdata(in: buffer.startIndex..<newline)
            buffer.removeSubrange(buffer.startIndex...newline)
            guard !lineData.isEmpty,
                  let object = try? JSONSerialization.jsonObject(with: lineData) as? [String: Any]
            else { continue }
            DispatchQueue.main.async { [weak self] in
                self?.onEvent?(object)
            }
        }
    }
}

extension AppPaths {
    static func ensureLogHandle(_ handle: inout FileHandle?) throws {
        guard handle == nil else { return }
        ensureDirectories()
        let url = logDirectory.appendingPathComponent("app.log")
        if !FileManager.default.fileExists(atPath: url.path) {
            FileManager.default.createFile(atPath: url.path, contents: nil)
        }
        handle = try FileHandle(forWritingTo: url)
        handle?.seekToEndOfFile()
    }
}
