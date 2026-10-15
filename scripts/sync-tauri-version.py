#!/usr/bin/env python3
"""把仓库根目录 VERSION 里的版本号同步到 Tauri 工程。

为什么需要它：Cargo.toml 的 package.version 和 tauri.conf.json 的 version 都必须是
字面量，没法像 Android 那样在构建时读文件；所以打 Tauri 包之前先跑一遍本脚本。
scripts/build-tauri.sh 会自动调用，一般不用手动执行。

用法：
    python3 scripts/sync-tauri-version.py            # 同步
    python3 scripts/sync-tauri-version.py --check     # 只检查是否一致（CI / 提交前用），不一致返回 1
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VERSION_FILE = ROOT / "VERSION"
TAURI_CONF = ROOT / "tauri" / "src-tauri" / "tauri.conf.json"
CARGO_TOML = ROOT / "tauri" / "src-tauri" / "Cargo.toml"


def read_version_name() -> str:
    for line in VERSION_FILE.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line.startswith("versionName="):
            value = line.split("=", 1)[1].strip()
            if value:
                return value
    raise SystemExit(f"[version][错误] 无法从 {VERSION_FILE} 读取 versionName")


def current_conf_version() -> str:
    return json.loads(TAURI_CONF.read_text(encoding="utf-8")).get("version", "")


def current_cargo_version() -> str:
    text = CARGO_TOML.read_text(encoding="utf-8")
    package_block = text.split("[package]", 1)[-1].split("\n[", 1)[0]
    match = re.search(r'^version\s*=\s*"([^"]+)"', package_block, re.M)
    return match.group(1) if match else ""


def apply(name: str) -> None:
    # tauri.conf.json：保持 2 空格缩进
    data = json.loads(TAURI_CONF.read_text(encoding="utf-8"))
    data["version"] = name
    TAURI_CONF.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    # Cargo.toml：只改 [package] 段里的 version，别碰到依赖的 version = "2"
    text = CARGO_TOML.read_text(encoding="utf-8")
    head, sep, rest = text.partition("[package]")
    if not sep:
        raise SystemExit(f"[version][错误] {CARGO_TOML} 里没有 [package] 段")
    package_block, sep2, tail = rest.partition("\n[")
    new_block, count = re.subn(
        r'^(version\s*=\s*)"[^"]+"', rf'\g<1>"{name}"', package_block, count=1, flags=re.M
    )
    if count != 1:
        raise SystemExit(f"[version][错误] 没能在 {CARGO_TOML} 的 [package] 段里找到 version")
    CARGO_TOML.write_text(head + sep + new_block + (sep2 + tail if sep2 else ""), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="把 VERSION 同步到 Tauri 工程")
    parser.add_argument("--check", action="store_true", help="只检查，不修改")
    args = parser.parse_args()

    name = read_version_name()
    conf = current_conf_version()
    cargo = current_cargo_version()

    if args.check:
        ok = conf == name and cargo == name
        print(f"[version] VERSION={name}  tauri.conf.json={conf}  Cargo.toml={cargo}")
        if not ok:
            print("[version][错误] Tauri 版本号与 VERSION 不一致，跑一下 scripts/build-tauri.sh 或本脚本")
        return 0 if ok else 1

    if conf == name and cargo == name:
        print(f"[version] Tauri 版本已是最新：{name}")
        return 0

    apply(name)
    print(f"[version] 已同步 Tauri 版本 → {name}（tauri.conf.json + Cargo.toml）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
