#!/usr/bin/env python3
"""素材 → 知识库 的 L1 / L2 管线。

分工：
  脚本做机械活（切分、抓正文、拼格式、写文件、回写状态）
  AI 做判断活（写摘要、抽要点、给标签、判分类、写综述）

L1：每篇素材 → 一份初加工笔记（去口语、留干货、抽要点、可复用结论）
    产出到  Obsidian/微信素材库/L1/<分类>/<标题>.md
L2：跨篇主题综述（不定期）
    产出到  Obsidian/微信素材库/L2/<主题>.md

用法：
    python3 kb_pipeline.py l1-prepare [--limit 3]
    python3 kb_pipeline.py l1-apply
    python3 kb_pipeline.py l2-prepare [--min-notes 2]
    python3 kb_pipeline.py l2-apply
    python3 kb_pipeline.py status
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import sys
import time
from typing import Any, Dict, List, Optional

COLLECTED_DIR = "/sdcard/Download/DSH-Go/collected"
INBOX_DIR = "/sdcard/Download/DSH-Go/inbox"
VAULT_DIR = "/sdcard/Documents/Obsidian/微信素材库"
L1_DIR = os.path.join(VAULT_DIR, "L1")
L2_DIR = os.path.join(VAULT_DIR, "L2")

# L1 加工要求（写进任务包，AI 按这个标准产出）
L1_SPEC = """## 加工要求（请按此产出 .meta.json）

这是一次「初加工」，不是给人读的读后感，是给未来的 AI 和你自己回看时用的**结构化知识**。

- `summary`：一句话说清这篇讲了什么（≤ 60 字，不要「本文介绍了」这种废话开头）
- `key_points`：3–6 条**干货**要点，每条一行，去掉口语、案例细节、重复表述；保留具体数字、方法、结论
- `takeaways`：**可复用的结论/做法**，脱离原文也能看懂，2–4 条；没有就留空数组
- `quote`：全文最值得记的一句原文（≤ 120 字），没有就留空
- `tags`：3–6 个标签，用来做主题聚类与后续检索，英文或短词（如 DuckDB、CLI、RAG）
- `category`：一个分类目录名，沿用已有分类（编程工具 / AI Agent / 行业与商业 / 效率与工具），不合适就自拟一个
- `note`：可选，你（AI）的一句话点评——这篇值不值得长期记住，为什么
- `action`：`archive` 表示值得入库；`skip` 表示质量太低不值得留

判断标准：读者半年后回看，能不能靠这一页想起来「哦，那个关于 X 的结论是 Y」。不能，就别写进要点。
"""


def eprint(*a: Any) -> None:
    print(*a, file=sys.stderr)


def ensure_dir(p: str) -> str:
    os.makedirs(p, exist_ok=True)
    return p


def slug(text: str, limit: int = 60) -> str:
    s = re.sub(r"[\\/:*?\"<>|\r\n\t]", "", text or "").strip()
    s = re.sub(r"\s+", " ", s)[:limit].strip()
    return s or "untitled"


def load_jsonl(path: str) -> List[Dict[str, Any]]:
    out: List[Dict[str, Any]] = []
    if not os.path.exists(path):
        return out
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                out.append(json.loads(line))
            except Exception:
                continue
    return out


def save_jsonl(path: str, items: List[Dict[str, Any]]) -> None:
    ensure_dir(os.path.dirname(path))
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        for it in items:
            f.write(json.dumps(it, ensure_ascii=False) + "\n")
    os.replace(tmp, path)


def read_md(path: str) -> Dict[str, Any]:
    """读 Markdown：返回 front matter 字段 + body"""
    meta: Dict[str, Any] = {}
    body = ""
    try:
        with open(path, encoding="utf-8") as f:
            text = f.read()
    except Exception:
        return {"body": "", "meta": meta}
    parts = text.split("---", 2)
    if len(parts) >= 3:
        head = parts[1]
        body = parts[2]
        for line in head.splitlines():
            line = line.rstrip()
            if not line.strip():
                continue
            if ":" not in line:
                continue
            k, v = line.split(":", 1)
            k = k.strip()
            v = v.strip()
            if k == "tags":
                if v.startswith("[") and v.endswith("]"):
                    meta[k] = [t.strip().strip("'\"") for t in v[1:-1].split(",") if t.strip()]
                else:
                    meta[k] = []  # 列表形式在下面单独处理
            elif v:
                meta[k] = v
        # tags 列表形式
        m = re.search(r"^tags:\s*$", head, re.M)
        if m:
            tail = head[m.end():]
            tags = re.findall(r"^\s*-\s*(.+)$", tail, re.M)
            if tags:
                meta["tags"] = [t.strip() for t in tags]
    else:
        body = text
    return {"meta": meta, "body": body.strip()}


def material_body(item: Dict[str, Any], collected: str) -> str:
    """取素材正文：优先 md 文件，其次按 url 在目录里找，最后才用 summary"""
    fn = item.get("file") or ""
    if fn:
        p = os.path.join(collected, fn)
        if os.path.exists(p):
            b = read_md(p)["body"]
            if len(b) > 120:
                return b
    url = item.get("url") or ""
    if url:
        best = ""
        try:
            for f in sorted(os.listdir(collected)):
                if not f.endswith(".md"):
                    continue
                p = os.path.join(collected, f)
                r = read_md(p)
                if r["meta"].get("url") == url and len(r["body"]) > len(best):
                    best = r["body"]
        except Exception:
            pass
        if len(best) > 120:
            return best
    return item.get("summary") or ""


def signal_of(item: Dict[str, Any]) -> str:
    if item.get("starred"):
        return "high"
    if item.get("status") == "archived":
        return "low"
    return "neutral"


# ------------------------------------------------------------ L1

def l1_prepare(args: argparse.Namespace) -> int:
    collected = args.collected
    items = load_jsonl(os.path.join(collected, "articles.jsonl"))
    inbox = ensure_dir(os.path.join(args.inbox, "l1"))

    todo = [it for it in items if it.get("layer") != "L1" and it.get("status") != "processed"]
    if args.limit:
        todo = todo[: args.limit]
    if not todo:
        print("没有待加工素材。")
        return 0

    made = 0
    for i, it in enumerate(todo, 1):
        title = it.get("title") or ""
        url = it.get("url") or ""
        body = material_body(it, collected)
        base = "%02d_%s" % (i, slug(title))
        md_path = os.path.join(inbox, base + ".md")
        meta_path = os.path.join(inbox, base + ".meta.json")

        sig = signal_of(it)
        header = [
            "---",
            "title: %s" % title,
            "account: %s" % (it.get("account") or ""),
            "url: %s" % url,
            "collected: %s" % (it.get("collected_at") or ""),
            "signal: %s" % sig,
            "chars: %d" % len(body),
            "---",
            "",
            "# %s" % title,
            "",
            "公众号：%s" % (it.get("account") or "未知"),
            "链接：%s" % url,
            "收藏时间：%s" % (it.get("collected_at") or ""),
            "人工信号：%s（high=你标星，low=你归档，neutral=仅收藏）" % sig,
            "",
            L1_SPEC,
            "## 原文（待加工素材）",
            "",
            body,
            "",
        ]
        with open(md_path, "w", encoding="utf-8") as f:
            f.write("\n".join(header))

        meta = {
            "url": url,
            "title": title,
            "account": it.get("account") or "",
            "signal": sig,
            "source_md": md_path,
            "action": "archive",
            "summary": "",
            "key_points": [],
            "takeaways": [],
            "quote": "",
            "tags": [],
            "category": "",
            "note": "",
        }
        with open(meta_path, "w", encoding="utf-8") as f:
            json.dump(meta, f, ensure_ascii=False, indent=2)
        made += 1

    print("已生成 %d 个 L1 任务包 → %s" % (made, inbox))
    print("下一步：让 AI 填写同名 .meta.json，再执行 l1-apply。")
    return 0


def l1_apply(args: argparse.Namespace) -> int:
    inbox = os.path.join(args.inbox, "l1")
    if not os.path.isdir(inbox):
        print("inbox/l1 不存在")
        return 1
    ensure_dir(L1_DIR)

    collected = args.collected
    items = load_jsonl(os.path.join(collected, "articles.jsonl"))
    by_url = {it.get("url"): it for it in items}

    done = 0
    for name in sorted(os.listdir(inbox)):
        if not name.endswith(".meta.json"):
            continue
        mp = os.path.join(inbox, name)
        try:
            with open(mp, encoding="utf-8") as f:
                meta = json.load(f)
        except Exception as e:
            eprint("跳过 %s：%s" % (name, e))
            continue
        if meta.get("action") != "archive":
            _rm(mp)
            _rm(os.path.join(inbox, name.replace(".meta.json", ".md")))
            print("skip %s" % meta.get("title", name))
            continue

        title = meta.get("title") or "untitled"
        url = meta.get("url") or ""
        account = meta.get("account") or ""
        summary = (meta.get("summary") or "").strip()
        points = meta.get("key_points") or []
        takeaways = meta.get("takeaways") or []
        quote = (meta.get("quote") or "").strip()
        tags = meta.get("tags") or []
        if isinstance(tags, str):
            tags = [t.strip() for t in tags.replace("，", ",").split(",") if t.strip()]
        category = (meta.get("category") or "未分类").strip() or "未分类"
        signal = meta.get("signal") or "neutral"

        src_md = meta.get("source_md") or os.path.join(inbox, name.replace(".meta.json", ".md"))
        raw = ""
        src_meta: Dict[str, Any] = {}
        if os.path.exists(src_md):
            r = read_md(src_md)
            raw = r["body"]
            src_meta = r["meta"]

        d = ensure_dir(os.path.join(L1_DIR, category))
        note_path = os.path.join(d, slug(title) + ".md")

        lines = ["---"]
        lines.append("title: %s" % title)
        lines.append("layer: L1")
        lines.append("category: %s" % category)
        lines.append("account: %s" % account)
        if src_meta.get("author"):
            lines.append("author: %s" % src_meta["author"])
        if src_meta.get("published"):
            lines.append("published: %s" % src_meta["published"])
        lines.append("url: %s" % url)
        lines.append("collected: %s" % time.strftime("%Y-%m-%d %H:%M"))
        lines.append("signal: %s" % signal)
        lines.append("tags: [%s]" % ", ".join(tags))
        lines.append("summary: %s" % summary.replace("\n", " "))
        lines.append("---")
        lines.append("")
        lines.append("# %s" % title)
        lines.append("")
        if summary:
            lines.append("> [!abstract] 一句话")
            lines.append("> %s" % summary.replace("\n", " "))
            lines.append("")
        if points:
            lines.append("## 要点")
            lines.append("")
            for p in points:
                lines.append("- %s" % str(p).strip())
            lines.append("")
        if takeaways:
            lines.append("## 可复用的结论")
            lines.append("")
            for t in takeaways:
                lines.append("- %s" % str(t).strip())
            lines.append("")
        if quote:
            lines.append("## 原文金句")
            lines.append("")
            lines.append("> %s" % quote)
            lines.append("")
        if meta.get("note"):
            lines.append("> [!note] 点评")
            lines.append("> %s" % str(meta["note"]).replace("\n", " "))
            lines.append("")
        lines.append("## 原文")
        lines.append("")
        lines.append(raw)
        lines.append("")

        with open(note_path, "w", encoding="utf-8") as f:
            f.write("\n".join(lines))

        it = by_url.get(url)
        if it is not None:
            it["layer"] = "L1"
            it["status"] = "processed"
            it["category"] = category
            it["tags"] = " ".join(tags)
            it["summary"] = summary
            it["l1_path"] = note_path
            it["signal"] = signal
            fn = it.get("file")
            if fn:
                src = os.path.join(collected, fn)
                if os.path.exists(src):
                    arch = ensure_dir(os.path.join(collected, "archived"))
                    try:
                        shutil.move(src, os.path.join(arch, fn))
                    except Exception:
                        pass
        _rm(mp)
        _rm(os.path.join(inbox, name.replace(".meta.json", ".md")))
        done += 1
        print("L1 → %s" % note_path)

    if done:
        save_jsonl(os.path.join(collected, "articles.jsonl"), items)
    print("共加工 %d 篇。" % done)
    return 0


# ------------------------------------------------------------ L2

def collect_l1_notes(vault: str) -> List[Dict[str, Any]]:
    out = []
    l1 = os.path.join(vault, "L1")
    for root, _dirs, files in os.walk(l1):
        for fn in files:
            if not fn.endswith(".md"):
                continue
            p = os.path.join(root, fn)
            r = read_md(p)
            m = r["meta"]
            out.append({
                "path": p,
                "title": m.get("title", fn[:-3]),
                "category": m.get("category", ""),
                "account": m.get("account", ""),
                "url": m.get("url", ""),
                "summary": m.get("summary", ""),
                "tags": m.get("tags", []) if isinstance(m.get("tags"), list) else [],
                "body": r["body"],
            })
    return out


def l2_prepare(args: argparse.Namespace) -> int:
    notes = collect_l1_notes(args.vault)
    if not notes:
        print("L1 还没有笔记，先跑 l1-prepare / l1-apply。")
        return 0

    groups: Dict[str, List[Dict[str, Any]]] = {}
    for n in notes:
        key = n["category"] or "未分类"
        groups.setdefault(key, []).append(n)

    inbox = ensure_dir(os.path.join(args.inbox, "l2"))
    made = 0
    for theme, ns in sorted(groups.items()):
        if len(ns) < args.min_notes:
            continue
        base = slug(theme)
        md_path = os.path.join(inbox, base + ".md")
        meta_path = os.path.join(inbox, base + ".meta.json")
        lines = [
            "---",
            "theme: %s" % theme,
            "notes: %d" % len(ns),
            "---",
            "",
            "# 主题综述：%s" % theme,
            "",
            "## 素材清单",
            "",
        ]
        for i, n in enumerate(ns, 1):
            lines.append("%d. **%s**（%s，%s）" % (i, n["title"], n["account"] or "未知公众号", n["url"]))
            if n["summary"]:
                lines.append("   - 摘要：%s" % n["summary"])
            tags = n["tags"] if isinstance(n["tags"], list) else []
            if tags:
                lines.append("   - 标签：%s" % ", ".join(tags))
        lines += [
            "",
            "## 综述要求（请按此产出 .meta.json）",
            "",
            "- `title`：主题标题",
            "- `summary`：这个主题下，这批文章合起来说明了什么（2–4 句）",
            "- `consensus`：**共识**——多篇都指向的结论（列表）",
            "- `tensions`：**分歧/边界条件**——互相矛盾或前提不同的地方（列表）",
            "- `playbook`：**可执行的做法**——看完能直接照做的步骤或检查清单（列表）",
            "- `open_questions`：还没被这批文章回答的问题（列表）",
            "- `tags`：这个主题的标签",
            "- `note`：可选点评",
            "",
            "## 素材正文（供你归纳）",
            "",
        ]
        for n in ns:
            lines.append("### %s" % n["title"])
            lines.append("")
            lines.append(n["body"][:6000])
            lines.append("")
        with open(md_path, "w", encoding="utf-8") as f:
            f.write("\n".join(lines))
        meta = {
            "theme": theme,
            "title": theme,
            "summary": "",
            "consensus": [],
            "tensions": [],
            "playbook": [],
            "open_questions": [],
            "tags": [],
            "note": "",
            "action": "archive",
            "source_md": md_path,
        }
        with open(meta_path, "w", encoding="utf-8") as f:
            json.dump(meta, f, ensure_ascii=False, indent=2)
        made += 1
    print("已生成 %d 个 L2 综述任务包 → %s" % (made, inbox))
    return 0


def l2_apply(args: argparse.Namespace) -> int:
    inbox = os.path.join(args.inbox, "l2")
    if not os.path.isdir(inbox):
        print("inbox/l2 不存在")
        return 1
    ensure_dir(L2_DIR)
    done = 0
    for name in sorted(os.listdir(inbox)):
        if not name.endswith(".meta.json"):
            continue
        mp = os.path.join(inbox, name)
        try:
            with open(mp, encoding="utf-8") as f:
                meta = json.load(f)
        except Exception as e:
            eprint("跳过 %s：%s" % (name, e))
            continue
        if meta.get("action") != "archive":
            _rm(mp)
            continue
        title = meta.get("title") or meta.get("theme") or "untitled"
        tags = meta.get("tags") or []
        if isinstance(tags, str):
            tags = [t.strip() for t in tags.replace("，", ",").split(",") if t.strip()]
        path = os.path.join(L2_DIR, slug(title) + ".md")
        lines = ["---"]
        lines.append("title: %s" % title)
        lines.append("layer: L2")
        lines.append("category: %s" % (meta.get("theme") or ""))
        lines.append("collected: %s" % time.strftime("%Y-%m-%d %H:%M"))
        lines.append("tags: [%s]" % ", ".join(tags))
        lines.append("summary: %s" % (meta.get("summary") or "").replace("\n", " "))
        lines.append("---")
        lines.append("")
        lines.append("# %s" % title)
        lines.append("")
        if meta.get("summary"):
            lines.append("> [!abstract] 这个主题说明白了什么")
            lines.append("> %s" % str(meta["summary"]).replace("\n", " "))
            lines.append("")
        for key, head in (("consensus", "共识"), ("tensions", "分歧与边界"),
                          ("playbook", "可执行的做法"), ("open_questions", "还没回答的问题")):
            vals = meta.get(key) or []
            if vals:
                lines.append("## %s" % head)
                lines.append("")
                for v in vals:
                    lines.append("- %s" % str(v).strip())
                lines.append("")
        if meta.get("note"):
            lines.append("> [!note] 点评")
            lines.append("> %s" % str(meta["note"]).replace("\n", " "))
            lines.append("")
        src = meta.get("source_md")
        if src and os.path.exists(src):
            r = read_md(src)
            lines.append("## 素材清单")
            lines.append("")
            head = r["body"].split("## 综述要求")[0]
            lines.append(head.strip())
            lines.append("")
        with open(path, "w", encoding="utf-8") as f:
            f.write("\n".join(lines))
        _rm(mp)
        _rm(os.path.join(inbox, name.replace(".meta.json", ".md")))
        done += 1
        print("L2 → %s" % path)
    print("共生成 %d 篇综述。" % done)
    return 0


# ------------------------------------------------------------ 其它

def cmd_status(args: argparse.Namespace) -> int:
    items = load_jsonl(os.path.join(args.collected, "articles.jsonl"))
    print("素材总数：%d" % len(items))
    by_layer: Dict[str, int] = {}
    for it in items:
        by_layer[it.get("layer", "L0")] = by_layer.get(it.get("layer", "L0"), 0) + 1
    for k, v in sorted(by_layer.items()):
        print("  %-6s %d" % (k, v))
    for stage in ("l1", "l2"):
        d = os.path.join(args.inbox, stage)
        metas = [f for f in os.listdir(d) if f.endswith(".meta.json")] if os.path.isdir(d) else []
        print("待处理 %s 任务包：%d" % (stage.upper(), len(metas)))
    for d, label in ((L1_DIR, "L1 笔记"), (L2_DIR, "L2 综述")):
        n = 0
        if os.path.isdir(d):
            for _root, _dirs, files in os.walk(d):
                n += len([f for f in files if f.endswith(".md")])
        print("%s：%d" % (label, n))
    return 0


def _rm(p: str) -> None:
    try:
        os.remove(p)
    except Exception:
        pass


def main() -> int:
    ap = argparse.ArgumentParser(description="素材 → 知识库（L1/L2）")
    ap.add_argument("action",
                    choices=["l1-prepare", "l1-apply", "l2-prepare", "l2-apply", "status"])
    ap.add_argument("--collected", default=COLLECTED_DIR)
    ap.add_argument("--inbox", default=INBOX_DIR)
    ap.add_argument("--vault", default=VAULT_DIR)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--min-notes", type=int, default=2)
    args = ap.parse_args()

    if args.action == "l1-prepare":
        return l1_prepare(args)
    if args.action == "l1-apply":
        return l1_apply(args)
    if args.action == "l2-prepare":
        return l2_prepare(args)
    if args.action == "l2-apply":
        return l2_apply(args)
    return cmd_status(args)


if __name__ == "__main__":
    sys.exit(main())
