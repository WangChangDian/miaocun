#!/usr/bin/env python3
"""视频下载器 - 引擎桥接进程（JSON Lines over stdio）。

由 SwiftUI 外壳以子进程方式启动，使用 yt-dlp 引擎的虚拟环境运行。

请求（stdin，每行一个 JSON）:
    {"id": "...", "cmd": "ping"}
    {"id": "...", "cmd": "engine_status"}
    {"id": "...", "cmd": "refresh_cookies", "browser": "auto|chrome|safari|edge|brave"}
    {"id": "...", "cmd": "probe", "url": "..."}
    {"id": "...", "cmd": "download", "url": "...", "out_dir": "...", "name_template": "..."}
    {"id": "...", "cmd": "cancel", "task_id": "..."}
    {"id": "...", "cmd": "set_settings", "settings": {...}}
    {"id": "...", "cmd": "update_engines"}
    {"id": "...", "cmd": "shutdown"}

事件（stdout，每行一个 JSON）:
    {"id": "...", "event": "stage|meta|progress|log|done|error|pong|engine_status", ...}
"""

import contextlib
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import traceback
import unicodedata
import uuid
from pathlib import Path

APP_NAME = "视频下载器"


def _load_version() -> tuple:
    """版本号唯一来源是仓库根目录的 VERSION 文件（打包时会复制到引擎目录旁）。

    versionCode 默认由 versionName 推导：major*10000 + minor*100 + patch（1.5.0 -> 10500）；
    VERSION 里显式写了 versionCode= 时以手写的为准。规则与 scripts/version.sh 一致。
    """
    for candidate in (Path(__file__).resolve().parent / "VERSION",
                      Path(__file__).resolve().parent.parent / "VERSION"):
        try:
            data = {}
            for line in candidate.read_text(encoding="utf-8").splitlines():
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, _, value = line.partition("=")
                data[key.strip()] = value.strip()
            name = data.get("versionName", "0.0.0")
            if data.get("versionCode"):
                return name, int(data["versionCode"])
            numbers = []
            for item in name.split("-")[0].split(".")[:3]:
                digits = "".join(ch for ch in item if ch.isdigit())
                numbers.append(int(digits) if digits else 0)
            while len(numbers) < 3:
                numbers.append(0)
            return name, numbers[0] * 10000 + numbers[1] * 100 + numbers[2]
        except (OSError, ValueError):
            continue
    return "0.0.0", 0


# Windows 上 Python 的 stdout 默认走本地编码（GBK）：JSON 里只要有中文（比如
# 保存目录 C:\...\视频下载），写出的字节就不是合法 UTF-8，宿主（Tauri 的 Rust 侧按
# UTF-8 逐行读）会读到坏行并中断整个事件流 —— 表现是界面"只有前两条事件，之后全没了"。
# 这里统一强制 UTF-8；macOS/Linux 本来就是 UTF-8，只是显式化。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except Exception:  # noqa: BLE001
        pass
# stdin 同样强制 UTF-8：Windows 上默认按本地编码（GBK）解码，
# 请求里带中文（比如保存目录 D:\下载\视频下载）会解出乱码，JSON 直接解析失败
try:
    sys.stdin.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # noqa: BLE001
    pass

APP_VERSION_NAME, APP_VERSION_CODE = _load_version()

def _support_dir() -> Path:
    """
    三端一致的应用数据目录（必须和 scripts/bootstrap-engine.py、
    tauri/src-tauri/src/main.rs 里的 support_dir() 保持一致，
    否则 Windows 上会出现「bootstrap 装到 A、引擎去 B 找」的死循环）。
    """
    home = Path.home()
    if sys.platform == "darwin":
        return home / "Library/Application Support" / APP_NAME
    if sys.platform.startswith("win"):
        base = os.environ.get("APPDATA") or (home / "AppData/Roaming")
        return Path(base) / APP_NAME
    return Path(os.environ.get("XDG_DATA_HOME", home / ".local/share")) / APP_NAME


SUPPORT_DIR = _support_dir()
ENGINES_DIR = SUPPORT_DIR / "engines"
BROWSERS_DIR = SUPPORT_DIR / "browsers"
LOG_DIR = SUPPORT_DIR / "logs"
COOKIE_DIR = SUPPORT_DIR / "cookies"
TMP_DIR = SUPPORT_DIR / "tmp"
SETTINGS_FILE = SUPPORT_DIR / "settings.json"
HISTORY_FILE = SUPPORT_DIR / "history.json"
COOKIE_FILE = COOKIE_DIR / "cookies.txt"
COOKIE_STORE = COOKIE_DIR / "store.json"
BROWSER_PROFILE_DIR = SUPPORT_DIR / "browser-profile"
BRIDGE_LOG = LOG_DIR / "bridge.log"

# 内置运行时目录（安装包自带的便携 Python + 依赖 + 浏览器 + ffmpeg）。
# 打包后位于 <应用资源目录>/runtime；开发机可以用 VIDEOGRABBER_RUNTIME 覆盖。
# 有它时：用户不需要系统装 Python / ffmpeg，也不用联网做首次初始化。
RUNTIME_DIR = Path(
    os.environ.get("VIDEOGRABBER_RUNTIME")
    or (Path(__file__).resolve().parent.parent / "runtime")
)

DEFAULT_SETTINGS = {
    "out_dir": str(Path.home() / "Downloads" / "视频下载"),
    "name_template": "{author}-{title}",
    "cookie_browser": "auto",
    "browser_engine": "auto",
    "auto_open_finder": False,
    "proxy": "",
    # 画质偏好：0 = 最高画质；1080 / 720 等表示不要超过这个高度
    "max_height": 0,
    # 视频号是否优先 H.265（同分辨率体积约减半，部分老播放器/剪辑软件不支持）
    "wxchannels_h265": False,
    # 云控后台
    "server_url": "https://music.aiyun-jing.com:8443/downloader-api",
    "device_id": "",
    "device_token": "",
    # B2 账号（手机号登录）令牌与手机号
    "account_token": "",
    "account_phone": "",
}

# 内置浏览器内核（Chrome for Testing）安装目录，Playwright 依赖该环境变量定位
os.environ["PLAYWRIGHT_BROWSERS_PATH"] = os.environ.get("VIDEOGRABBER_BROWSERS_PATH", str(BROWSERS_DIR))


def bundled_python() -> Path | None:
    """内置运行时里的便携 Python（Windows: python/python.exe；macOS/Linux: python/bin/python3）。"""
    for relative in ("python/python.exe", "python/bin/python3"):
        candidate = RUNTIME_DIR / relative
        if candidate.exists():
            return candidate
    return None


def bundled_ffmpeg_dir() -> Path | None:
    """内置 ffmpeg/ffprobe 目录（runtime/ffmpeg/bin）。"""
    for relative in ("ffmpeg/bin", "ffmpeg"):
        candidate = RUNTIME_DIR / relative
        if (candidate / "ffmpeg.exe").exists() or (candidate / "ffmpeg").exists():
            return candidate
    return None


def runtime_manifest() -> dict:
    """内置运行时的版本清单（由 scripts/bundle-runtime.py 生成，排障/界面展示用）。"""
    try:
        return json.loads((RUNTIME_DIR / "manifest.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def apply_runtime_paths() -> None:
    """把内置 ffmpeg 加进 PATH，让 yt-dlp 与 ffprobe 直接可用（用户不需要装 ffmpeg）。"""
    ffmpeg_dir = bundled_ffmpeg_dir()
    if ffmpeg_dir:
        current = os.environ.get("PATH", "")
        if str(ffmpeg_dir) not in current.split(os.pathsep):
            os.environ["PATH"] = str(ffmpeg_dir) + os.pathsep + current
    # 内置浏览器目录（安装包里的 runtime/browsers）优先于用户目录
    if (RUNTIME_DIR / "browsers").exists() and not os.environ.get("VIDEOGRABBER_BROWSERS_PATH"):
        os.environ["VIDEOGRABBER_BROWSERS_PATH"] = str(RUNTIME_DIR / "browsers")
        os.environ["PLAYWRIGHT_BROWSERS_PATH"] = str(RUNTIME_DIR / "browsers")


def apply_runtime_dependencies() -> None:
    """
    把内置运行时的依赖目录加进 sys.path。

    runtime/pkgs/<engine> 是 `pip --target` 装出来的（为了可搬迁，没用 venv），
    所以解释器不会自动找到它们 —— 不注入的话，用内置运行时启动引擎会直接报
    `No module named 'curl_cffi'`，表现就是「应用能开，但一下载就失败」。
    """
    pkgs = RUNTIME_DIR / "pkgs"
    if not pkgs.exists():
        return
    for name in ("xhs", "ks", "ytdlp"):  # 倒序 insert，让 ytdlp 排在最前
        candidate = pkgs / name
        if candidate.exists() and str(candidate) not in sys.path:
            sys.path.insert(0, str(candidate))


apply_runtime_paths()
apply_runtime_dependencies()

BROWSER_ORDER = ["chrome", "edge", "brave", "safari", "chromium", "firefox"]

UA_CHROME = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)

PLATFORM_DOMAINS = {
    "douyin": {
        "hosts": (
            "v.douyin.com",
            "www.douyin.com",
            "douyin.com",
            "iesdouyin.com",
            "m.douyin.com",
        ),
        "cookie_domains": ("douyin.com", "iesdouyin.com"),
        "label": "抖音",
    },
    "kuaishou": {
        "hosts": (
            "v.kuaishou.com",
            "www.kuaishou.com",
            "kuaishou.com",
            "kuaishou.cn",
            "c.kuaishou.com",
        ),
        "cookie_domains": ("kuaishou.com", "kuaishou.cn"),
        "label": "快手",
    },
    "xiaohongshu": {
        "hosts": (
            "xhslink.com",
            "xhslink.cn",
            "www.xiaohongshu.com",
            "xiaohongshu.com",
            "www.rednote.com",
            "rednote.com",
        ),
        "cookie_domains": ("xiaohongshu.com", "rednote.com", "xhslink.com"),
        "label": "小红书",
    },
    "x": {
        "hosts": ("x.com", "twitter.com", "t.co", "mobile.twitter.com", "mobile.x.com"),
        "cookie_domains": ("x.com", "twitter.com"),
        "label": "X",
    },
    "youtube": {
        "hosts": (
            "youtube.com",
            "www.youtube.com",
            "m.youtube.com",
            "music.youtube.com",
            "youtu.be",
            "youtube-nocookie.com",
        ),
        "cookie_domains": ("youtube.com", "google.com"),
        # 交给 yt-dlp 的 Cookie 优先只用 .youtube.com 域，避免与 .google.com 混用被判 CookieMismatch
        "preferred_domains": ("youtube.com",),
        "label": "YouTube",
    },
    "wechat_channels": {
        # 视频号的分享链接形如 weixin.qq.com/sph/xxx
        "hosts": ("weixin.qq.com",),
        # 必须带 /sph/ 才算视频号分享链接（weixin.qq.com 上还有别的页面）
        "path_marker": "/sph/",
        # 解析视频号要用「腾讯元宝」的登录态，不是视频号本身的 Cookie
        "cookie_domains": ("tencent.com",),
        "label": "微信视频号",
    },
    # 下面几个都交给 yt-dlp：可不下 Cookie 用，登录后清晰度/成功率更高
    "bilibili": {
        "hosts": (
            "bilibili.com",
            "www.bilibili.com",
            "m.bilibili.com",
            "b23.tv",
            "bili2233.cn",
        ),
        "cookie_domains": ("bilibili.com",),
        "label": "B 站",
    },
    "weibo": {
        "hosts": (
            "weibo.com",
            "www.weibo.com",
            "m.weibo.cn",
            "weibo.cn",
            "video.weibo.com",
        ),
        "cookie_domains": ("weibo.com", "weibo.cn"),
        "label": "微博",
    },
    "tiktok": {
        "hosts": (
            "tiktok.com",
            "www.tiktok.com",
            "m.tiktok.com",
            "vm.tiktok.com",
            "vt.tiktok.com",
        ),
        "cookie_domains": ("tiktok.com",),
        "label": "TikTok",
    },
    "instagram": {
        "hosts": (
            "instagram.com",
            "www.instagram.com",
            "m.instagram.com",
        ),
        "cookie_domains": ("instagram.com",),
        "label": "Instagram",
    },
    # ---- 第三梯队：yt-dlp 原生支持的平台（西瓜 / Reddit / Vimeo / Twitch）----
    "ixigua": {
        "hosts": ("ixigua.com", "www.ixigua.com"),
        "cookie_domains": ("ixigua.com",),
        "label": "西瓜视频",
    },
    "reddit": {
        "hosts": ("reddit.com", "www.reddit.com", "old.reddit.com", "redd.it", "v.redd.it"),
        "cookie_domains": ("reddit.com",),
        "label": "Reddit",
        "needs_proxy": True,
    },
    "vimeo": {
        "hosts": ("vimeo.com", "www.vimeo.com", "player.vimeo.com"),
        "cookie_domains": ("vimeo.com",),
        "label": "Vimeo",
        "needs_proxy": True,
    },
    "twitch": {
        "hosts": ("twitch.tv", "www.twitch.tv", "m.twitch.tv", "clips.twitch.tv"),
        "cookie_domains": ("twitch.tv",),
        "label": "Twitch",
        "needs_proxy": True,
    },
    # ---- 微视 / 好看视频：yt-dlp 还没有专用解析器，先交给通用解析 + 网页嗅探兜底 ----
    "weishi": {
        "hosts": ("isee.weishi.qq.com", "weishi.qq.com", "v.weishi.qq.com"),
        "cookie_domains": ("qq.com",),
        "label": "微视",
    },
    "haokan": {
        "hosts": ("haokan.baidu.com", "haokan.hao123.com"),
        "cookie_domains": ("baidu.com",),
        "label": "好看视频",
    },
    # ---- 音频平台：yt-dlp 原生支持（网易云不需要登录；QQ 音乐多数歌曲要登录 Cookie）----
    "netease": {
        "hosts": ("music.163.com", "y.music.163.com", "163cn.tv"),
        "cookie_domains": ("music.163.com", "163.com"),
        "label": "网易云音乐",
    },
    "qqmusic": {
        "hosts": ("y.qq.com", "c.y.qq.com", "i.y.qq.com", "c6.y.qq.com"),
        "cookie_domains": ("qq.com",),
        "label": "QQ 音乐",
    },
}

URL_PATTERN = re.compile(r"https?://[^\s\u4e00-\u9fff，。！？；：、“”‘’《》【】（）()\"'<>\\^`{|}]+")
TRAILING_CHARS = ".,，。；;!！?？、)）】》>\"'"

_REAL_STDOUT = sys.stdout
_EMIT_LOCK = threading.Lock()


def emit(event: dict) -> None:
    with _EMIT_LOCK:
        _REAL_STDOUT.write(json.dumps(event, ensure_ascii=False) + "\n")
        _REAL_STDOUT.flush()


def log(message: str, task_id: str | None = None, echo: bool = False) -> None:
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    try:
        if BRIDGE_LOG.exists() and BRIDGE_LOG.stat().st_size > 2 * 1024 * 1024:
            shutil.move(str(BRIDGE_LOG), str(BRIDGE_LOG.with_suffix(".log.1")))
    except OSError:
        pass
    stamp = time.strftime("%Y-%m-%d %H:%M:%S")
    line = f"[{stamp}] {message}"
    try:
        with BRIDGE_LOG.open("a", encoding="utf-8") as handle:
            handle.write(line + "\n")
    except OSError:
        pass
    if echo:
        emit({"id": task_id, "event": "log", "message": message})


_ACTIVE_SETTINGS: dict = {}


def load_settings() -> dict:
    SUPPORT_DIR.mkdir(parents=True, exist_ok=True)
    settings = dict(DEFAULT_SETTINGS)
    if SETTINGS_FILE.exists():
        try:
            settings.update(json.loads(SETTINGS_FILE.read_text(encoding="utf-8")))
        except (OSError, ValueError):
            # 真读坏了就用上一次的备份，避免"登录态莫名消失"
            backup = SETTINGS_FILE.with_suffix(".json.bak")
            if backup.exists():
                try:
                    settings.update(json.loads(backup.read_text(encoding="utf-8")))
                    log("settings.json 读取失败，已从备份恢复")
                except (OSError, ValueError):
                    log("settings.json 读取失败，使用默认设置")
            else:
                log("settings.json 读取失败，使用默认设置")
    _ACTIVE_SETTINGS.clear()
    _ACTIVE_SETTINGS.update(settings)
    return settings


def active_proxy() -> str:
    """
    当前生效的代理地址（如 http://127.0.0.1:7890），空表示直连。

    设置里手填的优先；没填就读系统代理（macOS 的 scutil，Clash/Surge 开了「系统代理」
    但用户没在设置里填端口时也能走通——之前这里只认手填值，导致 Cookie 有效性测试、
    抖音接口兜底这些走 http_session 的请求在需要代理时直接超时）。
    """
    manual = (_ACTIVE_SETTINGS.get("proxy") or "").strip()
    if manual:
        return manual
    return system_proxy()


_SYSTEM_PROXY_CACHE: dict = {"at": 0.0, "value": ""}


def system_proxy() -> str:
    """读系统代理（目前实现了 macOS；Windows 由 yt-dlp 自己读 IE 代理设置）。"""
    now = time.time()
    if now - _SYSTEM_PROXY_CACHE["at"] < 60:
        return _SYSTEM_PROXY_CACHE["value"]
    value = ""
    if sys.platform == "darwin":
        try:
            output = subprocess.run(
                ["scutil", "--proxy"], capture_output=True, text=True, timeout=3
            ).stdout
            enabled = re.search(r"HTTPEnable\s*:\s*(\d)", output)
            host = re.search(r"HTTPProxy\s*:\s*(\S+)", output)
            port = re.search(r"HTTPPort\s*:\s*(\d+)", output)
            if enabled and enabled.group(1) == "1" and host and port:
                value = f"http://{host.group(1)}:{port.group(1)}"
        except Exception:  # noqa: BLE001
            value = ""
    _SYSTEM_PROXY_CACHE["at"] = now
    _SYSTEM_PROXY_CACHE["value"] = value
    return value


def activate_settings(settings: dict) -> None:
    """把一份设置设为「当前生效」——http_session() 等地方读的就是它。

    下载请求里带的 proxy / out_dir 等覆盖项也要走这里，
    否则出现「yt-dlp 走了代理、http_session 还在直连」这种半套配置。
    """
    _ACTIVE_SETTINGS.clear()
    _ACTIVE_SETTINGS.update(settings or {})


def save_settings(patch: dict) -> dict:
    settings = load_settings()
    settings.update({key: value for key, value in patch.items() if key in DEFAULT_SETTINGS})
    # 原子写：先写临时文件再 rename。之前直接覆盖写，另一个线程正好在读时会读到半截 JSON
    # （日志里的「settings.json 读取失败，使用默认设置」就是这么来的）
    tmp = SETTINGS_FILE.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(settings, ensure_ascii=False, indent=2), encoding="utf-8")
    if SETTINGS_FILE.exists():
        with contextlib.suppress(OSError):
            shutil.copyfile(SETTINGS_FILE, SETTINGS_FILE.with_suffix(".json.bak"))
    os.replace(tmp, SETTINGS_FILE)
    activate_settings(settings)
    return settings


def extract_url(text: str) -> str | None:
    match = URL_PATTERN.search(text or "")
    if not match:
        return None
    return match.group().rstrip(TRAILING_CHARS)


def detect_platform(url: str) -> str | None:
    try:
        from urllib.parse import urlparse

        parsed = urlparse(url)
        host = (parsed.hostname or "").lower()
        path = parsed.path or ""
    except ValueError:
        return None
    for platform, meta in PLATFORM_DOMAINS.items():
        for candidate in meta["hosts"]:
            if host == candidate or host.endswith("." + candidate):
                # 有些平台（如视频号）只认特定路径，避免把同域名下的其它链接也吸过来
                marker = meta.get("path_marker")
                if marker and marker not in path:
                    break
                return platform
    return None


def engine_python(engine: str) -> Path:
    """跑某个引擎用哪个解释器：内置运行时优先，其次用户目录里的 venv（开发机 / 首次初始化）。"""
    bundled = bundled_python()
    if bundled:
        return bundled
    venv = ENGINES_DIR / engine / "venv"
    return venv / ("Scripts/python.exe" if os.name == "nt" else "bin/python3")


def engine_root(engine: str) -> Path:
    """引擎源码目录：内置运行时优先（安装包自带），其次用户目录（bootstrap 下载的）。"""
    bundled = RUNTIME_DIR / "engines" / engine
    if bundled.exists():
        return bundled
    return ENGINES_DIR / engine / "src"


def engine_env(engine: str) -> dict:
    """
    引擎子进程的环境变量。

    内置运行时把每个引擎的依赖装在独立目录（runtime/pkgs/<engine>，由 pip --target 生成），
    这里通过 PYTHONPATH 注入——比 venv 可搬迁（venv 里写死了绝对路径，装到别处就废了）。
    """
    env = dict(os.environ)
    packages = RUNTIME_DIR / "pkgs" / engine
    if packages.exists():
        existing = env.get("PYTHONPATH", "")
        env["PYTHONPATH"] = str(packages) + (os.pathsep + existing if existing else "")
    return env


def http_session():
    from curl_cffi import requests as cffi_requests

    session = cffi_requests.Session(impersonate="chrome")
    proxy = active_proxy()
    if proxy:
        session.proxies = {"http": proxy, "https": proxy}
    return session


BROWSER_LABELS = {
    "chromium": "内置内核",
    "chrome": "系统 Chrome",
    "msedge": "Microsoft Edge",
}


def browser_engine_candidates(settings: dict | None) -> list[tuple[str, str]]:
    """返回待尝试的 (channel, 显示名) 列表；browser_engine=auto 时按内置 → Chrome → Edge 顺序。"""
    preference = ((settings or {}).get("browser_engine") or "auto").strip()
    if preference in BROWSER_LABELS:
        return [(preference, BROWSER_LABELS[preference])]
    return [(key, label) for key, label in BROWSER_LABELS.items()]


def browser_installed_status() -> dict:
    """检测三种浏览器内核的可用性。"""
    bundled = sorted(path.name for path in BROWSERS_DIR.glob("chromium*")) if BROWSERS_DIR.exists() else []
    system_candidates = {
        "chrome": [
            Path("/Applications/Google Chrome.app"),
            Path.home() / "Applications/Google Chrome.app",
            Path("C:/Program Files/Google/Chrome/Application/chrome.exe"),
        ],
        "msedge": [
            Path("/Applications/Microsoft Edge.app"),
            Path("C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"),
        ],
    }
    return {
        "chromium": {"ready": bool(bundled), "detail": bundled[0] if bundled else "未安装"},
        "chrome": {
            "ready": any(path.exists() for path in system_candidates["chrome"]),
            "detail": "系统已安装" if any(path.exists() for path in system_candidates["chrome"]) else "未安装",
        },
        "msedge": {
            "ready": any(path.exists() for path in system_candidates["msedge"]),
            "detail": "系统已安装" if any(path.exists() for path in system_candidates["msedge"]) else "未安装",
        },
    }


def launch_browser(pw, settings: dict | None, headless: bool = True):
    """按设置顺序启动浏览器；返回 (browser, channel, label)。"""
    errors = []
    for channel, label in browser_engine_candidates(settings):
        try:
            browser = pw.chromium.launch(channel=channel, headless=headless)
            log(f"使用浏览器内核: {label} ({channel})")
            return browser, channel, label
        except Exception as error:  # noqa: BLE001
            errors.append(f"{label}: {error}")
            log(f"启动{label}失败: {error!r}")
    raise RuntimeError("没有可用的浏览器内核（" + "；".join(errors) + "）")


def launch_persistent_browser(
    pw,
    settings: dict | None,
    headless: bool = False,
    user_agent: str | None = None,
    mobile: bool = False,
):
    """带持久配置（可登录）的浏览器窗口；返回 context。"""
    BROWSER_PROFILE_DIR.mkdir(parents=True, exist_ok=True)
    errors = []
    for channel, label in browser_engine_candidates(settings):
        try:
            options = {
                "user_data_dir": str(BROWSER_PROFILE_DIR),
                "channel": channel,
                "headless": headless,
                "locale": "zh-CN",
                "args": ["--no-first-run", "--no-default-browser-check"],
            }
            if mobile:
                options.update({
                    "user_agent": user_agent,
                    "viewport": {"width": 393, "height": 852},
                    "is_mobile": True,
                    "has_touch": True,
                })
            else:
                options["viewport"] = {"width": 1280, "height": 900}
            context = pw.chromium.launch_persistent_context(
                **options
            )
            log(f"持久配置浏览器内核: {label} ({channel})")
            return context, channel, label
        except Exception as error:  # noqa: BLE001
            errors.append(f"{label}: {error}")
            log(f"登录窗口启动{label}失败: {error!r}")
    raise RuntimeError("没有可用的浏览器内核（" + "；".join(errors) + "）")


def resolve_url(url: str, session=None) -> str:
    """跟随短链跳转，返回最终 URL；失败时返回原 URL。"""
    session = session or http_session()
    try:
        response = session.get(
            url,
            allow_redirects=True,
            stream=True,
            timeout=45,
            headers={"User-Agent": UA_CHROME, "Accept-Language": "zh-CN,zh;q=0.9"},
        )
        final = str(response.url) or url
        response.close()
        return final
    except Exception as error:  # noqa: BLE001
        log(f"短链解析失败（将继续使用原始链接）: {error!r}")
        return url


def normalize_url(url: str, platform: str) -> str:
    if platform == "douyin":
        match = re.search(r"(?:video|note|share/video)/(\d{6,})", url)
        if not match:
            match = re.search(r"(\d{15,})", url)
        if match:
            return f"https://www.douyin.com/video/{match.group(1)}"
    return url


BLOCKED_REDIRECT_MARKERS = ("/login", "redirectpath=", "/verify", "captcha", "website-login")


def is_blocked_redirect(url: str) -> bool:
    """判断跳转结果是否是登录/验证页（这类页面不能交给引擎）。"""
    lowered = (url or "").lower()
    return any(marker in lowered for marker in BLOCKED_REDIRECT_MARKERS)


def sanitize_name(name: str, fallback: str) -> str:
    name = unicodedata.normalize("NFC", name or "")
    name = re.sub(r"[\x00-\x1f\x7f]", "", name)
    name = name.replace("/", "／").replace("\\", "＼").replace(":", "：")
    name = re.sub(r"\s+", " ", name).strip().strip(".")
    # 名称长度上限：Windows 的整条路径（保存目录 + 文件夹 + 文件名）上限 260 字符，
    # 中文名一个字算一个字符但仍很占位置，所以 Windows 收得更紧；macOS 没有这个问题
    limit = 60 if os.name == "nt" else 110
    name = name[:limit].strip()
    return name or fallback


def unique_path(directory: Path, base: str, suffix: str) -> Path:
    candidate = directory / f"{base}{suffix}"
    index = 2
    while candidate.exists():
        candidate = directory / f"{base} ({index}){suffix}"
        index += 1
    return candidate


def unique_dir(directory: Path, base: str) -> Path:
    candidate = directory / base
    index = 2
    while candidate.exists():
        candidate = directory / f"{base} ({index})"
        index += 1
    return candidate


def build_base_name(template: str, author: str, title: str, fallback: str) -> str:
    name = (template or "{author}-{title}")
    name = name.replace("{author}", author or "").replace("{title}", title or "")
    return sanitize_name(name.strip(" -_"), fallback)


# ---------------------------------------------------------------- cookies


def load_cookie_store() -> dict:
    """Cookie 存储：{platform: {cookie, updated_at, source, count}}"""
    if COOKIE_STORE.exists():
        try:
            data = json.loads(COOKIE_STORE.read_text(encoding="utf-8"))
            if isinstance(data, dict):
                return data
        except (OSError, ValueError):
            log("Cookie 存储读取失败，回退到旧格式")
    # 兼容旧的 platform-cookies.json
    legacy = COOKIE_DIR / "platform-cookies.json"
    store: dict = {}
    if legacy.exists():
        try:
            for platform, value in json.loads(legacy.read_text(encoding="utf-8")).items():
                if value:
                    store[platform] = {
                        "cookie": value,
                        "updated_at": time.strftime("%Y-%m-%d %H:%M:%S"),
                        "source": "系统浏览器",
                        "count": len([item for item in value.split("; ") if "=" in item]),
                    }
        except (OSError, ValueError):
            pass
    return store


def save_cookie_store(store: dict) -> None:
    COOKIE_DIR.mkdir(parents=True, exist_ok=True)
    COOKIE_STORE.write_text(json.dumps(store, ensure_ascii=False, indent=2), encoding="utf-8")
    write_netscape_cookies(store)


def netscape_domain_for(platform: str) -> str | None:
    """该平台写进 cookies.txt 时用哪个域。

    直接取 PLATFORM_DOMAINS 里的首个 cookie 域，避免又漏一个平台——
    之前只写了抖音/快手/小红书，导致 X / YouTube / B 站等平台的登录 Cookie
    没进 cookies.txt，yt-dlp 等于没带 Cookie 在跑。
    """
    meta = PLATFORM_DOMAINS.get(platform) or {}
    domains = meta.get("preferred_domains") or meta.get("cookie_domains") or ()
    if not domains:
        return None
    return "." + str(domains[0]).lstrip(".")


def write_netscape_cookies(store: dict) -> None:
    """把 Cookie 字符串写成 yt-dlp 可用的 Netscape 格式文件。"""
    lines = ["# Netscape HTTP Cookie File", ""]
    for platform, entry in store.items():
        domain = netscape_domain_for(platform)
        cookie = (entry or {}).get("cookie", "")
        if not domain or not cookie:
            continue
        pairs = cookie.split("; ")
        # B 站接口按 CURRENT_FNVAL 决定返回哪些清晰度：4048 = 含 4K/HDR/杜比。
        # 浏览器里播放过视频才会有这个 Cookie，应用内登录往往没有 → 这里补上，
        # 否则登录了也只能拿到 1080P（实测差异就在这里）。
        if platform == "bilibili":
            names = {p.split("=", 1)[0].strip() for p in pairs if "=" in p}
            if "CURRENT_FNVAL" not in names:
                pairs.append("CURRENT_FNVAL=4048")
            if "CURRENT_QUALITY" not in names:
                pairs.append("CURRENT_QUALITY=120")
        for pair in pairs:
            if "=" not in pair:
                continue
            name, _, value = pair.partition("=")
            lines.append("\t".join([domain, "TRUE", "/", "TRUE", "0", name.strip(), value.strip()]))
    COOKIE_DIR.mkdir(parents=True, exist_ok=True)
    COOKIE_FILE.write_text("\n".join(lines) + "\n", encoding="utf-8")


def update_platform_cookie(platform: str, cookie: str, source: str) -> dict:
    store = load_cookie_store()
    cookie = (cookie or "").strip().rstrip(";")
    if cookie:
        store[platform] = {
            "cookie": cookie,
            "updated_at": time.strftime("%Y-%m-%d %H:%M:%S"),
            "source": source,
            "count": len([item for item in cookie.split("; ") if "=" in item]),
        }
    else:
        store.pop(platform, None)
    save_cookie_store(store)
    # 重新登录 / 手动粘贴 / 清除后，之前的「登录已失效」标记都不再生效
    clear_cookie_invalid(platform)
    return store.get(platform, {})


def cookie_status_payload() -> dict:
    store = load_cookie_store()
    payload = {}
    for platform in PLATFORM_DOMAINS:
        entry = store.get(platform) or {}
        payload[platform] = {
            "has_cookie": bool(entry.get("cookie")),
            "count": entry.get("count", 0),
            "updated_at": entry.get("updated_at", ""),
            "source": entry.get("source", ""),
            "invalid": cookie_is_invalid(platform),
        }
    return payload


def extract_cookies(browser: str = "auto", task_id: str | None = None) -> dict:
    """从本机浏览器读取 Cookie，写出 cookies.txt 与各平台 Cookie 字符串。"""
    from yt_dlp.cookies import extract_cookies_from_browser

    candidates = BROWSER_ORDER if browser in ("", "auto", None) else [browser]
    last_error = None
    jar = None
    used = None
    for name in candidates:
        try:
            log(f"读取 {name} Cookie…", task_id, echo=True)
            jar = extract_cookies_from_browser(name)
            used = name
            break
        except Exception as error:  # noqa: BLE001
            last_error = error
            log(f"读取 {name} Cookie 失败: {error!r}", task_id)

    if jar is None:
        raise RuntimeError(f"未能从浏览器读取 Cookie：{last_error!r}")

    store = load_cookie_store()
    for platform, meta in PLATFORM_DOMAINS.items():
        pairs = [
            f"{cookie.name}={cookie.value}"
            for cookie in jar
            if any(keyword in (cookie.domain or "") for keyword in meta["cookie_domains"])
        ]
        # YouTube 特殊处理：浏览器只会把 .youtube.com 的 Cookie 发给 youtube.com，
        # 把 .google.com 的混进来会被 Google 判成 CookieMismatch。优先只用 youtube.com 的。
        preferred = meta.get("preferred_domains")
        if pairs and preferred:
            preferred_pairs = [
                f"{cookie.name}={cookie.value}"
                for cookie in jar
                if any(keyword in (cookie.domain or "") for keyword in preferred)
            ]
            if preferred_pairs:
                pairs = preferred_pairs
        if pairs:
            store[platform] = {
                "cookie": "; ".join(pairs),
                "updated_at": time.strftime("%Y-%m-%d %H:%M:%S"),
                "source": f"系统浏览器({used})",
                "count": len(pairs),
            }
            # 从系统浏览器重新导入后，旧的「登录已失效」标记作废
            clear_cookie_invalid(platform)
    save_cookie_store(store)

    payload = {
        "browser": used,
        "total": len(jar),
        "domains": {platform: (store.get(platform) or {}).get("count", 0) for platform in PLATFORM_DOMAINS},
    }
    log(f"Cookie 提取完成：{payload}", task_id, echo=True)
    return payload


def cookie_string_for(platform: str) -> str:
    return ((load_cookie_store().get(platform) or {}).get("cookie") or "").strip()


def cookie_invalid_flag(platform: str) -> Path:
    """登录态失效标记（下载时被平台拒绝就写一个，重新登录/下载成功自动清掉）"""
    return COOKIE_DIR / f"{platform}.invalid"


def cookie_is_invalid(platform: str) -> bool:
    if not cookie_string_for(platform):
        return False
    try:
        return cookie_invalid_flag(platform).exists()
    except OSError:
        return False


def mark_cookie_invalid(platform: str, reason: str = "") -> None:
    """只有已配置 Cookie 的平台才需要标记（没配置的走「未登录」提示）"""
    if not platform or not cookie_string_for(platform):
        return
    try:
        cookie_invalid_flag(platform).write_text(
            f"{time.strftime('%Y-%m-%d %H:%M:%S')} {reason}".strip(),
            encoding="utf-8",
        )
        log(f"{platform} 登录态已失效：{reason or '平台拒绝了请求'}")
    except OSError:
        pass


def clear_cookie_invalid(platform: str) -> None:
    try:
        cookie_invalid_flag(platform).unlink(missing_ok=True)
    except OSError:
        pass


# ---------------------------------------------------------------- 云控后台


def cloud_base_url() -> str:
    url = (_ACTIVE_SETTINGS.get("server_url") or DEFAULT_SETTINGS["server_url"]).strip()
    return url.rstrip("/")


def _http_json(method: str, path: str, payload: dict | None = None, token: str | None = None,
               timeout: float = 15.0) -> dict:
    """极简云端请求：只用标准库，避免给本机增加依赖。"""
    import urllib.error
    import urllib.request

    url = cloud_base_url() + path
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("Content-Type", "application/json")
    request.add_header("Accept", "application/json")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", errors="replace")
        try:
            parsed = json.loads(body)
        except ValueError:
            parsed = {}
        raise RuntimeError(parsed.get("msg") or f"服务器错误 HTTP {error.code}") from error
    except urllib.error.URLError as error:
        raise RuntimeError(f"无法连接云控后台（{error.reason}）") from error
    try:
        parsed = json.loads(body or "{}")
    except ValueError as error:
        raise RuntimeError("云控后台返回了无法解析的数据") from error
    if parsed.get("code") not in (200, None):
        raise RuntimeError(parsed.get("msg") or "云端请求失败")
    return parsed.get("data") or {}


def device_platform_key() -> str:
    return "macos" if sys.platform == "darwin" else "windows" if os.name == "nt" else "linux"


def machine_hostname() -> str:
    """跨平台主机名：Windows 上没有 os.uname()，统一走 socket。"""
    try:
        import socket

        return socket.gethostname() or "unknown"
    except Exception:  # noqa: BLE001
        return "unknown"


def machine_device_id() -> str:
    """稳定设备标识：优先用系统 UUID，退化到主机名 + 用户名。"""
    try:
        import uuid as _uuid

        node = _uuid.getnode()
        if node:
            return f"{device_platform_key()}-{node:012x}"
    except Exception:  # noqa: BLE001
        pass
    return f"{device_platform_key()}-{uuid.uuid5(uuid.NAMESPACE_DNS, machine_hostname()).hex[:12]}"


def cloud_register(force: bool = False) -> dict:
    """设备注册/续期，成功后把 token 写进 settings.json。"""
    settings = load_settings()
    device_id = (settings.get("device_id") or "").strip() or machine_device_id()
    payload = {
        "platform": device_platform_key(),
        "deviceId": device_id,
        "deviceName": machine_hostname() or device_id,
        "appVersion": APP_VERSION_NAME,
    }
    if settings.get("device_token") and not force:
        return {"deviceId": device_id, "deviceToken": settings["device_token"], "reused": True}
    data = _http_json("POST", "/app/device/register", payload)
    save_settings({"device_id": device_id, "device_token": data.get("token", "")})
    return {"deviceId": device_id, "deviceToken": data.get("token", ""), "expireDays": data.get("expireDays")}


def cloud_token() -> str:
    token = (_ACTIVE_SETTINGS.get("device_token") or "").strip()
    if token:
        return token
    return (load_settings().get("device_token") or "").strip()


def device_request(method: str, path: str, payload: dict | None = None, _retried: bool = False) -> dict:
    """带设备令牌的请求；令牌在服务端失效（清缓存 / 换库 / 重启）时自动重新注册并重试一次。"""
    token = cloud_token()
    if not token:
        cloud_register(force=True)
        token = cloud_token()
    try:
        return _http_json(method, path, payload, token=token)
    except RuntimeError as error:
        message = str(error)
        expired = ("未注册" in message) or ("过期" in message) or ("登录已失效" in message)
        if expired and not _retried:
            cloud_register(force=True)
            return device_request(method, path, payload, _retried=True)
        raise


def cloud_config() -> dict:
    return device_request("GET", "/app/config")


def cloud_check_update(platform: str | None = None) -> dict:
    """版本检查不要求注册/登录：有设备令牌就带上（顺带记活跃），没有也能查。"""
    key = platform or device_platform_key()
    return _http_json("GET", f"/app/version/latest?platform={key}&versionCode={APP_VERSION_CODE}",
                      token=cloud_token() or None)


def cloud_feedback(payload: dict) -> dict:
    body = {
        "type": payload.get("type") or "SUGGESTION",
        "content": payload.get("content") or "",
        "contact": payload.get("contact") or "",
        "platform": device_platform_key(),
        "appVersion": APP_VERSION_NAME,
        "osVersion": (os.uname().release if hasattr(os, "uname") else platform.platform()),
        "withLogs": bool(payload.get("with_logs")),
    }
    if not body["content"]:
        raise RuntimeError("请先填写建议内容")
    return device_request("POST", "/app/feedback", body)


# ---------------------------------------------------------------- 账号（B2：手机号登录 + 云端 Cookie）

DEVICE_TYPE = "pc" if os.name == "nt" or sys.platform == "darwin" else "mobile"


def account_token() -> str:
    return (load_settings().get("account_token") or "").strip()


def account_phone() -> str:
    return (load_settings().get("account_phone") or "").strip()


def account_call(method: str, path: str, payload: dict | None = None) -> dict:
    token = account_token()
    if not token:
        raise RuntimeError("还没有登录账号，请先在「设置 → 账号」里用手机号登录")
    return _http_json(method, path, payload, token=token, timeout=20)


def handle_account_sms_code(task: "Task", request: dict) -> None:
    phone = str(request.get("phone") or "").strip()
    data = _http_json("POST", "/app/auth/sms-code", {"phone": phone, "scene": "LOGIN"}, timeout=25)
    emit({"id": task.id, "event": "done", "kind": "account_sms_code", "phone": data.get("phone", phone)})


def handle_account_login(task: "Task", request: dict) -> None:
    phone = str(request.get("phone") or "").strip()
    code = str(request.get("code") or "").strip()
    data = _http_json(
        "POST",
        "/app/auth/sms-login",
        {"phone": phone, "code": code, "deviceName": machine_hostname(), "deviceType": DEVICE_TYPE},
        timeout=25,
    )
    token = (data.get("token") or "").strip()
    if not token:
        raise RuntimeError("登录失败：服务端没有返回令牌")
    save_settings({"account_token": token, "account_phone": phone})
    emit({
        "id": task.id,
        "event": "done",
        "kind": "account_login",
        "phone": data.get("phone", phone),
        "nickname": data.get("nickname", ""),
        "created": bool(data.get("created")),
    })


def handle_account_logout(task: "Task", request: dict) -> None:
    with contextlib.suppress(Exception):
        if account_token():
            _http_json("POST", "/app/auth/logout", {}, token=account_token(), timeout=15)
    save_settings({"account_token": "", "account_phone": ""})
    emit({"id": task.id, "event": "done", "kind": "account_logout"})


def handle_account_status(task: "Task", request: dict) -> None:
    payload = {"loggedIn": bool(account_token()), "phone": account_phone(), "deviceType": DEVICE_TYPE}
    if account_token():
        try:
            profile = _http_json("GET", "/app/auth/profile", token=account_token(), timeout=15)
            payload.update({
                "phone": profile.get("phone", account_phone()),
                "nickname": profile.get("nickname", ""),
                "lastLoginTime": profile.get("lastLoginTime"),
            })
        except Exception as error:  # noqa: BLE001
            payload["error"] = str(error)[:120]
    emit({"id": task.id, "event": "done", "kind": "account_status", **payload})


def handle_cookie_sync_status(task: "Task", request: dict) -> None:
    if not account_token():
        emit({"id": task.id, "event": "done", "kind": "cookie_sync_status", "loggedIn": False, "platforms": {}})
        return
    data = account_call("GET", "/app/cookie/status")
    emit({"id": task.id, "event": "done", "kind": "cookie_sync_status", "loggedIn": True,
          "deviceType": DEVICE_TYPE, "platforms": data.get("platforms") or {}})


def handle_cookie_sync_upload(task: "Task", request: dict) -> None:
    """把本机某个平台的 Cookie 上报到账号（密文存云端）"""
    platform = str(request.get("platform") or "").strip()
    cookie = cookie_string_for(platform)
    if not cookie:
        raise RuntimeError("本机还没有该平台的 Cookie，请先登录")
    data = account_call("POST", "/app/cookie/upload", {
        "platform": platform,
        "deviceType": DEVICE_TYPE,
        "cookie": cookie,
        "deviceName": machine_hostname(),
    })
    emit({"id": task.id, "event": "done", "kind": "cookie_sync_upload", "platform": platform,
          "deviceType": DEVICE_TYPE, "platforms": data.get("platforms") or {}})


def handle_cookie_sync_pull(task: "Task", request: dict) -> None:
    """把云端某一端的 Cookie 拉到本机，之后本机下载就用它（例如手机端切到电脑端会话拿 B 站 4K）"""
    platform = str(request.get("platform") or "").strip()
    device_type = str(request.get("deviceType") or ("mobile" if DEVICE_TYPE == "pc" else "pc")).strip()
    data = account_call("GET", f"/app/cookie/fetch?platform={platform}&deviceType={device_type}")
    cookie = (data.get("cookie") or "").strip()
    if not cookie:
        raise RuntimeError("云端没有这一端的 Cookie")
    update_platform_cookie(platform, cookie, f"云端·{'手机端' if device_type == 'mobile' else '电脑端'}")
    emit({
        "id": task.id,
        "event": "done",
        "kind": "cookie_sync_pull",
        "platform": platform,
        "deviceType": device_type,
        "deviceName": data.get("deviceName", ""),
        "cookies": cookie_status_payload(),
    })


def handle_cookie_sync_clear(task: "Task", request: dict) -> None:
    platform = str(request.get("platform") or "").strip()
    payload = {}
    if platform:
        payload["platform"] = platform
    if request.get("deviceType"):
        payload["deviceType"] = request["deviceType"]
    data = account_call("POST", "/app/cookie/delete", payload)
    emit({"id": task.id, "event": "done", "kind": "cookie_sync_clear", "platforms": data.get("platforms") or {}})


def handle_cookie_sync_autofill(task: "Task", request: dict) -> None:
    """登录后自动回填：本机没有 Cookie 的平台，从云端拉取对应端的登录态。

    优先使用与本机同类端的 Cookie（电脑端 ↔ pc / 手机端 ↔ mobile），
    同类端没有时用另一端兜底；本机已有 Cookie 的平台不覆盖（尊重本机登录）。
    """
    if not account_token():
        raise RuntimeError("请先登录账号")
    data = account_call("GET", "/app/cookie/status")
    platforms = data.get("platforms") or {}
    local_status = cookie_status_payload()
    same_type = DEVICE_TYPE
    other_type = "mobile" if DEVICE_TYPE == "pc" else "pc"
    applied = []
    for platform in PLATFORM_DOMAINS:
        local = local_status.get(platform) or {}
        if local.get("has_cookie"):
            continue
        slots = platforms.get(platform) or {}
        for device_type in (same_type, other_type):
            slot = slots.get(device_type) or {}
            if not slot.get("exists"):
                continue
            try:
                fetched = account_call("GET", f"/app/cookie/fetch?platform={platform}&deviceType={device_type}")
            except RuntimeError:
                continue
            cookie = (fetched.get("cookie") or "").strip()
            if not cookie:
                continue
            label = "手机端" if device_type == "mobile" else "电脑端"
            update_platform_cookie(platform, cookie, f"云端·{label}")
            applied.append({
                "platform": platform,
                "deviceType": device_type,
                "deviceName": fetched.get("deviceName", ""),
            })
            break
    latest = account_call("GET", "/app/cookie/status")
    emit({
        "id": task.id,
        "event": "done",
        "kind": "cookie_sync_autofill",
        "applied": applied,
        "platforms": latest.get("platforms") or {},
        "cookies": cookie_status_payload(),
    })


def cloud_status_payload() -> dict:
    settings = load_settings()
    payload = {
        "server_url": cloud_base_url(),
        "registered": bool((settings.get("device_token") or "").strip()),
        "device_id": settings.get("device_id") or "",
    }
    try:
        payload["config"] = cloud_config()
        payload["online"] = True
    except RuntimeError as error:
        payload["online"] = False
        payload["message"] = str(error)
    return payload


# ---------------------------------------------------------------- downloaders


class TaskCancelled(Exception):
    pass


class Task:
    def __init__(self, task_id: str):
        self.id = task_id
        self.cancelled = False
        self.process: subprocess.Popen | None = None

    def check(self) -> None:
        if self.cancelled:
            raise TaskCancelled()

    def terminate(self) -> None:
        self.cancelled = True
        if self.process and self.process.poll() is None:
            try:
                self.process.terminate()
            except OSError:
                pass


TASKS: dict[str, Task] = {}
TASKS_LOCK = threading.Lock()
BROWSER_LOCK = threading.Lock()  # 浏览器任务串行化（共享专属配置目录）


def friendly_error(platform: str, error: str) -> tuple[str, str]:
    text = error or ""
    if "暂不支持" in text:
        return ("UNSUPPORTED", text.strip()[:300])
    lowered = text.lower()
    if "腾讯元宝" in text or "视频号" in text:
        return (
            "NEED_COOKIE",
            "微信视频号解析失败：腾讯元宝的登录态可能已过期。"
            "请在「设置 → Cookie 管理 → 微信视频号」里重新登录后再试。",
        )
    if "no video in this post" in lowered or "instagram" in lowered:
        return (
            "NO_MEDIA",
            "没有取到 Instagram 内容：图片/图文帖需要在「设置 → Cookie 管理 → Instagram」登录一次；"
            "国内访问 Instagram 还需要开代理（设置 → 网络 → 代理）。",
        )
    if "no video could be found in this tweet" in lowered:
        return ("NO_MEDIA", "该推文里没有视频（纯文字或图片推文不支持下载）。")
    if "fresh cookies" in lowered:
        return (
            "NEED_COOKIE",
            "该内容需要有效的 Cookie：请在「设置 → Cookie 管理」里配置对应平台的 Cookie 后再试。",
        )
    if "sign in" in lowered or "cookies" in lowered:
        # X / YouTube 常见的「Sign in to confirm your age」这类风控，多半是登录态过期
        return (
            "NEED_COOKIE",
            "该内容需要有效的登录态：请在「设置 → Cookie 管理」里重新登录对应平台后再试"
            "（X / YouTube 需要先在应用内登录一次）。",
        )
    if "not available" in lowered or "requires authentication" in lowered or "login" in lowered:
        return (
            "NEED_COOKIE",
            "该内容需要登录：请在浏览器登录后点击设置里的「刷新 Cookie」，再重试。",
        )
    if "unsupported url" in lowered and ("youtube" in lowered or "twitter" in lowered):
        return ("UNSUPPORTED", "链接格式不被支持，请确认是作品详情链接。")
    if "cookie" in lowered or "s_v_web_id" in lowered or "not necessarily logged in" in lowered:
        return (
            "NEED_COOKIE",
            "该作品需要浏览器 Cookie 才能解析。请在设置中点击「刷新 Cookie」，"
            "并确认浏览器登录过对应平台。",
        )
    if "argus" in lowered or "blocked" in lowered or "verify" in lowered or "风控" in text:
        return (
            "BLOCKED",
            "被平台风控拦截。请稍后重试，或在浏览器中打开一次该平台页面后刷新 Cookie。",
        )
    if "unsupported url" in lowered or "no video" in lowered:
        return ("UNSUPPORTED", "该链接暂不支持下载，请确认是作品详情链接。")
    if any(key in lowered for key in ("timed out", "timeout", "connection", "network", "ssl")):
        return ("NETWORK", "网络请求失败，请检查网络连接后重试。")
    return ("ENGINE", f"下载失败：{text.strip()[:300] or '未知错误'}")


AUTH_ERROR_HINTS = (
    "login", "sign in", "cookie", "authentication",
    "not authorized", "unauthorized", "401", "403",
)


def mark_cookie_invalid_on_error(request: dict, code: str, error_text: str) -> None:
    """下载/解析因为登录态被平台拒绝时，把该平台标成「登录已失效」，设置页会提示重新登录。"""
    platform = (request.get("platform") or "").strip()
    if not platform:
        url = extract_url(request.get("url") or "") or (request.get("url") or "").strip()
        platform = detect_platform(url) or ""
    # 抖音 / 快手 / 小红书下载本来就不依赖 Cookie，别给它们标「登录已失效」误导用户
    if platform not in ("x", "youtube", "bilibili", "weibo", "tiktok", "instagram", "wechat_channels"):
        return
    text = error_text or ""
    lowered = text.lower()
    if code == "NEED_COOKIE" or "登录" in text or any(hint in lowered for hint in AUTH_ERROR_HINTS):
        mark_cookie_invalid(platform, f"任务失败（{code or 'ENGINE'}）")


def move_results(files: list[str], author: str, title: str, fallback: str, out_dir: Path, settings: dict) -> list[str]:
    out_dir.mkdir(parents=True, exist_ok=True)
    base = build_base_name(settings.get("name_template", "{author}-{title}"), author, title, fallback)
    moved: list[str] = []
    if len(files) == 1:
        source = Path(files[0])
        target = unique_path(out_dir, base, source.suffix)
        shutil.move(str(source), str(target))
        moved.append(str(target))
    else:
        folder = unique_dir(out_dir, base)
        folder.mkdir(parents=True, exist_ok=True)
        for index, item in enumerate(files, start=1):
            source = Path(item)
            target = unique_path(folder, f"{base}-{index:02d}", source.suffix)
            shutil.move(str(source), str(target))
            moved.append(str(target))
    return moved


class CookieRequired(Exception):
    """需要更新浏览器 Cookie 才能继续。"""


# 多连接分段下载的参数：小于 4MB 的文件分段反而更慢；单文件最多 8 段（与安卓端一致）
SEGMENT_MIN_BYTES = 4 * 1024 * 1024
SEGMENT_MAX_THREADS = 8
SEGMENT_CHUNK_BYTES = 2 * 1024 * 1024


def download_segmented(
    task: "Task",
    url: str,
    part: Path,
    headers: dict,
    cookies: dict,
    max_threads: int = SEGMENT_MAX_THREADS,
) -> bool:
    """
    多连接分段下载（类 IDM）：先探测服务端是否支持 Range，够大就并发拉多段再拼成一个文件。
    返回 True 表示下载成功；返回 False 表示"这个地址不适合分段"，由调用方走单连接逻辑。
    """
    probe = None
    try:
        probe = http_session().get(
            url,
            headers={**headers, "Range": "bytes=0-0"},
            cookies=cookies,
            stream=True,
            timeout=30,
        )
        if probe.status_code != 206:
            return False  # 不支持 Range：交给单连接
        match = re.match(r"bytes 0-0/(\d+)", probe.headers.get("content-range") or "")
        if not match:
            return False
        total = int(match.group(1))
    except TaskCancelled:
        raise
    except Exception as error:  # noqa: BLE001
        log(f"分段下载探测失败，改用单连接：{error!r}", task.id)
        return False
    finally:
        if probe is not None:
            with contextlib.suppress(Exception):
                probe.close()

    if total < SEGMENT_MIN_BYTES:
        return False
    threads = max(1, min(max_threads, total // SEGMENT_CHUNK_BYTES))
    if threads < 2:
        return False

    part.parent.mkdir(parents=True, exist_ok=True)
    with part.open("wb") as handle:
        handle.truncate(total)

    log(f"启用 {threads} 线程分段下载，总大小 {total} 字节", task.id, echo=True)
    emit({"id": task.id, "event": "stage", "stage": f"多线程下载（{threads} 段）"})

    span = total // threads
    ranges: list[tuple[int, int, int]] = []
    for index in range(threads):
        start = index * span
        end = total - 1 if index == threads - 1 else start + span - 1
        ranges.append((index, start, end))

    state = {"done": 0}
    state_lock = threading.Lock()
    last_emit = {"at": 0.0}
    failures: list[Exception] = []

    def report(delta: int) -> None:
        with state_lock:
            state["done"] += delta
            done = state["done"]
            now = time.time()
            if now - last_emit["at"] < 0.3:
                return
            last_emit["at"] = now
        emit({
            "id": task.id,
            "event": "progress",
            "percent": round(done / total * 100, 1) if total else None,
            "downloaded": done,
            "total": total,
        })

    def worker(start: int, end: int) -> None:
        attempt = 0
        while True:
            attempt += 1
            session = http_session()  # 每段独立会话，互不干扰
            response = None
            try:
                response = session.get(
                    url,
                    headers={**headers, "Range": f"bytes={start}-{end}"},
                    cookies=cookies,
                    stream=True,
                    timeout=60,
                )
                if response.status_code not in (200, 206):
                    raise RuntimeError(f"HTTP {response.status_code}")
                position = start
                with part.open("r+b") as handle:
                    handle.seek(start)
                    for block in response.iter_content(chunk_size=SEGMENT_CHUNK_BYTES):
                        task.check()
                        if not block:
                            continue
                        handle.write(block)
                        position += len(block)
                        report(len(block))
                if position < end + 1:
                    raise RuntimeError("连接提前结束")
                return
            except TaskCancelled:
                raise
            except Exception as error:  # noqa: BLE001
                if attempt >= 3:
                    failures.append(error)
                    return
                time.sleep(0.6 * attempt)
            finally:
                if response is not None:
                    with contextlib.suppress(Exception):
                        response.close()

    workers = [threading.Thread(target=worker, args=(start, end), daemon=True) for _, start, end in ranges]
    for item in workers:
        item.start()
    for item in workers:
        while item.is_alive():
            item.join(timeout=0.5)
            task.check()  # 取消时立刻抛 TaskCancelled，不等所有线程跑完

    if failures:
        log(f"分段下载失败，回退单连接：{failures[0]!r}", task.id)
        with contextlib.suppress(Exception):
            part.unlink()
        return False

    emit({
        "id": task.id,
        "event": "progress",
        "percent": 100.0,
        "downloaded": total,
        "total": total,
    })
    return True


def download_media_urls(
    task: "Task",
    urls: list[str],
    destination: Path,
    headers: dict | None = None,
    cookies: dict | None = None,
) -> None:
    session = http_session()
    headers = headers or {"User-Agent": UA_CHROME, "Referer": "https://www.douyin.com/"}
    cookies = cookies if cookies is not None else douyin_cookie_dict()
    last_error: Exception | None = None
    part = destination.with_suffix(destination.suffix + ".part")
    for index, url in enumerate(urls):
        if index:
            time.sleep(0.8)  # 给 CDN 一点缓冲，避免连续请求被重置
        try:
            # 先试多连接分段下载（类 IDM）。返回 False 表示这个地址不支持/不适合分段，
            # 走下面的单连接逻辑；下载中途出错也回退单连接重来一遍
            if download_segmented(task, url, part, headers, cookies):
                part.replace(destination)
                return
            stream = session.get(
                url,
                headers=headers,
                cookies=cookies,
                stream=True,
                timeout=60,
            )
            try:
                stream.raise_for_status()
                total = int(stream.headers.get("content-length") or 0)
                downloaded = 0
                last_time = 0.0
                with part.open("wb") as handle:
                    for chunk in stream.iter_content(chunk_size=256 * 1024):
                        task.check()
                        if not chunk:
                            continue
                        handle.write(chunk)
                        downloaded += len(chunk)
                        now = time.time()
                        if now - last_time >= 0.3:
                            last_time = now
                            emit(
                                {
                                    "id": task.id,
                                    "event": "progress",
                                    "percent": round(downloaded / total * 100, 1) if total else None,
                                    "downloaded": downloaded,
                                    "total": total,
                                }
                            )
            finally:
                stream.close()
            part.replace(destination)
            return
        except TaskCancelled:
            raise
        except Exception as error:  # noqa: BLE001
            last_error = error
            log(f"媒体地址下载失败（换下一个地址重试）: {error!r}", task.id)
    raise RuntimeError(f"视频文件下载失败：{last_error!r}")


ANSI_PATTERN = re.compile(r"\x1b\[[0-9;]*m")


def move_single_file(source: str, out_dir: Path, fallback_name: str) -> str:
    """按原文件名落盘（重名自动追加序号）。"""
    src = Path(source)
    out_dir.mkdir(parents=True, exist_ok=True)
    base = sanitize_name(src.stem or fallback_name, fallback_name)
    target = unique_path(out_dir, base, src.suffix or "")
    shutil.move(str(src), str(target))
    return str(target)


FILE_EXTENSIONS = {
    "pdf", "zip", "rar", "7z", "tar", "gz", "exe", "dmg", "apk", "deb", "rpm",
    "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus",
    "mp4", "mov", "mkv", "webm", "avi", "flv", "ts", "m4v", "3gp",
    "jpg", "jpeg", "png", "gif", "webp", "heic", "avif", "bmp", "svg",
    "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv", "json", "torrent",
}
PAGE_EXTENSIONS = {"htm", "html", "php", "asp", "aspx", "jsp", "shtml", "do"}


def should_try_generic_extractor(url: str) -> bool:
    """未识别平台的链接要不要先交给 yt-dlp 试一次。

    - 明确是媒体/文件直链（.mp4 / .jpg / .zip…）→ 直接下载，不绕 yt-dlp
    - 网页、短链、没有后缀的地址 → 先让 yt-dlp 试（覆盖它支持的上千个站点），
      失败再回退到「网页资源嗅探」，与原来的行为衔接
    """
    from urllib.parse import urlparse

    try:
        path = urlparse(url).path.lower()
    except Exception:  # noqa: BLE001
        return False
    if path.endswith("/"):
        return True
    ext = path.rsplit(".", 1)[-1] if "." in path.rsplit("/", 1)[-1] else ""
    if ext and (ext in FILE_EXTENSIONS or ext in PAGE_EXTENSIONS):
        return ext not in FILE_EXTENSIONS
    return True


def probe_remote(url: str) -> tuple[str, str, bool, int]:
    """探测远端内容类型与文件名，返回 (content_type, filename, is_html, status)。"""
    session = http_session()
    headers = {"User-Agent": UA_CHROME, "Accept": "*/*", "Accept-Language": "zh-CN,zh;q=0.9"}
    response = None
    try:
        response = session.head(url, headers=headers, timeout=30)
        if response.status_code >= 400:
            response = None
    except Exception:  # noqa: BLE001
        response = None
    if response is None:
        response = session.get(url, headers=headers, stream=True, timeout=30)
    ctype = (response.headers.get("content-type") or "").split(";")[0].strip()
    disposition = response.headers.get("content-disposition") or ""
    name = ""
    match = re.search(r"filename\*?=(?:UTF-8'')?\"?([^\";]+)\"?", disposition, re.IGNORECASE)
    if match:
        from urllib.parse import unquote

        name = unquote(match.group(1))
    if not name:
        from urllib.parse import unquote, urlparse

        name = unquote(Path(urlparse(str(response.url)).path).name)
    status = getattr(response, "status_code", 0)
    try:
        response.close()
    except Exception:  # noqa: BLE001
        pass
    return ctype, name, "text/html" in ctype, status


def remote_size(url: str) -> int:
    """探测远端文件大小（HEAD 不行就用 Range: bytes=0-0 看 Content-Range）。"""
    headers = {"User-Agent": UA_CHROME, "Referer": "https://mp.weixin.qq.com/"}
    session = http_session()
    try:
        response = session.head(url, headers=headers, timeout=15)
        size = int(response.headers.get("content-length") or 0)
        if size > 0:
            return size
    except Exception:  # noqa: BLE001
        pass
    try:
        response = session.get(url, headers={**headers, "Range": "bytes=0-0"}, timeout=15)
        content_range = response.headers.get("content-range") or ""
        if "/" in content_range:
            return int(content_range.rsplit("/", 1)[-1] or 0)
        return int(response.headers.get("content-length") or 0)
    except Exception:  # noqa: BLE001
        return 0


def pick_best_quality_videos(urls: list[str]) -> list[str]:
    """公众号视频同一段会有多个清晰度（...f10002.mp4 / ...f10104.mp4），按体积挑最大的一个。"""
    groups: dict[str, list[str]] = {}
    plain: list[str] = []
    for url in urls:
        match = re.match(r"(https://mpvideo\.qpic\.cn/[^?#]+?)\.f\d+\.mp4", url)
        if match:
            groups.setdefault(match.group(1), []).append(url)
        else:
            plain.append(url)
    picked: list[str] = list(plain)
    for candidates in groups.values():
        if len(candidates) == 1:
            picked.append(candidates[0])
            continue
        best, best_size = candidates[0], -1
        for candidate in candidates:
            size = remote_size(candidate)
            if size > best_size:
                best, best_size = candidate, size
        picked.append(best)
    return picked


def download_page_resources(task: "Task", url: str, settings: dict) -> dict:
    """普通网页：用真实浏览器嗅探页面里的图片 / 视频资源并全部下载。"""
    from playwright.sync_api import sync_playwright

    emit({"id": task.id, "event": "stage", "stage": "嗅探页面资源", "platform": "web"})
    script = """
        (() => {
          const urls = [];
          const add = (u) => {
            try {
              if (!u) return;
              u = String(u);
              if (u.startsWith('data:')) return;
              if (!u.startsWith('http')) u = new URL(u, location.href).href;
              if (!u.startsWith('http')) return;
              if (!urls.includes(u)) urls.push(u);
            } catch (e) {}
          };
          document.querySelectorAll('img').forEach(el => add(el.currentSrc || el.src));
          document.querySelectorAll('video').forEach(el => { add(el.currentSrc || el.src); add(el.poster); });
          document.querySelectorAll('source').forEach(el => add(el.src));
          document.querySelectorAll('meta[property="og:image"],meta[property="og:video"],meta[name="twitter:image"]')
            .forEach(el => add(el.content));
          // 微信公众号文章里的「视频号」组件：data-url 就是视频文件地址
          document.querySelectorAll('mp-common-videosnap[data-url],[data-pluginname="mpvideosnap"][data-url]')
            .forEach(el => add(el.getAttribute('data-url')));
          document.querySelectorAll('iframe.video_iframe[data-src],.js_video_iframe[data-src]')
            .forEach(el => add(el.getAttribute('data-src')));
          document.querySelectorAll('a[href]').forEach(el => {
            const h = el.getAttribute('href') || '';
            if (/\\.(jpg|jpeg|png|webp|gif|avif|mp4|mov|webm)(\\?|$)/i.test(h)) add(h);
          });
          // 同源 iframe 里也要看一眼（微信公众号文章的视频就在内嵌 iframe 里）
          const frames = [];
          document.querySelectorAll('iframe').forEach(el => {
            const src = el.getAttribute('data-src') || el.getAttribute('src') || '';
            if (src) frames.push(src);
          });
          for (const frame of Array.from(document.querySelectorAll('iframe'))) {
            try {
              const doc = frame.contentDocument;
              if (!doc) continue;
              doc.querySelectorAll('video').forEach(el => { add(el.currentSrc || el.src); add(el.poster); });
              doc.querySelectorAll('source').forEach(el => add(el.src));
              doc.querySelectorAll('img').forEach(el => add(el.currentSrc || el.src));
            } catch (e) {}
          }
          return { title: document.title || '', urls: urls.slice(0, 120), frames: frames.slice(0, 20) };
        })()
    """
    payload = None
    with BROWSER_LOCK:
        with sync_playwright() as pw:
            context, channel, label = launch_persistent_browser(pw, settings, headless=True)
            try:
                page = context.pages[0] if context.pages else context.new_page()
                page.goto(url, wait_until="domcontentloaded", timeout=60000)
                # 公众号这类文章的内容是 JS 注入的（含「视频号」组件），多等一会儿再嗅探
                page.wait_for_timeout(5000)
                # 文章里嵌的「视频号」卡片会自己去请求 feed 详情，把它拦下来就拿到真实地址
                captured_videos: list[str] = []

                def capture_feed(response) -> None:
                    if "get_feed_info" not in response.url and "get_object_detail" not in response.url:
                        return
                    try:
                        data = response.json()
                    except Exception:  # noqa: BLE001
                        return
                    feed = ((data.get("data") or {}).get("feedInfo")) or {}
                    for key in ("h264VideoInfo", "h265VideoInfo"):
                        candidate = ((feed.get(key) or {}).get("videoUrl") or "").strip()
                        if candidate and candidate not in captured_videos:
                            captured_videos.append(candidate)
                    plain = (feed.get("videoUrl") or "").strip()
                    if plain and plain not in captured_videos:
                        captured_videos.append(plain)

                page.on("response", capture_feed)
                # 视频号组件是异步渲染的：等它出现（等不到也不影响图片嗅探）
                with contextlib.suppress(Exception):
                    page.wait_for_selector(
                        "mp-common-videosnap[data-url],[data-pluginname='mpvideosnap'][data-url]",
                        timeout=8000,
                    )
                payload = page.evaluate(script)
                # 微信公众号把文章里的视频放在 window.__videoPageInfos（含真实 mp4 地址）
                try:
                    payload["video_infos"] = page.evaluate(
                        "(() => { try { return JSON.stringify(window.__videoPageInfos || []); } catch (e) { return '[]'; } })()"
                    )
                except Exception:  # noqa: BLE001
                    payload["video_infos"] = "[]"
                # 再兜一层：直接从页面 HTML 里捞视频号（findermp）与腾讯视频（mpvideo）地址，
                # 视频号组件是异步渲染的，DOM 抓不到时这一步还能拿到
                try:
                    html = page.content()
                    found = re.findall(
                        r"https://(?:findermp\.video\.qq\.com|mpvideo\.qpic\.cn)/[^\"'\\\s<>]{20,}",
                        html,
                    )
                    payload["inline_media"] = [
                        item.replace("\\x26amp;", "&").replace("&amp;", "&")
                        .replace("\\u0026", "&").replace("\\x26", "&")
                        for item in found[:20]
                    ]
                except Exception:  # noqa: BLE001
                    payload["inline_media"] = []
                payload["captured_videos"] = list(captured_videos)
            finally:
                try:
                    context.close()
                except Exception:  # noqa: BLE001
                    pass
    urls = list(dict.fromkeys((payload or {}).get("urls") or []))
    title = ((payload or {}).get("title") or "页面资源").strip()

    # 页面 HTML 里直接出现的视频号 / 腾讯视频地址（异步组件抓不到时的兜底）
    inline_media = pick_best_quality_videos((payload or {}).get("inline_media") or [])
    if inline_media:
        log(f"页内视频候选 {len(inline_media)} 个（已按体积挑最高清晰度）", task.id)
    for media_url in inline_media:
        if media_url not in urls:
            urls.insert(0, media_url)

    # 文章里嵌的「视频号」卡片：截获它自己的 feed 详情请求（比猜 data-url 靠谱）
    for media_url in (payload or {}).get("captured_videos") or []:
        if media_url not in urls:
            urls.insert(0, media_url)
            log(f"截获到公众号内嵌视频号地址：{media_url[:90]}", task.id, echo=True)

    # 公众号文章视频：window.__videoPageInfos[].mp_video_trans_info[] 里是各清晰度的 mp4
    for video_url in wechat_video_page_infos((payload or {}).get("video_infos") or "", task):
        if video_url not in urls:
            urls.insert(0, video_url)

    # 微信公众号 / 腾讯视频：iframe 指向 videoplayer 接口，真正的 mp4 地址要再调一次
    extra = wechat_article_videos(url, (payload or {}).get("frames") or [], task)
    for item in extra:
        if item not in urls:
            urls.insert(0, item)

    if not urls:
        raise RuntimeError("页面里没有找到可下载的图片或视频")

    out_dir = Path(settings.get("out_dir") or DEFAULT_SETTINGS["out_dir"])
    staging = TMP_DIR / task.id / "web"
    staging.mkdir(parents=True, exist_ok=True)
    files = []
    for index, media_url in enumerate(urls[:60], start=1):
        suffix = Path(media_url.split("?")[0]).suffix or ".jpg"
        target = staging / f"resource-{index:03d}{suffix}"
        try:
            # 嗅探会顺带捞到页面里的 HTML/JSON 链接（比如文章里的子页面），先探一次类型，
            # 非媒体直接跳过，避免把网页存成 .jpg
            content_type, _, is_html, status = probe_remote(media_url)
            if is_html or (content_type and not content_type.startswith(("image/", "video/", "audio/", "application/octet-stream"))):
                log(f"跳过非媒体资源 {media_url[:80]}（{content_type or 'HTML'}）", task.id)
                continue
            emit({"id": task.id, "event": "stage", "stage": f"下载资源 {index}/{len(urls)}"})
            download_media_urls(
                task,
                [media_url],
                target,
                headers={"User-Agent": UA_CHROME, "Referer": url},
                cookies={},
            )
            files.append(str(target))
        except Exception as error:  # noqa: BLE001
            log(f"资源下载失败 {media_url[:80]}: {error!r}", task.id)
    if not files:
        raise RuntimeError("页面资源全部下载失败")
    moved = move_results(files, "", title, "页面资源", out_dir, settings)
    return {"platform": "web", "title": title, "author": "", "files": moved, "source": "page-sniff"}


def download_direct_url(task: "Task", url: str, settings: dict) -> dict:
    """任意格式直链：探测类型后下载（网页则转为资源嗅探）。"""
    emit({"id": task.id, "event": "stage", "stage": "探测文件", "platform": "file"})
    ctype, name, is_html, status = probe_remote(url)
    from urllib.parse import urlparse

    ext = Path(urlparse(url).path).suffix.lower().lstrip(".")
    if ext in FILE_EXTENSIONS:
        is_html = False        # 明确的文件扩展名优先，避免被 Cloudflare 拦截页误导
    elif ext in PAGE_EXTENSIONS:
        is_html = True
    if is_html:
        if status >= 400:
            raise RuntimeError(f"目标站点返回 HTTP {status}（可能有反爬保护），无法直接下载")
        return download_page_resources(task, url, settings)
    if not name:
        name = f"download-{int(time.time())}"
    log(f"直链探测: type={ctype or '未知'} name={name}", task.id, echo=True)

    out_dir = Path(settings.get("out_dir") or DEFAULT_SETTINGS["out_dir"])
    staging = TMP_DIR / task.id
    staging.mkdir(parents=True, exist_ok=True)
    target = staging / sanitize_name(name, "download")
    emit({"id": task.id, "event": "stage", "stage": "下载中", "platform": "file"})
    download_media_urls(
        task,
        [url],
        target,
        headers={"User-Agent": UA_CHROME, "Accept": "*/*"},
        cookies={},
    )
    final = move_single_file(str(target), out_dir, "download")
    return {
        "platform": "file",
        "title": Path(final).stem,
        "author": "",
        "files": [final],
        "source": "direct",
    }


def download_with_ytdlp(task: "Task", url: str, settings: dict, platform: str) -> dict:
    """X / YouTube 等平台统一走 yt-dlp（自动合并音视频，需系统 ffmpeg）。"""
    import yt_dlp

    out_dir = Path(settings.get("out_dir") or DEFAULT_SETTINGS["out_dir"])
    tmp_dir = TMP_DIR / task.id
    tmp_dir.mkdir(parents=True, exist_ok=True)
    emit({"id": task.id, "event": "stage", "stage": "解析中", "platform": platform})

    state = {"time": 0.0}

    def hook(payload: dict) -> None:
        task.check()
        if payload.get("status") != "downloading":
            return
        now = time.time()
        if now - state["time"] < 0.3:
            return
        state["time"] = now
        total = payload.get("total_bytes") or payload.get("total_bytes_estimate") or 0
        downloaded = payload.get("downloaded_bytes") or 0
        emit(
            {
                "id": task.id,
                "event": "progress",
                "percent": round(downloaded / total * 100, 1) if total else None,
                "downloaded": downloaded,
                "total": total,
                "speed": payload.get("speed"),
            }
        )

    class YdlLogger:
        def debug(self, message):
            return

        def info(self, message):
            log(message, task.id)

        def warning(self, message):
            log(f"[警告] {message}", task.id)

        def error(self, message):
            log(f"[错误] {message}", task.id)

    options = {
        "outtmpl": str(tmp_dir / "%(title).120B [%(id)s].%(ext)s"),
        "quiet": True,
        "no_warnings": True,
        "noprogress": True,
        "overwrites": False,
        "continuedl": True,
        "retries": 5,
        "fragment_retries": 5,
        "socket_timeout": 30,
        "format": ytdlp_format_for(settings),
        "merge_output_format": "mp4",
        "noplaylist": True,
        "progress_hooks": [hook],
        "logger": YdlLogger(),
    }
    if bundled_ffmpeg_dir():
        options["ffmpeg_location"] = str(bundled_ffmpeg_dir())
    # 复用系统已装的 Node 作为 JS 运行时（YouTube 新版签名需要，避免额外安装 deno）
    node_path = shutil.which("node")
    if node_path:
        options["js_runtimes"] = {"node": {"path": node_path}}
    proxy = (settings.get("proxy") or active_proxy()).strip()
    if proxy:
        options["proxy"] = proxy
    if COOKIE_FILE.exists():
        options["cookiefile"] = str(COOKIE_FILE)

    with yt_dlp.YoutubeDL(options) as ydl:
        info = ydl.extract_info(url, download=True)

    filepath = None
    for item in info.get("requested_downloads") or []:
        if item.get("filepath"):
            filepath = Path(item["filepath"])
            break
    if filepath is None or not filepath.exists():
        candidates = [p for p in tmp_dir.glob("**/*") if p.is_file() and p.suffix != ".part"]
        filepath = max(candidates, key=lambda p: p.stat().st_mtime_ns) if candidates else None
    if not filepath or not filepath.exists():
        raise RuntimeError("yt-dlp 未产出文件")

    # 音乐平台未登录时常常只给「试听片段」：拿元数据时长和实际文件时长比一比，提醒用户去配置 Cookie
    if platform in ("netease", "qqmusic"):
        expected = float(info.get("duration") or 0)
        actual = float(probe_media_info(str(filepath)).get("duration") or 0)
        if expected and actual and actual < expected * 0.8:
            label = PLATFORM_DOMAINS.get(platform, {}).get("label", platform)
            log(
                f"{label} 这次只拿到试听片段（{actual:.0f}s / 全长 {expected:.0f}s）："
                f"在「设置 → Cookie 管理 → {label}」配一份浏览器 Cookie 才能下完整歌曲",
                task.id,
                echo=True,
            )

    author = info.get("uploader") or info.get("channel") or info.get("creator") or ""
    title = info.get("title") or ""
    # 标题里常自带「作者 - 」前缀，去掉避免文件名重复
    if author and title.startswith(author):
        title = title[len(author):].lstrip(" -—:|：")
    moved = move_results([str(filepath)], author, title, f"{platform}-{info.get('id', '')}", out_dir, settings)
    return {
        "platform": platform,
        "title": title,
        "author": author,
        "files": moved,
        "source": "yt-dlp",
    }


def ytdlp_format_for(settings: dict) -> str:
    """按「最大高度」偏好给出 yt-dlp 的 format 表达式（0 = 最高画质）。"""
    try:
        max_height = int(settings.get("max_height") or 0)
    except (TypeError, ValueError):
        max_height = 0
    if max_height <= 0:
        return "bv*+ba/b"
    return (
        f"bv*[height<={max_height}]+ba/b[height<={max_height}]"
        f"/bv*[height<={max_height}]+ba/b"
    )


def append_history(record: dict) -> None:
    records = []
    if HISTORY_FILE.exists():
        try:
            records = json.loads(HISTORY_FILE.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            records = []
    records.insert(0, record)
    HISTORY_FILE.write_text(json.dumps(records[:200], ensure_ascii=False, indent=2), encoding="utf-8")


def probe_media_info(path: str) -> dict:
    """用 ffprobe 读取分辨率/时长（best-effort，缺失则返回空）。"""
    if not path or not Path(path).exists():
        return {}
    try:
        result = subprocess.run(
            [
                "ffprobe", "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "stream=width,height",
                "-show_entries", "format=duration,size",
                "-of", "json",
                path,
            ],
            capture_output=True,
            text=True,
            timeout=15,
        )
        payload = json.loads(result.stdout or "{}")
        stream = (payload.get("streams") or [{}])[0]
        fmt = payload.get("format") or {}
        info = {}
        if stream.get("width") and stream.get("height"):
            info["width"] = int(stream["width"])
            info["height"] = int(stream["height"])
        if fmt.get("duration"):
            info["duration"] = round(float(fmt["duration"]), 1)
        if fmt.get("size"):
            info["size"] = int(fmt["size"])
        return info
    except (OSError, ValueError, subprocess.SubprocessError, IndexError):
        return {}


def handle_download(task: "Task", request: dict) -> None:
    settings = load_settings()
    if request.get("out_dir"):
        settings["out_dir"] = request["out_dir"]
    if request.get("name_template"):
        settings["name_template"] = request["name_template"]
    if request.get("browser_engine"):
        settings["browser_engine"] = request["browser_engine"]
    if request.get("proxy") is not None:
        settings["proxy"] = request["proxy"]
    # 请求级的覆盖也要同步成「当前设置」，否则 http_session() 还在用旧的（比如没代理）
    activate_settings(settings)

    raw = request.get("url") or ""
    url = extract_url(raw) or raw.strip()
    if not url:
        emit({"id": task.id, "event": "error", "code": "INVALID_URL", "message": "没有识别到链接，请检查粘贴内容。"})
        return

    platform = detect_platform(url)
    if not platform:
        session = http_session()
        final_url = resolve_url(url, session)
        platform = detect_platform(final_url)
    if not platform:
        final_url = url
    if not platform:
        # 通用兜底：不是明确的媒体直链时，先让 yt-dlp 试一次（一次覆盖上千个站点），
        # 它认不出来再回退到「直链 / 网页嗅探」，原行为不变
        if should_try_generic_extractor(url):
            emit({"id": task.id, "event": "meta", "platform": "generic", "url": url, "label": "通用解析"})
            try:
                result = download_with_ytdlp(task, url, settings, "generic")
            except TaskCancelled:
                raise
            except Exception as error:  # noqa: BLE001
                log(f"yt-dlp 通用解析没成功，回退网页嗅探: {error!r}", task.id)
            else:
                files = result.get("files") or []
                media_info = probe_media_info(files[0]) if files else {}
                record = {
                    "task_id": task.id,
                    "url": raw,
                    "resolved_url": url,
                    "platform": result.get("platform", "generic"),
                    "title": result.get("title", ""),
                    "author": result.get("author", ""),
                    "files": files,
                    "source": result.get("source", ""),
                    "media_info": media_info,
                    "finished_at": time.strftime("%Y-%m-%d %H:%M:%S"),
                }
                append_history(record)
                emit({"id": task.id, "event": "done", **record, "file": files[0] if files else None})
                return
        # 普通链接：任意格式直链直接下载；网页则嗅探其中的图片/视频资源
        emit({"id": task.id, "event": "meta", "platform": "web", "url": url, "label": "直链 / 网页"})
        try:
            result = download_direct_url(task, url, settings)
        except TaskCancelled:
            raise
        except Exception as error:  # noqa: BLE001
            log(f"直链/网页解析失败: {error!r}", task.id)
            code, message = friendly_error("", str(error))
            emit({"id": task.id, "event": "error", "code": code, "message": message, "detail": str(error)[:400]})
            return
        files = result.get("files") or []
        media_info = probe_media_info(files[0]) if files else {}
        record = {
            "task_id": task.id,
            "url": raw,
            "resolved_url": url,
            "platform": result.get("platform", "file"),
            "title": result.get("title", ""),
            "author": "",
            "files": files,
            "source": result.get("source", ""),
            "media_info": media_info,
            "finished_at": time.strftime("%Y-%m-%d %H:%M:%S"),
        }
        append_history(record)
        emit({"id": task.id, "event": "done", **record, "file": files[0] if files else None})
        return

    task.check()
    resolved_url = url
    if platform == "douyin":
        session = http_session()
        final_url = resolve_url(url, session)
        if is_blocked_redirect(final_url):
            log(f"抖音短链跳到登录/验证页，改回原始链接：{final_url[:80]}", task.id)
            final_url = url
        canonical = normalize_url(final_url, platform)
        resolved_url = canonical
        emit({"id": task.id, "event": "meta", "platform": platform, "url": canonical, "label": "抖音"})
        result = download_douyin(task, canonical, settings, raw_url=url)
    elif platform == "xiaohongshu":
        # 小红书链接（含 xsec_token）直接交给引擎，避免跟随跳转落到登录页
        emit({"id": task.id, "event": "meta", "platform": platform, "url": url, "label": "小红书"})
        result = download_xhs(task, url, settings, raw)
    elif platform == "wechat_channels":
        # 视频号：分享页拿不到视频地址，需要先用腾讯元宝换令牌（见 resolve_wxchannels）
        result = download_wxchannels(task, url, settings, raw)
    elif platform == "instagram":
        # Instagram：图片 / 视频 / 轮播统一走它自己的 web 接口（yt-dlp 处理不了图片帖）
        try:
            result = download_instagram(task, url, settings, raw)
        except TaskCancelled:
            raise
        except Exception as error:  # noqa: BLE001
            log(f"Instagram 接口解析失败，回退 yt-dlp：{error!r}", task.id)
            emit({"id": task.id, "event": "meta", "platform": platform, "url": url, "label": "Instagram"})
            if not cookie_string_for(platform):
                raise RuntimeError(
                    "Instagram 需要登录：请在「设置 → Cookie 管理 → Instagram」登录一次（视频帖也能顺带下载）"
                ) from error
            result = download_with_ytdlp(task, url, settings, platform)
    elif platform in ("x", "youtube", "bilibili", "weibo", "tiktok", "ixigua", "reddit", "vimeo", "twitch",
                      "netease", "qqmusic"):
        # 这几个平台 yt-dlp 原生支持：自动合并音视频（依赖系统 ffmpeg）
        label = PLATFORM_DOMAINS[platform]["label"]
        if PLATFORM_DOMAINS[platform].get("needs_proxy") and not active_proxy():
            log(f"{label} 国内直连通常失败：建议在「设置 → 代理」填本地代理地址（如 http://127.0.0.1:7897）", task.id, echo=True)
        emit(
            {
                "id": task.id,
                "event": "meta",
                "platform": platform,
                "url": url,
                "label": label,
            }
        )
        # X / YouTube 必须在本机登录过；B 站 / 微博 / TikTok / Instagram 可选 Cookie
        if platform in ("x", "youtube") and not cookie_string_for(platform):
            emit(
                {
                    "id": task.id,
                    "event": "error",
                    "code": "NEED_COOKIE",
                    "message": f"{label} 需要登录凭证："
                               "请在「设置 → Cookie 管理」里点「一键获取」登录一次，或用「从系统浏览器导入」。",
                }
            )
            return
        if platform in ("bilibili", "weibo", "tiktok") and not cookie_string_for(platform):
            log(f"{label} 未配置 Cookie：清晰度可能受限，需要登录的内容可能失败", task.id, echo=True)
        if platform == "youtube" and "youtu.be" in url:
            # youtu.be 短链交给 yt-dlp 自行处理，这里仅记录
            log(f"YouTube 短链：{url}", task.id)
        result = download_with_ytdlp(task, url, settings, platform)
    elif platform in ("weishi", "haokan"):
        # yt-dlp 还没有这两个平台的解析器：先用通用解析，认不出来再回退「网页资源嗅探」
        label = PLATFORM_DOMAINS[platform]["label"]
        emit({"id": task.id, "event": "meta", "platform": platform, "url": url, "label": label})
        try:
            result = download_with_ytdlp(task, url, settings, platform)
        except TaskCancelled:
            raise
        except Exception as error:  # noqa: BLE001
            log(f"{label} 通用解析没成功，回退网页嗅探: {error!r}", task.id)
            result = download_direct_url(task, url, settings)
    else:
        # 快手短链由引擎自行解析
        emit({"id": task.id, "event": "meta", "platform": platform, "url": url, "label": "快手"})
        result = download_ks(task, url, settings)

    # 下载成功说明登录态是可用的，清掉之前可能误标的「登录已失效」
    clear_cookie_invalid(platform)
    files = result.get("files") or []
    media_info = probe_media_info(files[0]) if files else {}
    record = {
        "task_id": task.id,
        "url": raw,
        "resolved_url": resolved_url,
        "platform": result.get("platform"),
        "title": result.get("title", ""),
        "author": result.get("author", ""),
        "files": files,
        "source": result.get("source", ""),
        "media_info": media_info,
        "finished_at": time.strftime("%Y-%m-%d %H:%M:%S"),
    }
    append_history(record)
    emit({"id": task.id, "event": "done", **record, "file": files[0] if files else None})


def handle_probe(task: "Task", request: dict) -> None:
    raw = request.get("url") or ""
    url = extract_url(raw) or raw.strip()
    if not url:
        emit({"id": task.id, "event": "error", "code": "INVALID_URL", "message": "没有识别到链接。"})
        return
    final_url = resolve_url(url)
    platform = detect_platform(final_url) or detect_platform(url)
    if not platform:
        emit({"id": task.id, "event": "error", "code": "UNSUPPORTED", "message": "暂不支持该平台。"})
        return
    emit(
        {
            "id": task.id,
            "event": "probe",
            "platform": platform,
            "label": PLATFORM_DOMAINS[platform]["label"],
            "url": normalize_url(final_url, platform),
        }
    )


_STATUS_CACHE: dict = {"at": 0.0, "payload": None}


def engine_versions_fast() -> dict[str, str]:
    """
    引擎版本号：优先读内置运行时 manifest.json（毫秒级）。

    原来每个引擎都起一次子进程探测：Windows 上 `python -c "import yt_dlp"` 偶发卡满
    20 秒超时、xhs/ks 再各跑一次 git，engine_status 要 40-60 秒才回，界面表现成
    「设置和 Cookie 列表一直是空的」。清单缺失时才回退到探测（超时也收紧到 5/3 秒）。
    """
    versions: dict[str, str] = {}
    manifest = runtime_manifest()
    for key in ("ytdlp", "xhs", "ks"):
        value = ((manifest.get("engines") or {}).get(key) or {}).get("version")
        if value:
            versions[key] = str(value)
    if len(versions) == 3:
        return versions

    if "ytdlp" not in versions:
        try:
            import yt_dlp  # noqa: PLC0415

            versions["ytdlp"] = getattr(getattr(yt_dlp, "version", None), "__version__", "") or ""
        except Exception:  # noqa: BLE001
            python = engine_python("ytdlp")
            version = ""
            if python.exists():
                try:
                    version = subprocess.run(
                        [str(python), "-c", "import yt_dlp; print(yt_dlp.version.__version__)"],
                        capture_output=True,
                        text=True,
                        timeout=5,
                        env=engine_env("ytdlp"),
                    ).stdout.strip()
                except (OSError, subprocess.SubprocessError):
                    version = ""
            versions["ytdlp"] = version

    for engine in ("xhs", "ks"):
        if engine in versions:
            continue
        root = engine_root(engine)
        version = ""
        if (root / ".git").exists():
            try:
                version = subprocess.run(
                    ["git", "-C", str(root), "rev-parse", "--short", "HEAD"],
                    capture_output=True,
                    text=True,
                    timeout=3,
                ).stdout.strip()
            except (OSError, subprocess.SubprocessError):
                version = ""
        versions[engine] = version
    return versions


def engine_status_payload() -> dict:
    now = time.time()
    if _STATUS_CACHE["payload"] is not None and now - _STATUS_CACHE["at"] < 10:
        return dict(_STATUS_CACHE["payload"])
    settings = load_settings()
    payload = {
        "support_dir": str(SUPPORT_DIR),
        "engines": {},
        "cookies": None,
        "browsers": browser_installed_status(),
        "browser_engine": settings.get("browser_engine", "auto"),
        # 给 Tauri（Windows）界面回填设置项用；Mac 端自己有 AppStorage，不依赖这里
        "settings": {
            "out_dir": settings.get("out_dir") or "",
            "name_template": settings.get("name_template") or "",
            "max_height": settings.get("max_height") or 0,
            "proxy": settings.get("proxy") or "",
            "auto_open_finder": bool(settings.get("auto_open_finder")),
            "wxchannels_h265": bool(settings.get("wxchannels_h265")),
        },
    }
    versions = engine_versions_fast()
    for engine, label in (("ytdlp", "yt-dlp"), ("xhs", "XHS-Downloader"), ("ks", "KS-Downloader")):
        python = engine_python(engine)
        root = engine_root(engine)
        ready = python.exists() and (root.exists() if engine != "ytdlp" else True)
        payload["engines"][engine] = {"label": label, "ready": ready, "version": versions.get(engine, "")}

    if COOKIE_FILE.exists():
        payload["cookies"] = {
            "path": str(COOKIE_FILE),
            "updated_at": time.strftime("%Y-%m-%d %H:%M", time.localtime(COOKIE_FILE.stat().st_mtime)),
        }
    return payload


def handle_update_engines(task: "Task") -> None:
    """
    更新解析引擎（yt-dlp / 小红书 / 快手）。

    走跨平台的 Python 版 bootstrap（Windows 没有 bash，之前调 .sh 必然失败）。
    注意：正式版本里引擎更新随「应用发版」走，界面不再暴露这个入口；
    这里保留给开发机排障用（也可以直接跑 scripts/bootstrap-engine.py --update）。
    """
    script = Path(__file__).resolve().parent.parent / "scripts" / "bootstrap-engine.py"
    if not script.exists():
        raise RuntimeError("未找到 bootstrap-engine.py")
    emit({"id": task.id, "event": "stage", "stage": "更新引擎中"})
    process = subprocess.Popen(
        [str(sys.executable), str(script), "--update"],
        env={
            **os.environ,
            # 内置运行时下，更新要落在用户数据目录（安装目录通常是只读的）
            "VIDEOGRABBER_HOME": str(SUPPORT_DIR),
        },
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )
    task.process = process
    assert process.stdout is not None
    for line in process.stdout:
        clean = ANSI_PATTERN.sub("", line).rstrip()
        if clean:
            emit({"id": task.id, "event": "log", "message": clean})
        if task.cancelled:
            task.terminate()
    process.wait()
    if task.cancelled:
        raise TaskCancelled()
    if process.returncode != 0:
        raise RuntimeError(f"引擎更新失败（退出码 {process.returncode}）")
    emit({"id": task.id, "event": "done", "kind": "update_engines", "engines": engine_status_payload()["engines"]})


def handle_refresh_cookies(task: "Task", request: dict) -> None:
    browser = request.get("browser") or load_settings().get("cookie_browser", "auto")
    payload = extract_cookies(browser, task.id)
    emit({"id": task.id, "event": "done", "kind": "refresh_cookies", "cookies": payload})


LOGIN_URLS = {
    "douyin": "https://www.douyin.com/",
    "kuaishou": "https://www.kuaishou.com/",
    "xiaohongshu": "https://www.xiaohongshu.com/",
    # X / YouTube 之前漏了这两项，导致 Mac 上点「一键获取」会直接报错
    "x": "https://x.com/login",
    "youtube": "https://accounts.google.com/ServiceLogin?service=youtube",
    # 视频号要登录的其实是「腾讯元宝」——解析接口用的就是它的登录态
    "wechat_channels": "https://yuanbao.tencent.com/",
    # 下面四个走 yt-dlp，登录后清晰度/成功率更高（不登录也能下大部分内容）
    "bilibili": "https://passport.bilibili.com/login",
    "weibo": "https://m.weibo.cn/",
    # 直接落到登录页，省得用户在信息流里找入口
    "tiktok": "https://www.tiktok.com/login",
    "instagram": "https://www.instagram.com/accounts/login/",
    # 抖音：正常下载不需要 Cookie，但「主页 / 收藏夹批量」要登录才给列表。
    # 直接落在「我的主页」：已登录时页面会请求带签名的 user/profile/self，登录检测才有服务端结论；
    # 未登录时抖音会把它重定向回首页，登录入口照常可见
    "douyin": "https://www.douyin.com/user/self",
    # 第三梯队 + 音乐平台：没有这几个 key 时，设置页点「一键获取」会直接抛错
    "ixigua": "https://www.ixigua.com/",
    "reddit": "https://www.reddit.com/login/",
    "vimeo": "https://vimeo.com/log_in",
    "twitch": "https://www.twitch.tv/login",
    "weishi": "https://isee.weishi.qq.com/",
    "haokan": "https://haokan.baidu.com/",
    "netease": "https://music.163.com/#/login",
    "qqmusic": "https://y.qq.com/",
}
LOGIN_SESSIONS: dict[str, dict] = {}

# 页面自检脚本：判断打开后的页面到底是"已登录"还是"还在要求登录"。
# 浏览器里"存着 Cookie"不等于"登录还有效"——平台过期会话的 Cookie 往往还留在本机，
# 只看 Cookie 名会把已经失效的登录态重新保存一遍。返回 'in' / 'out' / 'unknown'。
LOGIN_PROBES = {
    # 抖音不在这里做（见 probe_douyin_login）：它的接口要 a_bogus 签名，
    # 手工拼的请求一律被判「用户未登录」，只能读页面自己请求的响应。
    "wechat_channels": """
        (function(){try{
          var t=(document.body&&document.body.innerText)||'';
          var form=/获取验证码|其他登录方式|手机号登录|扫码登录/.test(t);
          var chat=/发消息/.test(t)||!!document.querySelector('textarea,[contenteditable="true"]');
          if(form&&!chat)return 'out';
          if(chat)return 'in';
          return 'unknown';
        }catch(e){return 'unknown'}})()
    """,
    "x": """
        (function(){try{
          if(document.querySelector('[data-testid="SideNav_AccountSwitcher_Button"],[data-testid="AppTabBar_Profile_Link"]'))return 'in';
          var t=(document.body&&document.body.innerText)||'';
          if(/Sign in to X|忘记密码|创建账号|使用 Google 登录|手机号、电子邮件地址或用户名/.test(t))return 'out';
          return 'unknown';
        }catch(e){return 'unknown'}})()
    """,
    "youtube": """
        (function(){try{
          if(document.querySelector('#avatar-btn,ytd-topbar-menu-button-renderer #avatar-btn,button[aria-label*="Account"],button[aria-label*="账号"]'))return 'in';
          var t=(document.body&&document.body.innerText)||'';
          if(/登录以|Sign in to/.test(t)||document.querySelector('a[href*="ServiceLogin"],a[href^="/signin"]'))return 'out';
          return 'unknown';
        }catch(e){return 'unknown'}})()
    """,
}


def probe_login_page(page, platform: str) -> str:
    """页面自检：'in' 已登录 / 'out' 还在要求登录 / 'unknown' 判不出来（按老行为放行）"""
    if platform == "douyin":
        return probe_douyin_login(page)
    script = LOGIN_PROBES.get(platform)
    if not script:
        return "unknown"
    try:
        return str(page.evaluate(script) or "unknown").strip()
    except Exception:  # noqa: BLE001
        return "unknown"


def wait_for_login_page(page, platform: str, timeout: float = 8.0) -> str:
    """SPA 首屏渲染慢，多试几次；判不出来就算了，不拦用户"""
    deadline = time.time() + timeout
    state = "unknown"
    while time.time() < deadline:
        state = probe_login_page(page, platform)
        if state != "unknown":
            return state
        time.sleep(1.0)
    return state


def handle_cookie_test(task: "Task", request: dict) -> None:
    platform = request.get("platform") or ""
    cookie = cookie_string_for(platform)
    if not cookie:
        emit({"id": task.id, "event": "error", "code": "NO_COOKIE", "message": "尚未配置该平台 Cookie。"})
        return

    # 先做「真实有效性」校验（各平台自己的接口），做不到才退回连通性检查
    verdict = check_cookie_validity(platform, cookie, task)
    if verdict is not None:
        ok, message = verdict
        emit({"id": task.id, "event": "done", "kind": "cookie_test", "platform": platform, "ok": ok, "message": message})
        return

    cookies = {}
    for pair in cookie.split("; "):
        if "=" in pair:
            key, _, value = pair.partition("=")
            cookies[key.strip()] = value.strip()

    if platform == "douyin":
        aweme_id = None
        if HISTORY_FILE.exists():
            try:
                for record in json.loads(HISTORY_FILE.read_text(encoding="utf-8")):
                    if record.get("platform") == "douyin":
                        source = record.get("resolved_url") or record.get("url", "")
                        match = re.search(r"(\d{15,})", source)
                        if match:
                            aweme_id = match.group(1)
                            break
            except (OSError, ValueError):
                pass
        if not aweme_id:
            aweme_id = DOUYIN_PROBE_ID
        try:
            fetch_douyin_detail_api(task, aweme_id)
            emit({"id": task.id, "event": "done", "kind": "cookie_test", "platform": platform, "ok": True, "message": "Cookie 有效：抖音接口正常返回作品数据。"})
        except CookieRequired as error:
            emit({"id": task.id, "event": "done", "kind": "cookie_test", "platform": platform, "ok": False, "message": f"Cookie 已失效或被风控：{error}"})
        except Exception as error:  # noqa: BLE001
            emit({"id": task.id, "event": "done", "kind": "cookie_test", "platform": platform, "ok": False, "message": f"检测失败：{error}"})
        return

    session = http_session()
    login_url = LOGIN_URLS.get(platform)
    if not login_url:
        emit({
            "id": task.id,
            "event": "done",
            "kind": "cookie_test",
            "platform": platform,
            "ok": True,
            "message": "该平台暂不支持自动校验；下载时如果被拒绝，请重新登录一次",
        })
        return
    try:
        response = session.get(
            login_url,
            headers={"User-Agent": UA_CHROME, "Accept-Language": "zh-CN,zh;q=0.9"},
            cookies=cookies,
            timeout=20,
        )
        ok = response.status_code == 200
        emit(
            {
                "id": task.id,
                "event": "done",
                "kind": "cookie_test",
                "platform": platform,
                "ok": ok,
                "message": f"站点连通性检查：HTTP {response.status_code}（仅表示网络可达，不代表未过期）",
            }
        )
    except Exception as error:  # noqa: BLE001
        emit({"id": task.id, "event": "done", "kind": "cookie_test", "platform": platform, "ok": False, "message": f"检测失败：{error}"})


def handle_cookie_set(task: "Task", request: dict) -> None:
    platform = request.get("platform") or ""
    entry = update_platform_cookie(platform, request.get("cookie") or "", "手动粘贴")
    emit({"id": task.id, "event": "done", "kind": "cookie_set", "platform": platform, "count": entry.get("count", 0), "cookie_status": cookie_status_payload()})


def handle_cookie_clear(task: "Task", request: dict) -> None:
    platform = request.get("platform") or ""
    update_platform_cookie(platform, "", "")
    emit({"id": task.id, "event": "done", "kind": "cookie_clear", "platform": platform, "cookie_status": cookie_status_payload()})


# ---------------------------------------------------------------- 通用解析兜底
#
# 引擎按「专用适配 → 通用解析」的顺序处理链接：
#   - 常规视频站点统一交给 yt-dlp 通用解析（支持上千个站点）；
#   - 需要登录态 / 页面签名的入口（如主页批量列表）在缺少凭证时给出明确提示；
#   - 想扩展解析能力？按 engine/README.md 的约定实现这些入口函数即可
#     （返回 dict：{"files": [...], "title": str, "author": str, "platform": str, "source": str}）。


def download_douyin(task: "Task", url: str, settings: dict, raw_url: "str | None" = None) -> dict:
    """抖音：走 yt-dlp 通用解析。"""
    return download_with_ytdlp(task, url, settings, "douyin")


def download_xhs(task: "Task", url: str, settings: dict, hint: str = "") -> dict:
    """小红书：走 yt-dlp 通用解析。"""
    return download_with_ytdlp(task, url, settings, "xiaohongshu")


def download_ks(task: "Task", url: str, settings: dict) -> dict:
    """快手：走 yt-dlp 通用解析。"""
    return download_with_ytdlp(task, url, settings, "kuaishou")


def download_instagram(task: "Task", url: str, settings: dict, raw_url: str) -> dict:
    """Instagram：走 yt-dlp 通用解析。"""
    return download_with_ytdlp(task, url, settings, "instagram")


def download_wxchannels(task: "Task", url: str, settings: dict, raw_url: str) -> dict:
    raise RuntimeError("该链接需要平台鉴权，当前暂不支持。")


def handle_batch(task: "Task", request: dict) -> None:
    raise RuntimeError("批量列表需要账号权限，当前暂不支持该操作。")


def fetch_douyin_detail_api(task: "Task", aweme_id: str) -> dict:
    raise RuntimeError("该接口需要页面签名，当前暂不支持。")


def douyin_cookie_dict() -> dict:
    return {}


def check_cookie_validity(platform: str, cookie: str, task: "Task | None" = None):
    """接口级校验不可用时返回 None → 调用方自动回退「连通性检查」。"""
    return None


def probe_douyin_login(page) -> str:
    return "unknown"


def wechat_video_page_infos(raw: str, task: "Task | None" = None) -> list:
    return []


def wechat_article_videos(page_url: str, frames: list, task: "Task | None" = None) -> list:
    return []


DOUYIN_PROBE_ID = ""
# ---------------------------------------------------------------- 兜底结束
def dispatch(request: dict) -> None:
    task_id = request.get("id") or uuid.uuid4().hex[:8]
    command = request.get("cmd")
    task = Task(task_id)

    if command == "ping":
        emit({"id": task_id, "event": "pong"})
        return
    if command == "engine_status":
        emit({"id": task_id, "event": "engine_status", **engine_status_payload()})
        return
    if command == "cookie_status":
        emit({"id": task_id, "event": "cookie_status", "cookies": cookie_status_payload()})
        return
    if command == "cloud_status":
        emit({"id": task_id, "event": "cloud_status", **cloud_status_payload()})
    if command == "account_sms_code":
        handle_account_sms_code(task, request)
        return
    if command == "account_login":
        handle_account_login(task, request)
        return
    if command == "account_logout":
        handle_account_logout(task, request)
        return
    if command == "account_status":
        handle_account_status(task, request)
        return
    if command == "cookie_sync_status":
        handle_cookie_sync_status(task, request)
        return
    if command == "cookie_sync_upload":
        handle_cookie_sync_upload(task, request)
        return
    if command == "cookie_sync_pull":
        handle_cookie_sync_pull(task, request)
        return
    if command == "cookie_sync_clear":
        handle_cookie_sync_clear(task, request)
        return
    if command == "cookie_sync_autofill":
        handle_cookie_sync_autofill(task, request)
        return
    if command == "cloud_register":
        try:
            data = cloud_register(force=True)
            emit({"id": task_id, "event": "done", "kind": "cloud_register", **data})
        except RuntimeError as error:
            emit({"id": task_id, "event": "error", "code": "CLOUD_ERROR", "message": str(error)})
        return
    if command == "cloud_sync_cookies":
        emit({"id": task_id, "event": "error", "code": "UNKNOWN_COMMAND",
              "message": "云端 Cookie 已下线：请在本机「设置 → Cookie 管理」中登录后使用本地 Cookie。"})
        return
    if command == "cloud_check_update":
        try:
            data = cloud_check_update(request.get("platform"))
            emit({"id": task_id, "event": "done", "kind": "cloud_check_update", **data})
        except RuntimeError as error:
            emit({"id": task_id, "event": "error", "code": "CLOUD_ERROR", "message": str(error)})
        return
    if command == "set_settings":
        emit({"id": task_id, "event": "settings", "settings": save_settings(request.get("settings") or {})})
        return
    if command == "shutdown":
        emit({"id": task_id, "event": "bye"})
        raise SystemExit(0)

    with TASKS_LOCK:
        TASKS[task_id] = task
    try:
        if command == "probe":
            handle_probe(task, request)
        elif command == "batch":
            handle_batch(task, request)
        elif command == "download":
            handle_download(task, request)
        elif command == "refresh_cookies":
            handle_refresh_cookies(task, request)
        elif command == "cookie_login":
            handle_cookie_login(task, request)
        elif command == "cookie_test":
            handle_cookie_test(task, request)
        elif command == "cookie_set":
            handle_cookie_set(task, request)
        elif command == "cookie_clear":
            handle_cookie_clear(task, request)
        elif command == "cloud_feedback":
            try:
                data = cloud_feedback(request)
                emit({"id": task_id, "event": "done", "kind": "cloud_feedback", **data})
            except RuntimeError as error:
                emit({"id": task_id, "event": "error", "code": "CLOUD_ERROR", "message": str(error)})
        elif command == "update_engines":
            handle_update_engines(task)
        else:
            emit({"id": task_id, "event": "error", "code": "UNKNOWN_COMMAND", "message": f"未知指令: {command}"})
    except TaskCancelled:
        emit({"id": task_id, "event": "error", "code": "CANCELLED", "message": "已取消下载。"})
    except Exception as error:  # noqa: BLE001
        log(f"任务失败: {error!r}\n{traceback.format_exc()}", task_id, echo=False)
        code, message = friendly_error("", str(error))
        mark_cookie_invalid_on_error(request, code, str(error))
        emit({"id": task_id, "event": "error", "code": code, "message": message, "detail": str(error)[:500]})
    finally:
        with TASKS_LOCK:
            TASKS.pop(task_id, None)
        shutil.rmtree(TMP_DIR / task_id, ignore_errors=True)


def cancel_task(task_id: str) -> None:
    with TASKS_LOCK:
        task = TASKS.get(task_id)
    if task:
        task.terminate()
        emit({"id": task_id, "event": "log", "message": "已请求取消…"})


def main() -> int:
    for directory in (SUPPORT_DIR, LOG_DIR, COOKIE_DIR, TMP_DIR):
        directory.mkdir(parents=True, exist_ok=True)
    log("桥接进程启动")
    emit({"id": "system", "event": "ready", "pid": os.getpid()})

    workers: list[threading.Thread] = []
    for line in sys.stdin:
        # 去掉 BOM / 首尾空白：Windows 上有些工具（PowerShell 的重定向、记事本另存）会带 UTF-8 BOM，
        # 直接 json.loads 会报 "Expecting value"，这里统一兼容掉
        line = line.lstrip("\ufeff").strip()
        if not line:
            continue
        try:
            request = json.loads(line)
        except ValueError:
            log(f"无法解析的请求: {line[:200]}")
            continue
        if request.get("cmd") == "cancel":
            cancel_task(request.get("task_id") or request.get("id") or "")
            continue
        if request.get("cmd") in ("cookie_login_save", "cookie_login_cancel"):
            session = LOGIN_SESSIONS.get(request.get("task_id") or "")
            if session:
                if request["cmd"] == "cookie_login_save":
                    session["save"] = True
                else:
                    session["cancel"] = True
                emit({"id": request.get("task_id"), "event": "log", "message": "正在保存 Cookie…" if session["save"] else "已取消登录"})
            continue
        thread = threading.Thread(target=dispatch, args=(request,), daemon=True)
        thread.start()
        workers.append(thread)
    # stdin 关闭（父进程退出）时，等待仍在执行的任务收尾
    for worker in workers:
        worker.join(timeout=1800)
    return 0


if __name__ == "__main__":
    sys.stdout = sys.stderr
    raise SystemExit(main())
