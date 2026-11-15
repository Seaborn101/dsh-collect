package com.dsh.collect;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 抓取公众号文章页面：标题 / 公众号 / 正文纯文本。
 *
 * <p>微信对请求很敏感：UA 里必须带 MicroMessenger；偶尔会返回「环境异常」验证页，
 * 这时明确报错而不是把验证页当成正文存进去。
 */
final class Meta {

    /** 带 MicroMessenger 的移动端 UA */
    static final String UA = "Mozilla/5.0 (Linux; Android 14; V2323A Build/UP1A) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/124.0.0.0 "
            + "Mobile Safari/537.36 MicroMessenger/8.0.49";

    private Meta() {
    }

    /** 抓取结果 */
    static class Result {
        boolean ok = false;
        String title = "";
        String account = "";
        String author = "";
        long publishedAt = 0L;
        String text = "";
        String html = "";
        String error = "";
    }

    static Result fetch(String url) {
        Result r = new Result();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            conn.connect();
            int code = conn.getResponseCode();
            if (code != 200) {
                r.error = "HTTP " + code;
                return r;
            }
            String html = read(conn.getInputStream());
            r.html = html;
            if (html.contains("环境异常") || html.contains("当前环境异常")
                    || html.contains("请在微信客户端打开")) {
                r.error = "被微信风控拦截（环境异常页）";
                return r;
            }
            r.title = firstMatch(html, new String[]{
                    "<meta\\s+property=\"og:title\"\\s+content=\"([^\"]*)\"",
                    "<meta\\s+content=\"([^\"]*)\"\\s+property=\"og:title\"",
                    "var\\s+msg_title\\s*=\\s*['\"]([^'\"]*)['\"]",
                    "<h1[^>]*class=\"[^\"]*rich_media_title[^\"]*\"[^>]*>(.*?)</h1>"
            });
            r.account = pickAccount(html);
            r.title = Html.decodeEntities(r.title).trim();
            r.account = Html.decodeEntities(r.account).trim();
            r.author = Html.decodeEntities(pickAuthor(html)).trim();
            r.publishedAt = pickPublished(html);
            r.text = Html.toText(Html.extractMain(html));
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

    /** 原文作者（可选，拿不到就留空） */
    private static String pickAuthor(String html) {
        String[] patterns = {
                "<meta\\s+name=\"author\"\\s+content=\"([^\"]*)\"",
                "var\\s+author\\s*=\\s*['\"]([^'\"]*)['\"]",
                "<span[^>]*id=\"js_author_name\"[^>]*>(.*?)</span>",
                "<a[^>]*id=\"js_author_name\"[^>]*>(.*?)</a>",
                "<strong[^>]*class=\"[^\"]*rich_media_meta[^\"]*\"[^>]*>(.*?)</strong>"
        };
        return firstMatch(html, patterns);
    }

    /** 原文发布时间（返回毫秒；拿不到返回 0） */
    private static long pickPublished(String html) {
        String ct = firstMatch(html, new String[]{"var\\s+ct\\s*=\\s*['\"]?(\\d{10})"});
        if (!ct.isEmpty()) {
            try {
                return Long.parseLong(ct) * 1000L;
            } catch (Exception ignored) {
            }
        }
        String s = firstMatch(html, new String[]{
                "<em[^>]*id=\"publish_time\"[^>]*>(.*?)</em>",
                "<span[^>]*id=\"publish_time\"[^>]*>(.*?)</span>",
                "var\\s+publish_time\\s*=\\s*['\"]([^'\"]*)['\"]"
        });
        if (s.isEmpty()) {
            return 0L;
        }
        s = s.trim().replace("年", "-").replace("月", "-").replace("日", "");
        String[] parts = s.split("[-/: ]");
        try {
            if (parts.length >= 3) {
                int y = Integer.parseInt(parts[0]);
                int m = Integer.parseInt(parts[1]);
                int d = Integer.parseInt(parts[2]);
                java.util.Calendar c = java.util.Calendar.getInstance();
                c.clear();
                c.set(y, m - 1, d);
                return c.getTimeInMillis();
            }
        } catch (Exception ignored) {
        }
        return 0L;
    }

    /**
     * 取公众号名。
     *
     * <p>注意：{@code og:site_name} 在微信文章里恒等于「微信公众平台」，不是真账号名，
     * 所以必须优先用 {@code var nickname} / {@code <a id="js_name">}，og 只作最后兜底。
     */
    private static String pickAccount(String html) {
        String[] patterns = {
                "var\\s+nickname\\s*=\\s*['\"]([^'\"]*)['\"]",
                "<a[^>]*id=\"js_name\"[^>]*>(.*?)</a>",
                "<meta\\s+property=\"og:site_name\"\\s+content=\"([^\"]*)\"",
                "<meta\\s+content=\"([^\"]*)\"\\s+property=\"og:site_name\""
        };
        String fallback = "";
        for (String p : patterns) {
            String v = firstMatch(html, new String[]{p});
            if (v.isEmpty()) {
                continue;
            }
            if (v.equals("微信公众平台")) {
                // 通用占位名，记下来但不当真账号
                if (fallback.isEmpty()) {
                    fallback = v;
                }
                continue;
            }
            return v;
        }
        return fallback;
    }

    private static String firstMatch(String html, String[] patterns) {
        for (String p : patterns) {
            try {
                Matcher m = Pattern.compile(p, Pattern.DOTALL).matcher(html);
                if (m.find()) {
                    String v = m.group(1);
                    if (v != null) {
                        v = v.trim();
                        if (!v.isEmpty()) {
                            return v;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    private static String read(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
            total += n;
            if (total > 1024 * 1024) {
                break;
            }
        }
        in.close();
        return bos.toString("UTF-8");
    }

    /**
     * 从微信分享出来的文本里猜标题。
     *
     * <p>微信 SEND 的文本通常是「文章标题 + 换行 + 链接」，也可能只有链接。
     *
     * @return 猜到的标题；没有就返回空串
     */
    static String guessTitle(String sharedText) {
        if (sharedText == null) {
            return "";
        }
        String[] lines = sharedText.split("\\r?\\n");
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (t.startsWith("http://") || t.startsWith("https://")) {
                continue;
            }
            // 去掉常见的尾巴
            t = t.replace("点击上方“蓝字”关注我们", "").trim();
            if (t.length() < 2) {
                continue;
            }
            return t;
        }
        return "";
    }
}
