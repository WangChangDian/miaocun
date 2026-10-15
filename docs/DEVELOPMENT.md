# 本地开发指南

面向「改代码 + 本地跑起来」。三端共用同一个 Python 引擎，改引擎三端同时生效。

## 1. 环境要求

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| Python | **3.13** | 引擎（`bridge.py` 使用了 3.10+ 语法与新特性） |
| Xcode 命令行工具 | — | 编译 macOS 外壳（SwiftUI，SwiftPM 工程） |
| Android Studio / Android SDK | — | 安卓端（Kotlin + Compose）；`android/local.properties` 里配 `sdk.dir` |
| JDK | 17+ | 安卓端编译 |
| Node.js + pnpm/npm | 20+ | Tauri（Windows）外壳 |
| Rust | stable | Tauri 编译（仅 Windows 端需要） |

> 引擎的第三方依赖（yt-dlp 等）安装在**应用数据目录**的 venv 里，
> 不需要全局装；首次运行 `scripts/bootstrap-engine.py` 会自动创建。

## 2. 引擎（三端共用）

引擎是一个常驻进程：**stdin 每行一条 JSON 请求，stdout 每行一条 JSON 事件**。

```bash
# macOS 示例（Windows / Linux 换成对应解释器路径）
/opt/homebrew/bin/python3.13 engine/bridge.py

# 手动交互（示例）
{"id":"1","cmd":"ping"}
{"id":"2","cmd":"probe","url":"https://www.bilibili.com/video/BV1xx411c7mD"}
{"id":"3","cmd":"download","url":"https://example.com/demo.pdf","out_dir":"~/Downloads"}
{"id":"4","cmd":"cancel","id_of_task":"3"}
```

常用命令：`ping` / `engine_status` / `probe` / `download` / `cancel` /
`cookie_status` / `cookie_set` / `cookie_clear` / `refresh_cookies` /
`cookie_login` / `cookie_test` / `set_settings`。

事件类型：`ready` / `stage` / `meta` / `progress` / `log` / `done` / `error` / `pong` 等。

更多协议细节、平台适配层如何实现，见 [`../engine/README.md`](../engine/README.md)。

## 3. macOS 外壳

```bash
bash scripts/build-app.sh          # 编译并组装 dist/秒存.app
bash scripts/build-app.sh --zip    # 额外产出分发包
```

首次运行若提示「引擎未初始化」，点界面里的「开始初始化」即可
（本质是运行 `scripts/bootstrap-engine.py` 创建 venv）。

## 4. Android

```bash
bash scripts/build-android.sh      # 产出 dist/秒存-Android-<版本>.apk
```

也可以直接用 Android Studio 打开 `android/` 目录调试。

## 5. Windows（Tauri）

```powershell
cd tauri
npm install
npm run tauri build               # 或在仓库根目录执行 scripts\build-windows.ps1
```

## 6. 版本号

全项目唯一版本号来源是根目录 `VERSION`（`versionName`，`versionCode` 由脚本自动推导：
`major*10000 + minor*100 + patch`）。打包脚本读取它写入三端与引擎。

## 7. 数据目录

| 平台 | 位置 |
| --- | --- |
| macOS | `~/Library/Application Support/视频下载器/` |
| Windows | `%APPDATA%/视频下载器/` |
| Android | 应用私有目录（设置页里可查看日志与保存位置） |

里面有：引擎 venv、浏览器内核、Cookie、下载历史——整个目录删除即完全卸载。
