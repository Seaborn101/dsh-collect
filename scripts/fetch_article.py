#!/usr/bin/env python3
"""抓取微信公众号文章，输出清洗后的正文纯文本。

用法:
    python3 fetch_article.py <url> [--out 文件]
    python3 fetch_article.py --batch <jsonl路径> --outdir <目录>

说明:
    * 微信文章页面对 User-Agent 敏感，这里固定带 MicroMessenger。
    * 只用标准库（urllib），不需要 pip 装任何东西。
    * 遇到微信的「环境异常 / 请在微信客户端打开」验证页时，退出码 2。
"""

from __future__ import annotations

import argparse
import html as html_mod
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

# 微信文章页面必须伪装成微信内置浏览器
USER_AGENT = (
    "Mozilla/5.0 (Linux; Android 14; V2323A Build/UP1A) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Version/4.0 Chrome/124.0.0.0 Mobile Safari/537.36 "
    "MicroMessenger/8.0.49"
)

HEADERS = {
    "User-Agent": USER_AGENT,
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "zh-CN,zh;q=0.9",
    "Cache-Control": "no-cache",
    "Pragma": "no-cache",
    "Connection": "close",
}

TIMEOUT = 15           # 秒
MAX_REDIRECTS = 5

EXIT_OK = 0
EXIT_ERROR = 1
EXIT_BLOCKED = 2       # 环境异常 / 验证页

# 微信验证页的特征文案
BLOCK_MARKERS = ("环境异常", "当前环境异常", "请在微信客户端打开")

# 常见实体（html.unescape 其实全都能解，这里额外覆盖几个）
EXTRA_ENTITIES = {
    "&hellip;": "…",
    "&mdash;": "—",
    "&ndash;": "–",
    "&ldquo;": "“",
    "&rdquo;": "”",
    "&lsquo;": "‘",
    "&rsquo;": "’",
    "&middot;": "·",
    "&times;": "×",
    "&copy;": "©",
    "&reg;": "®",
    "&trade;": "™",
    "&laquo;": "«",
    "&raquo;": "»",
    "&ensp;": " ",
    "&emsp;": " ",
    "&thinsp;": " ",
    "&shy;": "",
    "&zwnj;": "",
    "&zwj;": "",
}

# ---------------------------------------------------------------- 正则

_RE_SCRIPT = re.compile(
    r"<(script|style|noscript|iframe)\b[^>]*>.*?</\1\s*>", re.I | re.S
)
_RE_COMMENT = re.compile(r"<!--.*?-->", re.S)
_RE_STYLE_ATTR = re.compile(r"\s+style\s*=\s*['\"].*?['\"]", re.I | re.S)
_RE_BR = re.compile(r"<br\s*/?>", re.I)
_RE_BLOCK_END = re.compile(
    r"</\s*(p|div|h[1-6]|li|tr|section|blockquote|pre|article|ul|ol|table)\s*>", re.I
)
_RE_CELL_END = re.compile(r"</\s*(td|th)\s*>", re.I)
_RE_TAG = re.compile(r"<[^>]+>")
_RE_DIV = re.compile(r"<(/?)div\b[^>]*>", re.I)


# ---------------------------------------------------------------- 异常


class FetchError(Exception):
    """抓取过程中的普通错误（网络、HTTP 状态、解析不到内容）。"""


class BlockedError(FetchError):
    """命中微信的环境异常 / 验证页。"""


# ---------------------------------------------------------------- 网络


def http_get(url: str, timeout: int = TIMEOUT) -> tuple:
    """抓取网页，返回 (html 文本, 最终 URL)。自动处理重定向。"""
    current = url
    opener = urllib.request.build_opener()
    for _ in range(MAX_REDIRECTS + 1):
        req = urllib.request.Request(current, headers=HEADERS)
        try:
            with opener.open(req, timeout=timeout) as resp:
                raw = resp.read()
                charset = None
                try:
                    charset = resp.headers.get_content_charset()
                except Exception:
                    charset = None
                if not charset:
                    charset = "utf-8"
                return raw.decode(charset, errors="replace"), resp.geturl()
        except urllib.error.HTTPError as e:
            if e.code in (301, 302, 303, 307, 308):
                location = e.headers.get("Location") if e.headers else None
                if not location:
                    raise FetchError("HTTP %d 重定向但没有 Location 头" % e.code)
                current = urllib.parse.urljoin(current, location)
                continue
            raise FetchError("HTTP %d %s" % (e.code, e.reason))
        except urllib.error.URLError as e:
            raise FetchError("网络请求失败: %s" % (e.reason,))
        except (TimeoutError, OSError) as e:
            raise FetchError("网络请求失败: %s" % (e,))
    raise FetchError("重定向次数超过 %d 次" % MAX_REDIRECTS)


# ---------------------------------------------------------------- 解析


def decode_entities(text: str) -> str:
    """HTML 实体解码（命名实体 + 数字实体）。"""
    if "&" not in text:
        return text
    for key, value in EXTRA_ENTITIES.items():
        text = text.replace(key, value)
    return html_mod.unescape(text)


def _meta_content(page: str, prop: str) -> str:
    """取 <meta property="prop" content="...">（兼容属性顺序差异）。"""
    for m in re.finditer(r"<meta\b[^>]*>", page, re.I):
        tag = m.group(0)
        has_prop = re.search(
            r"\b(?:property|name)\s*=\s*['\"]" + re.escape(prop) + r"['\"]", tag, re.I
        )
        if not has_prop:
            continue
        content = re.search(r"\bcontent\s*=\s*['\"](.*?)['\"]", tag, re.I | re.S)
        if content:
            return _clean_inline(decode_entities(content.group(1)))
    return ""


def _js_var(page: str, name: str) -> str:
    """取 `var xxx = '...'` 里的字符串（微信页面里标题/昵称常这么写）。"""
    m = re.search(
        r"\bvar\s+" + re.escape(name) + r"\s*=\s*(['\"])(.*?)\1", page, re.S
    )
    if not m:
        m = re.search(
            r"\b" + re.escape(name) + r"\s*=\s*(['\"])(.*?)\1", page, re.S
        )
    if not m:
        return ""
    return _clean_inline(decode_entities(m.group(2)))


def _clean_inline(text: str) -> str:
    """把一小段 HTML 变成单行纯文本。"""
    text = _RE_TAG.sub("", text or "")
    text = decode_entities(text)
    return re.sub(r"\s+", " ", text).strip()


def extract_title(page: str) -> str:
    """标题：og:title -> var msg_title -> h1.rich_media_title。"""
    title = _meta_content(page, "og:title")
    if title:
        return title
    title = _js_var(page, "msg_title")
    if title:
        return title
    m = re.search(
        r"<h1[^>]*class\s*=\s*['\"][^'\"]*rich_media_title[^'\"]*['\"][^>]*>(.*?)</h1>",
        page,
        re.I | re.S,
    )
    if not m:
        m = re.search(r"<h1[^>]*>(.*?)</h1>", page, re.I | re.S)
    if m:
        return _clean_inline(m.group(1))
    return ""


def extract_account(page: str) -> str:
    """公众号名：var nickname -> a#js_name -> og:site_name。

    注意：og:site_name 在微信文章里恒等于「微信公众平台」（通用占位名），
    不是真实账号，所以只能当最后的兜底。
    """
    candidates = []
    name = _js_var(page, "nickname")
    if name:
        candidates.append(name)
    m = re.search(
        r"<a[^>]*id\s*=\s*['\"]js_name['\"][^>]*>(.*?)</a>", page, re.I | re.S
    )
    if m:
        candidates.append(_clean_inline(m.group(1)))
    name = _meta_content(page, "og:site_name")
    if name:
        candidates.append(name)
    for c in candidates:
        c = (c or "").strip()
        if c and c != "微信公众平台":
            return c
    return candidates[0].strip() if candidates else ""


def _extract_div(page: str, class_name: str) -> str:
    """取 <div class="class_name"> ... </div> 的内部 HTML（按 div 深度配对）。"""
    m = re.search(
        r"<div[^>]*class\s*=\s*['\"][^'\"]*\b"
        + re.escape(class_name)
        + r"\b[^'\"]*['\"][^>]*>",
        page,
        re.I,
    )
    if not m:
        return ""
    depth = 0
    for tm in _RE_DIV.finditer(page, m.start()):
        if tm.group(1) == "/":
            depth -= 1
            if depth == 0:
                return page[m.end():tm.start()]
        else:
            depth += 1
    return page[m.end():]


def _extract_div_by_id(page: str, dom_id: str) -> str:
    """取 <div id="..."> ... </div> 的内部 HTML。"""
    m = re.search(
        r"<div[^>]*id\s*=\s*['\"]" + re.escape(dom_id) + r"['\"][^>]*>", page, re.I
    )
    if not m:
        return ""
    depth = 0
    for tm in _RE_DIV.finditer(page, m.start()):
        if tm.group(1) == "/":
            depth -= 1
            if depth == 0:
                return page[m.end():tm.start()]
        else:
            depth += 1
    return page[m.end():]


def extract_body_html(page: str) -> tuple:
    """正文 HTML：优先 div.rich_media_content，其次 div#js_content，最后整页。

    返回 (html, found)：found 表示是否真的找到了正文容器，
    用来区分「正常文章」和「验证页」。
    """
    body = _extract_div(page, "rich_media_content")
    if body.strip():
        return body, True
    body = _extract_div_by_id(page, "js_content")
    if body.strip():
        return body, True
    return page, False


def html_to_text(fragment: str) -> str:
    """HTML -> 纯文本：去脚本/样式/注释/内联样式，标签转换行，再解实体。"""
    text = fragment or ""
    text = _RE_SCRIPT.sub("\n", text)
    text = _RE_COMMENT.sub(" ", text)
    text = _RE_STYLE_ATTR.sub("", text)
    text = _RE_BR.sub("\n", text)
    text = _RE_BLOCK_END.sub("\n", text)
    text = _RE_CELL_END.sub(" ", text)
    text = _RE_TAG.sub("", text)
    text = decode_entities(text)
    text = text.replace("\u200b", "").replace("\ufeff", "")
    text = re.sub(r"[ \t\u00a0\u3000]+", " ", text)
    text = re.sub(r" *\n *", "\n", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def detect_block_message(page: str) -> str:
    """从验证页里提取一句可读的提示。"""
    known = (
        "当前环境异常，完成验证后即可继续访问。",
        "环境异常",
        "请在微信客户端打开",
    )
    for item in known:
        if item in page:
            return item
    for marker in BLOCK_MARKERS:
        idx = page.find(marker)
        if idx >= 0:
            snippet = _RE_TAG.sub(" ", page[max(0, idx - 150):idx + 150])
            snippet = re.sub(r"\s+", " ", decode_entities(snippet)).strip()
            return snippet or marker
    return "未知原因（页面含验证页特征文案）"


def is_blocked_page(page: str, body_text: str = "", found: bool = False, title: str = "") -> bool:
    """是否环境异常/验证页。

    出现特征文案即高度怀疑；只有「正文容器存在且正文够长」时才当成正常文章
    （避免文章正文里恰好提到「环境异常」被误杀）。
    """
    if not any(marker in page for marker in BLOCK_MARKERS):
        return False
    if title and any(marker in title for marker in BLOCK_MARKERS):
        return True
    if not found:
        return True
    return len((body_text or "").strip()) < 200


# ---------------------------------------------------------------- 主流程


def fetch_article(url: str, timeout: int = TIMEOUT) -> dict:
    """抓一篇文章，返回 {"url","title","account","text"}。"""
    page, final_url = http_get(url, timeout)
    body_html, found = extract_body_html(page)
    text = html_to_text(body_html)
    title = extract_title(page)
    if is_blocked_page(page, text, found, title):
        raise BlockedError(detect_block_message(page))
    if not text.strip():
        # 正文没抓到，看看是不是验证页（有时特征文案在 JS 变量里）
        if any(marker in page for marker in BLOCK_MARKERS):
            raise BlockedError(detect_block_message(page))
        raise FetchError("没有解析到正文（页面可能是空壳或需要验证）")
    return {
        "url": final_url,
        "title": title,
        "account": extract_account(page),
        "text": text,
    }


def safe_name(text: str, limit: int = 40) -> str:
    """把标题变成安全文件名片段。"""
    text = re.sub(r"[\\/:*?\"<>|\r\n\t]+", "", text or "")
    text = re.sub(r"\s+", "_", text.strip())
    return text[:limit] or "untitled"


def load_jsonl(path: str) -> list:
    """读 jsonl，跳过坏行。"""
    items = []
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                obj = json.loads(line)
            except ValueError:
                continue
            if isinstance(obj, dict):
                items.append(obj)
    return items


def run_batch(jsonl_path: str, outdir: str, timeout: int) -> int:
    """批量抓取 articles.jsonl 里的所有文章。"""
    os.makedirs(outdir, exist_ok=True)
    try:
        records = load_jsonl(jsonl_path)
    except OSError as e:
        print("FAIL 读取 %s 失败: %s" % (jsonl_path, e), file=sys.stderr)
        return EXIT_ERROR

    ok = 0
    failed = 0
    blocked = 0
    for index, record in enumerate(records, 1):
        url = str(record.get("url") or "").strip()
        if not url:
            print("FAIL %s 缺少 url" % (record.get("title") or "-",))
            failed += 1
            continue
        try:
            article = fetch_article(url, timeout)
        except BlockedError as e:
            print("FAIL %s %s" % (url, e))
            failed += 1
            blocked += 1
            continue
        except FetchError as e:
            print("FAIL %s %s" % (url, e))
            failed += 1
            continue
        except Exception as e:  # 兜底：单篇失败不影响其它篇
            print("FAIL %s 未预期错误: %s" % (url, e))
            failed += 1
            continue

        title = article.get("title") or record.get("title") or ""
        if not title:
            title = str(
                record.get("collected_at") or record.get("collected") or ""
            )
        name = "%03d_%s.txt" % (index, safe_name(title))
        path = os.path.join(outdir, name)
        try:
            with open(path, "w", encoding="utf-8", newline="\n") as f:
                f.write(article["text"])
                if not article["text"].endswith("\n"):
                    f.write("\n")
        except OSError as e:
            print("FAIL %s 写入 %s 失败: %s" % (url, path, e))
            failed += 1
            continue
        print("OK %s %d" % (url, len(article["text"])))
        ok += 1

    print("--- 共 %d 篇，成功 %d，失败 %d，输出目录 %s ---" % (len(records), ok, failed, outdir))
    if failed == 0:
        return EXIT_OK
    if blocked == failed:
        # 全部都是环境异常/验证页
        return EXIT_BLOCKED
    return EXIT_ERROR


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="fetch_article.py",
        description="抓取微信公众号文章，输出清洗后的正文纯文本",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "示例:\n"
            "  python3 fetch_article.py 'https://mp.weixin.qq.com/s/XXXX'\n"
            "  python3 fetch_article.py 'https://mp.weixin.qq.com/s/XXXX' --out /sdcard/Download/a.txt\n"
            "  python3 fetch_article.py --batch /sdcard/Download/DSH-Go/collected/articles.jsonl "
            "--outdir /sdcard/Download/DSH-Go/fetched\n"
        ),
    )
    parser.add_argument("url", nargs="?", help="单篇模式：文章链接")
    parser.add_argument("--out", help="单篇模式：输出文件（默认打印到 stdout）")
    parser.add_argument("--batch", metavar="JSONL", help="批量模式：读取的 articles.jsonl 路径")
    parser.add_argument("--outdir", help="批量模式：输出目录（默认 ./fetched）")
    parser.add_argument("--timeout", type=int, default=TIMEOUT, help="单次请求超时秒数（默认 15）")
    parser.add_argument("--json", action="store_true", help="单篇模式：附带标题/公众号等元信息")
    return parser


def main(argv=None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8")
        except Exception:
            pass

    if args.batch:
        outdir = args.outdir or os.path.join(os.getcwd(), "fetched")
        return run_batch(args.batch, outdir, args.timeout)

    if not args.url:
        parser.print_usage(sys.stderr)
        print("错误：请提供文章链接，或用 --batch 批量模式", file=sys.stderr)
        return EXIT_ERROR

    try:
        article = fetch_article(args.url, args.timeout)
    except BlockedError as e:
        print("微信环境异常/验证页：%s" % e, file=sys.stderr)
        print("提示：这类页面需要真机微信里的会话环境，脚本无法绕过。", file=sys.stderr)
        return EXIT_BLOCKED
    except FetchError as e:
        print("抓取失败：%s" % e, file=sys.stderr)
        return EXIT_ERROR
    except KeyboardInterrupt:
        return EXIT_ERROR

    text = article["text"]
    if args.out:
        try:
            with open(args.out, "w", encoding="utf-8", newline="\n") as f:
                f.write(text)
                if not text.endswith("\n"):
                    f.write("\n")
        except OSError as e:
            print("写入 %s 失败：%s" % (args.out, e), file=sys.stderr)
            return EXIT_ERROR
        if args.json:
            meta = dict(article)
            meta.pop("text", None)
            print(json.dumps(meta, ensure_ascii=False))
        print("已写入 %s（%d 字符）" % (args.out, len(text)))
        return EXIT_OK

    if args.json:
        meta = dict(article)
        meta.pop("text", None)
        print(json.dumps(meta, ensure_ascii=False))
    try:
        sys.stdout.write(text + "\n")
    except BrokenPipeError:
        pass
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main())
