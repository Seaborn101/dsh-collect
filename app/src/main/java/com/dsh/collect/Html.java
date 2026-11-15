package com.dsh.collect;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** HTML 清洗：把公众号文章页面变成可读纯文本。 */
final class Html {

    private Html() {
    }

    private static final Pattern SCRIPT = Pattern.compile("(?is)<(script|style|noscript|iframe)[^>]*>.*?</\\1>");
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern BLOCK = Pattern.compile("(?i)</(p|div|br|li|h[1-6]|tr|section)>|<br\\s*/?>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern MULTI_NEWLINE = Pattern.compile("\n{3,}");
    private static final Pattern MULTI_SPACE = Pattern.compile("[ \\t\\u00a0]{2,}");

    /** 取文章正文片段（rich_media_content 区块） */
    static String extractMain(String html) {
        if (html == null) {
            return "";
        }
        int start = html.indexOf("rich_media_content");
        if (start < 0) {
            return html;
        }
        start = html.lastIndexOf('<', start);
        if (start < 0) {
            start = 0;
        }
        String[] markers = {
                "id=\"js_content_bottom\"", "id=\"content_bottom_area\"",
                "id=\"js_underline_content\"", "js_pc_qr_code", "js_related_article",
                "id=\"js_profile_qrcode\"", "<img class=\"rich_pages_inner"
        };
        int end = html.length();
        for (String m : markers) {
            int i = html.indexOf(m, start);
            if (i > start && i < end) {
                end = i;
            }
        }
        return html.substring(start, end);
    }

    /** HTML -> 纯文本 */
    static String toText(String html) {
        if (html == null) {
            return "";
        }
        String s = html;
        s = SCRIPT.matcher(s).replaceAll(" ");
        s = COMMENT.matcher(s).replaceAll(" ");
        s = BLOCK.matcher(s).replaceAll("\n");
        s = TAG.matcher(s).replaceAll(" ");
        s = decodeEntities(s);
        s = s.replace("\r\n", "\n").replace('\r', '\n');
        s = MULTI_SPACE.matcher(s).replaceAll(" ");
        s = MULTI_NEWLINE.matcher(s).replaceAll("\n\n");
        return s.trim();
    }

    /** HTML 实体解码（含数字实体） */
    static String decodeEntities(String s) {
        if (s == null) {
            return "";
        }
        String out = s;
        out = out.replace("&nbsp;", " ").replace("&ensp;", " ").replace("&emsp;", " ")
                .replace("&ldquo;", "“").replace("&rdquo;", "”")
                .replace("&lsquo;", "‘").replace("&rsquo;", "’")
                .replace("&mdash;", "—").replace("&ndash;", "–")
                .replace("&hellip;", "…").replace("&middot;", "·")
                .replace("&times;", "×").replace("&divide;", "÷")
                .replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">");
        // 数字实体 &#123; &#x1F600;
        return decodeNumeric(out);
    }

    /** 手写数字实体解码（避免依赖 Java 9 的函数式重载，Android 兼容性更稳） */
    private static String decodeNumeric(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            if (s.charAt(i) == '&' && i + 2 < n && s.charAt(i + 1) == '#') {
                int j = i + 2;
                boolean hex = j < n && (s.charAt(j) == 'x' || s.charAt(j) == 'X');
                if (hex) {
                    j++;
                }
                int start = j;
                while (j < n && (hex ? isHex(s.charAt(j)) : isDigit(s.charAt(j)))) {
                    j++;
                }
                if (j > start && j < n && s.charAt(j) == ';') {
                    try {
                        int code = Integer.parseInt(s.substring(start, j), hex ? 16 : 10);
                        if (code > 0 && code <= 0xFFFF) {
                            sb.append((char) code);
                            i = j + 1;
                            continue;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            sb.append(s.charAt(i));
            i++;
        }
        return sb.toString();
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** 取摘要：正文前 n 个字，尽量断在句号 */
    static String summary(String text, int max) {
        if (text == null) {
            return "";
        }
        String t = text.replace("\n", " ").trim();
        if (t.length() <= max) {
            return t;
        }
        String cut = t.substring(0, max);
        int dot = cut.lastIndexOf('。');
        if (dot > max / 3) {
            return cut.substring(0, dot + 1);
        }
        return cut + "…";
    }
}
