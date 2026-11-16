package com.dsh.collect;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 「今日精选」：让模型从一批文章里挑出最值得看的几篇，并各给一句理由。
 *
 * <p>通知里的「今日精选」和 App 里的精选页共用这段逻辑。
 */
final class AiPicks {

    private AiPicks() {
    }

    /** 一条推荐 */
    static class Pick {
        String title = "";
        String reason = "";
        String url = "";
        String account = "";
    }

    /** 挑出最多 n 篇 */
    static List<Pick> pick(Context ctx, List<Article> articles, int n) {
        List<Pick> out = new ArrayList<>();
        if (articles == null || articles.isEmpty()) {
            return out;
        }
        Settings st = Settings.load(ctx);
        if (!st.isConfigured()) {
            return out;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("下面是用户收集的文章。请挑出最值得他花时间读的 ").append(n)
                .append(" 篇，并给每篇一句话理由（不超过 30 字，说明『为什么值得看』）。\n");
        sb.append("输出严格 JSON，不要解释文字：{\"picks\":[{\"title\":\"原文标题\","
                + "\"reason\":\"理由\"}]}\n\n");
        for (Article a : articles) {
            sb.append("- ").append(a.title);
            if (!a.account.isEmpty()) {
                sb.append("（").append(a.account).append("）");
            }
            if (!a.summary.isEmpty()) {
                sb.append("：").append(a.summary);
            }
            sb.append("\n");
        }
        try {
            LlmClient.Result r = LlmClient.complete(st.endpoint, st.apiKey, st.model, "",
                    sb.toString(), 0.6f, 30);
            if (!r.ok) {
                return out;
            }
            JSONObject o = LlmClient.parseJson(r.content);
            if (o == null) {
                return out;
            }
            JSONArray picks = o.optJSONArray("picks");
            if (picks == null) {
                return out;
            }
            for (int k = 0; k < picks.length() && out.size() < n; k++) {
                JSONObject p = picks.optJSONObject(k);
                if (p == null) {
                    continue;
                }
                Pick pk = new Pick();
                pk.title = p.optString("title", "").trim();
                pk.reason = p.optString("reason", "").trim();
                if (pk.title.isEmpty()) {
                    continue;
                }
                // 关联回原文章（模型返回的标题可能略有出入）
                for (Article a : articles) {
                    if (a.title.equals(pk.title) || a.title.contains(pk.title)
                            || pk.title.contains(a.title)) {
                        pk.url = a.url;
                        pk.account = a.account;
                        break;
                    }
                }
                out.add(pk);
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }
}
