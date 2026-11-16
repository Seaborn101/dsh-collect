package com.dsh.collect;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/**
 * 调用 OpenAI 兼容接口做初加工（L1）。
 *
 * <p>只发一个请求，不流式；要求模型返回严格 JSON，解析失败时做容错（剥代码块、截取花括号）。
 */
public final class LlmClient {

    private LlmClient() {
    }

    /** 调用结果 */
    static class Result {
        boolean ok = false;
        String error = "";
        /** 加工产物 */
        Article article = null;
        /** 原始返回（调试用） */
        String raw = "";
        /** 模型返回的正文内容 */
        String content = "";
    }

    /**
     * 发起一次补全请求。
     *
     * @param endpoint 完整 URL，例如 https://api.deepseek.com/v1/chat/completions
     * @param apiKey   密钥
     * @param model    模型名
     * @param system   系统提示词
     * @param user     用户提示词（已填好模板变量）
     * @param temp     温度
     */
    static Result complete(String endpoint, String apiKey, String model,
                           String system, String user, float temp, int timeoutSec) {
        Result r = new Result();
        if (endpoint == null || endpoint.trim().isEmpty()) {
            r.error = "未配置 API 地址";
            return r;
        }
        if (apiKey == null || apiKey.trim().isEmpty()) {
            r.error = "未配置 API Key";
            return r;
        }
        HttpURLConnection conn = null;
        try {
            JSONObject body = new JSONObject();
            body.put("model", model == null || model.isEmpty() ? "deepseek-chat" : model);
            body.put("temperature", temp);
            body.put("stream", false);

            JSONArray messages = new JSONArray();
            if (system != null && !system.isEmpty()) {
                JSONObject m = new JSONObject();
                m.put("role", "system");
                m.put("content", system);
                messages.put(m);
            }
            JSONObject um = new JSONObject();
            um.put("role", "user");
            um.put("content", user);
            messages.put(um);
            body.put("messages", messages);

            conn = (HttpURLConnection) new URL(endpoint.trim()).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(timeoutSec * 1000);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
            conn.setRequestProperty("Accept", "application/json");

            OutputStream os = conn.getOutputStream();
            try {
                os.write(body.toString().getBytes("UTF-8"));
                os.flush();
            } finally {
                os.close();
            }

            int code = conn.getResponseCode();
            String text;
            if (code == 200) {
                text = read(conn.getInputStream());
            } else {
                text = read(conn.getErrorStream());
                r.error = "HTTP " + code + ": " + clip(text, 200);
                return r;
            }
            r.raw = text;

            JSONObject resp = new JSONObject(text);
            JSONArray choices = resp.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                r.error = "返回里没有 choices";
                return r;
            }
            JSONObject first = choices.getJSONObject(0);
            JSONObject msg = first.optJSONObject("message");
            String content = msg != null ? msg.optString("content", "")
                    : first.optString("text", "");
            if (content.isEmpty()) {
                r.error = "返回内容为空";
                return r;
            }
            r.content = content;
            Article a = parseArticle(content);
            if (a == null) {
                r.error = "无法解析模型返回的 JSON：" + clip(content, 160);
                return r;
            }
            r.article = a;
            r.ok = true;
            return r;
        } catch (Exception e) {
            r.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            return r;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 从模型返回里提取 JSON 对象（容忍 ```json 包裹与前后废话） */
    static JSONObject parseJson(String content) {
        if (content == null) {
            return null;
        }
        String s = content.trim();
        if (s.startsWith("```")) {
            int i = s.indexOf('\n');
            int j = s.lastIndexOf("```");
            if (i > 0 && j > i) {
                s = s.substring(i + 1, j).trim();
            }
        }
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        if (a < 0 || b <= a) {
            return null;
        }
        try {
            return new JSONObject(s.substring(a, b + 1));
        } catch (Exception e) {
            return null;
        }
    }

    /** 把模型返回的 JSON 解析成 Article 的加工字段 */
    static Article parseArticle(String content) {
        String s = content.trim();
        // 去掉 ```json 包裹
        if (s.startsWith("```")) {
            int i = s.indexOf('\n');
            int j = s.lastIndexOf("```");
            if (i > 0 && j > i) {
                s = s.substring(i + 1, j).trim();
            }
        }
        // 截取第一个 { 到最后一个 }
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        if (a < 0 || b <= a) {
            return null;
        }
        try {
            JSONObject o = new JSONObject(s.substring(a, b + 1));
            Article art = new Article();
            art.summary = o.optString("summary", "");
            art.keyPoints = joinList(o.optJSONArray("key_points"));
            art.takeaways = joinList(o.optJSONArray("takeaways"));
            art.quote = o.optString("quote", "");
            art.tags = joinList(o.optJSONArray("tags")).replace(",", " ");
            art.category = o.optString("category", "");
            art.note = o.optString("note", "");
            art.layer = "L1";
            return art;
        } catch (Exception e) {
            return null;
        }
    }

    private static String joinList(JSONArray arr) {
        if (arr == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            String v = arr.optString(i, "").trim();
            if (v.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(v);
        }
        return sb.toString();
    }

    private static String read(java.io.InputStream in) throws java.io.IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line);
        }
        br.close();
        return sb.toString();
    }

    private static String clip(String s, int n) {
        if (s == null) {
            return "";
        }
        s = s.replace("\n", " ");
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }
}
