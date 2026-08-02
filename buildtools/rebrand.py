#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""将用户可见的「滇震卫士」替换为「地震哨兵」。

注意：只改中文产品名，不改代码包名 / 仓库名 Dianguard / 历史归档发布说明。
"""
import os

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

TARGETS = [
    "README.md",
    "BUILD.md",
    "app/src/main/res/values/strings.xml",
    "app/src/main/res/layout/activity_guide.xml",
    "app/src/main/java/com/dianguard/app/EewService.kt",
    "app/src/main/java/com/dianguard/app/MainActivity.kt",
    "app/src/main/java/com/dianguard/app/GuideActivity.kt",
    "app/proguard-rules.pro",
    "buildtools/publish_v111.py",
    "archive/RELEASE-v1.0.11-info.md",
]

OLD = "滇震卫士"
NEW = "地震哨兵"


def main():
    for rel in TARGETS:
        path = os.path.join(BASE, rel)
        if not os.path.isfile(path):
            print(f"SKIP (not found): {path}")
            continue
        with open(path, "r", encoding="utf-8") as f:
            text = f.read()
        if OLD not in text:
            print(f"OK (no change): {rel}")
            continue
        new_text = text.replace(OLD, NEW)
        with open(path, "w", encoding="utf-8") as f:
            f.write(new_text)
        print(f"REBRANDED: {rel} ({text.count(OLD)} occurrences)")


if __name__ == "__main__":
    main()
