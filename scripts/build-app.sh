#!/bin/bash
# 构建「视频下载器.app」：SwiftPM release 编译 + 组装 .app + ad-hoc 签名
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_NAME="秒存"
DIST_DIR="$PROJECT_DIR/dist"
APP_BUNDLE="$DIST_DIR/$APP_NAME.app"

# 版本号唯一来源：仓库根目录的 VERSION 文件（versionCode 自动推导）
VERSION_FILE="$PROJECT_DIR/VERSION"
. "$PROJECT_DIR/scripts/version.sh"
echo "[build] 版本：$APP_VERSION_NAME ($APP_VERSION_CODE)"

# Tauri（Windows）的版本写死在配置文件里，顺手同步一次避免各端漂移；失败不影响本次构建
if command -v python3 >/dev/null 2>&1; then
  python3 "$PROJECT_DIR/scripts/sync-tauri-version.py" >/dev/null 2>&1 || true
fi

echo "[build] 编译 Swift 可执行文件…"
swift build -c release --package-path "$PROJECT_DIR/app" --product VideoGrabber
BIN_DIR="$(swift build -c release --package-path "$PROJECT_DIR/app" --show-bin-path)"
BIN="$BIN_DIR/VideoGrabber"
[ -x "$BIN" ] || { echo "[build][错误] 未找到编译产物: $BIN" >&2; exit 1; }

echo "[build] 组装 $APP_BUNDLE"
rm -rf "$APP_BUNDLE"
mkdir -p "$APP_BUNDLE/Contents/MacOS" "$APP_BUNDLE/Contents/Resources/scripts"
cp "$BIN" "$APP_BUNDLE/Contents/MacOS/$APP_NAME"

# 应用图标：由 app/Resources/icon.png 生成 icns
ICON_SRC="$PROJECT_DIR/app/Resources/icon.png"
ICONSET="$PROJECT_DIR/.build/AppIcon.iconset"
if [ -f "$ICON_SRC" ] && command -v iconutil >/dev/null 2>&1; then
  echo "[build] 生成应用图标…"
  rm -rf "$ICONSET"
  mkdir -p "$ICONSET"
  for spec in "16 icon_16x16" "32 icon_16x16@2x" "32 icon_32x32" "64 icon_32x32@2x" \
              "128 icon_128x128" "256 icon_128x128@2x" "256 icon_256x256" \
              "512 icon_256x256@2x" "512 icon_512x512" "1024 icon_512x512@2x"; do
    size="${spec%% *}"
    name="${spec##* }"
    sips -z "$size" "$size" "$ICON_SRC" --out "$ICONSET/$name.png" >/dev/null 2>&1 || true
  done
  if iconutil -c icns "$ICONSET" -o "$APP_BUNDLE/Contents/Resources/AppIcon.icns" 2>/dev/null; then
    echo "[build] 图标已写入 AppIcon.icns"
  else
    echo "[build][警告] 图标生成失败，使用系统默认图标"
  fi
fi

# 引擎与脚本随包分发，App 内可直接初始化 / 更新
# 注意：必须先清掉目标目录再拷贝——`cp -R src dest` 在 dest 已存在时会拷成 dest/src
# （历史 bug：外层 bridge.py 一直停留在首次打包的旧版本，引擎修复打不进包）
rm -rf "$APP_BUNDLE/Contents/Resources/engine"
cp -R "$PROJECT_DIR/engine" "$APP_BUNDLE/Contents/Resources/engine"
cp "$PROJECT_DIR/scripts/bootstrap-engine.sh" "$APP_BUNDLE/Contents/Resources/scripts/"
cp "$PROJECT_DIR/scripts/bootstrap-engine.py" "$APP_BUNDLE/Contents/Resources/scripts/"
chmod +x "$APP_BUNDLE/Contents/Resources/scripts/bootstrap-engine.sh" \
         "$APP_BUNDLE/Contents/Resources/scripts/bootstrap-engine.py"

# 第三方开源声明（GPL/Unlicense 等组件随包分发，需一并提供声明）
cp "$PROJECT_DIR/legal/THIRD-PARTY-NOTICES.txt" "$APP_BUNDLE/Contents/Resources/"

# 内置运行时（scripts/bundle-runtime.py 生成）：便携 Python + 依赖 + 内核 + ffmpeg，
# 随包分发后用户下载即用，不再依赖系统 Python；未生成时包体保持精简（首次启动初始化）
if [ -f "$PROJECT_DIR/runtime/manifest.json" ]; then
  echo "[build] 打包内置运行时（体积较大，请稍候）…"
  rm -rf "$APP_BUNDLE/Contents/Resources/runtime"
  cp -R "$PROJECT_DIR/runtime" "$APP_BUNDLE/Contents/Resources/runtime"
else
  echo "[build][警告] 未找到 runtime/：本包需要用户首次启动初始化（运行 scripts/bundle-runtime.py 可生成内置运行时）"
fi

# 引擎进程读同一个 VERSION 文件，保证上报给云控后台的版本与 App 一致
cp "$VERSION_FILE" "$APP_BUNDLE/Contents/Resources/engine/VERSION"

# 清理 Python 运行期缓存，避免影响代码签名封条
find "$APP_BUNDLE/Contents/Resources" -name '__pycache__' -type d -prune -exec rm -rf {} + 2>/dev/null || true

cat > "$APP_BUNDLE/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleDevelopmentRegion</key>
    <string>zh_CN</string>
    <key>CFBundleDisplayName</key>
    <string>${APP_NAME}</string>
    <key>CFBundleExecutable</key>
    <string>${APP_NAME}</string>
    <key>CFBundleIdentifier</key>
    <string>local.wangchangdian.videograbber</string>
    <key>CFBundleIconFile</key>
    <string>AppIcon</string>
    <key>CFBundleInfoDictionaryVersion</key>
    <string>6.0</string>
    <key>CFBundleName</key>
    <string>${APP_NAME}</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>CFBundleShortVersionString</key>
    <string>${APP_VERSION_NAME}</string>
    <key>CFBundleVersion</key>
    <string>${APP_VERSION_CODE}</string>
    <key>LSMinimumSystemVersion</key>
    <string>13.0</string>
    <key>NSHighResolutionCapable</key>
    <true/>
    <key>NSPrincipalClass</key>
    <string>NSApplication</string>
    <key>NSHumanReadableCopyright</key>
    <string>仅供个人学习与备份使用</string>
</dict>
</plist>
PLIST

echo "[build] ad-hoc 签名…"
codesign --force --deep --sign - "$APP_BUNDLE" >/dev/null 2>&1 || \
  echo "[build][警告] 签名失败（不影响本机使用）"

echo "[build] 完成: $APP_BUNDLE"

# --zip：顺手打出可直接分发的 zip（发版用；与云控后台的 macOS 发布件命名一致）
if [ "${1:-}" = "--zip" ] || [ "${ZIP:-}" = "1" ]; then
  ZIP_PATH="$PROJECT_DIR/dist/秒存-macOS-${APP_VERSION_NAME}.zip"
  rm -f "$ZIP_PATH"
  echo "[build] 打包 zip（发版件）…"
  (cd "$PROJECT_DIR/dist" && ditto -c -k --sequesterRsrc --keepParent "$(basename "$APP_BUNDLE")" "$(basename "$ZIP_PATH")")
  echo "[build] 完成: ${ZIP_PATH}（$(du -h "$ZIP_PATH" | cut -f1)）"
fi
