package com.dsh.collect;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 自愈导入：App 被卸载/重装后，SQLite 会被清空，但导出到共享目录的
 * Markdown 与 articles.jsonl 还在。把它们读回来，App 就"恢复如初"。
 *
 * <p>顺带解决另一个问题：导出文件名依赖 DB 里的 export_name，
 * DB 一丢就会生成重名孤儿文件；导入时恢复 export_name 就不会有重复了。
 */
public final class Import {

    private Import() {
    }

    /** 导入结果 */
    static class Result {
        int added = 0;
        int updated = 0;
    }

    /** 从共享目录恢复全部素材（任何异常都不许把 App 带崩） */
    public static Result restore(Context ctx, Db db) {
        try {
            return restoreInternal(ctx, db);
        } catch (Throwable t) {
            try {
                Store.debug(ctx, "import failed: " + t);
            } catch (Throwable ignored) {
            }
            return new Result();
        }
    }

    private static Result restoreInternal(Context ctx, Db db) {
        Result r = new Result();
        File dir = Store.collectedDir(ctx);
        if (dir == null || !dir.exists()) {
            return r;
        }

        Map<String, Article> byUrl = new HashMap<>();

        // 1) 读 Markdown（顶层 + archived）
        List<File> mds = new ArrayList<>();
        collectMd(dir, mds, 0);
        for (File f : mds) {
            Article a = parseMd(f);
            if (a == null || a.url.isEmpty()) {
                continue;
            }
            Article prev = byUrl.get(a.url);
            if (prev == null) {
                byUrl.put(a.url, a);
            } else if (bodyLen(a) > bodyLen(prev)) {
                // 同一篇文章可能有「占位版 + 完整版」两个文件（DB 丢过时导出产生的），
                // 保留内容多的那个
                byUrl.put(a.url, a);
            }
        }

        // 2) 读 jsonl（agent 侧状态更权威：加工层级、标签、状态、星标）
        Map<String, JSONObject> jsonl = new HashMap<>();
        File jf = new File(dir, "articles.jsonl");
        if (jf.exists()) {
            BufferedReader br = null;
            try {
                br = new BufferedReader(new InputStreamReader(new FileInputStream(jf), "UTF-8"));
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    try {
                        JSONObject o = new JSONObject(line);
                        String url = o.optString("url", "");
                        if (!url.isEmpty()) {
                            jsonl.put(url, o);
                        }
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            } finally {
                Store.close(br);
            }
        }

        // 3) 合并：以 md 为主体，jsonl 补状态
        for (Map.Entry<String, Article> e : byUrl.entrySet()) {
            Article a = e.getValue();
            JSONObject o = jsonl.get(e.getKey());
            if (o != null) {
                if (o.has("title") && !o.optString("title").isEmpty()) {
                    a.title = o.optString("title");
                }
                if (o.has("account") && !o.optString("account").isEmpty()) {
                    a.account = o.optString("account");
                }
                if (o.has("summary") && !o.optString("summary").isEmpty()) {
                    a.summary = o.optString("summary");
                }
                a.tags = o.optString("tags", a.tags);
                a.status = o.optString("status", a.status);
                a.layer = o.optString("layer", a.layer);
                a.starred = o.optBoolean("starred", a.starred);
                long ts = o.optLong("collected_ts", 0L);
                if (ts > 0) {
                    a.collectedAt = ts;
                }
            }

            Article exist = db.byUrl(a.url);
            if (exist == null) {
                db.upsert(a);
                r.added++;
            } else {
                // 已存在：只补空缺，别覆盖用户后来的改动
                boolean changed = false;
                if ((exist.title == null || exist.title.isEmpty()) && !a.title.isEmpty()) {
                    exist.title = a.title;
                    changed = true;
                }
                if ((exist.account == null || exist.account.isEmpty()) && !a.account.isEmpty()) {
                    exist.account = a.account;
                    changed = true;
                }
                if ((exist.contentText == null || exist.contentText.isEmpty())
                        && a.contentText != null && !a.contentText.isEmpty()) {
                    exist.contentText = a.contentText;
                    exist.fetchState = Article.FETCH_OK;
                    changed = true;
                }
                if ((exist.summary == null || exist.summary.isEmpty()) && !a.summary.isEmpty()) {
                    exist.summary = a.summary;
                    changed = true;
                }
                if ((exist.exportName == null || exist.exportName.isEmpty())
                        && a.exportName != null && !a.exportName.isEmpty()) {
                    exist.exportName = a.exportName;
                    changed = true;
                }
                if (!"L0".equals(a.layer) && !"L0".equals(exist.layer)) {
                    exist.layer = a.layer;
                    changed = true;
                }
                if (changed) {
                    db.upsert(exist);
                    r.updated++;
                }
            }
        }
        // 4) 把无权限期间落在 App 私有目录的素材提升到共享目录（共享目录才是唯一真相）
        if (Store.canUseShared(ctx)) {
            try {
                Store.exportAll(ctx, db.all());
            } catch (Exception ignored) {
            }
        }
        return r;
    }

    private static int bodyLen(Article a) {
        return a == null || a.contentText == null ? 0 : a.contentText.length();
    }

    private static void collectMd(File dir, List<File> out, int depth) {
        if (depth > 2) {
            return;
        }
        File[] fs = dir.listFiles();
        if (fs == null) {
            return;
        }
        for (File f : fs) {
            if (f.isDirectory()) {
                if (f.getName().equals("archived")) {
                    collectMd(f, out, depth + 1);
                }
            } else if (f.getName().endsWith(".md")) {
                out.add(f);
            }
        }
    }

    /** 解析导出的 Markdown：front matter + 正文 */
    static Article parseMd(File f) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            Article a = new Article();
            a.exportName = f.getName();
            a.fetchState = Article.FETCH_OK;
            a.layer = "L0";

            String line = br.readLine();
            if (line == null || !line.trim().equals("---")) {
                return null;
            }
            StringBuilder body = new StringBuilder();
            boolean inFront = true;
            while ((line = br.readLine()) != null) {
                if (inFront) {
                    if (line.trim().equals("---")) {
                        inFront = false;
                        continue;
                    }
                    int idx = line.indexOf(':');
                    if (idx <= 0) {
                        continue;
                    }
                    String k = line.substring(0, idx).trim().toLowerCase();
                    String v = line.substring(idx + 1).trim();
                    if (k.equals("title")) {
                        a.title = v;
                    } else if (k.equals("account")) {
                        a.account = v;
                    } else if (k.equals("url")) {
                        a.url = v;
                    } else if (k.equals("summary")) {
                        a.summary = v;
                    } else if (k.equals("tags")) {
                        a.tags = v;
                    } else if (k.equals("status")) {
                        a.status = v;
                    } else if (k.equals("layer")) {
                        a.layer = v;
                    } else if (k.equals("signal")) {
                        a.signal = v;
                    } else if (k.equals("collected")) {
                        a.collectedAt = parseTime(v);
                    }
                } else {
                    body.append(line).append('\n');
                }
            }
            a.contentText = body.toString().trim();
            if (a.url.isEmpty()) {
                return null;
            }
            if (a.status == null || a.status.isEmpty()) {
                a.status = Article.STATUS_NEW;
            }
            return a;
        } catch (Exception e) {
            return null;
        } finally {
            Store.close(br);
        }
    }

    /** "2026-11-15 19:28" -> 毫秒 */
    static long parseTime(String s) {
        if (s == null || s.isEmpty()) {
            return 0L;
        }
        try {
            java.text.SimpleDateFormat f =
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US);
            try {
                return f.parse(s).getTime();
            } catch (Exception e) {
                f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US);
                return f.parse(s).getTime();
            }
        } catch (Exception e) {
            return 0L;
        }
    }
}
