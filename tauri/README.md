# 视频下载器 · Tauri 外壳（Windows / macOS 通用）

复用与 macOS / Android 完全相同的思路：**Python 引擎（`engine/bridge.py`）+ JSON Lines 协议**，
Tauri 只负责界面与进程管理，因此三个平台的解析与下载能力一致。

## 结构

```
tauri/
  src/                  前端（静态 HTML/CSS/JS，无需 Node 构建）
  src-tauri/
    src/main.rs         Rust 外壳：启动 Python 引擎、转发事件、读取历史
    tauri.conf.json     窗口与打包配置（含 engine / bootstrap 脚本资源）
    icons/              应用图标（由 app/Resources/icon.png 生成）
```

## 开发运行（macOS / Windows 相同）

```bash
# 1) 初始化引擎（只需一次；会创建 Python venv 与内置浏览器内核）
python3 scripts/bootstrap-engine.py          # Windows: python scripts\bootstrap-engine.py

# 2) 运行
cd tauri/src-tauri
cargo run
```

程序会自动查找：

- Python 引擎：`<应用数据目录>/engines/ytdlp/venv/…`
  （macOS `~/Library/Application Support/视频下载器/`，Windows `%APPDATA%\视频下载器\`）
- `engine/bridge.py`：优先 `<可执行文件>/resources/engine/`（打包后），开发时向上查找工程目录

## 内置运行时（用户零安装）

Windows / macOS 的**正式安装包**会把运行时一起打进去，用户不需要装 Python / ffmpeg，也不用联网做首次初始化：

```bash
python3 scripts/bundle-runtime.py      # 生成 <仓库>/runtime/（便携 Python + 依赖 + Chromium + ffmpeg + 引擎源码）
bash scripts/build-tauri.sh            # 打包（runtime/ 会作为 resources 一起进安装包）
```

- `engine/bridge.py` 会优先使用内置运行时（`runtime/python`、`runtime/pkgs/*`、`runtime/browsers`、`runtime/ffmpeg`），
  找不到才回退到用户数据目录 / 系统 Python（开发机模式）。
- 安装包体积会涨到 **约 350-450MB**（安装后约 600MB）——这是"双击即用"的代价。
- Tauri 侧：`bundle.resources` 已包含 `runtime`；NSIS 默认 **per-user 安装**（免管理员）；
  WebView2 用 **embedBootstrapper**（Win10 没装 WebView2 时也能离线装）。

## 打包

```bash
# 推荐：用仓库的统一入口，会自动同步版本号
bash scripts/build-tauri.sh          # macOS 产出 .app/.dmg，Windows 产出 .msi/.exe

# Windows 也可以直接用 PowerShell 一键脚本（会顺便检查依赖、初始化引擎）
powershell -ExecutionPolicy Bypass -File scripts\build-windows.ps1

# 等价的手工流程（想单独跑 cargo 时）
python3 scripts/sync-tauri-version.py     # 先把 VERSION 同步到 tauri.conf.json + Cargo.toml
cd tauri/src-tauri && cargo tauri build
```

### 远程协助（局域网内）

> 如果让 Codex/同事远程帮你在 Windows 上打包测试，在这台 Windows 上开一次 OpenSSH 服务即可：
>
> ```powershell
> # 管理员 PowerShell
> Add-WindowsCapability -Online -Name OpenSSH.Server~~~~0.0.1.0
> Start-Service sshd
> Set-Service -Name sshd -StartupType Automatic
> New-Item -ItemType Directory -Force -Path C:\ProgramData\ssh | Out-Null
> Add-Content C:\ProgramData\ssh\administrators_authorized_keys "ssh-ed25519 AAAA…对方的公钥…"
> icacls C:\ProgramData\ssh\administrators_authorized_keys /inheritance:r /grant "Administrators:F" /grant "SYSTEM:F"
> ```
>
> 之后对方就能 `ssh <你的用户名>@192.168.x.x` 直接连进来跑 `scripts\build-windows.ps1`。
> **版本号**：全项目唯一来源是仓库根目录的 `VERSION`。Tauri 的版本必须写死在
> `tauri.conf.json` 和 `Cargo.toml` 里（Cargo 不支持从文件读版本），所以改完 `VERSION`
> 之后要跑一次同步——`build-tauri.sh` 会自动做，`build-app.sh` / `build-android.sh`
> 也会顺手同步一次。检查是否一致：`python3 scripts/sync-tauri-version.py --check`。
>
> 打包时 `VERSION` 会一起打进 `engine/VERSION`，引擎靠它向云控后台上报版本号；
> 少了这个文件，Windows 端会以 `0.0.0` 上报，后台就一直提示它「有更新」。

Windows 打包前需要：

1. 安装 **Rust**（<https://rustup.rs>）与 **WebView2 Runtime**（Win11 自带）
2. 安装 **Python 3.12+**（用于初始化引擎与创建 venv）
3. 首次运行 App 时点「开始初始化」，或先手工执行 `python scripts\bootstrap-engine.py`

## 与 macOS 版的功能对照

| 能力 | macOS（SwiftUI 版） | Tauri 版 |
| --- | --- | --- |
| 抖音 / 快手 / 小红书 / 视频号 / B 站 / 微博 / TikTok / Instagram / X / YouTube 解析 | ✅ | ✅（同一套引擎） |
| 普通文件直链 / 网页嗅探 | ✅ | ✅ |
| 主页 / 收藏夹批量下载 | ✅ | ✅（「批量下载」按钮；需先在桌面端登录对应平台） |
| 多线程分段下载（类 IDM） | ✅ | ✅（引擎内置，最多 8 段） |
| 画质信息展示 | ✅ | ✅ |
| 历史记录 / 在访达（资源管理器）中显示 | ✅ | ✅（读取同一份 history.json） |
| 版本更新检查 | ✅（弹窗 → 浏览器下载） | ✅（更新卡片 → 浏览器下载） |
| 引擎更新方式 | ✅ 随应用版本发布（客户端不再提供手动更新入口） | ✅ 同左（内置运行时随安装包一起更新） |
| Cookie 管理（登录窗口 / 导入 / 手动粘贴 / 测试 / 清除） | ✅ | ✅（登录窗口 / 手动粘贴 / 测试 / 清除；「从系统浏览器导入」待接入） |
| 账号 + 云端 Cookie 同步（手机端 / 电脑端切换） | ✅ | ✅（手机号验证码登录、上传本机、按平台切换云端两端） |
| 提建议 / 联系官方 | ✅ | ✅ |
| 保存目录选择（系统目录选择框） | ✅ | ✅（Windows 走 PowerShell FolderBrowserDialog，macOS 走 osascript） |
| 主题配色（浅橙 / 浅绿） | ✅ | ✅ |
| 下载完自动清空输入框 | ✅ | ✅ |

> 说明：Tauri 版与 macOS 版共用同一个应用数据目录（引擎、Cookie、历史），因此两边的下载记录一致。

## 版本更新与后台发版（Windows）

更新链路和 Mac / Android 完全一致，走云控后台：

1. Windows 上跑 `bash scripts/build-tauri.sh`（Git Bash / WSL 均可），产出
   `tauri/src-tauri/target/release/bundle/msi/*.msi` 与 `bundle/nsis/*.exe`
2. 打开管理端 <https://music.aiyun-jing.com:8443/downloader/> →「下载器管理 → 客户端版本」→ 新增：
   - **平台选 `windows`**（和客户端查询时用的 `platform=windows` 对应）
   - 版本名 / versionCode 与仓库 `VERSION` 一致（versionCode = major*10000 + minor*100 + patch）
   - 上传 `.msi` 或 `.exe`（后台会转存到 OSS，客户端下载时走 `/app/download` 接口中转）
   - 填更新日志，按需勾选「强制更新」
3. 客户端启动时自动检查一次，也可在界面点「检查更新」；发现新版本弹出更新卡片，
   点「前往下载」用系统浏览器打开后台的下载接口

> 打包脚本会自动把版本号同步到 `tauri.conf.json` 与 `Cargo.toml`，并把 `VERSION` 一起打进
> `engine/VERSION`（引擎靠它向后台汇报版本号）。检查是否同步：`python3 scripts/sync-tauri-version.py --check`。

### Windows 打包前置条件

1. **Rust**（<https://rustup.rs>，选 MSVC 工具链）+ **Visual Studio Build Tools**（含 C++ 桌面开发）
2. **WebView2 Runtime**（Win10 需要单独装，Win11 自带）
3. **Python 3.12+**（初始化引擎 venv）
4. `cargo install tauri-cli --version "^2"`（打包用）

首次运行 App 时点「开始初始化」（约 1-3 分钟，会创建 venv 与内置浏览器内核），
或先手工执行 `python scripts\bootstrap-engine.py`。


## 已知限制

- **X / YouTube 需要代理**：程序会自动使用系统代理（macOS 会读取 `scutil --proxy`），也可在设置里手动填写本地端口（如 `http://127.0.0.1:7890`）。
- **X 需要登录 Cookie**：在浏览器登录 X 后，用桌面版的「设置 → Cookie 管理 → 刷新 Cookie」把登录态导出给 yt-dlp。
- **YouTube 需要 JS 运行时**：桌面端会自动复用系统已安装的 Node；未安装 Node 时部分清晰度会缺失。
