#!/usr/bin/env python3
"""列出 DSH 收集 App 收藏的公众号文章。

用法:
    python3 list_collected.py            # 列出全部
    python3 list_collected.py --json     # 机器可读
"""
import argparse
import json
import os
import re
import sys

COLLECT_DIR = "/sdcard/Download/DSH-Go/collected"
FALLBACK_DIR = "/root/Android/data/com.dsh.collect/files/collected"

FM = re.compile(r"^(title|account|url|collected):\s*(.*)$")


def pick_dir():
    if os.path.isdir(COLLECT_DIR):
        return COLLECT_DIR
    if os.path.isdir(FALLBACK_DIR):
        return FALLBACK_DIR
    return COLLECT_DIR


def parse(path):
    item = {"file": os.path.basename(path)}
    try:
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.rstrip("\n")
                if line.strip() == "---":
                    break
                m = FM.match(line)
                if m:
                    item[m.group(1)] = m.group(2).strip()
    except Exception as e:  # noqa
        item["error"] = str(e)
    return item


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--dir", default=None)
    args = ap.parse_args()

    d = args.dir or pick_dir()
    if not os.path.isdir(d):
        print("收藏目录不存在: %s" % d)
        print("可能还没安装 App，或还没点过「用DSH收集打开」。")
        return 0

    files = sorted(
        (os.path.join(d, f) for f in os.listdir(d) if f.endswith(".md")),
        reverse=True,
    )
    items = [parse(p) for p in files]

    if args.json:
        print(json.dumps({"dir": d, "items": items}, ensure_ascii=False, indent=2))
        return 0

    print("收藏目录: %s" % d)
    print("共 %d 篇\n" % len(items))
    for i, it in enumerate(items, 1):
        print("%2d. %s" % (i, it.get("title", "(无标题)")))
        print("    %s · %s" % (it.get("account", "?"), it.get("collected", "?")))
        print("    %s" % it.get("url", ""))
    log = os.path.join(d, "_intents.log")
    if os.path.exists(log):
        print("\n--- 最近收到的 intent ---")
        with open(log, encoding="utf-8") as f:
            for line in f.readlines()[-10:]:
                print(line.rstrip())
    return 0


if __name__ == "__main__":
    sys.exit(main())
