# 秒存 · 一键解除打包占用
#
# 打包时报「拒绝访问 (os error 5)」或「另一个程序正在使用此文件 (os error 32)」，
# 基本都是上次从构建目录启动的程序还开着：video-grabber.exe 本身，以及它的
# runtime\python\python.exe 与 runtime 里的 Chromium 子进程，都会锁住
# tauri\src-tauri\target 下的文件，导致资源拷贝/覆盖失败。
#
# 用法（在仓库根目录执行）：
#     powershell -ExecutionPolicy Bypass -File scripts\kill-running-app.ps1
#
# build-windows.ps1 在打包前会自动调用它，一般不需要手动跑。

$ErrorActionPreference = 'SilentlyContinue'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$buildPrefix = Join-Path $root 'tauri\src-tauri'
Write-Host "[clean] 查找从构建目录启动的进程：$buildPrefix" -ForegroundColor Cyan

$killed = 0
Get-CimInstance Win32_Process | ForEach-Object {
    $path = $_.ExecutablePath
    if ($path -and $path.StartsWith($buildPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        try {
            Stop-Process -Id $_.ProcessId -Force -ErrorAction Stop
            Write-Host ("  已结束: " + $_.Name + "  " + $path)
            $killed++
        } catch {
            Write-Host ("  无法结束（可能已退出）: " + $_.Name + " pid=" + $_.ProcessId) -ForegroundColor Yellow
        }
    }
}
if ($killed -eq 0) { Write-Host '  没有发现占用进程' }
Start-Sleep -Milliseconds 800

# 上次拷贝到 target 里的资源目录（引擎 / 运行时），清掉让本次重新复制
foreach ($name in @('engine', 'runtime', 'resources')) {
    $target = Join-Path $root ('tauri\src-tauri\target\release\' + $name)
    if (Test-Path $target) {
        Remove-Item $target -Recurse -Force -ErrorAction SilentlyContinue
        if (Test-Path $target) {
            Write-Host ("  [警告] 清理失败（仍被占用）: target\release\" + $name) -ForegroundColor Yellow
        } else {
            Write-Host ("  已清理: target\release\" + $name)
        }
    }
}
Write-Host '[clean] 完成，可以重新打包了' -ForegroundColor Green
