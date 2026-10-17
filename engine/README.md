# 引擎框架（bridge.py）

三端共用的 Python 引擎：常驻进程 + **JSON Lines 协议**（stdin 收请求、stdout 发事件）。
外壳（SwiftUI / Tauri / Android）只做界面，所有解析、下载、文件管理都在这里完成。

## 协议速览

请求（每行一条）：

```json
{"id":"1","cmd":"download","url":"https://example.com/a.pdf","out_dir":"~/Downloads"}
```

事件（每行一条）：

```json
{"id":"1","event":"meta","platform":"web","url":"..."}
{"id":"1","event":"progress","percent":42.0,"speed":"3.2MB/s","eta":"00:12"}
{"id":"1","event":"done","file":"/Users/me/Downloads/a.pdf","files":["..."]}
{"id":"1","event":"error","code":"ENGINE","message":"..."}
```

核心命令：`ping` / `probe` / `download` / `cancel` / `batch` / `engine_status` /
`cookie_status` / `cookie_set` / `cookie_clear` / `refresh_cookies` /
`cookie_login` / `cookie_test` / `set_settings` / `update_engines`。

## 平台解析策略

`bridge.py` 里，各平台的解析 / 下载以函数形式组织（由 `handle_download()` 分发），
按「专用适配 → 通用解析」的顺序处理：

- 常规视频站点交给 `download_with_ytdlp()`（yt-dlp 生态，覆盖 1700+ 站点）；
- 需要登录态 / 页面签名的入口（如主页批量列表）在缺少凭证时给出明确提示；
- 想扩展新的解析能力，按下面的约定实现对应入口函数即可：

```python
def download_xxx(task: "Task", url: str, settings: dict, ...) -> dict:
    # 1. 解析（可发进度/日志事件）
    task.check()                        # 处理「取消」
    emit({"id": task.id, "event": "stage", "stage": "解析中"})
    # 2. 下载文件（推荐复用 download_segmented()，自带分段并发 + 断点续传）
    # 3. 返回统一结构
    return {
        "files": ["/abs/path/file.mp4"],
        "title": "...", "author": "...",
        "platform": "xxx", "source": "engine",
    }
```

常用工具函数（都已在框架里）：

- `download_segmented()` —— 多线程分段下载（8 段并发、断点续传、多 CDN 重试）；
- `download_media_urls()` —— 一组媒体 URL 的批量下载 + 去重；
- `move_results()` —— 按「命名模板 / 保存目录」归档文件；
- `cookie_string_for(platform)` —— 取该平台 Cookie（由设置页维护）；
- `http_session()` —— 带代理与会话复用的 HTTP 客户端；
- `probe_remote()` / `remote_size()` —— 探测远端文件类型与大小。

## 通用能力（开箱即用）

- **yt-dlp 通用解析**：`download_with_ytdlp()` 覆盖 YouTube / X / B 站 / 微博 /
  Reddit / Vimeo / Twitch 等 1700+ 站点（yt-dlp 为运行时依赖，Unlicense）；
- **直链下载**：任意 HTTP(S) 文件直链，类型探测后直接归档；
- **网页嗅探**：`download_page_resources()` 从普通网页提取图片 / 视频并批量保存。

## 运行

```bash
python3.13 engine/bridge.py
echo '{"id":"1","cmd":"ping"}' | python3.13 engine/bridge.py
```

依赖安装在应用数据目录的 venv（`scripts/bootstrap-engine.py`），不需要全局环境。

## 许可证提示

仓库不打包 GPL 组件；部分第三方引擎（GPL-3.0）以「运行时获取」方式使用。
若你的扩展实现调用了这类组件，请自行遵守其许可证。
