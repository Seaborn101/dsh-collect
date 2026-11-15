package com.dsh.collect;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 抓取公众号文章页面的标题 / 公众号名。
 *
 * <p>只做尽力而为：微信对非浏览器请求可能返回「环境异常」验证页，
 * 抓不到就用降级标题，链接本身才是最有价值的部分。
 */
final class Meta {

    /** 带上 MicroMessenger 的移动端 UA，兼容性最好 */
    static final String UA = "Mozilla/5.0 (Linux; Android 14; V2323A Build/UP1A) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/124.0.0.0 "
            + "Mobile Safari/537.36 MicroMessenger/8.0.49";

    private Meta() {
    }

    /** 抓取结果：title / account */
    static class Info {
        String title = "";
        String account = "";
    }

    static Info fetch(String url) {
        Info info = new Info();
        String html = get(url);
        if (html == null || html.isEmpty()) {
            return info;
        }
        info.title = firstMatch(html, new String[]{
                "<meta\\s+property=\"og:title\"\\s+content=\"([^\"]*)\"",
                "<meta\\s+content=\"([^\"]*)\"\\s+property=\"og:title\"",
                "var\\s+msg_title\\s*=\\s*['\"]([^'\"]*)['\"]",
                "<h1[^>]*class=\"[^\"]*rich_media_title[^\"]*\"[^>]*>(.*?)</h1>"
        });
        info.account = firstMatch(html, new String[]{
                "<meta\\s+property=\"og:site_name\"\\s+content=\"([^\"]*)\"",
                "<meta\\s+content=\"([^\"]*)\"\\s+property=\"og:site_name\"",
                "var\\s+nickname\\s*=\\s*['\"]([^'\"]*)['\"]",
                "<a[^>]*id=\"js_name\"[^>]*>(.*?)</a>"
        });
        if (!info.title.isEmpty()) {
            info.title = decode(info.title);
        }
        if (!info.account.isEmpty()) {
            info.account = decode(info.account);
        }
        // 标题里有时带着公众号后缀，如「xxx（新智元）」，去掉
        return info;
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

    private static String decode(String s) {
        return s.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ")
                .replace("&#x27;", "'")
                .replace("&#x2F;", "/");
    }

    private static String get(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml");
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            conn.connect();
            int code = conn.getResponseCode();
            if (code != 200) {
                return null;
            }
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                total += n;
                if (total > 400 * 1024) {
                    break;
                }
            }
            in.close();
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
