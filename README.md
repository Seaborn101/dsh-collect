# DSH 收集 · 微信素材库

> 把微信公众号文章变成 **AI 可用的知识库**。
> 人负责「严选」，AI 负责「加工」，duckdb 负责「检索」。

## 定位

微信公众号的批量抓取与爬取极难，所以**不与爬虫较劲**：人在微信里看到有价值的文章，
点右上角「…」→「用DSH收集打开」——**人工转发即人工筛选**。
App 只做三件事：抓住链接、留住全文、记录你的判断信号。加工和综述交给 AI。

```
微信文章 ──用DSH收集打开──▶ App（静默入库 + 抓全文）
                              │
                              ├─▶ L0 素材  collected/*.md + articles.jsonl
                              │      │  （含人工信号 high / neutral / low）
                              │      ▼
                              │   AI 初加工（L1）：去口语、留干货、抽要点、给标签
                              │      ▼
                              │   Obsidian/微信素材库/L1/<分类>/<标题>.md
                              │      │  （攒够一批后）
                              │      ▼
                              │   AI 综述（L2）：共识 / 分歧 / 可执行做法 / 未解问题
                              │      ▼
                              │   Obsidian/微信素材库/L2/<主题>.md
                              │
                              └─▶ 检索层 duckdb：kb/kb.duckdb，agent 随时全文搜
```

## App

- 包名 `com.dsh.collect`，Java + Gradle 8.2.2，**零第三方依赖**（不用 AndroidX）
- **静默收集**：透明 Activity + 无启动预览 + 独立 taskAffinity + 不进最近任务，秒退无界面
  - 微信有两条路径：`ACTION_VIEW + URL`（文章页「用…打开」）和 `ACTION_SEND + text/plain`（转发给）
  - 两条都接；标题在 `EXTRA_TEXT` 第一行
- **数据落地**：内部 SQLite（`articles` 表）。**故意不放 /sdcard**——外部存储走 FUSE，SQLite 锁不稳
- **导出**：`/sdcard/Download/DSH-Go/collected/`（YAML front matter + 全文）+ `articles.jsonl`
  - 需要「所有文件访问」权限
- **阅读器**：内嵌 WebView + 离线正文双模式；顶栏 ⋯ 菜单（复制/导出/入队/已读/归档/星标/标签/删除）
- **加工状态回读**：App 读 `articles.jsonl` 里的 `layer`，列表显示「已加工 ✓」
- 版本号走 `0.0.x`（还没成品），`versionCode` 同步递增

### 自动加工（v0.0.7+）

设置页（顶栏 ⚙）可配置：

| 项 | 说明 |
|---|---|
| API 地址 | OpenAI 兼容接口的**完整 URL**，如 `https://api.deepseek.com/v1/chat/completions` |
| API Key | 只存在本机 SharedPreferences，不上传 |
| 模型名 | 如 `deepseek-chat` |
| 温度 | 默认 0.4 |
| 提示词模板 | 可编辑，支持 `{{title}} {{account}} {{url}} {{signal}} {{content}}` |
| 收藏后自动加工 | 打开后：收集 → 抓正文 → 调模型 → 写 L1 笔记到 Obsidian `微信素材库/L1/` |

模型必须返回**严格 JSON**（`summary / key_points / takeaways / quote / tags / category / note`），
解析端容忍 ```json 包裹与前后缀文字。

抓取正文**不需要浏览器/爬虫框架**：`GET https://mp.weixin.qq.com/s/xxx` + 带 `MicroMessenger`
的 UA 即可拿到全文（已实测 12K 字正文）。PRoot 侧的 `scripts/fetch_article.py` 作为补抓兜底。

## 构建

本机没有 Android SDK，**走 GitHub Actions 云构建**（`.github/workflows/build.yml`）：

```bash
git push            # → Actions → app-debug artifact
gh run download <run-id> -n app-debug -D _artifact
```

本地离线查语法错误（不需要 SDK）：

```bash
# 需要 android.jar（platform-34）+ 一个手写的 R 桩类
javac -nowarn -encoding UTF-8 -cp aj.jar -d out stub/com/dsh/collect/R.java app/src/main/java/com/dsh/collect/*.java
```

## 脚本（agent 侧）

| 脚本 | 作用 |
|---|---|
| `scripts/fetch_article.py` | 抓公众号正文（UA 带 MicroMessenger；识别「环境异常」验证页 → exit 2） |
| `scripts/collect_lib.py` | front matter / jsonl 读写公共库 |
| `scripts/kb_pipeline.py` | L1/L2 管线的 prepare / apply / status |
| `scripts/kb_index.py` | duckdb 索引：build / search / stats |
| `scripts/archive_to_obsidian.py` | 早期的直接归档脚本（保留） |
| `scripts/list_collected.py` | 列出收藏（调试） |

```bash
python3 scripts/kb_pipeline.py l1-prepare      # 素材 → L1 任务包
#   ↓ AI 填写同名 .meta.json（summary/key_points/takeaways/quote/tags/category/note）
python3 scripts/kb_pipeline.py l1-apply        # → Obsidian/L1
python3 scripts/kb_pipeline.py l2-prepare      # L1 → 主题综述任务包
#   ↓ AI 填写 consensus/tensions/playbook/open_questions
python3 scripts/kb_pipeline.py l2-apply        # → Obsidian/L2
python3 scripts/kb_index.py build              # 建索引
python3 scripts/kb_index.py search "关键词"      # 检索
```

## 目录约定

```
/sdcard/Download/DSH-Go/
  collected/          L0 素材：*.md + articles.jsonl + archived/
  inbox/l1/           L1 任务包（*.md + *.meta.json）
  inbox/l2/           L2 任务包
  kb/kb.duckdb        检索索引
/sdcard/Documents/Obsidian/微信素材库/
  L1/<分类>/<标题>.md
  L2/<主题>.md
```

## 人工信号

| 操作 | signal | 含义 |
|---|---|---|
| 收藏 | `neutral` | 只是存下来了 |
| 星标 | `high` | 有价值，值得学 |
| 归档 | `low` | 不感兴趣 |

信号是给未来自动打标用的正负样本。
