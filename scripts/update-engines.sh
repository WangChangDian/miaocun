#!/bin/bash
# 更新解析引擎（yt-dlp 升级 + 两个引擎源码 git pull + 依赖重装）
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$SCRIPT_DIR/bootstrap-engine.sh" --update
