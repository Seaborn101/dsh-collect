#!/usr/bin/env python3
"""在知识库里做混合检索（语义 + 关键词），并把路径映射回 Obsidian 库。

用法：
    python3 scripts/kb_search.py "怎么防止 AI 把错误结论写进记忆库"
    python3 scripts/kb_search.py "token" --fts
    python3 scripts/kb_search.py "多 agent 协作" --vector --limit 5
    python3 scripts/kb_search.py "prometheus" --rg      # 精确/正则全文
"""
import argparse
import os
import subprocess
import sys

INDEX_DIR = "/root/workspace/dsh-collect/kb-index"
VAULT = "/sdcard/Documents/Obsidian/微信素材库"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("query", nargs="+")
    ap.add_argument("--mode", choices=["hybrid", "fts", "vector", "rg"], default="hybrid")
    ap.add_argument("--vector", action="store_true", help="等价于 --mode vector")
    ap.add_argument("--fts", action="store_true", help="等价于 --mode fts")
    ap.add_argument("--rg", action="store_true", help="等价于 --mode rg")
    ap.add_argument("--limit", type=int, default=5)
    ap.add_argument("--index-dir", default=INDEX_DIR)
    ap.add_argument("--vault", default=VAULT)
    args = ap.parse_args()

    if not os.path.isdir(args.index_dir):
        print("索引目录不存在，先跑 python3 scripts/kb_sync.py")
        return 1

    if args.vector:
        args.mode = "vector"
    elif args.fts:
        args.mode = "fts"
    elif args.rg:
        args.mode = "rg"

    q = " ".join(args.query)
    if args.mode == "rg":
        cmd = ["zg", "--rg", q]
    else:
        cmd = ["zg", "--" + args.mode, q]

    r = subprocess.run(cmd, cwd=args.index_dir, capture_output=True, text=True, timeout=300)
    out = (r.stdout or "") + (r.stderr or "")
    if r.returncode != 0:
        print(out.strip()[-600:])
        return 1

    # 路径映射：索引里的本地路径 → Obsidian 库路径
    out = out.replace(args.index_dir.rstrip("/") + "/", args.vault.rstrip("/") + "/")

    # 只保留前 N 条结果块（以 "#N " 开头）
    blocks = []
    cur = []
    for line in out.splitlines():
        if line.startswith("#") and line[1:2].isdigit():
            if cur:
                blocks.append("\n".join(cur))
            cur = [line]
        elif cur:
            cur.append(line)
    if cur:
        blocks.append("\n".join(cur))
    if blocks:
        print("\n\n".join(blocks[: args.limit]))
    else:
        print(out.strip()[:2000])
    return 0


if __name__ == "__main__":
    sys.exit(main())
