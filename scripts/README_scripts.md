# DSH 收集 · 手机端脚本

「DSH 收集」是跑在 Android 上的 App，负责把微信公众号文章收进手机；
这里配套两个**纯标准库**的 Python 脚本，让手机里的 dsh-go agent（或你自己）
能把收藏文章的正文抓下来、写回 Markdown。

> 环境：Ubuntu 24.04 in PRoot，Python 3.12（`python3`）。
> **不需要 pip 安装任何东西**，只用 `urllib` / `json` / `re` 等标准库。

## 文件

| 文件 | 职责 |
| --- | --- |
| `fetch_article.py` | 命令行工具：抓一篇（或批量）微信公众号文章，输出清洗后的正文纯文本 |
| `collect_lib.py` | 可 import 的小库：读写收藏目录里的 `*.md` 和 `articles.jsonl` |
| `list_collected.py` | 之前就有的：列出收藏（本次未改动） |

## 和 App 的关系

App（`app/src/main/java/com/dsh/collect/Store.java`）把收藏写到：

```
/sdcard/Download/DSH-Go/collected/
├── articles.jsonl          # 索引，一行一个 JSON
├── 20261115-181900_文章标题.md
└── ...
```

每篇一个 Markdown，YAML front matter：

```markdown
---
title: 文章标题
account: 新智元
url: https://mp.weixin.qq.com/s/XXXX
source: wechat
collected: 2026-11-15 18:19
---

# 文章标题

> 来源：新智元
> 链接：https://mp.weixin.qq.com/s/XXXX
> 收藏于 2026-11-15 18:19

<!-- 原文正文（可由 dsh-go agent 抓取后写入此处） -->
```

`articles.jsonl` 每行一个对象，字段：
`url`、`title`、`account`、`collected_at`（ISO 字符串）、
`tags`（空格分隔的字符串）、`status`（`new` / `read` / `archived`）、
`file`（文件名）、`summary`（可为空）。

脚本和 App 共用这一套格式：**改字段要两边同步改**
（front matter 的字段与顺序见 `collect_lib.FRONT_MATTER_KEYS`）。

## fetch_article.py

### 单篇

```bash
cd /root/workspace/dsh-collect
python3 scripts/fetch_article.py 'https://mp.weixin.qq.com/s/XXXX'
python3 scripts/fetch_article.py 'https://mp.weixin.qq.com/s/XXXX' --out /sdcard/Download/a.txt
python3 scripts/fetch_article.py 'https://mp.weixin.qq.com/s/XXXX' --json   # 额外打印标题/公众号
```

* 默认把清洗后的正文打到 stdout（UTF-8）；`--out` 则写文件。
* 超时 15 秒（`--timeout` 可改），自动跟随重定向（最多 5 次）。
* 请求头固定带 `MicroMessenger` 的 UA 和 `Accept-Language: zh-CN,zh;q=0.9`
  —— 微信文章页面对 UA 敏感，换成普通浏览器 UA 很容易拿到验证页。

### 批量

```bash
python3 scripts/fetch_article.py \
    --batch /sdcard/Download/DSH-Go/collected/articles.jsonl \
    --outdir /sdcard/Download/DSH-Go/fetched
```

* 每篇写一个 `<序号>_<标题或时间戳>.txt`（文件名里的非法字符会被替换掉）。
* stdout 每篇一行摘要：`OK <url> <字符数>` 或 `FAIL <url> <原因>`。
* **任何一篇失败都不会影响其它篇**，最后一行的汇总给出成功/失败数。

### 退出码

| 码 | 含义 |
| --- | --- |
| 0 | 成功 |
| 1 | 普通错误（网络不通、HTTP 非 200、解析不到正文……） |
| 2 | 命中微信的「环境异常 / 请在微信客户端打开」验证页（批量模式下全部失败都属这一类时也返回 2） |

## collect_lib.py

```python
import sys
sys.path.insert(0, "/root/workspace/dsh-collect/scripts")
import collect_lib

# 1) 读文章列表（优先 articles.jsonl，没有就解析 *.md 的 front matter）
articles = collect_lib.read_articles("/sdcard/Download/DSH-Go/collected")
for a in articles:
    print(a["title"], a["url"], a["status"])

# 2) 读单个 Markdown -> front matter + body
md = collect_lib.read_markdown("/sdcard/Download/DSH-Go/collected/xxx.md")
print(md["title"], md["account"], md["body"][:50])

# 3) 抓正文后写回（front matter 顺序固定，正文末尾自动留占位注释）
collect_lib.write_markdown(
    {"title": md["title"], "account": md["account"], "url": md["url"],
     "source": "wechat", "collected": md["collected"], "tags": md["tags"],
     "status": md["status"], "summary": md["summary"]},
    "这里是抓回来的正文纯文本",
    "/sdcard/Download/DSH-Go/collected/xxx.md",
)

# 4) 原子写回索引（先写临时文件再 rename，中途断电不会写坏）
collect_lib.update_jsonl("/sdcard/Download/DSH-Go/collected", articles)
```

命令行自检：

```bash
python3 scripts/collect_lib.py            # 列出收藏目录里的文章
python3 scripts/collect_lib.py /sdcard/Download/DSH-Go/collected
```

## 常见问题

### 1. 「微信环境异常，完成验证后即可继续访问。」

抓到的不是文章，而是微信的风控页。脚本会识别出来并以退出码 **2** 结束，
stderr 打印识别到的提示。

原因与对策：

* 服务器看到的是脚本 UA/出口 IP，判定为异常环境；
* 文章需要登录态或分享者的会话；
* 频率太高被临时限流 —— 批量时中间隔几秒、一次少抓几篇。

**脚本无法绕过这个验证**，只能识别并优雅退出。想稳定拿到正文，可行路径是：
在真机微信里打开文章 → 用「DSH 收集」App 收藏 → 再由 App/其它能拿到会话的
方式补正文；或者把已有的 Markdown 直接喂给 agent 摘要。

### 2. 链接失效

公众号文章被删除、转为私密、或超过有效期时，微信会返回「该内容已被发布者删除」
之类的页面，或者返回 HTTP 200 但没有正文。这时脚本报
`没有解析到正文（页面可能是空壳或需要验证）`（退出码 1）。
收藏目录里的记录仍然保留，只是抓不到正文。

### 3. 超时 / 网络错误

移动网络下偶尔超时。脚本超时 15 秒，报 `网络请求失败: ...`（退出码 1）。
批量模式会跳过失败的那篇继续跑。信号差可以把 `--timeout` 调大。

### 4. 权限 / 目录

* App 没有拿到「所有文件访问」权限时，收藏会落在 App 私有目录
  `/root/Android/data/com.dsh.collect/files/collected`；
  脚本读不到那里，就把 `collected_dir` 参数指过去。
* `/sdcard` 在 PRoot 里就是手机的内部存储，直接读写即可；
  写 `articles.jsonl` 用的是「临时文件 + rename」，Android 存储不支持 rename 时
  会自动退化成直接写。

### 5. 编码

输出统一 UTF-8；脚本启动时会 `sys.stdout.reconfigure(encoding="utf-8")`，
管道给 `less`/`grep` 也不会乱码。

## 典型工作流

```bash
cd /root/workspace/dsh-collect
# 1) 看看收藏了啥
python3 scripts/list_collected.py
# 2) 批量抓正文（失败会逐条打印原因，不影响其它篇）
python3 scripts/fetch_article.py --batch \
    /sdcard/Download/DSH-Go/collected/articles.jsonl \
    --outdir /sdcard/Download/DSH-Go/fetched
# 3) 把正文写回 Markdown（用 collect_lib.write_markdown）
```

写回 Markdown 时请**保留原来的 front matter**，只替换正文段落；
`write_markdown()` 会按 App 的字段顺序重新输出，正文末尾自动补上
`<!-- 原文正文（可由 dsh-go agent 抓取后写入此处） -->` 占位注释。
