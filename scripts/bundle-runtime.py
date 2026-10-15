#!/usr/bin/env python3
"""
生成「内置运行时」——让用户装完下载器就能用，不需要装 Python / ffmpeg，也不用联网初始化。

用法（在目标系统上运行，Windows 上跑出来的是 Windows 运行时）：
    python scripts/bundle-runtime.py              # 生成 <仓库>/runtime/
    python scripts/bundle-runtime.py --check      # 只解析下载地址并打印计划，不下载
    python scripts/bundle-runtime.py --force      # 已存在也重新生成
    python scripts/bundle-runtime.py --out DIR    # 自定义输出目录

产物结构（Tauri 会把它整个打进安装包，engine/bridge.py 优先使用它）：
    runtime/
      python/        便携版 CPython（自带 pip，可整体搬迁）
      pkgs/ytdlp/    yt-dlp / curl_cffi / playwright（pip --target，用 PYTHONPATH 注入）
      pkgs/xhs/      小红书引擎依赖
      pkgs/ks/       快手引擎依赖
      engines/xhs/   小红书引擎源码（固定版本，去 .git）
      engines/ks/    快手引擎源码
      browsers/      Playwright Chromium 内核
      ffmpeg/bin/    ffmpeg + ffprobe 静态构建
      manifest.json  版本清单
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

# Windows 控制台默认 GBK：直接打印 "✓" 会抛 UnicodeEncodeError，
# 工具明明跑完了却因为一个字符报错退出，这里统一做容错
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(errors="replace")
    except Exception:  # noqa: BLE001
        pass

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_OUT = ROOT / "runtime"

PYTHON_VERSION = os.environ.get("VIDEOGRABBER_BUNDLE_PYTHON", "3.13")
PBS_API = "https://api.github.com/repos/astral-sh/python-build-standalone/releases/latest"
# GitHub 直连在国内不稳，按顺序回退（前缀式镜像）
# GitHub 下载镜像：直连优先，其次两个实测可用的代理（ghfast.top 常挂，已移除）
GH_MIRRORS = ["", "https://gh-proxy.com/", "https://ghproxy.net/"]
PIP_INDEX = os.environ.get("VIDEOGRABBER_PIP_INDEX", "https://pypi.tuna.tsinghua.edu.cn/simple")
PLAYWRIGHT_HOSTS = [
    "",
    "https://cdn.npmmirror.com/binaries/playwright",
    "https://npmmirror.com/mirrors/playwright",
]
XHS_REPO = "https://github.com/JoeanAmier/XHS-Downloader.git"
KS_REPO = "https://github.com/JoeanAmier/KS-Downloader.git"
FFMPEG_WIN_URL = (
    "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/"
    "ffmpeg-master-latest-win64-lgpl.zip"
)


def step(message: str) -> None:
    print(f"\n=== {message}", flush=True)


def info(message: str) -> None:
    print(f"    {message}", flush=True)


def human(size: int) -> str:
    value = float(size)
    for unit in ("B", "KB", "MB", "GB"):
        if value < 1024 or unit == "GB":
            return f"{value:.1f}{unit}"
        value /= 1024
    return f"{value:.1f}GB"


def dir_size(path: Path) -> int:
    total = 0
    for item in path.rglob("*"):
        if item.is_file():
            total += item.stat().st_size
    return total


def download(url: str, destination: Path, mirrors: bool = True) -> Path:
    """下载文件，失败时依次尝试镜像前缀。"""
    candidates = [url]
    if mirrors and "github.com" in url:
        candidates = [f"{mirror}{url}" for mirror in GH_MIRRORS]
    last_error: Exception | None = None
    for candidate in candidates:
        try:
            info(f"下载 {candidate}")
            with urllib.request.urlopen(candidate, timeout=30) as response:  # noqa: S310
                total = int(response.headers.get("Content-Length") or 0)
                done = 0
                last_report = 0.0
                with destination.open("wb") as handle:
                    while True:
                        chunk = response.read(1024 * 512)
                        if not chunk:
                            break
                        handle.write(chunk)
                        done += len(chunk)
                        now = time.time()
                        if now - last_report > 1.0:
                            last_report = now
                            if total:
                                print(f"\r    {done * 100 // total}%  {human(done)}", end="", flush=True)
                print("", flush=True)
            return destination
        except Exception as error:  # noqa: BLE001
            last_error = error
            info(f"失败（{error}），换下一个源")
    raise RuntimeError(f"下载失败：{url}（{last_error}）")


def github_json(url: str) -> dict:
    last_error: Exception | None = None
    for mirror in GH_MIRRORS:
        try:
            with urllib.request.urlopen(f"{mirror}{url}", timeout=60) as response:  # noqa: S310
                return json.loads(response.read().decode("utf-8"))
        except Exception as error:  # noqa: BLE001
            last_error = error
            info(f"接口失败（{error}），换下一个源")
    raise RuntimeError(f"读取 GitHub 接口失败：{url}（{last_error}）")


def python_asset_suffix() -> str:
    """按当前系统挑 python-build-standalone 的资产名后缀。"""
    machine = platform.machine().lower()
    if sys.platform.startswith("win"):
        arch = "x86_64" if machine in ("amd64", "x86_64") else "aarch64"
        return f"{arch}-pc-windows-msvc-install_only"
    if sys.platform == "darwin":
        arch = "aarch64" if machine in ("arm64", "aarch64") else "x86_64"
        return f"{arch}-apple-darwin-install_only"
    arch = "x86_64" if machine in ("x86_64", "amd64") else "aarch64"
    return f"{arch}-unknown-linux-gnu-install_only"


def resolve_python_asset() -> tuple[str, str]:
    """返回 (版本说明, 下载地址)。"""
    suffix = python_asset_suffix()
    release = github_json(PBS_API)
    tag = release.get("tag_name") or ""
    candidates = []
    for asset in release.get("assets") or []:
        name = asset.get("name") or ""
        if not name.endswith(f"{suffix}.tar.gz"):
            continue
        if f"cpython-{PYTHON_VERSION}." not in name:
            continue
        candidates.append((name, asset.get("browser_download_url") or ""))
    if not candidates:
        raise RuntimeError(f"python-build-standalone 里没有匹配 {PYTHON_VERSION} / {suffix} 的资产（tag={tag}）")
    candidates.sort(reverse=True)  # 名字里带版本号，倒序取最新补丁
    name, url = candidates[0]
    return name, url


def resolve_ffmpeg_plan() -> tuple[str, str]:
    if sys.platform.startswith("win"):
        return "BtbN/FFmpeg-Builds（win64 LGPL）", FFMPEG_WIN_URL
    if sys.platform == "darwin":
        return "imageio-ffmpeg（macOS 静态构建，仅 ffmpeg，不含 ffprobe）", ""
    return "系统 ffmpeg（Linux 请自行准备）", ""


def run(command: list[str], env: dict | None = None, label: str = "") -> None:
    if label:
        info(label)
    merged = {**os.environ, **(env or {})}
    result = subprocess.run(command, env=merged)
    if result.returncode != 0:
        raise RuntimeError(f"命令失败（退出码 {result.returncode}）：{' '.join(command[:3])} …")


def install_portable_python(out: Path, force: bool) -> dict:
    step("1/5 准备便携版 CPython")
    target = out / "python"
    if target.exists() and not force:
        info(f"已存在：{target}（用 --force 重新下载）")
        return {"ready": True, "reused": True}
    name, url = resolve_python_asset()
    info(f"选用：{name}")
    staging = out / ".staging"
    staging.mkdir(parents=True, exist_ok=True)
    archive = staging / name
    if not archive.exists():
        download(url, archive)
    info("解压中…")
    with tarfile.open(archive) as tar:
        tar.extractall(staging)  # noqa: S202
    extracted = staging / "python"
    if not extracted.exists():
        raise RuntimeError("解压后没有找到 python/ 目录")
    if target.exists():
        shutil.rmtree(target)
    shutil.move(str(extracted), str(target))
    archive.unlink(missing_ok=True)
    python = bundled_python(out)
    info(f"Python：{python}")
    return {"ready": True, "version": name, "path": str(python)}


def bundled_python(out: Path) -> Path:
    for relative in ("python/python.exe", "python/bin/python3"):
        candidate = out / relative
        if candidate.exists():
            return candidate
    raise RuntimeError("便携 Python 不存在")


def pip_install(python: Path, target: Path, packages: list[str], extra: list[str] | None = None) -> None:
    target.mkdir(parents=True, exist_ok=True)
    command = [
        str(python), "-m", "pip", "install",
        "--disable-pip-version-check", "--no-warn-script-location",
        "--quiet", "--target", str(target),
        "--index-url", PIP_INDEX,
        *(extra or []),
        *packages,
    ]
    run(command, label=f"安装依赖到 {target.name}：{' '.join(packages)[:80]}")


def install_packages(out: Path, force: bool) -> dict:
    step("2/5 安装三个引擎的依赖（pip --target，可整体搬迁）")
    python = bundled_python(out)
    pkgs = out / "pkgs"
    result = {}

    ytdlp_target = pkgs / "ytdlp"
    if force or not (ytdlp_target / "yt_dlp").exists():
        pip_install(python, ytdlp_target, ["yt-dlp", "curl_cffi", "playwright"])
    result["ytdlp"] = {"target": str(ytdlp_target)}

    for engine, repo in (("xhs", XHS_REPO), ("ks", KS_REPO)):
        source = fetch_engine_source(out, engine, repo)
        target = pkgs / engine
        requirements = source / "requirements.txt"
        if force or not target.exists():
            if requirements.exists():
                pip_install(python, target, ["-r", str(requirements)])
            else:
                info(f"{engine}: 没有 requirements.txt，跳过依赖安装")
        result[engine] = {"target": str(target), "source": str(source)}
    return result


def fetch_engine_source(out: Path, engine: str, repo: str) -> Path:
    source = out / "engines" / engine
    if source.exists() and (source / "source").exists():
        info(f"{engine} 源码已存在：{source}")
        return source
    source.parent.mkdir(parents=True, exist_ok=True)
    last_error: Exception | None = None
    for mirror in GH_MIRRORS:
        try:
            if source.exists():
                shutil.rmtree(source)
            run(["git", "clone", "--depth", "1", f"{mirror}{repo}", str(source)],
                label=f"拉取 {engine} 源码（{mirror or '直连'}）")
            break
        except Exception as error:  # noqa: BLE001
            last_error = error
            info(f"失败（{error}），换下一个源")
    else:
        raise RuntimeError(f"{engine} 源码拉取失败：{last_error}")
    commit = subprocess.run(
        ["git", "-C", str(source), "rev-parse", "--short", "HEAD"],
        capture_output=True, text=True,
    ).stdout.strip()
    shutil.rmtree(source / ".git", ignore_errors=True)
    (source / ".version").write_text(commit or "", encoding="utf-8")
    info(f"{engine} 源码：{source}（{commit}）")
    return source


def install_browsers(out: Path, force: bool) -> dict:
    step("3/5 安装 Playwright Chromium 内核")
    python = bundled_python(out)
    browsers = out / "browsers"
    if browsers.exists() and any(browsers.glob("chromium*")) and not force:
        info(f"已存在：{browsers}")
        return {"ready": True, "reused": True}
    browsers.mkdir(parents=True, exist_ok=True)
    last_error: Exception | None = None
    for host in PLAYWRIGHT_HOSTS:
        try:
            env = {
                "PLAYWRIGHT_BROWSERS_PATH": str(browsers),
                "PYTHONPATH": str(out / "pkgs" / "ytdlp"),
            }
            if host:
                env["PLAYWRIGHT_DOWNLOAD_HOST"] = host
            run([str(python), "-m", "playwright", "install", "chromium", "--no-shell"],
                env=env, label=f"playwright install（{host or '官方源'}）")
            return {"ready": True, "host": host or "official"}
        except Exception as error:  # noqa: BLE001
            last_error = error
            info(f"失败（{error}），换下一个源")
    raise RuntimeError(f"浏览器内核安装失败：{last_error}")


def install_ffmpeg(out: Path, force: bool) -> dict:
    step("4/5 准备 ffmpeg（用户侧不用自己装）")
    label, url = resolve_ffmpeg_plan()
    target_dir = out / "ffmpeg" / "bin"
    if (target_dir / ("ffmpeg.exe" if sys.platform.startswith("win") else "ffmpeg")).exists() and not force:
        info(f"已存在：{target_dir}")
        return {"ready": True, "source": label, "reused": True}
    target_dir.mkdir(parents=True, exist_ok=True)

    if sys.platform.startswith("win") and url:
        staging = out / ".staging"
        staging.mkdir(parents=True, exist_ok=True)
        archive = staging / "ffmpeg-win64.zip"
        if not archive.exists() or force:
            download(url, archive)
        info("解压中…")
        with zipfile.ZipFile(archive) as zf:
            for member in zf.namelist():
                name = Path(member).name
                if name in ("ffmpeg.exe", "ffprobe.exe"):
                    with zf.open(member) as source, (target_dir / name).open("wb") as handle:
                        shutil.copyfileobj(source, handle)
        archive.unlink(missing_ok=True)
        info(f"已就位：{target_dir}（{label}）")
        return {"ready": True, "source": label}

    if sys.platform == "darwin":
        # macOS 没有官方静态构建：用 imageio-ffmpeg 的静态二进制（只有 ffmpeg）
        python = bundled_python(out)
        tmp_target = out / ".staging" / "imageio-ffmpeg"
        pip_install(python, tmp_target, ["imageio-ffmpeg"])
        binary = next(tmp_target.glob("imageio_ffmpeg/binaries/ffmpeg-*"), None)
        if binary:
            shutil.copy2(binary, target_dir / "ffmpeg")
            (target_dir / "ffmpeg").chmod(0o755)
            info(f"已就位：{target_dir}/ffmpeg（{label}）")
            return {"ready": True, "source": label, "note": "不含 ffprobe，媒体信息会自动降级"}
        info("没取到 imageio-ffmpeg 二进制，跳过（Mac 端可继续用系统 ffmpeg）")
        return {"ready": False, "source": label}

    info("Linux：跳过，请自行准备 ffmpeg")
    return {"ready": False, "source": label}


def write_manifest(out: Path, python_info: dict, packages: dict, browsers: dict, ffmpeg: dict) -> None:
    step("5/5 写清单并统计体积")
    python = bundled_python(out)
    ytdlp_version = ""
    try:
        result = subprocess.run(
            [str(python), "-c", "import yt_dlp; print(yt_dlp.version.__version__)"],
            capture_output=True, text=True,
            env={**os.environ, "PYTHONPATH": str(out / "pkgs" / "ytdlp")},
        )
        ytdlp_version = result.stdout.strip()
    except Exception:  # noqa: BLE001
        pass

    def source_version(engine: str) -> str:
        version_file = out / "engines" / engine / ".version"
        if version_file.exists():
            return version_file.read_text(encoding="utf-8").strip()
        return ""

    manifest = {
        "built_at": time.strftime("%Y-%m-%d %H:%M:%S"),
        "platform": f"{sys.platform}-{platform.machine()}",
        "python": python_info,
        "engines": {
            "ytdlp": {"version": ytdlp_version},
            "xhs": {"version": source_version("xhs")},
            "ks": {"version": source_version("ks")},
        },
        "browsers": browsers,
        "ffmpeg": ffmpeg,
    }
    (out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")

    staging = out / ".staging"
    shutil.rmtree(staging, ignore_errors=True)
    total = dir_size(out)
    info(f"运行时目录：{out}")
    for part in ("python", "pkgs", "engines", "browsers", "ffmpeg"):
        path = out / part
        if path.exists():
            info(f"  {part:<9} {human(dir_size(path))}")
    info(f"合计 {human(total)}（安装包会被压缩，通常再小 30-45%）")
    info("下一步：在 Windows 上打安装包（scripts/build-windows.ps1），或先跑 build-tauri.sh")
    info("提示：运行时目录不会被提交到 git（见 .gitignore）")


def main() -> int:
    parser = argparse.ArgumentParser(description="生成内置运行时（用户侧零安装）")
    parser.add_argument("--out", default=str(DEFAULT_OUT), help="输出目录，默认 <仓库>/runtime")
    parser.add_argument("--check", action="store_true", help="只解析并打印计划，不下载")
    parser.add_argument("--force", action="store_true", help="已存在也重新生成")
    args = parser.parse_args()

    out = Path(args.out).expanduser().resolve()
    print(f"目标目录：{out}")
    print(f"当前系统：{sys.platform}-{platform.machine()}（Python {platform.python_version()}）")

    if args.check:
        name, url = resolve_python_asset()
        label, ffmpeg_url = resolve_ffmpeg_plan()
        print("\n计划：")
        print(f"  便携 Python : {name}")
        print(f"                {url}")
        print(f"  依赖        : yt-dlp + curl_cffi + playwright / xhs / ks（pip 源 {PIP_INDEX}）")
        print(f"  浏览器      : playwright install chromium --no-shell（镜像 {PLAYWRIGHT_HOSTS[1]}）")
        print(f"  ffmpeg      : {label}")
        if ffmpeg_url:
            print(f"                {ffmpeg_url}")
        print(f"  引擎源码    : {XHS_REPO} / {KS_REPO}（clone --depth 1）")
        return 0

    out.mkdir(parents=True, exist_ok=True)
    python_info = install_portable_python(out, args.force)
    packages = install_packages(out, args.force)
    browsers = install_browsers(out, args.force)
    ffmpeg = install_ffmpeg(out, args.force)
    write_manifest(out, python_info, packages, browsers, ffmpeg)
    print("\n全部完成 ✓")
    return 0


if __name__ == "__main__":
    sys.exit(main())
