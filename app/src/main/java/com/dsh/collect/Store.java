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
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 收藏目录管理。
 *
 * <p>优先使用 /sdcard/Download/DSH-Go/collected —— 那是 dsh-go / agent 约定的工作目录，
 * 这样手机上的脚本与 AI 可以直接读到这些 Markdown。写共享目录需要「所有文件访问」
 * 权限（MANAGE_EXTERNAL_STORAGE）；没授权时退回到 App 私有目录，至少 App 自己可用。
 */
public final class Store {

    /** 共享收藏目录（相对外部存储根） */
    private static final String SHARED_DIR = "Download/DSH-Go/collected";

    private Store() {
    }

    /** 是否具备读写共享收藏目录的权限 */
    public static boolean canUseShared(Context ctx) {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        // API 29：靠 legacy 外部存储 + 运行时权限
        return ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 实际使用的收藏目录 */
    public static File dir(Context ctx) {
        if (canUseShared(ctx)) {
            File f = new File(Environment.getExternalStorageDirectory(), SHARED_DIR);
            if (f.exists() || f.mkdirs()) {
                return f;
            }
        }
        File fallback = new File(ctx.getExternalFilesDir(null), "collected");
        //noinspection ResultOfMethodCallIgnored
        fallback.mkdirs();
        return fallback;
    }

    /** 把时间戳格式化成 yyyyMMdd-HHmmss */
    public static String stamp(long time) {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(time));
    }

    /** 把时间戳格式化成可读时间 */
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
        String out = sb.toString().replaceAll("-+", "-");
        return out.isEmpty() ? "untitled" : out;
    }

    /** 写出/更新一条收藏（Obsidian 友好的 YAML front matter） */
    public static File save(Context ctx, Article a) {
        File d = dir(ctx);
        if (a.file == null || a.file.isEmpty()) {
            a.file = stamp(a.time) + "_" + safeName(a.title) + ".md";
        }
        File f = new File(d, a.file);
        Writer w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(f, false), "UTF-8");
            w.write("---\n");
            w.write("title: " + oneLine(a.title) + "\n");
            w.write("account: " + oneLine(a.account) + "\n");
            w.write("url: " + oneLine(a.url) + "\n");
            w.write("source: wechat\n");
            w.write("collected: " + readable(a.time) + "\n");
            w.write("---\n\n");
            w.write("# " + a.title + "\n\n");
            w.write("> 来源：" + (a.account.isEmpty() ? "未知公众号" : a.account) + "\n");
            w.write("> 链接：" + a.url + "\n");
            w.write("> 收藏于 " + readable(a.time) + "\n\n");
            w.write("<!-- 原文正文（可由 dsh-go agent 抓取后写入此处） -->\n");
        } catch (Exception e) {
            // 忽略：收藏失败时上层会提示
        } finally {
            close(w);
        }
        return f;
    }

    private static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\n", " ").replace("\r", " ").trim();
    }

    /** 列出全部收藏，按时间倒序 */
    public static ArrayList<Article> list(Context ctx) {
        ArrayList<Article> out = new ArrayList<>();
        File d = dir(ctx);
        File[] files = d.listFiles();
        if (files == null) {
            return out;
        }
        List<File> md = new ArrayList<>();
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(".md")) {
                md.add(f);
            }
        }
        Collections.sort(md, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return b.getName().compareTo(a.getName());
            }
        });
        for (File f : md) {
            Article a = parse(f);
            if (a != null) {
                out.add(a);
            }
        }
        return out;
    }

    /** 解析 front matter */
    private static Article parse(File f) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            Article a = new Article();
            a.file = f.getName();
            String line;
            boolean inFront = false;
            while ((line = r.readLine()) != null) {
                if (line.trim().equals("---")) {
                    if (inFront) {
                        break;
                    }
                    inFront = true;
                    continue;
                }
                if (!inFront) {
                    continue;
                }
                int idx = line.indexOf(':');
                if (idx <= 0) {
                    continue;
                }
                String k = line.substring(0, idx).trim();
                String v = line.substring(idx + 1).trim();
                if (k.equals("title")) {
                    a.title = v;
                } else if (k.equals("account")) {
                    a.account = v;
                } else if (k.equals("url")) {
                    a.url = v;
                }
            }
            a.time = timeFromName(f.getName());
            if (a.url.isEmpty() && a.title.isEmpty()) {
                return null;
            }
            return a;
        } catch (Exception e) {
            return null;
        } finally {
            close(r);
        }
    }

    private static long timeFromName(String name) {
        try {
            String s = name.length() >= 15 ? name.substring(0, 15) : name;
            return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).parse(s).getTime();
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 删除一条收藏 */
    public static boolean delete(Context ctx, Article a) {
        if (a == null || a.file.isEmpty()) {
            return false;
        }
        File f = new File(dir(ctx), a.file);
        return f.exists() && f.delete();
    }

    /** 追加一行调试日志（记录 intent 长什么样，方便排查） */
    public static void debug(Context ctx, String line) {
        File d = dir(ctx);
        Writer w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(new File(d, "_intents.log"), true), "UTF-8");
            w.write(Store.readable(System.currentTimeMillis()) + "  " + line + "\n");
        } catch (Exception ignored) {
        } finally {
            close(w);
        }
    }

    static void close(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 供调试：把 intent 的关键信息拼成一行 */
    public static String describeIntent(android.content.Intent intent) {
        if (intent == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("action=").append(intent.getAction());
        sb.append(" data=").append(intent.getDataString());
        sb.append(" type=").append(intent.getType());
        sb.append(" extras=");
        if (intent.getExtras() != null) {
            for (String k : intent.getExtras().keySet()) {
                sb.append(k).append(",");
            }
        }
        return sb.toString();
    }

    static String[] asArray(List<String> l) {
        return l == null ? new String[0] : l.toArray(new String[0]);
    }

    static List<String> asList(String[] a) {
        return a == null ? new ArrayList<String>() : Arrays.asList(a);
    }
}