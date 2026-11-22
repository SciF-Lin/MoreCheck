#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 ChangelogScreen.kt 的 RELEASES 解析版本日志，按区间生成 GitHub Release 正文。

区间映射（用户指定）：
  v1.1.2(不含) ~ v1.1.50(含)  ->  MoreCheck 1.1.50 Release
  v1.1.51 ~ v1.1.70           ->  MoreCheck 1.1.70 Release
  v1.1.71 ~ v1.1.90           ->  MoreCheck 1.1.90 Release
"""
import re
from pathlib import Path

SRC = Path("E:/download/app/MoreCheck/sources/v1.1.90/MoreCheck/app/src/main/java/com/morecheck/ui/settings/ChangelogScreen.kt")
OUT = Path("E:/download/app/MoreCheck/docs/release-notes")

text = SRC.read_text(encoding="utf-8")

# 抓出每个 ReleaseNote(...) 块
blocks = re.findall(r"ReleaseNote\(\s*version\s*=\s*\"([^\"]+)\",\s*date\s*=\s*\"([^\"]+)\",\s*isFormal\s*=\s*(true|false),\s*items\s*=\s*listOf\((.*?)\)\s*\)", text, re.S)

notes = []
for version, date, formal, body in blocks:
    items = re.findall(r"\"([^\"]+)\"", body)
    notes.append({"version": version, "date": date, "formal": formal == "true", "items": items})

def key(n):
    m = re.search(r"(\d+)\.(\d+)\.(\d+)", n["version"])
    return tuple(int(x) for x in m.groups())

notes.sort(key=key, reverse=True)

# 兜底句：每个版本都写会冗余，整段末尾统一放一条
BOILERPLATE = {
    "修复了若干细节问题，优化应用体验",
    "修复了若干细节问题",
    "修复了若干界面细节问题",
    "修复了已知问题",
    "修复了已知问题，优化了用户体验",
}

GROUPS = [
    {
        "file": "1.1.50.md",
        "title": "MoreCheck 1.1.50",
        "lo": (1, 1, 2), "hi": (1, 1, 50),
        "scope": "1.1.2 之后至 1.1.50（含）",
    },
    {
        "file": "1.1.70.md",
        "title": "MoreCheck 1.1.70",
        "lo": (1, 1, 50), "hi": (1, 1, 70),
        "scope": "1.1.51 至 1.1.70",
    },
    {
        "file": "1.1.90.md",
        "title": "MoreCheck 1.1.90",
        "lo": (1, 1, 70), "hi": (1, 1, 90),
        "scope": "1.1.71 至 1.1.90",
    },
]

OUT.mkdir(parents=True, exist_ok=True)

for g in GROUPS:
    lines = [f"# {g['title']}", "", f"> 本版本更新涵盖 {g['scope']} 期间的改动。", ""]
    selected = [n for n in notes if g["lo"] < key(n) <= g["hi"]]
    for n in selected:
        tag = "正式版" if n["formal"] else "预发布"
        lines.append(f"## {n['version']} · {tag}（{n['date']}）")
        lines.append("")
        kept = []
        for it in n["items"]:
            if it in BOILERPLATE and len(n["items"]) > 1:
                continue
            it = it.replace("“", "「").replace("”", "」")
            kept.append(it)
        for it in kept:
            lines.append(f"- {it}")
        lines.append("")
    lines.append("- 修复了若干细节问题，优化应用体验")
    lines.append("")
    (OUT / g["file"]).write_text("\n".join(lines), encoding="utf-8")
    print(f"{g['file']}: {len(selected)} versions, {sum(len(n['items']) for n in selected)} raw items")
