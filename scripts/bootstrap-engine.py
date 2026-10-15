#!/usr/bin/env python3
"""视频下载器 - 跨平台引擎初始化脚本（macOS / Windows / Linux 通用）。

用法:
    python3 bootstrap-engine.py            初始化（幂等）
    python3 bootstrap-engine.py --check    仅检查状态（输出 ENGINE_*= 键值对）
    python3 bootstrap-engine.py --update   升级 yt-dlp / 浏览器内核并拉取引擎最新源码
    python3 bootstrap-engine.py --force    忽略已安装状态重新初始化

说明:
    - 三个独立 venv（yt-dlp / 小红书 / 快手）避免依赖冲突。
    - xhs-downloader、KS-Downloader 未发布到 PyPI，从 GitHub 拉源码，内置镜像回退。
    - 浏览器内核使用 Chrome for Testing，装在应用数据目录，可用国内镜像。
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import shutil
import subprocess
import sys
import time
from pathlib import Path

APP_NAME = "视频下载器"
XHS_REPO = "https://github.com/JoeanAmier/XHS-Downloader.git"
KS_REPO = "https://github.com/JoeanAmier/KS-Downloader.git"
GIT_MIRRORS = ["", "https://ghproxy.net/", "https://ghfast.top/", "https://gh-proxy.com/"]
PIP_INDEX_FALLBACK = os.environ.get("VIDEOGRABBER_PIP_INDEX", "https://pypi.tuna.tsinghua.edu.cn/simple")
BROWSER_MIRRORS = ["", "https://cdn.npmmirror.com/binaries/playwright", "https://npmmirror.com/mirrors/playwright"]


def support_dir() -> Path:
    override = os.environ.get("VIDEOGRABBER_HOME")
    if override:
        return Path(override).expanduser()
    home = Path.home()
    if sys.platform == "darwin":
        return home / "Library/Application Support" / APP_NAME
    if sys.platform.startswith("win"):
        base = os.environ.get("APPDATA") or (home / "AppData/Roaming")
        return Path(base) / APP_NAME
    return Path(os.environ.get("XDG_DATA_HOME", home / ".local/share")) / APP_NAME


SUPPORT = support_dir()
ENGINES = SUPPORT / "engines"
BROWSERS = SUPPORT / "browsers"
LOGS = SUPPORT / "logs"
MANIFEST = ENGINES / "manifest.txt"


# Windows 控制台默认是 GBK，直接打印 "✓" 会抛 UnicodeEncodeError；
# 这里统一把输出改成「编不出来就替换」，避免工具跑完了却因为一个字符报错退出
try:
    sys.stdout.reconfigure(errors="replace")
    sys.stderr.reconfigure(errors="replace")
except Exception:  # noqa: BLE001
    pass


def say(message: str) -> None:
    print(message, flush=True)


def step(message: str) -> None:
    say(f"[bootstrap] {message}")


def venv_python(engine: str) -> Path:
    base = ENGINES / engine / "venv"
    return base / ("Scripts/python.exe" if sys.platform.startswith("win") else "bin/python3")


def engine_src(engine: str) -> Path:
    return ENGINES / engine / "src"


def run(cmd: list[str], log_file: Path | None = None, env: dict | None = None) -> int:
    merged = dict(os.environ)
    if env:
        merged.update(env)
    if log_file:
        LOGS.mkdir(parents=True, exist_ok=True)
        with log_file.open("a", encoding="utf-8", errors="ignore") as handle:
            handle.write(f"\n$ {' '.join(cmd)}\n")
            handle.flush()
            return subprocess.call(cmd, stdout=handle, stderr=subprocess.STDOUT, env=merged)
    return subprocess.call(cmd, env=merged)


def find_python() -> Path | None:
    candidates: list[str] = []
    override = os.environ.get("VIDEOGRABBER_PYTHON")
    if override:
        candidates.append(override)
    if sys.version_info >= (3, 12):
        candidates.append(sys.executable)
    names = ["python3.13", "python3.12", "python3"]
    if sys.platform.startswith("win"):
        names = ["python.exe", "python3.exe"]
    extra_dirs = ["/opt/homebrew/bin", "/usr/local/bin", "/usr/bin"]
    for name in names:
        candidates.append(name)
        for directory in extra_dirs:
            candidates.append(str(Path(directory) / name))
    for candidate in candidates:
        path = shutil.which(candidate) or (candidate if Path(candidate).exists() else None)
        if not path:
            continue
        result = subprocess.run(
            [path, "-c", "import sys; raise SystemExit(0 if sys.version_info >= (3, 12) else 1)"],
            capture_output=True,
        )
        if result.returncode == 0:
            return Path(path)
    return None


def venv_ready(engine: str, imports: str) -> bool:
    python = venv_python(engine)
    if not python.exists():
        return False
    return subprocess.run([str(python), "-c", imports], capture_output=True).returncode == 0


def ensure_venv(engine: str, python_bin: Path) -> Path:
    python = venv_python(engine)
    if python.exists():
        return python
    step(f"创建虚拟环境: {ENGINES / engine / 'venv'}")
    run([str(python_bin), "-m", "venv", str(ENGINES / engine / "venv")])
    run([str(python), "-m", "pip", "install", "--quiet", "--no-cache-dir", "--upgrade", "pip"], LOGS / "pip-venv.log")
    return python


def pip_install(engine: str, label: str, args: list[str]) -> None:
    python = venv_python(engine)
    log = LOGS / f"pip-{engine}.log"
    step(f"安装依赖: {label}")
    base = [str(python), "-m", "pip", "install", "--disable-pip-version-check", "--quiet", "--no-cache-dir"]
    if run(base + args, log) == 0:
        return
    step(f"默认源失败，改用镜像源重试: {label}")
    if run(base + ["-i", PIP_INDEX_FALLBACK] + args, log) == 0:
        return
    say("----- 日志末尾 20 行 -----")
    if log.exists():
        for line in log.read_text(encoding="utf-8", errors="ignore").splitlines()[-20:]:
            say(line)
    raise SystemExit(f"依赖安装失败: {label}（完整日志: {log}）")


def clone_repo(repo: str, dest: Path, label: str) -> None:
    if (dest / ".git").exists():
        say(f"[bootstrap] {label} 源码已存在，跳过下载")
        return
    for mirror in GIT_MIRRORS:
        url = f"{mirror}{repo}"
        step(f"下载 {label} 源码: {mirror or 'git 直连'}")
        if dest.exists():
            shutil.rmtree(dest, ignore_errors=True)
        code = run(["git", "-c", "http.version=HTTP/1.1", "clone", "--depth", "1", url, str(dest)],
                   LOGS / f"clone-{dest.name}.log")
        if code == 0:
            return
    raise SystemExit(f"下载 {label} 源码失败，请检查网络（或手动克隆到 {dest}）")


def repo_head(path: Path) -> str:
    result = subprocess.run(["git", "-C", str(path), "rev-parse", "--short", "HEAD"], capture_output=True, text=True)
    return result.stdout.strip() or "unknown"


def browser_ready() -> bool:
    return any(path.name.startswith("chromium") for path in BROWSERS.glob("chromium*"))


def install_browser() -> bool:
    python = venv_python("ytdlp")
    for host in BROWSER_MIRRORS:
        label = host or "官方源"
        step(f"下载浏览器内核（{label}，约 182MB）")
        env = {"PLAYWRIGHT_BROWSERS_PATH": str(BROWSERS)}
        if host:
            env["PLAYWRIGHT_DOWNLOAD_HOST"] = host
        code = run([str(python), "-m", "playwright", "install", "chromium", "--no-shell"],
                   LOGS / "playwright-install.log", env)
        if code == 0 and browser_ready():
            return True
        say(f"[bootstrap][警告] {label} 下载失败，尝试下一个源")
    return False


def check_mode() -> None:
    def line(key: str, ready: int, detail: str) -> None:
        print(f"ENGINE_{key}_READY={ready}")
        print(f"ENGINE_{key}_DETAIL={detail}")

    if venv_ready("ytdlp", "import yt_dlp, curl_cffi, playwright"):
        result = subprocess.run([str(venv_python("ytdlp")), "-c", "import yt_dlp; print(yt_dlp.version.__version__)"],
                                capture_output=True, text=True)
        line("YTDLP", 1, f"yt-dlp {result.stdout.strip()}")
    else:
        line("YTDLP", 0, "未初始化")

    if (engine_src("xhs") / "source").exists() and venv_ready("xhs", "import curl_cffi, fastapi, yaml"):
        line("XHS", 1, f"源码 {repo_head(engine_src('xhs'))}")
    else:
        line("XHS", 0, "未初始化")

    if (engine_src("ks") / "source").exists() and venv_ready("ks", "import curl_cffi, fastapi, yaml"):
        line("KS", 1, f"源码 {repo_head(engine_src('ks'))}")
    else:
        line("KS", 0, "未初始化")

    cookie_file = SUPPORT / "cookies" / "cookies.txt"
    line("COOKIES", 1 if cookie_file.exists() else 0,
         time.strftime("%Y-%m-%d %H:%M", time.localtime(cookie_file.stat().st_mtime)) if cookie_file.exists() else "未提取")
    line("BROWSER", 1 if browser_ready() else 0, next((p.name for p in BROWSERS.glob("chromium*")), "未安装"))
    print(f"SUPPORT_DIR={SUPPORT}")


def main() -> int:
    parser = argparse.ArgumentParser(description="视频下载器引擎初始化")
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--update", action="store_true")
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()

    for directory in (SUPPORT, ENGINES, BROWSERS, LOGS):
        directory.mkdir(parents=True, exist_ok=True)

    if args.check:
        check_mode()
        return 0

    python_bin = find_python()
    if not python_bin:
        say("[bootstrap][错误] 未找到 Python 3.12+（可设置 VIDEOGRABBER_PYTHON 指定路径）")
        return 1
    version = platform.python_version() if Path(sys.executable) == python_bin else ""
    say(f"[bootstrap] 使用 Python: {python_bin} {version}")
    say(f"[bootstrap] 安装目录: {SUPPORT}")

    # 1) yt-dlp 引擎
    ensure_venv("ytdlp", python_bin)
    if args.update:
        pip_install("ytdlp", "yt-dlp 升级", ["--upgrade", "yt-dlp", "curl_cffi", "playwright"])
    elif args.force or not venv_ready("ytdlp", "import yt_dlp, curl_cffi, playwright"):
        pip_install("ytdlp", "yt-dlp + curl_cffi + playwright", ["yt-dlp", "curl_cffi", "playwright"])
    else:
        say("[bootstrap] yt-dlp 引擎已就绪，跳过")

    # 2) 小红书引擎
    clone_repo(XHS_REPO, engine_src("xhs"), "小红书引擎")
    ensure_venv("xhs", python_bin)
    if args.update or args.force or not venv_ready("xhs", "import curl_cffi, fastapi, yaml"):
        pip_install("xhs", "小红书引擎依赖", ["-r", str(engine_src("xhs") / "requirements.txt")])
    else:
        say("[bootstrap] 小红书引擎已就绪，跳过")

    # 3) 快手引擎
    clone_repo(KS_REPO, engine_src("ks"), "快手引擎")
    ensure_venv("ks", python_bin)
    if args.update or args.force or not venv_ready("ks", "import curl_cffi, fastapi, yaml"):
        pip_install("ks", "快手引擎依赖", ["-r", str(engine_src("ks") / "requirements.txt")])
    else:
        say("[bootstrap] 快手引擎已就绪，跳过")

    # 4) 浏览器内核
    if args.update or args.force or not browser_ready():
        if install_browser():
            say("[bootstrap] 浏览器内核安装完成")
        else:
            say("[bootstrap][警告] 浏览器内核安装失败（可稍后在设置页重试）")
    else:
        say("[bootstrap] 内置浏览器内核已就绪，跳过")

    # 5) 清单
    MANIFEST.write_text(
        "\n".join([
            f"updated_at={time.strftime('%Y-%m-%d %H:%M:%S')}",
            f"python={python_bin}",
            f"platform={sys.platform}",
            f"xhs_commit={repo_head(engine_src('xhs'))}",
            f"ks_commit={repo_head(engine_src('ks'))}",
            f"browser={'installed' if browser_ready() else 'missing'}",
        ]) + "\n",
        encoding="utf-8",
    )
    say("[bootstrap] 完成 ✓")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
