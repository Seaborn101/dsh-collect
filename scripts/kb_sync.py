#!/usr/bin/env python3
"""把 Obsidian 知识库同步到本地索引目录，并增量更新 zvec 混合索引。

为什么要快照：/sdcard 是 FUSE 挂载，不支持文件锁，
zvec 的索引存储（以及 SQLite）放上面会报 "acquire file lock: Function not implemented"。
所以索引放本地磁盘，用 rsync 快照保持内容同步。

用法：
    python3 scripts/kb_sync.py            # 同步 + 增量更新索引
    python3 scripts/kb_sync.py --rebuild  # 重建索引
"""
import argparse
import os
import shutil
import subprocess
import sys

VAULT = "/sdcard/Documents/Obsidian/微信素材库"
INDEX_DIR = "/root/workspace/dsh-collect/kb-index"


def sh(cmd, **kw):
    return subprocess.run(cmd, shell=isinstance(cmd, str), capture_output=True, text=True, **kw)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--vault", default=VAULT)
    ap.add_argument("--index-dir", default=INDEX_DIR)
    ap.add_argument("--rebuild", action="store_true")
    args = ap.parse_args()

    if not os.path.isdir(args.vault):
        print("知识库不存在：%s" % args.vault)
        return 1
    os.makedirs(args.index_dir, exist_ok=True)

    r = sh(["rsync", "-a", "--delete", args.vault + "/", args.index_dir + "/"])
    if r.returncode != 0:
        print("rsync 失败：%s" % r.stderr.strip()[:300])
        return 1

    n = sum(1 for root, _d, files in os.walk(args.index_dir)
            for f in files if f.endswith(".md"))
    print("已同步 %d 篇笔记 → %s" % (n, args.index_dir))

    cmd = ["zg", "--index", "."]
    if args.rebuild:
        cmd.append("--rebuild")
    r = sh(cmd, cwd=args.index_dir)
    out = (r.stdout or "") + (r.stderr or "")
    for line in out.splitlines():
        if any(k in line for k in ("files", "entities", "duration", "failed", "Error")):
            print(line.strip())
    if r.returncode != 0:
        print("索引更新失败：%s" % out.strip()[-400:])
        return 1
    print("索引已更新（本地嵌入模型，端侧推理）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
