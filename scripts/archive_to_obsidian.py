#!/usr/bin/env python3
"""把「DSH 收集」App 收藏的公众号文章归档进 Obsidian 库。

分两步，人和 AI 各干自己擅长的事：

1. prepare —— 机械活（脚本做）
   读 /sdcard/Download/DSH-Go/collected/articles.jsonl（或 inbox/*.md），
   把待处理文章整理成「任务包」放进 /sdcard/Download/DSH-Go/inbox/：
       <序号>_<标题>.md        正文 + 元数据
       <序号>_<标题>.meta.json 待 AI 填写的小 JSON

2. apply —— 落库（脚本做）
   读 inbox/*.meta.json，按 AI 给的 summary / tags / category 写进 Obsidian：
       /sdcard/Documents/Obsidian/网页收藏/微信文章/<分类>/<标题>.md
   front matter 沿用库里已有笔记的约定：
       author / source / url / saved / tags / id
   然后回写 articles.jsonl（status=archived, vault_path=...），并把源文件移到
   /sdcard/Download/DSH-Go/collected/archived/

用法：
    python3 archive_to_obsidian.py prepare [--limit 5]
    python3 archive_to_obsidian.py apply
    python3 archive_to_obsidian.py status
"""
import argparse
import json
import os
import re
import shutil
import sys
import time
import uuid
from typing import Any, Dict, List

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
try:
    import collect_lib
except ImportError:  # pragma: no cover
    collect_lib = None  # type: ignore

COLLECTED_DIR = "/sdcard/Download/DSH-Go/collected"
INBOX_DIR = "/sdcard/Download/DSH-Go/inbox"
ARCHIVED_DIR = "/sdcard/Download/DSH-Go/collected/archived"
VAULT_ROOT = "/sdcard/Documents/Obsidian/网页收藏"
WECHAT_SUBDIR = "微信文章"

# 分类规则：关键词 -> 目录名（按顺序匹配，第一个命中即用）
CATEGORY_RULES = [
    ("编程工具", ["编程", "开发", "代码", "CLI", "sdk", "SDK", "开源", "github", "GitHub",
                  "python", "rust", "go ", "golang", "java", "前端", "后端", "数据库",
                  "docker", "k8s", "架构", "编译器", "debug"]),
    ("AI Agent", ["agent", "Agent", "智能体", "LLM", "大模型", "提示词", "prompt",
                  "Prompt", "RAG", "MCP", "claude", "Claude", "GPT", "openai", "OpenAI",
                  "gemini", "Gemini", "copilot", "Copilot", "Cursor", "DeepSeek",
                  "微调", "推理模型", "多模态"]),
    ("行业与商业", ["融资", "估值", "上市", "商业", "市场", "增长", "创业", "公司",
                    "裁员", "招聘", "财报", "用户量"]),
    ("效率与工具", ["效率", "工具", "工作流", "自动化", "笔记", "知识管理", "插件"]),
]


def eprint(*args: Any) -> None:
    print(*args, file=sys.stderr)


def ensure_dir(path: str) -> str:
    os.makedirs(path, exist_ok=True)
    return path


def load_articles(collected_dir: str) -> List[Dict[str, Any]]:
    if collect_lib:
        return collect_lib.read_articles(collected_dir)
    # 兜底：极简 jsonl 解析
    out = []
    p = os.path.join(collected_dir, "articles.jsonl")
    if os.path.exists(p):
        with open(p, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    out.append(json.loads(line))
                except Exception:
                    pass
    return out


def save_articles(collected_dir: str, articles: List[Dict[str, Any]]) -> None:
    if collect_lib:
        collect_lib.update_jsonl(collected_dir, articles)
        return
    p = os.path.join(collected_dir, "articles.jsonl")
    tmp = p + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        for a in articles:
            f.write(json.dumps(a, ensure_ascii=False) + "\n")
    os.replace(tmp, p)


def classify(text: str) -> str:
    low = (text or "").lower()
    for name, keys in CATEGORY_RULES:
        for k in keys:
            if k.lower() in low:
                return name
    return "未分类"


def slug(text: str, fallback: str = "untitled") -> str:
    s = re.sub(r"[\\/:*?\"<>|\r\n\t]", "", text or "").strip()
    s = s[:80]
    return s or fallback


def fetch_body(url: str) -> str:
    """正文缺失时，用 fetch_article.py 抓一次（App 没抓到正文的旧数据用得上）"""
    if not url:
        return ""
    here = os.path.dirname(os.path.abspath(__file__))
    script = os.path.join(here, "fetch_article.py")
    if not os.path.exists(script):
        return ""
    try:
        import subprocess
        r = subprocess.run([sys.executable, script, url], capture_output=True, timeout=60)
        if r.returncode == 0:
            return r.stdout.decode("utf-8", "replace").strip()
        eprint("抓取正文失败（exit=%s）：%s" % (r.returncode, r.stderr.decode("utf-8", "replace")[:200]))
    except Exception as e:
        eprint("抓取正文异常：%s" % e)
    return ""


def read_body(md_path: str) -> str:
    """取 Markdown 里 --- 之后的正文"""
    try:
        with open(md_path, encoding="utf-8") as f:
            text = f.read()
    except Exception:
        return ""
    parts = text.split("---", 2)
    if len(parts) >= 3:
        return parts[2].strip()
    return text.strip()


def cmd_prepare(args: argparse.Namespace) -> int:
    collected = args.collected
    inbox = ensure_dir(args.inbox)
    articles = load_articles(collected)

    pending = [a for a in articles if a.get("status") not in ("archived",)]
    if args.limit:
        pending = pending[: args.limit]

    if not pending:
        print("没有待处理文章。")
        return 0

    made = 0
    for i, a in enumerate(pending, 1):
        url = a.get("url", "")
        title = a.get("title", "") or slug(url, "untitled")
        base = "%02d_%s" % (i, slug(title))
        md_path = os.path.join(inbox, base + ".md")
        meta_path = os.path.join(inbox, base + ".meta.json")

        body = ""
        src_md = os.path.join(collected, a.get("file", "") or "")
        if a.get("file") and os.path.exists(src_md):
            body = read_body(src_md)
        # 只有占位注释 / 太短 = 没正文，去抓一次
        if len(body.strip()) < 200 or "原文正文（可由" in body:
            fetched = fetch_body(url)
            if fetched:
                body = fetched
        if not body:
            body = a.get("summary", "") or ""

        article = {
            "title": title,
            "account": a.get("account", ""),
            "url": url,
            "source": "微信公众号",
            "collected": a.get("collected_at", ""),
            "tags": a.get("tags", ""),
            "status": a.get("status", "new"),
            "summary": a.get("summary", ""),
        }
        if collect_lib:
            collect_lib.write_markdown(article, body, md_path)
        else:
            with open(md_path, "w", encoding="utf-8") as f:
                f.write(body)

        # 待 AI 填的小 JSON
        meta = {
            "url": url,
            "title": title,
            "account": a.get("account", ""),
            "source_md": md_path,
            "category": classify(title + " " + (a.get("summary") or "") + " " + body[:2000]),
            "summary": "",          # ← AI 写：不超过 120 字的中文摘要
            "tags": [],             # ← AI 写：3-6 个标签
            "action": "archive",    # archive | skip
            "note": "",             # ← AI 写：可选点评
        }
        with open(meta_path, "w", encoding="utf-8") as f:
            json.dump(meta, f, ensure_ascii=False, indent=2)
        made += 1

    print("已生成 %d 个任务包 → %s" % (made, inbox))
    print("下一步：让 AI 填写同名的 .meta.json（summary / tags / category），再执行 apply。")
    return 0


def cmd_apply(args: argparse.Namespace) -> int:
    inbox = args.inbox
    if not os.path.isdir(inbox):
        print("inbox 不存在：%s" % inbox)
        return 1

    vault_root = ensure_dir(args.vault)
    target_base = os.path.join(vault_root, WECHAT_SUBDIR)
    ensure_dir(target_base)

    articles = load_articles(args.collected)
    by_url = {a.get("url"): a for a in articles}

    done = 0
    for name in sorted(os.listdir(inbox)):
        if not name.endswith(".meta.json"):
            continue
        meta_path = os.path.join(inbox, name)
        try:
            with open(meta_path, encoding="utf-8") as f:
                meta = json.load(f)
        except Exception as e:
            eprint("跳过无法解析的 %s：%s" % (name, e))
            continue

        if meta.get("action") != "archive":
            _drop(inbox, name)
            _drop(inbox, name.replace(".meta.json", ".md"))
            print("skip %s" % meta.get("title", name))
            continue

        src_md = meta.get("source_md") or os.path.join(inbox, name.replace(".meta.json", ".md"))
        body = read_body(src_md) if os.path.exists(src_md) else ""
        title = meta.get("title") or "untitled"
        account = meta.get("account") or ""
        url = meta.get("url") or ""
        summary = (meta.get("summary") or "").strip()
        tags = meta.get("tags") or []
        if isinstance(tags, str):
            tags = [t.strip() for t in tags.split() if t.strip()]
        category = (meta.get("category") or "未分类").strip() or "未分类"

        note_dir = ensure_dir(os.path.join(target_base, category))
        note_path = os.path.join(note_dir, slug(title) + ".md")

        fm = {
            "author": account,
            "source": "微信公众号",
            "url": url,
            "saved": time.strftime("%Y-%m-%d %H:%M:%S"),
            "tags": tags,
            "id": str(uuid.uuid4()),
        }
        lines = ["---"]
        for k in ("author", "source", "url", "saved"):
            lines.append("%s: %s" % (k, fm[k]))
        lines.append("tags:")
        for t in tags:
            lines.append("  - %s" % t)
        lines.append("id: %s" % fm["id"])
        lines.append("---")
        lines.append("")

        if summary:
            lines.append("> [!abstract] 摘要")
            lines.append("> " + summary.replace("\n", " "))
            lines.append("")
        if account:
            lines.append("公众号名称：%s" % account)
        lines.append("原文链接：%s" % url)
        if meta.get("note"):
            lines.append("")
            lines.append("> [!note] AI 点评")
            lines.append("> " + str(meta["note"]).replace("\n", " "))
        lines.append("")
        lines.append(body)
        lines.append("")

        with open(note_path, "w", encoding="utf-8") as f:
            f.write("\n".join(lines))

        # 回写状态 + 源文件归档
        a = by_url.get(url)
        if a is not None:
            a["status"] = "archived"
            a["vault_path"] = note_path
            a["tags"] = " ".join(tags)
            a["summary"] = summary
            a["category"] = category
        src_name = a.get("file") if a else None
        if src_name:
            src = os.path.join(args.collected, src_name)
            if os.path.exists(src):
                ensure_dir(ARCHIVED_DIR)
                try:
                    shutil.move(src, os.path.join(ARCHIVED_DIR, src_name))
                except Exception:
                    pass
        _drop(inbox, name)
        _drop(inbox, name.replace(".meta.json", ".md"))
        done += 1
        print("archived → %s" % note_path)

    if done:
        save_articles(args.collected, articles)
    print("共归档 %d 篇。" % done)
    return 0


def _drop(folder: str, name: str) -> None:
    p = os.path.join(folder, name)
    try:
        os.remove(p)
    except Exception:
        pass


def cmd_status(args: argparse.Namespace) -> int:
    articles = load_articles(args.collected)
    total = len(articles)
    by_status: Dict[str, int] = {}
    for a in articles:
        s = a.get("status", "new")
        by_status[s] = by_status.get(s, 0) + 1
    print("收藏总数：%d" % total)
    for k, v in sorted(by_status.items(), key=lambda kv: -kv[1]):
        print("  %-10s %d" % (k, v))
    inbox = args.inbox
    if os.path.isdir(inbox):
        metas = [f for f in os.listdir(inbox) if f.endswith(".meta.json")]
        print("待 AI 处理的任务包：%d" % len(metas))
        for m in metas:
            p = os.path.join(inbox, m)
            try:
                with open(p, encoding="utf-8") as f:
                    d = json.load(f)
                print("  - %s [%s] summary=%s" % (
                    d.get("title", "?"), d.get("category", "?"),
                    "有" if d.get("summary") else "待填"))
            except Exception:
                pass
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="DSH 收集 → Obsidian 归档")
    ap.add_argument("action", choices=["prepare", "apply", "status"])
    ap.add_argument("--collected", default=COLLECTED_DIR)
    ap.add_argument("--inbox", default=INBOX_DIR)
    ap.add_argument("--vault", default=VAULT_ROOT)
    ap.add_argument("--limit", type=int, default=0)
    args = ap.parse_args()

    if args.action == "prepare":
        return cmd_prepare(args)
    if args.action == "apply":
        return cmd_apply(args)
    return cmd_status(args)


if __name__ == "__main__":
    sys.exit(main())
