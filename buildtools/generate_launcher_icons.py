#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""根据提供的 logo 图片生成 Android mipmap 各密度启动图标。

输出到 app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/
同时生成 ic_launcher.png 与 ic_launcher_round.png（内容相同，系统会自动裁剪圆角）。
"""
import os
from PIL import Image

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = r"C:\Users\nsklr\.workbuddy\clipboard-images\clipboard-2026-08-01T15-05-06-654Z-0af880e5.jpg"
OUT_ROOT = os.path.join(BASE, "app", "src", "main", "res")

SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}


def main():
    img = Image.open(SRC).convert("RGBA")
    # 裁剪为正方形（以中心为基准），避免拉伸变形
    w, h = img.size
    side = min(w, h)
    left = (w - side) // 2
    top = (h - side) // 2
    cropped = img.crop((left, top, left + side, top + side))

    for folder, size in SIZES.items():
        out_dir = os.path.join(OUT_ROOT, folder)
        os.makedirs(out_dir, exist_ok=True)
        resized = cropped.resize((size, size), Image.LANCZOS)
        for name in ("ic_launcher.png", "ic_launcher_round.png"):
            out_path = os.path.join(out_dir, name)
            resized.save(out_path, "PNG")
            print(f"OK: {out_path}")


if __name__ == "__main__":
    main()
