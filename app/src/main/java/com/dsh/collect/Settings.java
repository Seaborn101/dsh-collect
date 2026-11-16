package com.dsh.collect;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;

/**
 * App 配置：模型接入 + 提示词模板。
 *
 * <p>全部存在本机 SharedPreferences（不联网同步、不上传）。
 */
public final class Settings {

    private Settings() {
    }

    private static final String PREFS = "dsh_collect_settings";

    private static final String K_ENDPOINT = "endpoint";
    private static final String K_API_KEY = "api_key";
    private static final String K_MODEL = "model";
    private static final String K_TEMP = "temperature";
    private static final String K_PROMPT = "prompt";
    private static final String K_AUTO = "auto_process";
    private static final String K_LAST = "last_message";
    private static final String K_PROMPT_L2 = "prompt_l2";
    private static final String K_REMIND = "remind";
    private static final String K_REMIND_H = "remind_hour";
    private static final String K_REMIND_M = "remind_minute";

    /** 默认：OpenAI 兼容接口的完整地址 */
    public static final String DEFAULT_ENDPOINT = "https://api.deepseek.com/v1/chat/completions";
    public static final String DEFAULT_MODEL = "deepseek-chat";

    /** 默认提示词模板（用户可改） */
    public static final String DEFAULT_PROMPT =
            "你是个人知识库的加工助手。请阅读下面这篇公众号文章，输出**严格的 JSON**，"
            + "不要任何解释文字，不要 Markdown 代码块。\n"
            + "\n"
            + "JSON 字段：\n"
            + "{\n"
            + "  \"summary\": \"一句话说清这篇讲了什么，≤60字，不要『本文介绍了』这种开头\",\n"
            + "  \"key_points\": [\"3-6条干货要点，去掉口语与营销话术，保留具体数字、方法、结论\"],\n"
            + "  \"takeaways\": [\"2-4条可复用的结论或做法，脱离原文也能看懂\"],\n"
            + "  \"quote\": \"全文最值得记的一句原文，≤120字\",\n"
            + "  \"tags\": [\"3-6个用于检索聚类的标签\"],\n"
            + "  \"category\": \"分类名，如 编程工具 / AI Agent / 行业与商业 / 效率与工具\",\n"
            + "  \"note\": \"一句话点评：这篇值不值得长期记住，为什么\"\n"
            + "}\n"
            + "\n"
            + "文章标题：{{title}}\n"
            + "公众号：{{account}}\n"
            + "链接：{{url}}\n"
            + "\n"
            + "正文：\n"
            + "{{content}}";

    /** 默认综述提示词（多篇 → 一篇） */
    public static final String DEFAULT_PROMPT_L2 =
            "你在帮一个人整理个人知识库。下面是同一主题下的多篇笔记，请做**跨篇归纳**，"
            + "输出**严格的 JSON**，不要解释文字，不要 Markdown 代码块。\n"
            + "\n"
            + "JSON 字段：\n"
            + "{\n"
            + "  \"title\": \"主题标题\",\n"
            + "  \"summary\": \"这批文章合起来说明白了什么（2-4句）\",\n"
            + "  \"consensus\": [\"多篇都指向的共识\"],\n"
            + "  \"tensions\": [\"互相矛盾或前提不同的地方\"],\n"
            + "  \"playbook\": [\"看完能直接照做的步骤或检查清单\"],\n"
            + "  \"open_questions\": [\"这批文章还没回答的问题\"],\n"
            + "  \"tags\": [\"主题标签\"]\n"
            + "}\n"
            + "\n"
            + "要求：只写这批材料支持的结论；不要为了凑数编造；"
            + "playbook 必须是可执行的动作，而不是感想。\n"
            + "\n"
            + "主题分类：{{category}}\n"
            + "笔记数：{{count}}\n"
            + "\n"
            + "{{content}}";

    public static Settings load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Settings s = new Settings();
        s.endpoint = p.getString(K_ENDPOINT, DEFAULT_ENDPOINT);
        s.apiKey = p.getString(K_API_KEY, "");
        s.model = p.getString(K_MODEL, DEFAULT_MODEL);
        s.temperature = p.getFloat(K_TEMP, 0.4f);
        s.prompt = p.getString(K_PROMPT, DEFAULT_PROMPT);
        s.autoProcess = p.getBoolean(K_AUTO, false);
        s.lastMessage = p.getString(K_LAST, "");
        s.promptL2 = p.getString(K_PROMPT_L2, DEFAULT_PROMPT_L2);
        s.autoRemind = p.getBoolean(K_REMIND, true);
        s.remindHour = p.getInt(K_REMIND_H, 21);
        s.remindMinute = p.getInt(K_REMIND_M, 0);
        return s;
    }

    public void save(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.edit()
                .putString(K_ENDPOINT, endpoint)
                .putString(K_API_KEY, apiKey)
                .putString(K_MODEL, model)
                .putFloat(K_TEMP, temperature)
                .putString(K_PROMPT, prompt)
                .putBoolean(K_AUTO, autoProcess)
                .putString(K_LAST, lastMessage)
                .putString(K_PROMPT_L2, promptL2)
                .putBoolean(K_REMIND, autoRemind)
                .putInt(K_REMIND_H, remindHour)
                .putInt(K_REMIND_M, remindMinute)
                .apply();
    }

    public void setLastMessage(Context ctx, String msg) {
        lastMessage = msg;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(K_LAST, msg).apply();
    }

    /** 是否已经配置好（可以发起调用） */
    public boolean isConfigured() {
        return endpoint != null && !endpoint.trim().isEmpty()
                && apiKey != null && !apiKey.trim().isEmpty();
    }

    /** 用模板生成 user prompt */
    public String buildPrompt(Article a, String content) {
        String t = prompt == null || prompt.isEmpty() ? DEFAULT_PROMPT : prompt;
        return t.replace("{{title}}", safe(a.title))
                .replace("{{account}}", safe(a.account))
                .replace("{{url}}", safe(a.url))
                .replace("{{signal}}", safe(a.signal))
                .replace("{{content}}", content == null ? "" : content);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    public String endpoint = DEFAULT_ENDPOINT;
    public String apiKey = "";
    public String model = DEFAULT_MODEL;
    public float temperature = 0.4f;
    public String prompt = DEFAULT_PROMPT;
    public boolean autoProcess = false;
    public String lastMessage = "";
    public String promptL2 = DEFAULT_PROMPT_L2;
    public boolean autoRemind = true;
    public int remindHour = 21;
    public int remindMinute = 0;

    /** 用 L2 模板生成 prompt */
    public String buildL2Prompt(String category, List<Article> notes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < notes.size(); i++) {
            Article a = notes.get(i);
            sb.append("### ").append(i + 1).append(". ").append(a.title).append("\n");
            sb.append("公众号：").append(a.account).append("\n");
            if (!a.summary.isEmpty()) {
                sb.append("摘要：").append(a.summary).append("\n");
            }
            if (a.contentText != null && !a.contentText.trim().isEmpty()) {
                sb.append("笔记：\n").append(a.contentText.trim()).append("\n");
            }
            sb.append("\n");
        }
        String t = promptL2 == null || promptL2.isEmpty() ? DEFAULT_PROMPT_L2 : promptL2;
        return t.replace("{{category}}", category == null ? "" : category)
                .replace("{{count}}", String.valueOf(notes.size()))
                .replace("{{content}}", sb.toString());
    }
}
