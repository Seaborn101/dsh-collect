#!/usr/bin/env bash
# kb_index.py 自测脚本：在 /tmp/kbtest 造几个假笔记，跑一遍 build → search → stats。
# 用法：bash scripts/kb_selftest.sh
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(dirname "$HERE")"
ROOT=/tmp/kbtest
DB="$ROOT/kb.duckdb"

cd "$REPO"

echo "== 0) 语法检查 =="
python3 -m py_compile scripts/kb_index.py && echo "py_compile OK"
python3 -c "import duckdb; print('duckdb', duckdb.__version__)"

echo
echo "== 1) 造测试夹具 =="
rm -rf "$ROOT"
mkdir -p "$ROOT/L1/编程工具" "$ROOT/L1/AI Agent" "$ROOT/L2"

cat > "$ROOT/L1/编程工具/GitHub 开源神器.md" <<'EOF'
---
title: GitHub 开源神器：一人公司也能拥有 AI 团队
layer: L1
category: 编程工具
account: Ghub 代码库
author: 某某
url: https://mp.weixin.qq.com/s/xxxx
collected: 2026-11-15 19:28
tags: [GitHub, AI团队]
summary: 一句话摘要
signal: high
---

# GitHub 开源神器：一人公司也能拥有 AI 团队

最近盘点了几个 GitHub 上开源的 AI 团队协作项目，配置好之后一个人也能像一支队伍那样工作。

## 值得关注的仓库

- **agent-swarm**：把任务拆给多个 AI 代理并行跑，适合批量处理长文档。
- **note-agent**：自动把网页整理成结构化笔记，接 Obsidian 很顺手。
- **duckdb-kit**：本地小数据集的检索方案，SQL 就能查。

这些项目的共同点是：都可以在本机跑，不依赖云端 API，也不需要联网下载模型。
所谓「AI 团队」，本质上是把提示词、工具调用和记忆串成流水线。
EOF

cat > "$ROOT/L1/AI Agent/Claude Code 使用心得.md" <<'EOF'
---
title: Claude Code 使用心得：把 AI 当同事而不是玩具
layer: L1
category: AI Agent
account: 新智元
author: 张三
url: https://mp.weixin.qq.com/s/yyyy
collected: 2026-11-16 08:10
tags:
  - Claude
  - 编程助手
  - 工作流
summary: 用 Claude Code 三个月的心得：先给上下文，再提需求。
signal: medium
---

用 Claude Code 三个月，踩过的坑和得到的经验：

1. 上下文比指令重要。先让它读懂仓库结构，再提需求。
2. 让它自己跑测试，比让它「看起来对」靠谱。
3. 拆任务：一个会话干一件事，别贪多。

关于 AI 团队的讨论里，Claude Code 常被当作「一个人的 AI 团队」入口，
配合 GitHub 上的开源编排项目，能覆盖从选题到写作的大部分环节。
EOF

cat > "$ROOT/L2/AI 编程工具总览.md" <<'EOF'
---
title: AI 编程工具总览：一个人怎么搭一支 AI 团队
layer: L2
category: AI 编程工具
account: AI 综述机器人
collected: 2026-11-17 10:00
tags:
  - AI团队
  - GitHub
  - 综述
summary: 把 GitHub 开源神器与 Claude Code 的使用心得串起来的一份主题综述。
---

## 综述

本主题汇总了两篇 L1 笔记：GitHub 上的开源 AI 团队项目，以及 Claude Code 的实际使用心得。

### 共同结论

1. **上下文工程 > 提示词技巧**：先让 AI 读懂仓库再提需求。
2. **本地优先**：DuckDB 这类嵌入式库能在手机 / 笔记本上跑完整检索层，不需要联网装扩展。
3. **流水线化**：选题 → 初加工（L1）→ 主题归纳（L2），人只做判断，AI 做搬运。

### 可落地清单

- 用 Markdown 当唯一事实来源，front matter 里放 tags / summary / category。
- 用一个小脚本把 Markdown 灌进 DuckDB，检索用 ILIKE 就够。
- 综述笔记的 tags 要跟 L1 的标签对得上，否则主题串不起来。
EOF

cat > "$ROOT/README.md" <<'EOF'
# 微信素材库（测试夹具）

这个目录用来测试 kb_index.py。没有 front matter，解析器要能兜住。
里面提到 DuckDB 和 AI 团队，用来验证「没有 front matter 的文件也会被索引」。
EOF

find "$ROOT" -name '*.md' | sort

echo
echo "== 2) build =="
python3 scripts/kb_index.py build --root "$ROOT" --db "$DB"

echo
echo "== 3) search: AI 团队 =="
python3 scripts/kb_index.py search AI 团队 --root "$ROOT" --db "$DB"

echo
echo "== 4) search: GitHub（--limit 3）=="
python3 scripts/kb_index.py search GitHub --root "$ROOT" --db "$DB" --limit 3

echo
echo "== 5) search: 短语（引号）=="
python3 scripts/kb_index.py search "AI 团队" --root "$ROOT" --db "$DB" --limit 3

echo
echo "== 6) search --json =="
python3 scripts/kb_index.py search DuckDB --root "$ROOT" --db "$DB" --json

echo
echo "== 7) 增量：改一个文件再 build =="
printf '\n\n新增一段：本地优先，用 DuckDB 做检索层。\n' >> "$ROOT/L1/AI Agent/Claude Code 使用心得.md"
python3 scripts/kb_index.py build --root "$ROOT" --db "$DB"

echo
echo "== 8) 增量：删一个文件再 build =="
rm "$ROOT/L1/编程工具/GitHub 开源神器.md"
python3 scripts/kb_index.py build --root "$ROOT" --db "$DB"

echo
echo "== 9) stats =="
python3 scripts/kb_index.py stats --root "$ROOT" --db "$DB"

echo
echo "自测结束。"
