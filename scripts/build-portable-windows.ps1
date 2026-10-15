# 秒存 · Windows 便捷版（免安装，含运行时环境）
#
# 产物：dist\秒存-Windows-<版本>-便捷版.zip
# 解压即用：秒存.exe + engine/ + runtime/ + scripts/bootstrap-engine.py + 使用说明.txt
# （runtime 里已含便携 Python、浏览器内核与 ffmpeg，用户不用装任何东西）
#
# 用法（仓库根目录，先跑一次 build-windows.ps1 生成 exe）：
#     powershell -ExecutionPolicy Bypass -File scripts\build-portable-windows.ps1
#
# build-windows.ps1 打包成功后会自动调用它。

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root

$versionLine = (Select-String -Path (Join-Path $root 'VERSION') -Pattern '^\s*versionName\s*=' | Select-Object -First 1).Line
$version = ($versionLine -replace '^\s*versionName\s*=\s*', '').Trim()
if (-not $version) { Write-Host '[便携版][错误] 读不到 VERSION 里的版本号' -ForegroundColor Red; exit 1 }

$exe = Join-Path $root 'tauri\src-tauri\target\release\video-grabber.exe'
$engine = Join-Path $root 'engine'
$runtime = Join-Path $root 'runtime'
foreach ($need in @($exe, $engine, $runtime)) {
    if (-not (Test-Path $need)) {
        Write-Host ("[便携版][错误] 缺少 " + $need + "（先跑 scripts\build-windows.ps1；运行时缺失时先跑 scripts\bundle-runtime.py）") -ForegroundColor Red
        exit 1
    }
}

$stage = Join-Path $env:TEMP ('vg_portable_' + $version)
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue }
New-Item -ItemType Directory -Force $stage | Out-Null

Write-Host "[便携版] 组装目录：$stage" -ForegroundColor Cyan
Copy-Item $exe (Join-Path $stage '秒存.exe') -Force
Copy-Item $engine (Join-Path $stage 'engine') -Recurse -Force
Copy-Item $runtime (Join-Path $stage 'runtime') -Recurse -Force
# 版本号：安装包里由 Tauri 资源映射写成 engine/VERSION，这里手动补上
# （缺了它引擎会兜底成 0.0.0，更新检查/反馈上报的版本就不对了）
Copy-Item (Join-Path $root 'VERSION') (Join-Path $stage 'engine\VERSION') -Force
New-Item -ItemType Directory -Force (Join-Path $stage 'scripts') | Out-Null
Copy-Item (Join-Path $root 'scripts\bootstrap-engine.py') (Join-Path $stage 'scripts\') -Force
Copy-Item (Join-Path $root 'legal\THIRD-PARTY-NOTICES.txt') (Join-Path $stage 'THIRD-PARTY-NOTICES.txt') -Force

$readme = @(
    "秒存 $version · Windows 便捷版（免安装）",
    "",
    "用法：把整个文件夹解压到任意位置，双击「秒存.exe」即可使用；",
    "     不需要安装，也不需要自己装 Python / ffmpeg（runtime 目录里已经带好了）。",
    "",
    "建议：解压后把整个文件夹放到固定位置再建桌面快捷方式；",
    "     不要只把 exe 单独拷走（engine / runtime 是它的运行环境）。"
) -join "`r`n"
Set-Content -Path (Join-Path $stage '使用说明.txt') -Value $readme -Encoding UTF8

$dist = Join-Path $root 'dist'
New-Item -ItemType Directory -Force $dist | Out-Null
$zip = Join-Path $dist ('秒存-Windows-' + $version + '-便捷版.zip')
if (Test-Path $zip) { Remove-Item $zip -Force -ErrorAction SilentlyContinue }

Write-Host "[便携版] 压缩中（约 1GB 数据，需要几分钟）…" -ForegroundColor Cyan
# tar.exe（Windows 自带 bsdtar）比 Compress-Archive 快很多
& tar.exe -a -c -f $zip -C $stage .
if ($LASTEXITCODE -ne 0) { Write-Host '[便携版][错误] 压缩失败（可改用 7-Zip 手工打包）' -ForegroundColor Red; exit 1 }

Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue
$size = [math]::Round((Get-Item $zip).Length / 1MB, 1)
Write-Host ("[便携版] 完成: " + $zip + "（" + $size + " MB）") -ForegroundColor Green
