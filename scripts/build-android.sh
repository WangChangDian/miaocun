#!/bin/bash
# 打安卓 APK：读仓库根目录的 VERSION 文件 → gradle 构建 → 复制到 dist/ 并规范化命名
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_NAME="秒存"

# versionCode 由 versionName 自动推导
. "$PROJECT_DIR/scripts/version.sh"

# Tauri（Windows）的版本写死在配置文件里，顺手同步一次避免各端漂移；失败不影响本次构建
if command -v python3 >/dev/null 2>&1; then
  python3 "$PROJECT_DIR/scripts/sync-tauri-version.py" >/dev/null 2>&1 || true
fi

echo "[build] 版本：$APP_VERSION_NAME ($APP_VERSION_CODE)"
echo "[build] 编译 Android release APK…（首次或清过缓存会慢一些）"
cd "$PROJECT_DIR/android"
./gradlew assembleRelease

SRC="$PROJECT_DIR/android/app/build/outputs/apk/release/app-release.apk"
[ -f "$SRC" ] || { echo "[build][错误] 未找到 APK: $SRC" >&2; exit 1; }

DEST="$PROJECT_DIR/dist/${APP_NAME}-Android-${APP_VERSION_NAME}.apk"
mkdir -p "$PROJECT_DIR/dist"
cp "$SRC" "$DEST"

echo "[build] 完成: $DEST"
ls -lh "$DEST" | awk '{print "[build] 体积:", $5}'
