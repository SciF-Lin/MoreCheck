#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 design/icon-concepts 下的图标设计稿接入三个版本的 Android 工程。

- 方案 A（品牌蓝 · 进度环对勾）→ 三个版本的默认图标（ic_launcher / ic_launcher_round）
- 方案 B（曜石黑 · 清单四宫格）→ 仅 v1.1.70，新增可替换图标 id = checklist
- 方案 C（青绿 · 日历对勾）    → 仅 v1.1.70，新增可替换图标 id = calendar

自适应图标 = 渐变 <shape> 背景 drawable + 透明前景 PNG（432×432 / xxxhdpi）。
activity-alias / BUILT_IN_ICON_IDS / 界面映射由 apply_icons_code.py 之外的手工 Edit 完成。
"""
import os
import shutil

BASE = "E:/download/app/MoreCheck/sources"
DESIGN = os.path.dirname(os.path.abspath(__file__))
VERSIONS = ["v1.1.2", "v1.1.50", "v1.1.70"]

ADAPTIVE = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/{bg}" />
    <foreground android:drawable="@drawable/{fg}" />
</adaptive-icon>
'''


def ensure(path):
    os.makedirs(path, exist_ok=True)


def write(path, text):
    ensure(os.path.dirname(path))
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def main():
    # ── 1. 默认图标（方案 A）：三个版本 ──────────────────────────────────
    for v in VERSIONS:
        res = f"{BASE}/{v}/MoreCheck/app/src/main/res"
        ensure(f"{res}/drawable")
        ensure(f"{res}/drawable-xxxhdpi")
        ensure(f"{res}/mipmap-anydpi-v26")

        shutil.copy(f"{DESIGN}/bg-a.xml", f"{res}/drawable/ic_launcher_a_background.xml")
        shutil.copy(f"{DESIGN}/fg-a.png", f"{res}/drawable-xxxhdpi/ic_launcher_a_foreground.png")

        adaptive = ADAPTIVE.format(bg="ic_launcher_a_background", fg="ic_launcher_a_foreground")
        write(f"{res}/mipmap-anydpi-v26/ic_launcher.xml", adaptive)
        write(f"{res}/mipmap-anydpi-v26/ic_launcher_round.xml", adaptive)
        print(f"[{v}] 默认图标 → 方案 A")

    # ── 2. 可替换图标（B / C）：仅 v1.1.70 ──────────────────────────────
    res70 = f"{BASE}/v1.1.70/MoreCheck/app/src/main/res"
    for icon_id, bg_src, fg_src in (
        ("checklist", "bg-b.xml", "fg-b.png"),
        ("calendar", "bg-c.xml", "fg-c.png"),
    ):
        shutil.copy(f"{DESIGN}/{bg_src}", f"{res70}/drawable/ic_launcher_{icon_id}_background.xml")
        shutil.copy(f"{DESIGN}/{fg_src}", f"{res70}/drawable-xxxhdpi/ic_fore_{icon_id}.png")
        adaptive = ADAPTIVE.format(
            bg=f"ic_launcher_{icon_id}_background", fg=f"ic_fore_{icon_id}"
        )
        write(f"{res70}/mipmap-anydpi-v26/ic_launcher_{icon_id}.xml", adaptive)
        print(f"[v1.1.70] 可替换图标 → {icon_id}")


if __name__ == "__main__":
    main()
