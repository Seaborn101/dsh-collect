package com.dsh.collect;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 共享目录与导出。
 *
 * <p>共享根目录：/sdcard/Download/DSH-Go
 * <ul>
 *   <li>{@code collected/} —— 每篇文章一个 Markdown（Obsidian 友好）+ articles.jsonl 索引</li>
 *   <li>{@code inbox/} —— 「待整理队列」，交给 dsh-go / agent 批量抓取与归档</li>
 * </ul>
 *
 * <p>写共享目录需要「所有文件访问」权限；未授权时退回 App 私有目录，App 自己仍可用。
 */
public final class Store {

    public static final String ROOT_REL = "Download/DSH-Go";
    public static final String COLLECTED_REL = "collected";
    public static final String INBOX_REL = "inbox";

    private Store() {
    }

    // ------------------------------------------------------------ 目录

    /** 是否具备读写共享目录的权限 */
    public static boolean canUseShared(Context ctx) {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        return ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 共享根目录（未授权时返回 null） */
    public static File sharedRoot(Context ctx) {
        if (!canUseShared(ctx)) {
            return null;
        }
        File f = new File(Environment.getExternalStorageDirectory(), ROOT_REL);
        if (f.exists() || f.mkdirs()) {
            return f;
        }
        return null;
    }

    /** 收藏目录（共享；未授权时用 App 私有目录） */
    public static File collectedDir(Context ctx) {
        File root = sharedRoot(ctx);
        if (root != null) {
            File d = new File(root, COLLECTED_REL);
            if (d.exists() || d.mkdirs()) {
                return d;
            }
        }
        return privateDir(ctx, COLLECTED_REL);
    }

    /** 待整理队列目录 */
    public static File inboxDir(Context ctx) {
        File root = sharedRoot(ctx);
        if (root != null) {
            File d = new File(root, INBOX_REL);
            if (d.exists() || d.mkdirs()) {
                return d;
            }
        }
        return privateDir(ctx, INBOX_REL);
    }

    private static File privateDir(Context ctx, String name) {
        File base = ctx.getExternalFilesDir(null);
        File d = new File(base, name);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    // ------------------------------------------------------------ 工具

    public static String stamp(long time) {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(time));
    }

    public static String readable(long time) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(time));
    }

    private static String safeName(String s) {
        if (s == null || s.isEmpty()) {
            return "untitled";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 30; i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                sb.append(c);
            } else if (c == ' ' || c == '·' || c == '，' || c == '。') {
                sb.append('-');
            }
        }
        String out = sb.toString().replaceAll("-+", "-").replaceAll("^-|-$", "");
        return out.isEmpty() ? "untitled" : out;
    }

    // ------------------------------------------------------------ 导出

    /**
     * 导出单篇为 Markdown（正文一并写入，方便离线阅读与 agent 处理）。
     *
     * <p>文件名跟着标题走，但**同一个 url 只有一个文件**：标题变了就重命名，
     * 不会像早期版本那样留下「占位标题 + 真标题」两份孤儿文件。
     */
    public static File exportMd(Context ctx, Article a) {
        File dir = collectedDir(ctx);
        String desired = stamp(a.collectedAt) + "_" + safeName(a.title) + ".md";
        String old = a.exportName;
        if (old == null || old.isEmpty()) {
            a.exportName = desired;
        } else if (!old.equals(desired)) {
            File of = new File(dir, old);
            File nf = new File(dir, desired);
            if (of.exists() && !nf.exists()) {
                //noinspection ResultOfMethodCallIgnored
                of.renameTo(nf);
            }
            a.exportName = desired;
        }
        File f = new File(dir, a.exportName);
        writeMarkdown(f, a);
        return f;
    }

    /** 重写全部 Markdown（用于标题变更后同步文件名与内容） */
    public static void exportAll(Context ctx, List<Article> articles) {
        for (Article a : articles) {
            exportMd(ctx, a);
        }
        exportJsonl(ctx, articles);
    }

    /** 导出 JSONL 索引 */
    public static File exportJsonl(Context ctx, List<Article> articles) {
        File dir = collectedDir(ctx);
        File f = new File(dir, "articles.jsonl");
        Writer w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(f, false), "UTF-8");
            for (Article a : articles) {
                w.write(jsonLine(a));
                w.write("\n");
            }
        } catch (Exception e) {
            // 忽略
        } finally {
            close(w);
        }
        return f;
    }

    /** 把文章放进待整理队列，供 agent 批量处理 */
    public static File addToInbox(Context ctx, Article a) {
        File dir = inboxDir(ctx);
        File f = new File(dir, stamp(a.collectedAt) + "_" + safeName(a.title) + ".md");
        writeMarkdown(f, a);
        return f;
    }

    private static void writeMarkdown(File f, Article a) {
        Writer w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(f, false), "UTF-8");
            w.write("---\n");
            w.write("title: " + oneLine(a.title) + "\n");
            w.write("account: " + oneLine(a.account) + "\n");
            w.write("url: " + oneLine(a.url) + "\n");
            w.write("source: " + oneLine(a.source) + "\n");
            w.write("collected: " + readable(a.collectedAt) + "\n");
            w.write("tags: " + oneLine(a.tags) + "\n");
            w.write("status: " + oneLine(a.status) + "\n");
            w.write("summary: " + oneLine(a.summary) + "\n");
            w.write("---\n\n");
            w.write("# " + a.title + "\n\n");
            w.write("> 来源：" + (a.account.isEmpty() ? "未知公众号" : a.account) + "\n");
            w.write("> 链接：" + a.url + "\n");
            w.write("> 收藏于 " + readable(a.collectedAt) + "\n\n");
            if (a.contentText != null && !a.contentText.isEmpty()) {
                w.write(a.contentText);
                w.write("\n\n");
            }
            w.write("<!-- 正文结束 -->\n");
        } catch (Exception e) {
            // 忽略
        } finally {
            close(w);
        }
    }

    /** 一行 JSON */
    private static String jsonLine(Article a) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"id\":").append(a.id);
        sb.append(",\"url\":\"").append(esc(a.url)).append("\"");
        sb.append(",\"title\":\"").append(esc(a.title)).append("\"");
        sb.append(",\"account\":\"").append(esc(a.account)).append("\"");
        sb.append(",\"source\":\"").append(esc(a.source)).append("\"");
        sb.append(",\"collected_at\":\"").append(readable(a.collectedAt)).append("\"");
        sb.append(",\"collected_ts\":").append(a.collectedAt);
        sb.append(",\"tags\":\"").append(esc(a.tags)).append("\"");
        sb.append(",\"status\":\"").append(esc(a.status)).append("\"");
        sb.append(",\"starred\":").append(a.starred);
        sb.append(",\"summary\":\"").append(esc(a.summary)).append("\"");
        sb.append(",\"content_length\":").append(a.contentLength());
        sb.append(",\"file\":\"").append(esc(a.exportName)).append("\"");
        sb.append("}");
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", " ")
                .replace("\t", " ");
    }

    private static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\n", " ").replace("\r", " ").trim();
    }

    // ------------------------------------------------------------ 调试

    /** 追加一行调试日志（记录 intent 长什么样，方便排查） */
    public static void debug(Context ctx, String line) {
        File d = collectedDir(ctx);
        Writer w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(new File(d, "_intents.log"), true), "UTF-8");
            w.write(readable(System.currentTimeMillis()) + "  " + line + "\n");
        } catch (Exception ignored) {
        } finally {
            close(w);
        }
    }

    /** 把 intent 的关键信息拼成一行（含共享文本，便于排查微信到底传了什么） */
    public static String describeIntent(android.content.Intent intent) {
        if (intent == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("action=").append(intent.getAction());
        sb.append(" data=").append(intent.getDataString());
        sb.append(" type=").append(intent.getType());
        if (intent.getExtras() != null) {
            Object t = intent.getExtras().get(android.content.Intent.EXTRA_TEXT);
            Object s = intent.getExtras().get(android.content.Intent.EXTRA_SUBJECT);
            sb.append(" extraText=").append(clip(t));
            sb.append(" extraSubject=").append(clip(s));
        }
        return sb.toString();
    }

    private static String clip(Object o) {
        if (o == null) {
            return "null";
        }
        String s = String.valueOf(o).replace("\n", "⏎").replace("\r", "");
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    /** 读取调试日志（MainActivity 展示用） */    public static List<String> readDebug(Context ctx, int max) {
        List<String> out = new ArrayList<>();
        File f = new File(collectedDir(ctx), "_intents.log");
        if (!f.exists()) {
            return out;
        }
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                out.add(line);
            }
        } catch (Exception ignored) {
        } finally {
            close(r);
        }
        while (out.size() > max) {
            out.remove(0);
        }
        return out;
    }

    static void close(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }
}
