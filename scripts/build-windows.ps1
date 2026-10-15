# 秒存 · Windows 一键打包
#
# 用法（在仓库根目录的 PowerShell 里执行）：
#     powershell -ExecutionPolicy Bypass -File scripts\build-windows.ps1
#
# 做四件事：检查依赖 → 初始化解析引擎 → 同步版本号 → 打包 .msi / .exe

$ErrorActionPreference = "Stop"
# PowerShell 7.3+ 会把「命令返回非 0」当成异常，这里关掉，统一用 $LASTEXITCODE 判断
if (Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root
Write-Host "[1/4] 仓库目录：$root" -ForegroundColor Cyan

function Require-Command($name, $hint) {
    if (-not (Get-Command $name -ErrorAction SilentlyContinue)) {
        Write-Host "[错误] 找不到 $name。$hint" -ForegroundColor Red
        exit 1
    }
}

# ---------- 依赖检查 ----------
$python = $null
foreach ($candidate in @("py", "python", "python3")) {
    if (Get-Command $candidate -ErrorAction SilentlyContinue) { $python = $candidate; break }
}
if (-not $python) {
    Write-Host "[错误] 未找到 Python。请先安装 Python 3.12+（https://www.python.org/downloads/windows/，安装时勾选 Add to PATH）" -ForegroundColor Red
    exit 1
}
Write-Host "      Python: $python"

Require-Command cargo "请先安装 Rust（https://rustup.rs，选 MSVC 工具链，还需要 Visual Studio Build Tools 的 C++ 桌面开发组件）"
Require-Command npm "请先安装 Node.js（https://nodejs.org）—— 打包用 npm 版 Tauri CLI，比 cargo install 快很多"

# 优先用 npm 版 tauri CLI（预编译二进制）；没有 npm 才退回 cargo install
Write-Host "      准备 Tauri CLI（npm 版）…"
Push-Location "tauri"
npm install --no-save --no-audit --no-fund "@tauri-apps/cli@^2" | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Host "      npm 安装失败，退回 cargo install tauri-cli…" -ForegroundColor Yellow
    cargo install tauri-cli --version "^2"
    if ($LASTEXITCODE -ne 0) { Write-Host "[错误] Tauri CLI 安装失败" -ForegroundColor Red; exit 1 }
}
Pop-Location

# ---------- 初始化引擎（venv + yt-dlp + 浏览器内核）----------
Write-Host "[2/4] 初始化解析引擎（首次约 1-3 分钟）…" -ForegroundColor Cyan
& $python "scripts\bootstrap-engine.py"
if ($LASTEXITCODE -ne 0) {
    Write-Host "[错误] 引擎初始化失败，请把上面的输出发给维护者" -ForegroundColor Red
    exit 1
}

# ---------- 同步版本号 ----------
Write-Host "[3/4] 从 VERSION 同步版本号到 Tauri 工程…" -ForegroundColor Cyan
& $python "scripts\sync-tauri-version.py"
if ($LASTEXITCODE -ne 0) {
    Write-Host "[错误] 版本号同步失败" -ForegroundColor Red
    exit 1
}

# ---------- 打包 ----------
Write-Host "[4/4] 开始打包（首次编译 Rust 依赖会比较久，5-15 分钟）…" -ForegroundColor Cyan
# 上次打包后启动的程序（target 里的 video-grabber.exe + python / Chromium）
# 会锁住 target 目录，导致「拒绝访问 (os error 5)」：先自动解除占用
& (Join-Path $root "scripts\kill-running-app.ps1")
Push-Location "tauri"
npx tauri build
$buildExit = $LASTEXITCODE
Pop-Location

# Tauri 的资源拷贝偶发「拒绝访问 (os error 5)」（1GB+ 运行时反复拷贝时）：
# 清掉上次拷贝的目标目录再重试一次，实测可恢复。
if ($buildExit -ne 0) {
    Write-Host "      构建失败，清理资源缓存后重试一次…" -ForegroundColor Yellow
    foreach ($name in @("engine", "runtime", "resources")) {
        $target = "tauri\src-tauri\target\release\$name"
        if (Test-Path $target) { Remove-Item $target -Recurse -Force -ErrorAction SilentlyContinue }
    }
    (Get-Item "tauri\src-tauri\tauri.conf.json").LastWriteTime = Get-Date
    Push-Location "tauri"
    npx tauri build
    $buildExit = $LASTEXITCODE
    Pop-Location
}
if ($buildExit -ne 0) {
    Write-Host "[错误] 打包失败，常见原因：没装 WebView2 Runtime / 缺 C++ 桌面开发组件" -ForegroundColor Red
    exit 1
}

# ---------- 便捷版（免安装，含运行时）----------
# 解压即用：秒存.exe + engine/ + runtime/ + scripts/bootstrap-engine.py + 使用说明.txt
& (Join-Path $root "scripts\build-portable-windows.ps1")

Write-Host ""
Write-Host "完成。产物位置：" -ForegroundColor Green
Write-Host "  tauri\src-tauri\target\release\bundle\msi\*.msi"
Write-Host "  tauri\src-tauri\target\release\bundle\nsis\*.exe"
Write-Host "  dist\秒存-Windows-<版本>-便捷版.zip（免安装，含运行时）"
Write-Host ""
Write-Host "下一步：把安装包放到管理端「客户端版本」里发布（平台选 windows），客户端就能收到更新提示。"
