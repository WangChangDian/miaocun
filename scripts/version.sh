#!/bin/bash
# 统一的版本号读取逻辑：source 之后可以使用 $APP_VERSION_NAME / $APP_VERSION_CODE
#
# 规则见仓库根目录 VERSION 的注释：
#   versionCode 默认由 versionName 推导（major*10000 + minor*100 + patch），
#   如果 VERSION 里显式写了 versionCode=，则以手写的为准。
#
# 用法： . "$(dirname "$0")/version.sh"   或   source scripts/version.sh

VERSION_FILE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/VERSION"

# 注意：调用方通常开着 set -e/-o pipefail，取不到值时（如 VERSION 里没有 versionCode）
# 这里必须返回 0，否则整个构建脚本会静默退出
_vg_read() {
  local value
  value="$(grep -E "^${1}=" "$VERSION_FILE" 2>/dev/null | head -1 | cut -d= -f2- | tr -d '[:space:]')" || true
  printf '%s' "$value"
  return 0
}

APP_VERSION_NAME="$(_vg_read versionName)"
APP_VERSION_CODE="$(_vg_read versionCode)"

if [ -z "$APP_VERSION_NAME" ]; then
  echo "[version][错误] 无法从 $VERSION_FILE 读取 versionName" >&2
  return 1 2>/dev/null || exit 1
fi

if [ -z "$APP_VERSION_CODE" ]; then
  # 1.5.0 -> 10500；预发布后缀（1.6.0-beta.1）只取前导数字部分
  _vg_base="${APP_VERSION_NAME%%-*}"
  IFS=. read -r _vg_major _vg_minor _vg_patch <<< "$_vg_base"
  _vg_major="${_vg_major//[!0-9]/}"; _vg_minor="${_vg_minor//[!0-9]/}"; _vg_patch="${_vg_patch//[!0-9]/}"
  APP_VERSION_CODE=$(( ${_vg_major:-0} * 10000 + ${_vg_minor:-0} * 100 + ${_vg_patch:-0} ))
fi

export APP_VERSION_NAME APP_VERSION_CODE
