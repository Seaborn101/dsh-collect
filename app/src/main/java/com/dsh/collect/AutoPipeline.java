package com.dsh.collect;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * 自动加工流水线：收集 → 抓正文 → 调模型 → 写 L1 笔记。
 *
 * <p>跑在后台线程；App 崩了/被杀也不影响已写入的部分。
 */
public final class AutoPipeline {

    private AutoPipeline() {
    }

    /** 送入模型的正文上限（字符），防止超长上下文 */
    private static final int MAX_INPUT = 12000;

    /** 异步加工某一篇 */
    public static void processAsync(Context ctx, final long id) {
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    process(app, id);
                } catch (Throwable t) {
                    try {
                        Settings.load(app).setLastMessage(app, "加工异常：" + t);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }).start();
    }

    /** 同步加工某一篇 */
    public static void process(Context ctx, long id) {
        Db db = Db.get(ctx);
        Article a = db.byId(id);
        if (a == null) {
            return;
        }
        Settings st = Settings.load(ctx);
        if (!st.isConfigured()) {
            st.setLastMessage(ctx, "未配置模型（去设置里填 API 地址与 Key）");
            return;
        }

        // 1) 正文
        String content = a.contentText;
        if (content == null || content.length() < 200) {
            Meta.Result r = Meta.fetch(a.url);
            if (r.ok && r.text != null && r.text.length() > 100) {
                content = r.text;
                db.updateContent(a.id, r.html, r.text,
                        r.title.isEmpty() ? a.title : r.title,
                        r.account.isEmpty() ? a.account : r.account,
                        Html.summary(r.text, 120));
                a.title = r.title.isEmpty() ? a.title : r.title;
                a.account = r.account.isEmpty() ? a.account : r.account;
                a.contentText = r.text;
                a.summary = Html.summary(r.text, 120);
                if (!r.author.isEmpty() || r.publishedAt > 0) {
                    db.updateAuthor(a.id, r.author, r.publishedAt);
                }
            }
        }
        if (content == null || content.length() < 100) {
            st.setLastMessage(ctx, "正文抓取失败（可能被微信风控），可稍后重试");
            return;
        }
        if (content.length() > MAX_INPUT) {
            content = content.substring(0, MAX_INPUT);
        }

        // 2) 调模型
        LlmClient.Result lr = LlmClient.complete(
                st.endpoint, st.apiKey, st.model, "", st.buildPrompt(a, content),
                st.temperature, 180);
        if (!lr.ok) {
            st.setLastMessage(ctx, "模型调用失败：" + lr.error);
            Store.debug(ctx, "llm failed " + a.url + " -> " + lr.error);
            return;
        }

        // 3) 写回
        Article ai = lr.article;
        a.summary = ai.summary.isEmpty() ? a.summary : ai.summary;
        a.keyPoints = ai.keyPoints;
        a.takeaways = ai.takeaways;
        a.quote = ai.quote;
        a.note = ai.note;
        if (!ai.category.isEmpty()) {
            a.category = ai.category;
        }
        a.tags = mergeTags(a.tags, ai.tags);
        a.layer = "L1";
        a.fetchState = Article.FETCH_OK;
        db.upsert(a);
        db.updateLayer(a.id, "L1");
        db.updateStatus(a.id, "processed");

        // L0 原件留在 collected/，L1 笔记写进 Obsidian 知识库（索引能直接检索到）
        Store.exportMd(ctx, a);
        Store.exportToVault(ctx, a);
        Store.exportJsonl(ctx, db.all());
        st.setLastMessage(ctx, "已加工：" + a.title);
    }

    /** 合并标签（去重） */
    private static String mergeTags(String userTags, String aiTags) {
        List<String> out = new ArrayList<>();
        if (userTags != null) {
            for (String t : userTags.trim().split("\\s+")) {
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
        }
        if (aiTags != null) {
            for (String t : aiTags.trim().split("[\\s,，]+")) {
                if (!t.isEmpty() && !out.contains(t)) {
                    out.add(t);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String t : out) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(t);
        }
        return sb.toString();
    }
}
