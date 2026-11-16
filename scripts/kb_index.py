#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""kb_index.py —— 微信素材库（Obsidian）→ DuckDB 检索层。

给 /root/workspace/dsh-collect 项目补上「索引 + 检索」这一层：
App 把公众号文章收集成 Markdown（L1 初加工笔记），AI 再汇总成 L2 主题综述，
全部放在 Obsidian 库里；本脚本把它们扫进一个 DuckDB 库，然后按关键词检索。

用法（三个子命令）：

    python3 kb_index.py build
    python3 kb_index.py search 关键词1 关键词2
    python3 kb_index.py stats

全局参数：

    --root DIR    知识库根目录（默认 /sdcard/Documents/Obsidian/微信素材库）
    --db PATH     duckdb 库文件（默认 /sdcard/Download/DSH-Go/kb/kb.duckdb）
    --limit N     search 返回条数（默认 5）
    --json        search 以 JSON 输出（方便给 agent 用）
    --fts         尝试使用 duckdb 的 fts 扩展（默认不用；没有它也完全正常）

依赖：只用标准库 + duckdb（手机端已自带 duckdb 2.0.0），不联网、不装扩展。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shlex
import sys
import time
from datetime import datetime

try:
    import duckdb
except ImportError:  # pragma: no cover
    sys.stderr.write("缺少 duckdb 模块（pip install duckdb）\n")
    raise SystemExit(1)

DEFAULT_ROOT = "/sdcard/Documents/Obsidian/微信素材库"
DEFAULT_DB = "/sdcard/Download/DSH-Go/kb/kb.duckdb"

# 这些目录不扫（Obsidian 自己的、回收站、版本库……）
SKIP_DIRS = {
    ".obsidian", ".trash", ".git", ".svn", ".hg",
    "node_modules", "__pycache__", ".smart-env", ".space",
}

# front matter 字段别名（键统一小写）
ALIASES = {
    "title": ("title", "标题"),
    "layer": ("layer", "层级", "level"),
    "category": ("category", "分类", "cat"),
    "account": ("account", "公众号", "公众号名称", "source_name", "public"),
    "author": ("author", "作者"),
    "url": ("url", "link", "链接", "原文链接"),
    "summary": ("summary", "摘要", "abstract", "desc"),
    "collected": ("collected", "collected_at", "date", "saved", "收集时间", "创建时间"),
    "tags": ("tags", "tag", "标签"),
}

DT_FORMATS = (
    "%Y-%m-%d %H:%M:%S",
    "%Y-%m-%d %H:%M",
    "%Y-%m-%d",
    "%Y/%m/%d %H:%M:%S",
    "%Y/%m/%d %H:%M",
    "%Y/%m/%d",
    "%Y年%m月%d日 %H:%M:%S",
    "%Y年%m月%d日 %H:%M",
    "%Y年%m月%d日",
)


# --------------------------------------------------------------------------
# 工具
# --------------------------------------------------------------------------
def eprint(*args) -> None:
    print(*args, file=sys.stderr)


def human_size(n: int) -> str:
    step = 1024.0
    val = float(n)
    for unit in ("B", "KB", "MB", "GB"):
        if val < step or unit == "GB":
            return ("%.1f %s" % (val, unit)) if unit != "B" else ("%d B" % n)
        val /= step
    return "%d B" % n


def escape_like(text: str) -> str:
    """把 LIKE 模式里的 % _ \\ 转义掉。"""
    return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")


def parse_dt(value) -> datetime | None:
    """把 front matter 里的时间字符串解析成 datetime；解析不了返回 None。"""
    if value is None:
        return None
    if isinstance(value, datetime):
        return value
    s = str(value).strip().strip("\"'")
    if not s:
        return None
    try:
        return datetime.fromisoformat(s)
    except ValueError:
        pass
    for fmt in DT_FORMATS:
        try:
            return datetime.strptime(s, fmt)
        except ValueError:
            continue
    m = re.match(r"^(\d{4}[-/]\d{1,2}[-/]\d{1,2}([ T]\d{1,2}:\d{2}(:\d{2})?)?)", s)
    if m:
        for fmt in DT_FORMATS:
            try:
                return datetime.strptime(m.group(1), fmt)
            except ValueError:
                continue
    return None


def clean_scalar(value: str) -> str:
    """去掉一层引号。"""
    v = value.strip()
    if len(v) >= 2 and v[0] == v[-1] and v[0] in "\"'":
        v = v[1:-1]
    return v.strip()


def parse_tags(value) -> list:
    """tags 支持三种写法：[a, b] / a,b / YAML 块列表（解析器给的是 list）。"""
    if value is None:
        return []
    if isinstance(value, (list, tuple)):
        raw = [str(x) for x in value]
    else:
        s = str(value).strip()
        if s.startswith("[") and s.endswith("]"):
            s = s[1:-1]
        raw = [x for x in s.split(",")]
    out = []
    for item in raw:
        item = clean_scalar(str(item)).strip()
        if item and item not in out:
            out.append(item)
    return out


def split_keywords(tokens) -> list:
    """命令行关键词：空格分隔，支持 "带空格的短语"（双引号）。"""
    joined = " ".join(str(t) for t in tokens)
    try:
        parts = shlex.split(joined)
    except ValueError:
        parts = joined.split()
    return [p.strip() for p in parts if p.strip()]


def make_snippet(body: str, kws: list, width: int = 120) -> str:
    """截正文里关键词附近的一段（默认 120 字，带上下文与省略号）。"""
    if not body:
        return ""
    low = body.lower()
    pos = -1
    for kw in kws:
        i = low.find(kw.lower())
        if i >= 0 and (pos < 0 or i < pos):
            pos = i
    if pos < 0:
        return ""
    start = max(0, pos - width // 3)
    end = min(len(body), start + width)
    if end - start < width:
        start = max(0, end - width)
    frag = re.sub(r"\s+", " ", body[start:end]).strip()
    prefix = "…" if start > 0 else ""
    suffix = "…" if end < len(body) else ""
    return prefix + frag + suffix


# --------------------------------------------------------------------------
# front matter 解析（不依赖 yaml）
# --------------------------------------------------------------------------
def split_front_matter(text: str):
    """返回 (front matter 行列表, 正文)。没有 front matter 时第一项为 None。"""
    lines = text.split("\n")
    if not lines:
        return None, ""
    if lines[0].strip().lstrip("﻿") != "---":
        return None, text
    if len(lines) == 1:
        return None, text
    for i in range(1, len(lines)):
        if lines[i].strip() in ("---", "..."):
            return lines[1:i], "\n".join(lines[i + 1:])
    return None, text


def parse_front_matter(text: str):
    """极简 YAML front matter 解析。

    支持：
        key: value              # 值里可以带冒号（按第一个冒号切）
        key: "quoted value"     # 去一层引号
        key: [a, b]             # 行内列表（tags 用）
        key:                    # 块列表
          - a
          - b
    其它 YAML 特性一律忽略（不报错、不中断）。
    """
    fm_lines, body = split_front_matter(text)
    if fm_lines is None:
        return {}, body
    data = {}
    i = 0
    n = len(fm_lines)
    while i < n:
        raw = fm_lines[i].rstrip("\r")
        stripped = raw.strip()
        if not stripped or stripped.startswith("#"):
            i += 1
            continue
        # 缩进行：不属于顶层键（块列表在下面单独处理）
        if raw[:1] in (" ", "\t"):
            i += 1
            continue
        if ":" not in stripped:
            i += 1
            continue
        key, _, val = stripped.partition(":")
        key = key.strip().lower()
        val = val.strip()
        if val:
            data[key] = clean_scalar(val)
            i += 1
            continue
        # 值为空：往后看是不是块列表
        items = []
        j = i + 1
        while j < n:
            nxt = fm_lines[j].rstrip("\r")
            s = nxt.strip()
            if not s:
                j += 1
                continue
            if s == "-" or s.startswith("- "):
                items.append(clean_scalar(s[1:].strip()))
                j += 1
                continue
            if nxt[:1] in (" ", "\t") and items:
                items[-1] = (items[-1] + " " + s).strip()
                j += 1
                continue
            break
        if items:
            data[key] = items
            i = j
            continue
        data[key] = ""
        i += 1
    return data, body


def fm_get(fm: dict, field: str):
    for key in ALIASES.get(field, (field,)):
        if key in fm:
            val = fm[key]
            if isinstance(val, list):
                if val:
                    return val
                continue
            if val is not None and str(val).strip() != "":
                return val
    return None


# --------------------------------------------------------------------------
# 库
# --------------------------------------------------------------------------
CREATE_SQL = """
CREATE TABLE IF NOT EXISTS notes (
    id         VARCHAR PRIMARY KEY,
    path       VARCHAR NOT NULL,
    rel_path   VARCHAR NOT NULL,
    layer      VARCHAR,
    title      VARCHAR,
    category   VARCHAR,
    account    VARCHAR,
    author     VARCHAR,
    url        VARCHAR,
    tags       VARCHAR,
    summary    VARCHAR,
    body       VARCHAR,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    mtime      TIMESTAMP
);
"""

COLUMNS = ("id", "path", "rel_path", "layer", "title", "category", "account",
           "author", "url", "tags", "summary", "body", "created_at",
           "updated_at", "mtime")

INSERT_SQL = """
INSERT INTO notes (%s)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT (id) DO UPDATE SET
    path = excluded.path,
    rel_path = excluded.rel_path,
    layer = excluded.layer,
    title = excluded.title,
    category = excluded.category,
    account = excluded.account,
    author = excluded.author,
    url = excluded.url,
    tags = excluded.tags,
    summary = excluded.summary,
    body = excluded.body,
    created_at = excluded.created_at,
    updated_at = excluded.updated_at,
    mtime = excluded.mtime
""" % ", ".join(COLUMNS)


def connect_db(db_path: str, readonly: bool = False):
    parent = os.path.dirname(db_path)
    if parent and not readonly:
        os.makedirs(parent, exist_ok=True)
    try:
        return duckdb.connect(db_path, read_only=readonly)
    except Exception:
        if readonly:
            return duckdb.connect(db_path)
        raise


def ensure_schema(con) -> None:
    con.execute(CREATE_SQL)
    cols = {r[1] for r in con.execute("PRAGMA table_info('notes')").fetchall()}
    missing = [c for c in COLUMNS if c not in cols]
    if missing:
        # 老库结构不对：直接重建（build 会把文件重新扫回来）
        con.execute("DROP TABLE notes")
        con.execute(CREATE_SQL)


def open_db_for_read(db_path: str, readonly: bool = True):
    """打开索引库；没有库 / 没有表时给出友好提示并返回 None。"""
    if not os.path.exists(db_path):
        eprint("索引库不存在：%s\n先跑一次：python3 %s build" % (db_path, os.path.basename(__file__)))
        return None
    try:
        con = connect_db(db_path, readonly=readonly)
    except Exception as e:
        eprint("打不开索引库 %s：%s" % (db_path, e))
        return None
    try:
        con.execute("SELECT 1 FROM notes LIMIT 1").fetchall()
    except Exception:
        eprint("索引库里没有 notes 表，先跑一次：python3 %s build" % os.path.basename(__file__))
        try:
            con.close()
        except Exception:
            pass
        return None
    return con


# --------------------------------------------------------------------------
# build
# --------------------------------------------------------------------------
def iter_md_files(root: str):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted(d for d in dirnames
                             if d not in SKIP_DIRS and not d.startswith("."))
        for name in sorted(filenames):
            if not name.lower().endswith(".md"):
                continue
            if name.startswith("."):
                continue
            full = os.path.join(dirpath, name)
            rel = os.path.relpath(full, root).replace(os.sep, "/")
            yield full, rel


def detect_layer(fm: dict, rel_path: str) -> str:
    val = fm_get(fm, "layer")
    if val:
        return str(val).strip()
    first = rel_path.split("/", 1)[0].upper()
    if first in ("L1", "L2"):
        return first
    return ""


def parse_note(full: str, rel: str):
    with open(full, "r", encoding="utf-8-sig", errors="replace") as f:
        text = f.read()
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    fm, body = parse_front_matter(text)
    body = body.strip()

    mtime = os.path.getmtime(full)
    mt = datetime.fromtimestamp(mtime)

    title = fm_get(fm, "title")
    if not title:
        title = os.path.basename(full)[:-3] if full.lower().endswith(".md") else os.path.basename(full)
    title = str(title).strip()

    parts = rel.split("/")
    category = fm_get(fm, "category")
    if not category:
        # L1/<分类>/<标题>.md → 分类取中间那层
        if len(parts) >= 3 and parts[0].upper() == "L1":
            category = parts[1]
        else:
            category = ""

    account = fm_get(fm, "account")
    author = fm_get(fm, "author")
    account = str(account).strip() if account else ""
    author = str(author).strip() if author else ""
    if not account and author:
        # 微信文章的 author 通常就是公众号名
        account = author
    if not author:
        author = account

    url = fm_get(fm, "url")
    url = str(url).strip() if url else ""

    tags = parse_tags(fm_get(fm, "tags"))
    summary = fm_get(fm, "summary")
    summary = str(summary).strip() if summary else ""

    collected = parse_dt(fm_get(fm, "collected"))
    created = collected if collected else mt

    note_id = hashlib.sha1(rel.encode("utf-8")).hexdigest()
    return {
        "id": note_id,
        "path": os.path.abspath(full),
        "rel_path": rel,
        "layer": detect_layer(fm, rel),
        "title": title,
        "category": category,
        "account": account,
        "author": author,
        "url": url,
        "tags": ",".join(tags),
        "summary": summary,
        "body": body,
        "created_at": created,
        "updated_at": mt,
        "mtime": mt,
    }


def cmd_build(args) -> int:
    t0 = time.time()
    root = os.path.abspath(os.path.expanduser(args.root))
    if not os.path.isdir(root):
        eprint("知识库根目录不存在：%s" % root)
        return 1
    db = os.path.abspath(os.path.expanduser(args.db))
    con = connect_db(db)
    ensure_schema(con)

    old = {}
    try:
        for rid, mt in con.execute("SELECT id, mtime FROM notes").fetchall():
            old[rid] = mt
    except Exception:
        old = {}

    rows = []
    added = updated = unchanged = 0
    for full, rel in iter_md_files(root):
        try:
            note = parse_note(full, rel)
        except Exception as e:
            eprint("跳过读不了的文件 %s：%s" % (rel, e))
            continue
        rows.append(note)
        if note["id"] in old:
            if old[note["id"]] == note["mtime"]:
                unchanged += 1
            else:
                updated += 1
        else:
            added += 1

    if rows:
        try:
            con.executemany(INSERT_SQL, [tuple(r[c] for c in COLUMNS) for r in rows])
        except Exception as e:
            eprint("批量写入失败，改逐条写入：%s" % e)
            for r in rows:
                con.execute(INSERT_SQL, [r[c] for c in COLUMNS])

    # 删掉库里存在、文件已不存在的笔记（只清本 root 下面的）
    root_pat = escape_like(root) + "/%"
    root_pat_plain = root + "/%"
    seen = [r["id"] for r in rows]
    if seen:
        con.execute("CREATE OR REPLACE TEMP TABLE seen_ids(id VARCHAR)")
        con.executemany("INSERT INTO seen_ids VALUES (?)", [(i,) for i in seen])

    def _sweep(esc_clause: str, pat: str) -> int:
        if seen:
            where = ("id NOT IN (SELECT id FROM seen_ids) AND path LIKE ? " + esc_clause)
        else:
            where = "path LIKE ? " + esc_clause
        cnt = con.execute("SELECT COUNT(*) FROM notes WHERE " + where, [pat]).fetchone()[0]
        con.execute("DELETE FROM notes WHERE " + where, [pat])
        return cnt

    try:
        deleted = _sweep("ESCAPE '\\'", root_pat)
    except Exception:
        deleted = _sweep("", root_pat_plain)

    total = con.execute("SELECT COUNT(*) FROM notes").fetchone()[0]
    con.close()

    print("索引完成（根目录：%s）" % root)
    print("  新增 %d · 更新 %d · 未变 %d · 删除 %d" % (added, updated, unchanged, deleted))
    print("  库内笔记：%d" % total)
    size = os.path.getsize(db) if os.path.exists(db) else 0
    print("  库文件：%s（%s）" % (db, human_size(size)))
    print("  耗时：%.2fs" % (time.time() - t0))
    return 0


# --------------------------------------------------------------------------
# search
# --------------------------------------------------------------------------
def _score_sql(esc: str) -> str:
    """命中统计 SQL。esc 是 ILIKE 后面的 ESCAPE 子句（可为空）。"""
    return """
    SELECT id,
           (CASE WHEN title    ILIKE ? %(esc)s THEN 1 ELSE 0 END)
         + (CASE WHEN category ILIKE ? %(esc)s THEN 1 ELSE 0 END)
         + (CASE WHEN tags     ILIKE ? %(esc)s THEN 1 ELSE 0 END)
         + (CASE WHEN summary  ILIKE ? %(esc)s THEN 1 ELSE 0 END)
         + (CASE WHEN account  ILIKE ? %(esc)s THEN 1 ELSE 0 END)
         + (CASE WHEN author   ILIKE ? %(esc)s THEN 1 ELSE 0 END)
         + (CASE WHEN url      ILIKE ? %(esc)s THEN 1 ELSE 0 END) AS f,
           CAST((length(body) - length(replace(lower(body), lower(?), '')))
                / length(?) AS BIGINT) AS b,
           mtime
    FROM notes
    WHERE title    ILIKE ? %(esc)s
       OR category ILIKE ? %(esc)s
       OR tags     ILIKE ? %(esc)s
       OR summary  ILIKE ? %(esc)s
       OR account  ILIKE ? %(esc)s
       OR author   ILIKE ? %(esc)s
       OR url      ILIKE ? %(esc)s
       OR body     ILIKE ? %(esc)s
    """ % {"esc": esc}


def ilike_scores(con, kw: str):
    """关键词在标题/分类/标签/摘要/正文里的命中数。

    返回 [(id, 字段命中数, 正文出现次数, mtime)]。
    先用带 ESCAPE 的写法（关键词里的 % _ 不会被当通配符），
    万一这个 DuckDB 版本不认 ESCAPE，就退回不带 ESCAPE 的普通 ILIKE。
    """
    variants = [
        (" ESCAPE '\\'", "%" + escape_like(kw) + "%"),
        ("", "%" + kw + "%"),
    ]
    last_err = None
    for esc, pat in variants:
        sql = _score_sql(esc)
        params = [pat] * 7 + [kw, kw] + [pat] * 8
        try:
            return con.execute(sql, params).fetchall()
        except Exception as e:
            last_err = e
    eprint("检索失败：%s" % last_err)
    return []


def try_fts(con, kws, install: bool):
    """可选的 FTS 通路；失败就返回 None，让调用方退回 ILIKE。"""
    try:
        if install:
            con.execute("INSTALL fts")
        con.execute("LOAD fts")
        con.execute("PRAGMA create_fts_index('notes', 'id', 'title', 'body', "
                    "'tags', 'summary', overwrite=1)")
    except Exception as e:
        eprint("FTS 不可用（%s），改用 ILIKE 检索" % e)
        return None
    scores = {}
    for kw in kws:
        try:
            rows = con.execute(
                "SELECT id, fts_main_notes.match(id, ?) AS s FROM notes "
                "WHERE fts_main_notes.match(id, ?) IS NOT NULL",
                [kw, kw]).fetchall()
        except Exception as e:
            eprint("FTS 查询失败（%s），改用 ILIKE 检索" % e)
            return None
        for rid, s in rows:
            scores[rid] = scores.get(rid, 0.0) + float(s or 0.0)
    return scores


def cmd_search(args) -> int:
    kws = split_keywords(args.keywords)
    if not kws:
        eprint("请给至少一个关键词，例如：kb_index.py search AI 工具")
        return 2
    db = os.path.abspath(os.path.expanduser(args.db))
    # 用 FTS 时需要能写库（要建 FTS 索引），否则只读打开
    con = open_db_for_read(db, readonly=not bool(getattr(args, "fts", False)))
    if con is None:
        return 1

    # 每个关键词各查一遍 ILIKE，再合并
    per_kw = {}
    for kw in kws:
        for rid, f, b, mt in ilike_scores(con, kw):
            per_kw.setdefault(rid, {})[kw] = (int(f or 0), int(b or 0))

    fts_scores = None
    if getattr(args, "fts", False):
        fts_scores = try_fts(con, kws, install=True)

    results = []
    for rid, kwmap in per_kw.items():
        score = 0
        n_kw = 0
        for kw, (f, b) in kwmap.items():
            score += f * 3 + b
            if f or b:
                n_kw += 1
        if fts_scores and rid in fts_scores:
            score += int(fts_scores[rid] * 10)
        results.append((score, n_kw, rid))
    results.sort(key=lambda x: (-x[0], -x[1], x[2]))
    total_hits = len(results)
    results = results[: max(1, int(args.limit))]

    if results:
        ids = ",".join("'" + r[2].replace("'", "''") + "'" for r in results)
        detail = con.execute(
            "SELECT id, rel_path, layer, title, category, account, url, tags, "
            "summary, body FROM notes WHERE id IN (%s)" % ids).fetchall()
        info = {r[0]: r for r in detail}
    else:
        info = {}

    payload = []
    for score, n_kw, rid in results:
        r = info.get(rid)
        if not r:
            continue
        body = r[9] or ""
        snippet = make_snippet(body, kws, args.snippet_width)
        if not snippet:
            snippet = (r[8] or "")[: args.snippet_width]
        payload.append({
            "id": rid,
            "title": r[3] or "",
            "layer": r[2] or "",
            "category": r[4] or "",
            "account": r[5] or "",
            "url": r[6] or "",
            "tags": r[7] or "",
            "rel_path": r[1] or "",
            "score": score,
            "matched_keywords": n_kw,
            "snippet": snippet,
        })
    con.close()

    if args.json:
        print(json.dumps({"keywords": kws, "total_hits": total_hits,
                          "results": payload}, ensure_ascii=False, indent=2))
        return 0

    if not payload:
        print("没有命中：%s" % " ".join(kws))
        return 0

    print("关键词：%s（命中 %d 条，显示前 %d 条）\n" % (" ".join(kws), total_hits, len(payload)))
    for i, item in enumerate(payload, 1):
        print("%d. %s" % (i, item["title"] or "(无标题)"))
        print("   layer=%s  category=%s  命中=%d（命中关键词 %d 个）"
              % (item["layer"] or "-", item["category"] or "-",
                 item["score"], item["matched_keywords"]))
        if item["snippet"]:
            print("   片段：%s" % item["snippet"])
        print("   路径：%s" % item["rel_path"])
        if item["tags"]:
            print("   标签：%s" % item["tags"])
        print()
    return 0


# --------------------------------------------------------------------------
# stats
# --------------------------------------------------------------------------
def cmd_stats(args) -> int:
    db = os.path.abspath(os.path.expanduser(args.db))
    con = open_db_for_read(db)
    if con is None:
        return 1
    total = con.execute("SELECT COUNT(*) FROM notes").fetchone()[0]
    layers = con.execute(
        "SELECT COALESCE(NULLIF(layer, ''), '(未知)') AS l, COUNT(*) AS c "
        "FROM notes GROUP BY 1 ORDER BY c DESC, l").fetchall()
    cats = con.execute(
        "SELECT COALESCE(NULLIF(category, ''), '(未分类)') AS l, COUNT(*) AS c "
        "FROM notes GROUP BY 1 ORDER BY c DESC, l").fetchall()
    chars = con.execute("SELECT COALESCE(SUM(length(body)), 0) FROM notes").fetchone()[0]
    newest = con.execute(
        "SELECT title, rel_path, mtime FROM notes ORDER BY mtime DESC NULLS LAST LIMIT 3"
    ).fetchall()
    con.close()

    size = os.path.getsize(db) if os.path.exists(db) else 0
    print("笔记总数：%d" % total)
    print("正文总字数：%d" % chars)
    print("按 layer：")
    if layers:
        for name, cnt in layers:
            print("  %-12s %d" % (name, cnt))
    else:
        print("  (空)")
    print("按 category：")
    if cats:
        for name, cnt in cats:
            print("  %-16s %d" % (name, cnt))
    else:
        print("  (空)")
    if newest:
        print("最近修改：")
        for title, rel, mt in newest:
            when = mt.strftime("%Y-%m-%d %H:%M") if isinstance(mt, datetime) else str(mt)
            print("  %s  %s  (%s)" % (when, title or "(无标题)", rel))
    print("库文件：%s（%s）" % (db, human_size(size)))
    return 0


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------
def build_parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(
        prog="kb_index.py",
        description="微信素材库 → DuckDB 索引 / 检索",
        formatter_class=argparse.RawDescriptionHelpFormatter)
    # 主 parser 也收 --root/--db，这样 `kb_index.py --root X build` 和
    # `kb_index.py build --root X` 都能用。
    ap.add_argument("--root", default=DEFAULT_ROOT, help="知识库根目录（默认 %(default)s）")
    ap.add_argument("--db", default=DEFAULT_DB, help="duckdb 库文件（默认 %(default)s）")

    # 子 parser 用 SUPPRESS，避免子 parser 的默认值盖掉主 parser 已解析的值
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--root", default=argparse.SUPPRESS, help=argparse.SUPPRESS)
    common.add_argument("--db", default=argparse.SUPPRESS, help=argparse.SUPPRESS)

    sub = ap.add_subparsers(dest="cmd")

    p_build = sub.add_parser("build", parents=[common], help="扫描并写入索引")
    p_build.set_defaults(func=cmd_build)

    p_search = sub.add_parser("search", parents=[common], help="按关键词检索")
    p_search.add_argument("keywords", nargs="+", help="关键词（空格分隔，可用 \"引号\" 包短语）")
    p_search.add_argument("--limit", type=int, default=5, help="返回条数（默认 %(default)s）")
    p_search.add_argument("--snippet-width", type=int, default=120, help="片段长度（默认 %(default)s）")
    p_search.add_argument("--json", action="store_true", help="JSON 输出")
    p_search.add_argument("--fts", action="store_true", help="尝试使用 duckdb fts 扩展（可选，没有也能用）")
    p_search.set_defaults(func=cmd_search)

    p_stats = sub.add_parser("stats", parents=[common], help="看索引统计")
    p_stats.set_defaults(func=cmd_stats)
    return ap


def main(argv=None) -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass
    ap = build_parser()
    args = ap.parse_args(argv)
    if not getattr(args, "func", None):
        ap.print_help()
        return 2
    try:
        return args.func(args)
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main())
