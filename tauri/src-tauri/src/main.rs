// 视频下载器 · Tauri 外壳
// 复用与 macOS/Android 相同的一套思路：Python 引擎（bridge.py）以 JSON Lines 协议通信。
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use serde::Serialize;
use serde_json::Value;
use std::io::{BufRead, BufReader, Write};
use std::path::PathBuf;
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::Mutex;
use tauri::{AppHandle, Emitter, Manager, State};

struct EngineState {
    child: Mutex<Option<Child>>,
    stdin: Mutex<Option<ChildStdin>>,
}

#[derive(Serialize)]
struct EngineInfo {
    python: String,
    bridge: String,
    support_dir: String,
    running: bool,
}

fn app_name() -> &'static str {
    "视频下载器"
}

/// 与 bridge.py 保持一致的跨平台数据目录
fn support_dir() -> PathBuf {
    if let Ok(value) = std::env::var("VIDEOGRABBER_HOME") {
        if !value.is_empty() {
            return PathBuf::from(value);
        }
    }
    let home = dirs_home();
    if cfg!(target_os = "windows") {
        let appdata = std::env::var("APPDATA").unwrap_or_else(|_| format!("{}\\AppData\\Roaming", home));
        PathBuf::from(appdata).join(app_name())
    } else if cfg!(target_os = "macos") {
        PathBuf::from(home).join("Library/Application Support").join(app_name())
    } else {
        PathBuf::from(home).join(".local/share").join(app_name())
    }
}

fn dirs_home() -> String {
    std::env::var("HOME")
        .or_else(|_| std::env::var("USERPROFILE"))
        .unwrap_or_else(|_| ".".to_string())
}

/// 内置运行时目录（安装包自带；由 scripts/bundle-runtime.py 生成）
///
/// 查找顺序：环境变量 → 应用资源目录 → 开发时的仓库目录。
/// 有它的用户不需要装 Python / ffmpeg，也不用联网做首次初始化。
fn runtime_dir(app: &AppHandle) -> Option<PathBuf> {
    if let Ok(value) = std::env::var("VIDEOGRABBER_RUNTIME") {
        if !value.is_empty() {
            let path = PathBuf::from(value);
            if path.exists() {
                return Some(path);
            }
        }
    }
    if let Ok(resources) = app.path().resource_dir() {
        let candidate = resources.join("runtime");
        if candidate.exists() {
            return Some(candidate);
        }
    }
    let mut current = std::env::current_exe().ok()?;
    for _ in 0..6 {
        current = current.parent()?.to_path_buf();
        let candidate = current.join("runtime");
        if candidate.exists() {
            return Some(candidate);
        }
    }
    None
}

/// 内置运行时里的便携 Python（Windows: python/python.exe；其它: python/bin/python3）
fn bundled_python(app: &AppHandle) -> Option<PathBuf> {
    let runtime = runtime_dir(app)?;
    let candidates = if cfg!(target_os = "windows") {
        vec![runtime.join("python/python.exe")]
    } else {
        vec![runtime.join("python/bin/python3"), runtime.join("python/bin/python")]
    };
    candidates.into_iter().find(|path| path.exists())
}


/// Windows 上启动子进程时不让它弹黑框（0x08000000 = CREATE_NO_WINDOW）
#[cfg(target_os = "windows")]
fn hide_console(command: &mut Command) {
    use std::os::windows::process::CommandExt;
    command.creation_flags(0x0800_0000);
}

#[cfg(not(target_os = "windows"))]
fn hide_console(_command: &mut Command) {}

/// 跑引擎用哪个 Python：内置运行时优先，其次用户数据目录里的 venv（开发机）
fn engine_python(app: &AppHandle) -> Option<PathBuf> {
    if let Some(bundled) = bundled_python(app) {
        return Some(bundled);
    }
    let venv = support_dir().join("engines/ytdlp/venv");
    let candidate = if cfg!(target_os = "windows") {
        venv.join("Scripts/python.exe")
    } else {
        venv.join("bin/python3")
    };
    candidate.exists().then_some(candidate)
}

/// 查找可用的 Python 3.12+（用于首次初始化）
fn system_python() -> Option<PathBuf> {
    if let Ok(value) = std::env::var("VIDEOGRABBER_PYTHON") {
        if !value.is_empty() {
            return Some(PathBuf::from(value));
        }
    }
    let names: Vec<&str> = if cfg!(target_os = "windows") {
        vec!["python", "python3"]
    } else {
        vec!["python3.13", "python3.12", "python3"]
    };
    for name in names {
        let mut probe = Command::new(name);
        probe.arg("--version");
        hide_console(&mut probe);
        if let Ok(output) = probe.output() {
            if output.status.success() {
                let version = String::from_utf8_lossy(&output.stdout);
                if version.contains("3.1") {
                    return Some(PathBuf::from(name));
                }
            }
        }
    }
    for candidate in ["/opt/homebrew/bin/python3.13", "/opt/homebrew/bin/python3.12", "/usr/bin/python3"] {
        let path = PathBuf::from(candidate);
        if path.exists() {
            return Some(path);
        }
    }
    None
}

/// bridge.py 的位置：打包后在 resources/engine/，开发时向上查找工程目录
fn bridge_script(app: &AppHandle) -> Option<PathBuf> {
    if let Ok(root) = std::env::var("VIDEOGRABBER_ROOT") {
        let candidate = PathBuf::from(root).join("engine/bridge.py");
        if candidate.exists() {
            return Some(candidate);
        }
    }
    if let Ok(resources) = app.path().resource_dir() {
        let candidate = resources.join("engine/bridge.py");
        if candidate.exists() {
            return Some(candidate);
        }
    }
    let mut current = std::env::current_exe().ok()?;
    for _ in 0..6 {
        current = current.parent()?.to_path_buf();
        let candidate = current.join("engine/bridge.py");
        if candidate.exists() {
            return Some(candidate);
        }
    }
    None
}

#[tauri::command]
fn engine_info(app: AppHandle, state: State<EngineState>) -> EngineInfo {
    let running = state.child.lock().map(|guard| guard.is_some()).unwrap_or(false);
    EngineInfo {
        python: engine_python(&app).map(|p| p.display().to_string()).unwrap_or_default(),
        bridge: bridge_script(&app).map(|p| p.display().to_string()).unwrap_or_default(),
        support_dir: support_dir().display().to_string(),
        running,
    }
}

#[tauri::command]
fn engine_start(app: AppHandle, state: State<EngineState>) -> Result<String, String> {
    if state.child.lock().map_err(|_| "状态锁失败")?.is_some() {
        return Ok("already-running".into());
    }
    let python = engine_python(&app).ok_or("未找到解析引擎运行时，请重新安装应用")?;
    let bridge = bridge_script(&app).ok_or("未找到 engine/bridge.py")?;

    let mut command = Command::new(&python);
    command
        .arg(&bridge)
        .env("PYTHONUNBUFFERED", "1")
        .env("PYTHONIOENCODING", "utf-8")
        .env("PYTHONDONTWRITEBYTECODE", "1")
        .env("VIDEOGRABBER_PYTHON", python.display().to_string());

    // 内置运行时：把浏览器内核与 ffmpeg 的路径一并注入，用户侧零安装
    if let Some(runtime) = runtime_dir(&app) {
        command.env("VIDEOGRABBER_RUNTIME", runtime.display().to_string());
        let browsers = runtime.join("browsers");
        if browsers.exists() {
            command.env("VIDEOGRABBER_BROWSERS_PATH", browsers.display().to_string());
        }
        // 内置依赖目录（pip --target 装的）也要通过 PYTHONPATH 暴露给引擎及其子进程
        let pkgs = runtime.join("pkgs").join("ytdlp");
        if pkgs.exists() {
            let current = std::env::var("PYTHONPATH").unwrap_or_default();
            let joiner = if cfg!(target_os = "windows") { ";" } else { ":" };
            command.env("PYTHONPATH", format!("{}{}{}", pkgs.display(), joiner, current));
        }
        let ffmpeg_bin = runtime.join("ffmpeg").join("bin");
        let ffmpeg_dir = if ffmpeg_bin.exists() { ffmpeg_bin } else { runtime.join("ffmpeg") };
        if ffmpeg_dir.exists() {
            let current_path = std::env::var("PATH").unwrap_or_default();
            let joiner = if cfg!(target_os = "windows") { ";" } else { ":" };
            command.env(
                "PATH",
                format!("{}{}{}", ffmpeg_dir.display(), joiner, current_path),
            );
        }
    }

    hide_console(&mut command);
    let mut child = command
        .env("PYTHONIOENCODING", "utf-8")
        .env("PYTHONUTF8", "1")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|error| format!("启动引擎失败: {error}"))?;

    let stdout = child.stdout.take().ok_or("无法读取引擎输出")?;
    let stderr = child.stderr.take().ok_or("无法读取引擎日志")?;
    let stdin = child.stdin.take().ok_or("无法写入引擎输入")?;

    let app_for_stdout = app.clone();
    std::thread::spawn(move || {
        // 注意：不能用 lines().map_while(Result::ok) —— 一旦遇到非法 UTF-8 的行，
        // 迭代会直接结束，整个事件流就断在这里（Windows 上 Python 输出中文时踩过）。
        // 这里按字节读行 + from_utf8_lossy，坏字节只影响那一行，不会掐断后续事件。
        let mut reader = BufReader::new(stdout);
        let mut buffer = Vec::new();
        loop {
            buffer.clear();
            match reader.read_until(b'\n', &mut buffer) {
                Ok(0) => break,
                Ok(_) => {
                    let text = String::from_utf8_lossy(&buffer);
                    let trimmed = text.trim();
                    if trimmed.is_empty() {
                        continue;
                    }
                    if let Ok(value) = serde_json::from_str::<Value>(trimmed) {
                        let _ = app_for_stdout.emit("bridge-event", value);
                    }
                }
                Err(_) => break,
            }
        }
    });
    let app_for_stderr = app.clone();
    std::thread::spawn(move || {
        for line in BufReader::new(stderr).lines().map_while(Result::ok) {
            let _ = app_for_stderr.emit("engine-log", line);
        }
    });

    *state.stdin.lock().map_err(|_| "状态锁失败")? = Some(stdin);
    *state.child.lock().map_err(|_| "状态锁失败")? = Some(child);
    Ok("started".into())
}



#[tauri::command]
fn engine_send(state: State<EngineState>, payload: Value) -> Result<(), String> {
    let mut guard = state.stdin.lock().map_err(|_| "状态锁失败")?;
    let stdin = guard.as_mut().ok_or("引擎尚未启动")?;
    let mut line = serde_json::to_string(&payload).map_err(|error| error.to_string())?;
    line.push('\n');
    stdin.write_all(line.as_bytes()).map_err(|error| error.to_string())?;
    stdin.flush().map_err(|error| error.to_string())
}

#[tauri::command]
fn read_history(app: AppHandle) -> Result<Value, String> {
    let path = support_dir().join("history.json");
    if !path.exists() {
        return Ok(Value::Array(vec![]));
    }
    let text = std::fs::read_to_string(&path).map_err(|error| error.to_string())?;
    let value: Value = serde_json::from_str(&text).map_err(|error| error.to_string())?;
    // 允许界面用 asset 协议读取这些文件（缩略图 / 点开预览 / 播放 / PDF）
    if let Some(items) = value.as_array() {
        let scope = app.asset_protocol_scope();
        for item in items {
            if let Some(files) = item.get("files").and_then(|value| value.as_array()) {
                for file in files {
                    if let Some(path) = file.as_str() {
                        let _ = scope.allow_file(path);
                    }
                }
            }
        }
    }
    Ok(value)
}

/// 批量取文件大小（卡片里每个文件都要显示自己的大小）
#[tauri::command]
fn file_sizes(paths: Vec<String>) -> Vec<u64> {
    paths
        .iter()
        .map(|path| std::fs::metadata(path).map(|meta| meta.len()).unwrap_or(0))
        .collect()
}

/// 弹出系统「选择文件夹」对话框，返回用户选中的目录（取消返回 None）。
///
/// 不引入额外插件：Windows 走 PowerShell 的 FolderBrowserDialog，macOS 走 osascript 的 choose folder。
#[tauri::command]
fn pick_folder() -> Option<String> {
    #[cfg(target_os = "windows")]
    {
        let script = "Add-Type -AssemblyName System.Windows.Forms; \
                      $dialog = New-Object System.Windows.Forms.FolderBrowserDialog; \
                      $dialog.Description = '选择下载目录'; \
                      if ($dialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) { Write-Output $dialog.SelectedPath }";
        if let Ok(output) = std::process::Command::new("powershell")
            .args(["-NoProfile", "-STA", "-Command", script])
            .output()
        {
            let path = String::from_utf8_lossy(&output.stdout).trim().to_string();
            if !path.is_empty() {
                return Some(path);
            }
        }
    }
    #[cfg(target_os = "macos")]
    {
        if let Ok(output) = std::process::Command::new("osascript")
            .args(["-e", "POSIX path of (choose folder with prompt \"选择下载目录\")"])
            .output()
        {
            let path = String::from_utf8_lossy(&output.stdout).trim().to_string();
            if !path.is_empty() {
                return Some(path);
            }
        }
    }
    None
}

/// 用系统默认程序打开下载好的文件（视频→播放器 / PDF→阅读器 / 其他→文件管理器）


/// 在资源管理器 / 访达中定位文件（打开所在目录并选中它）
#[tauri::command]
fn reveal_file(path: String) -> Result<(), String> {
    if path.trim().is_empty() {
        return Err("路径为空".into());
    }
    #[cfg(target_os = "windows")]
    {
        let mut command = Command::new("explorer");
        command.arg(format!("/select,{}", path));
        hide_console(&mut command);
        let _ = command.status();  // explorer 成功时也可能返回非 0，这里不校验
        return Ok(());
    }
    #[cfg(target_os = "macos")]
    {
        let mut command = Command::new("open");
        command.args(["-R", &path]);
        let _ = command.status();
        return Ok(());
    }
    #[allow(unreachable_code)]
    Err("当前平台不支持".into())
}

/// 打开「保存目录」：直接读引擎的 settings.json（比让前端传更可靠）
#[tauri::command]
fn open_download_dir() -> Result<String, String> {
    let path = download_dir();
    open_path(path.display().to_string())?;
    Ok(path.display().to_string())
}

/// 默认下载目录：settings.json 里的 out_dir，取不到就用 ~/Downloads/视频下载
fn download_dir() -> PathBuf {
    let settings_path = support_dir().join("settings.json");
    if let Ok(text) = std::fs::read_to_string(&settings_path) {
        if let Ok(value) = serde_json::from_str::<Value>(&text) {
            if let Some(dir) = value.get("out_dir").and_then(|item| item.as_str()) {
                let trimmed = dir.trim();
                if !trimmed.is_empty() {
                    let candidate = PathBuf::from(trimmed);
                    if candidate.exists() {
                        return candidate;
                    }
                }
            }
        }
    }
    let home = std::env::var("USERPROFILE")
        .or_else(|_| std::env::var("HOME"))
        .unwrap_or_default();
    PathBuf::from(home).join("Downloads").join("视频下载")
}

/// 分享文件：Windows 用 Set-Clipboard -Path 把「文件本身」放进剪贴板，
/// 可以直接粘贴到微信 / QQ；macOS 用 Finder 的复制。
#[tauri::command]
fn share_file(path: String) -> Result<String, String> {
    if path.trim().is_empty() {
        return Err("路径为空".into());
    }
    #[cfg(target_os = "windows")]
    {
        let escaped = path.replace('\'', "''");
        let script = format!("Set-Clipboard -LiteralPath '{}'", escaped);
        let mut command = Command::new("powershell");
        command.args(["-NoProfile", "-Command", &script]);
        hide_console(&mut command);
        match command.status() {
            Ok(code) if code.success() => return Ok("已复制文件，可直接粘贴到微信 / QQ 发送".into()),
            Ok(code) => return Err(format!("复制失败，退出码 {code}")),
            Err(error) => return Err(format!("复制失败: {error}")),
        }
    }
    #[cfg(target_os = "macos")]
    {
        let mut command = Command::new("osascript");
        command.args([
            "-e",
            &format!("set the clipboard to (POSIX file \"{}\")", path.replace('"', "\\\"")),
        ]);
        match command.status() {
            Ok(code) if code.success() => return Ok("已复制文件，可直接粘贴到微信 / QQ 发送".into()),
            Ok(code) => return Err(format!("复制失败，退出码 {code}")),
            Err(error) => return Err(format!("复制失败: {error}")),
        }
    }
    #[allow(unreachable_code)]
    Err("当前平台不支持分享".into())
}

/// 清空下载历史（可同时删除记录对应的文件），返回 (清空条数, 删除文件数)
#[tauri::command]
fn clear_history(delete_files: bool) -> Result<Value, String> {
    let history_path = support_dir().join("history.json");
    if !history_path.exists() {
        return Ok(serde_json::json!({ "records": 0, "files": 0 }));
    }
    let text = std::fs::read_to_string(&history_path).map_err(|error| error.to_string())?;
    let items: Vec<Value> = serde_json::from_str(&text).unwrap_or_default();
    let mut deleted = 0usize;
    if delete_files {
        for item in &items {
            let files = item
                .get("files")
                .and_then(|value| value.as_array())
                .map(|array| array.iter().filter_map(|entry| entry.as_str()).collect::<Vec<_>>())
                .unwrap_or_default();
            for file in files {
                if std::fs::remove_file(file).is_ok() {
                    deleted += 1;
                }
            }
        }
    }
    std::fs::write(&history_path, "[]").map_err(|error| error.to_string())?;
    Ok(serde_json::json!({ "records": items.len(), "files": deleted }))
}

/// 删除单条下载记录（可连同其全部文件，不弹确认）；index 是 read_history 返回数组的序号
#[tauri::command]
fn delete_history_item(index: usize, delete_files: bool) -> Result<Value, String> {
    let history_path = support_dir().join("history.json");
    let text = std::fs::read_to_string(&history_path).map_err(|error| error.to_string())?;
    let mut items: Vec<Value> = serde_json::from_str(&text).unwrap_or_default();
    if index >= items.len() {
        return Err("记录不存在".into());
    }
    let mut deleted = 0usize;
    if delete_files {
        let files = items[index]
            .get("files")
            .and_then(|value| value.as_array())
            .map(|array| array.iter().filter_map(|entry| entry.as_str()).collect::<Vec<_>>())
            .unwrap_or_default();
        for file in files {
            if std::fs::remove_file(file).is_ok() {
                deleted += 1;
            }
        }
    }
    items.remove(index);
    let out = serde_json::to_string(&items).map_err(|error| error.to_string())?;
    std::fs::write(&history_path, out).map_err(|error| error.to_string())?;
    Ok(serde_json::json!({ "files": deleted }))
}

/// 删除记录里的单个文件（从记录移除；全部删光时整条记录一并删除，不弹确认）
#[tauri::command]
fn delete_history_file(index: usize, path: String) -> Result<Value, String> {
    let history_path = support_dir().join("history.json");
    let text = std::fs::read_to_string(&history_path).map_err(|error| error.to_string())?;
    let mut items: Vec<Value> = serde_json::from_str(&text).unwrap_or_default();
    if index >= items.len() {
        return Err("记录不存在".into());
    }
    let _ = std::fs::remove_file(&path);
    if let Some(files) = items[index].get_mut("files").and_then(|value| value.as_array_mut()) {
        files.retain(|entry| entry.as_str() != Some(path.as_str()));
    }
    let empty = items[index]
        .get("files")
        .and_then(|value| value.as_array())
        .map(|array| array.is_empty())
        .unwrap_or(true);
    if empty {
        items.remove(index);
    }
    let out = serde_json::to_string(&items).map_err(|error| error.to_string())?;
    std::fs::write(&history_path, out).map_err(|error| error.to_string())?;
    Ok(serde_json::json!({ "ok": true }))
}

#[tauri::command]
fn open_path(path: String) -> Result<(), String> {
    if path.trim().is_empty() {
        return Err("路径为空".into());
    }
    let status = if cfg!(target_os = "macos") {
        Command::new("open").arg(&path).status()
    } else if cfg!(target_os = "windows") {
        let mut command = Command::new("cmd");
        command.args(["/C", "start", "", &path]);
        hide_console(&mut command);
        command.status()
    } else {
        Command::new("xdg-open").arg(&path).status()
    };
    match status {
        Ok(code) if code.success() => Ok(()),
        Ok(code) => Err(format!("打开失败，退出码 {code}")),
        Err(error) => Err(format!("打开失败: {error}")),
    }
}

/// 用系统默认浏览器打开链接（版本更新的「前往下载」）
#[tauri::command]
fn open_url(url: String) -> Result<(), String> {
    let target = url.trim();
    if target.is_empty() {
        return Err("链接为空".into());
    }
    if !(target.starts_with("http://") || target.starts_with("https://")) {
        return Err("只允许打开 http/https 链接".into());
    }
    let status = if cfg!(target_os = "windows") {
        // `start` 是 cmd 内置命令，第一个引号参数会被当成窗口标题，所以给一个空标题
        {
            let mut command = Command::new("cmd");
            command.args(["/C", "start", "", target]);
            hide_console(&mut command);
            command.status()
        }
    } else if cfg!(target_os = "macos") {
        Command::new("open").arg(target).status()
    } else {
        Command::new("xdg-open").arg(target).status()
    };
    match status {
        Ok(code) if code.success() => Ok(()),
        Ok(code) => Err(format!("打开链接失败，退出码 {code}")),
        Err(error) => Err(format!("打开链接失败: {error}")),
    }
}

/// 当前外壳版本号（源头是仓库根目录 VERSION → scripts/sync-tauri-version.py）
#[tauri::command]
fn app_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

#[tauri::command]
fn run_bootstrap(app: AppHandle) -> Result<String, String> {
    let python = system_python().ok_or("未找到 Python 3.12+，请先安装后再初始化")?;
    let script = bootstrap_script(&app).ok_or("未找到 bootstrap-engine.py")?;
    let app_handle = app.clone();
    std::thread::spawn(move || {
        let output = Command::new(python)
            .arg(&script)
            .env("PYTHONUNBUFFERED", "1")
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .output();
        match output {
            Ok(result) => {
                let text = format!(
                    "{}{}",
                    String::from_utf8_lossy(&result.stdout),
                    String::from_utf8_lossy(&result.stderr)
                );
                for line in text.lines() {
                    let _ = app_handle.emit("bootstrap-log", line.to_string());
                }
                let _ = app_handle.emit(
                    "bootstrap-done",
                    if result.status.success() { "ok" } else { "failed" },
                );
            }
            Err(error) => {
                let _ = app_handle.emit("bootstrap-log", format!("执行失败: {error}"));
                let _ = app_handle.emit("bootstrap-done", "failed");
            }
        }
    });
    Ok("bootstrap-started".into())
}

fn bootstrap_script(app: &AppHandle) -> Option<PathBuf> {
    if let Ok(root) = std::env::var("VIDEOGRABBER_ROOT") {
        let candidate = PathBuf::from(root).join("scripts/bootstrap-engine.py");
        if candidate.exists() {
            return Some(candidate);
        }
    }
    if let Ok(resources) = app.path().resource_dir() {
        let candidate = resources.join("scripts/bootstrap-engine.py");
        if candidate.exists() {
            return Some(candidate);
        }
    }
    let mut current = std::env::current_exe().ok()?;
    for _ in 0..6 {
        current = current.parent()?.to_path_buf();
        let candidate = current.join("scripts/bootstrap-engine.py");
        if candidate.exists() {
            return Some(candidate);
        }
    }
    None
}

fn main() {
    tauri::Builder::default()
        .manage(EngineState {
            child: Mutex::new(None),
            stdin: Mutex::new(None),
        })
        .invoke_handler(tauri::generate_handler![
            engine_info,
            engine_start,
            engine_send,
            read_history,
            file_sizes,
            pick_folder,
            open_download_dir,
            share_file,
            reveal_file,
            clear_history,
            delete_history_item,
            delete_history_file,
            open_path,
            open_url,
            app_version,
            run_bootstrap
        ])
        .run(tauri::generate_context!())
        .expect("启动 Tauri 应用失败");
}
