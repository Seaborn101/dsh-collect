#!/usr/bin/env python3
"""「DSH 收集」App 收藏文章的小型读写库。

只依赖 Python 标准库，可在手机 PRoot 环境（Ubuntu 24.04 / Python 3.12）直接运行。

主要能力：
    read_articles(dir)        读 articles.jsonl（没有就回退解析 *.md 的 front matter）
    read_markdown(path)       解析单个 Markdown 的 front matter + 正文
    write_markdown(a, b, p)   按 App 的格式写回 Markdown（原子写）
    update_jsonl(dir, list)   原子写回 articles.jsonl

App 侧的对应实现见 app/src/main/java/com/dsh/collect/Store.java，
两边的 front matter 字段与顺序保持一致，改动请两边同步。
"""

from __future__ import annotations

import json
import os
import re
import tempfile
from typing import Any, Dict, Iterable, List

__all__ = [
    "FRONT_MATTER_KEYS",
    "PLACEHOLDER_COMMENT",
    "default_body",
    "read_articles",
    "read_markdown",
    "update_jsonl",
    "write_markdown",
]

# front matter 里允许出现的字段，顺序即写出顺序（与 App 的 Store.save 一致）
FRONT_MATTER_KEYS = (
    "title",
    "account",
    "url",
    "source",
    "collected",
    "tags",
    "status",
    "summary",
)

# 正文末尾的占位注释，和 App 写出来的完全一致
PLACEHOLDER_COMMENT = "<!-- 原文正文（可由 dsh-go agent 抓取后写入此处） -->"

# 单行 key: value
_FM_LINE = re.compile(r"^([A-Za-z_][A-Za-z0-9_-]*)\s*:\s*(.*)$")

# "2026-11-15 18:19" -> "2026-11-15T18:19:00"
_COLLECTED_RE = re.compile(r"^(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2})(:\d{2})?$")


# ---------------------------------------------------------------- 读


def read_markdown(path: str) -> Dict[str, Any]:
    """解析一个 Markdown 文件。

    返回字典包含 FRONT_MATTER_KEYS 里的全部字段（缺失为空字符串），
    另外附带 "body"（正文）和 "file"（文件名）。
    """
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        text = f.read()
    if text.startswith("\ufeff"):
        text = text[1:]

    lines = text.splitlines()
    meta: Dict[str, Any] = {}
    body = text
    if lines and lines[0].strip() == "---":
        end = None
        for idx in range(1, len(lines)):
            line = lines[idx].strip()
            if line == "---":
                end = idx
                break
            m = _FM_LINE.match(line)
            if not m:
                continue
            key = m.group(1).lower()
            if key in FRONT_MATTER_KEYS:
                meta[key] = _unquote(m.group(2).strip())
        if end is None:
            body = "\n".join(lines[1:])
        else:
            body = "\n".join(lines[end + 1:])

    result: Dict[str, Any] = {key: meta.get(key, "") for key in FRONT_MATTER_KEYS}
    result["body"] = body.strip("\n")
    result["file"] = os.path.basename(path)
    return result


def read_articles(collected_dir: str) -> List[Dict[str, Any]]:
    """读取收藏目录里的文章列表。

    优先读 articles.jsonl；如果该文件不存在或为空，回退扫描目录里的 *.md，
    用 front matter 组装出与 jsonl 同构的记录。
    """
    articles: List[Dict[str, Any]] = []
    jsonl = os.path.join(collected_dir, "articles.jsonl")
    if os.path.isfile(jsonl):
        articles = _read_jsonl(jsonl)
        if articles:
            return articles

    if not os.path.isdir(collected_dir):
        return []

    for name in sorted(os.listdir(collected_dir)):
        if not name.endswith(".md"):
            continue
        path = os.path.join(collected_dir, name)
        try:
            md = read_markdown(path)
        except OSError:
            continue
        articles.append(
            {
                "url": md.get("url", ""),
                "title": md.get("title", ""),
                "account": md.get("account", ""),
                "collected_at": _iso_from_collected(md.get("collected", "")),
                "tags": md.get("tags", ""),
                "status": md.get("status", "") or "new",
                "file": name,
                "summary": md.get("summary", ""),
            }
        )
    return articles


# ---------------------------------------------------------------- 写


def write_markdown(article: Dict[str, Any], body: str, path: str) -> str:
    """把一篇文章写回 Markdown。

    article: 至少包含 title/account/url/source/collected/tags/status/summary
    body:    正文纯文本；为空时自动生成标题块
    path:    目标文件路径

    写完后正文末尾一定留有 PLACEHOLDER_COMMENT 占位说明。
    """
    fm_lines: List[str] = []
    for key in FRONT_MATTER_KEYS:
        value = article.get(key, "")
        if value is None:
            value = ""
        if isinstance(value, (list, tuple, set)):
            value = " ".join(str(v) for v in value)
        value = str(value).replace("\r", " ").replace("\n", " ").strip()
        fm_lines.append("%s: %s" % (key, value))

    content = (body or "").strip("\n")
    if not content:
        content = default_body(article)
    if PLACEHOLDER_COMMENT not in content:
        content = content.rstrip() + "\n\n" + PLACEHOLDER_COMMENT
    content = content.rstrip() + "\n"

    text = "---\n" + "\n".join(fm_lines) + "\n---\n\n" + content
    _atomic_write_text(path, text)
    return path


def update_jsonl(collected_dir: str, articles: Iterable[Dict[str, Any]]) -> str:
    """原子写回 articles.jsonl（先写同目录临时文件，再 rename）。"""
    os.makedirs(collected_dir, exist_ok=True)
    path = os.path.join(collected_dir, "articles.jsonl")
    lines = []
    for item in articles:
        lines.append(json.dumps(item, ensure_ascii=False))
    text = "\n".join(lines)
    if lines:
        text += "\n"
    _atomic_write_text(path, text)
    return path


def default_body(article: Dict[str, Any]) -> str:
    """生成正文开头的标题块（App 里那段 "> 来源 / > 链接 / > 收藏于"）。"""
    title = str(article.get("title") or "").strip() or "未命名文章"
    account = str(article.get("account") or "").strip() or "未知公众号"
    url = str(article.get("url") or "").strip()
    collected = str(
        article.get("collected") or article.get("collected_at") or ""
    ).strip()
    lines = [
        "# %s" % title,
        "",
        "> 来源：%s" % account,
        "> 链接：%s" % url,
    ]
    if collected:
        lines.append("> 收藏于 %s" % collected)
    lines.append("")
    return "\n".join(lines)


# ---------------------------------------------------------------- 内部工具


def _read_jsonl(path: str) -> List[Dict[str, Any]]:
    """读 jsonl，跳过空行和坏行。"""
    out: List[Dict[str, Any]] = []
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                obj = json.loads(line)
            except ValueError:
                continue
            if not isinstance(obj, dict):
                continue
            obj.setdefault("file", "")
            obj.setdefault("tags", "")
            obj.setdefault("status", "new")
            obj.setdefault("summary", "")
            obj.setdefault("collected_at", "")
            out.append(obj)
    return out


def _unquote(value: str) -> str:
    """去掉一层引号。"""
    if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
        return value[1:-1]
    return value


def _iso_from_collected(value: str) -> str:
    """把 "2026-11-15 18:19" 转成 ISO 字符串；已经是 ISO 就原样返回。"""
    value = (value or "").strip()
    if not value:
        return ""
    m = _COLLECTED_RE.match(value)
    if m:
        return "%sT%s%s" % (m.group(1), m.group(2), m.group(3) or ":00")
    return value


def _atomic_write_text(path: str, text: str) -> None:
    """原子写：同目录临时文件 + os.replace。"""
    directory = os.path.dirname(os.path.abspath(path)) or "."
    os.makedirs(directory, exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix=".tmp_", suffix=".part", dir=directory)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
            f.flush()
            os.fsync(f.fileno())
    except Exception:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise
    try:
        os.replace(tmp, path)
    except OSError:
        # 某些 Android 存储挂载不支持 rename，退化成直接写
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
        try:
            os.unlink(tmp)
        except OSError:
            pass


if __name__ == "__main__":
    # 简单的自检：打印目录里的文章列表
    import sys

    target = sys.argv[1] if len(sys.argv) > 1 else "/sdcard/Download/DSH-Go/collected"
    items = read_articles(target)
    print("目录 %s：%d 篇" % (target, len(items)))
    for item in items:
        print("- %s | %s | %s" % (item.get("title", ""), item.get("account", ""), item.get("url", "")))
