package com.dsh.collect;

import android.content.Context;
import android.content.SharedPreferences;

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
}
