#!/bin/bash
# 兼容入口：转调跨平台实现 scripts/bootstrap-engine.py（macOS 上保持 ./xxx.sh 的用法）
#
# 用法:
#   ./scripts/bootstrap-engine.sh            初始化（幂等）
#   ./scripts/bootstrap-engine.sh --check    仅检查状态
#   ./scripts/bootstrap-engine.sh --update   升级引擎与浏览器内核
#   ./scripts/bootstrap-engine.sh --force    重新初始化
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

find_python() {
  local candidates=(
    "${VIDEOGRABBER_PYTHON:-}"
    /opt/homebrew/bin/python3.13
    /opt/homebrew/bin/python3.12
    "$(command -v python3.13 2>/dev/null || true)"
    "$(command -v python3.12 2>/dev/null || true)"
    "$(command -v python3 2>/dev/null || true)"
  )
  local candidate
  for candidate in "${candidates[@]}"; do
    [ -n "$candidate" ] || continue
    [ -x "$candidate" ] || continue
    if "$candidate" -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 12) else 1)' 2>/dev/null; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

PYTHON_BIN="$(find_python)" || {
  echo "[bootstrap][错误] 未找到 Python 3.12+（可设置 VIDEOGRABBER_PYTHON 指定路径）" >&2
  exit 1
}

exec "$PYTHON_BIN" "$SCRIPT_DIR/bootstrap-engine.py" "$@"
