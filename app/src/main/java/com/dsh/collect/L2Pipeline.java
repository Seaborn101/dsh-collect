package com.dsh.collect;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * L2 主题综述：把同一分类下多篇 L1 笔记交给模型，做跨篇归纳。
 *
 * <p>产出写到 Obsidian 微信素材库/L2/&lt;主题&gt;.md，与 agent 侧 kb_pipeline 的格式一致。
 */
public final class L2Pipeline {

    private L2Pipeline() {
    }

    /** 结果 */
    static class Result {
        boolean ok = false;
        String error = "";
        String path = "";
        String title = "";
    }

    /** 分类 → 篇数 */
    static Map<String, Integer> categories(Context ctx) {
        Map<String, Integer> map = new LinkedHashMap<>();
        File l1 = new File(Store.VAULT_DIR, "L1");
        File[] dirs = l1.listFiles();
        if (dirs == null) {
            return map;
        }
        for (File d : dirs) {
            if (!d.isDirectory()) {
                continue;
            }
            File[] fs = d.listFiles();
            int n = 0;
            if (fs != null) {
                for (File f : fs) {
                    if (f.isFile() && f.getName().endsWith(".md")) {
                        n++;
                    }
                }
            }
            if (n > 0) {
                map.put(d.getName(), n);
            }
        }
        return map;
    }

    /** 异步生成某个分类的综述 */
    static void generateAsync(final Context ctx, final String category, final Callback cb) {
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Result r = generate(app, category);
                cb.done(r);
            }
        }).start();
    }

    interface Callback {
        void done(Result r);
    }

    /** 同步生成综述 */
    static Result generate(Context ctx, String category) {
        Result r = new Result();
        List<Article> notes = readNotes(category);
        if (notes.size() < 2) {
            r.error = "这个分类下只有 " + notes.size() + " 篇，至少要 2 篇才能综述";
            return r;
        }
        Settings st = Settings.load(ctx);
        if (!st.isConfigured()) {
            r.error = "未配置模型（设置里填 API 地址与 Key）";
            return r;
        }
        String prompt = st.buildL2Prompt(category, notes);

        LlmClient.Result lr = LlmClient.complete(st.endpoint, st.apiKey, st.model, "",
                prompt, st.temperature, 300);
        if (!lr.ok) {
            r.error = "模型调用失败：" + lr.error;
            Store.debug(ctx, "l2 failed " + category + " -> " + lr.error);
            return r;
        }
        JSONObject o = LlmClient.parseJson(lr.content);
        if (o == null) {
            r.error = "无法解析模型返回的 JSON";
            return r;
        }
        String title = o.optString("title", category + " 综述");
        String summary = o.optString("summary", "");
        List<String> consensus = strList(o.optJSONArray("consensus"));
        List<String> tensions = strList(o.optJSONArray("tensions"));
        List<String> playbook = strList(o.optJSONArray("playbook"));
        List<String> questions = strList(o.optJSONArray("open_questions"));
        List<String> tags = strList(o.optJSONArray("tags"));

        File dir = new File(Store.VAULT_DIR, "L2");
        if (!dir.exists() && !dir.mkdirs()) {
            r.error = "无法创建 L2 目录：" + dir.getAbsolutePath();
            return r;
        }
        File f = new File(dir, Store.titleSlug(title) + ".md");
        Writer w = null;
        try {
            w = new OutputStreamWriter(new java.io.FileOutputStream(f, false), "UTF-8");
            w.write("---\n");
            w.write("title: " + title + "\n");
            w.write("layer: L2\n");
            w.write("category: " + category + "\n");
            w.write("collected: "
                    + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date()) + "\n");
            w.write("tags: [" + join(tags) + "]\n");
            w.write("summary: " + oneLine(summary) + "\n");
            w.write("---\n\n");
            w.write("# " + title + "\n\n");
            if (!summary.isEmpty()) {
                w.write("> [!abstract] 这个主题说明白了什么\n");
                w.write("> " + oneLine(summary) + "\n\n");
            }
            writeSection(w, "共识", consensus);
            writeSection(w, "分歧与边界", tensions);
            writeSection(w, "可执行的做法", playbook);
            writeSection(w, "还没回答的问题", questions);
            w.write("## 素材清单\n\n");
            for (int i = 0; i < notes.size(); i++) {
                Article a = notes.get(i);
                w.write((i + 1) + ". **" + a.title + "**（"
                        + (a.account.isEmpty() ? "未知公众号" : a.account) + "，" + a.url + "）\n");
                if (!a.summary.isEmpty()) {
                    w.write("   - 摘要：" + oneLine(a.summary) + "\n");
                }
            }
            w.write("\n");
        } catch (Exception e) {
            r.error = "写文件失败：" + e.getMessage();
            return r;
        } finally {
            Store.close(w);
        }
        r.ok = true;
        r.path = f.getAbsolutePath();
        r.title = title;
        Store.debug(ctx, "l2 written " + r.path);
        return r;
    }

    private static void writeSection(Writer w, String heading, List<String> items) throws Exception {
        if (items.isEmpty()) {
            return;
        }
        w.write("## " + heading + "\n\n");
        for (String s : items) {
            w.write("- " + oneLine(s) + "\n");
        }
        w.write("\n");
    }

    /** 读取某分类下的 L1 笔记 */
    private static List<Article> readNotes(String category) {
        List<Article> out = new ArrayList<>();
        File dir = new File(Store.VAULT_DIR, "L1/" + category);
        File[] fs = dir.listFiles();
        if (fs == null) {
            return out;
        }
        for (File f : fs) {
            if (!f.isFile() || !f.getName().endsWith(".md")) {
                continue;
            }
            Article a = Import.parseMd(f);
            if (a == null) {
                continue;
            }
            // 正文里其实已经有要点/结论，这里保留摘要即可，细节进 prompt
            if (a.contentText != null && a.contentText.length() > 2000) {
                a.contentText = a.contentText.substring(0, 2000);
            }
            out.add(a);
        }
        return out;
    }

    private static List<String> strList(org.json.JSONArray arr) {
        List<String> out = new ArrayList<>();
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            String v = arr.optString(i, "").trim();
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        return out;
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(s.replace(",", " "));
        }
        return sb.toString();
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replace("\n", " ").replace("\r", " ").trim();
    }
}
