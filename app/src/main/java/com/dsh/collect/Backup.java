package com.dsh.collect;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 备份与恢复。
 *
 * <p>App 内部数据（SQLite、SharedPreferences）会随卸载消失，所以：
 * <ul>
 *   <li>设置（模型配置、提示词模板）额外写一份到共享目录，卸载重装后自动恢复</li>
 *   <li>提供「导出备份」：把设置 + 素材清单打成一个 zip，放到用户指定的地方</li>
 * </ul>
 * 文章正文本来就在 Obsidian 库里，不进备份包；<b>API Key 默认不打包</b>，换机后手填一次。
 */
public final class Backup {

    private Backup() {
    }

    /** 共享目录里的设置文件 */
    private static File settingsFile(Context ctx) {
        File root = Store.sharedRoot(ctx);
        if (root == null) {
            return null;
        }
        return new File(root, "settings.json");
    }

    /** 把设置写到共享目录 */
    public static boolean saveToShared(Context ctx, Settings s) {
        File f = settingsFile(ctx);
        if (f == null) {
            return false;
        }
        Writer w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(f, false), "UTF-8");
            JSONObject o = new JSONObject();
            o.put("endpoint", s.endpoint);
            o.put("model", s.model);
            o.put("temperature", s.temperature);
            o.put("prompt", s.prompt);
            o.put("prompt_l2", s.promptL2);
            o.put("auto_process", s.autoProcess);
            o.put("auto_remind", s.autoRemind);
            o.put("remind_hour", s.remindHour);
            o.put("remind_minute", s.remindMinute);
            o.put("saved_at", new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                    .format(new Date()));
            w.write(o.toString(2));
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            Store.close(w);
        }
    }

    /** 从共享目录恢复设置；成功返回 true */
    public static boolean restoreFromShared(Context ctx, Settings s) {
        File f = settingsFile(ctx);
        if (f == null || !f.exists()) {
            return false;
        }
        try {
            String text = readAll(f);
            if (text.trim().isEmpty()) {
                return false;
            }
            JSONObject o = new JSONObject(text);
            s.endpoint = o.optString("endpoint", s.endpoint);
            s.model = o.optString("model", s.model);
            s.temperature = (float) o.optDouble("temperature", s.temperature);
            s.prompt = o.optString("prompt", s.prompt);
            s.promptL2 = o.optString("prompt_l2", s.promptL2);
            s.autoProcess = o.optBoolean("auto_process", s.autoProcess);
            s.autoRemind = o.optBoolean("auto_remind", s.autoRemind);
            s.remindHour = o.optInt("remind_hour", s.remindHour);
            s.remindMinute = o.optInt("remind_minute", s.remindMinute);
            s.save(ctx);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 导出备份包：设置（不含 Key）+ 素材清单 + 知识库笔记清单。
     *
     * @return 生成的 zip 路径；失败返回 null
     */
    public static File exportBackup(Context ctx) {
        File root = Store.sharedRoot(ctx);
        if (root == null) {
            return null;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File out = new File(root, "dsh-collect-backup-" + stamp + ".zip");
        ZipOutputStream zos = null;
        try {
            zos = new ZipOutputStream(new FileOutputStream(out));
            Settings s = Settings.load(ctx);
            StringBuilder sb = new StringBuilder();
            sb.append("# DSH 收集 备份 ").append(stamp).append("\n");
            sb.append("endpoint=").append(s.endpoint).append("\n");
            sb.append("model=").append(s.model).append("\n");
            sb.append("temperature=").append(s.temperature).append("\n");
            sb.append("auto_process=").append(s.autoProcess).append("\n");
            sb.append("auto_remind=").append(s.autoRemind).append("\n");
            sb.append("remind_time=").append(String.format("%02d:%02d",
                    s.remindHour, s.remindMinute)).append("\n");
            sb.append("\n--- prompt ---\n").append(s.prompt);
            sb.append("\n--- prompt_l2 ---\n").append(s.promptL2);
            sb.append("\n\n# 注意：API Key 不在备份里，换机后需在设置页重新填写\n");
            addText(zos, "settings.txt", sb.toString());

            // 素材清单
            StringBuilder list = new StringBuilder();
            for (Article a : Db.get(ctx).all()) {
                list.append(a.collectedAt).append("\t").append(a.layer).append("\t")
                        .append(a.title.replace("\n", " ")).append("\t")
                        .append(a.url).append("\n");
            }
            addText(zos, "articles.tsv", list.toString());

            // 知识库笔记清单
            StringBuilder kb = new StringBuilder();
            File l1 = new File(Store.VAULT_DIR, "L1");
            File l2 = new File(Store.VAULT_DIR, "L2");
            kb.append("# ").append(Store.VAULT_DIR).append("\n");
            kb.append(listTree(l1)).append(listTree(l2));
            addText(zos, "vault-manifest.txt", kb.toString());

            zos.finish();
            return out;
        } catch (Exception e) {
            return null;
        } finally {
            Store.close(zos);
        }
    }

    private static String listTree(File dir) {
        StringBuilder sb = new StringBuilder();
        if (dir == null || !dir.exists()) {
            return "";
        }
        File[] fs = dir.listFiles();
        if (fs == null) {
            return "";
        }
        for (File f : fs) {
            if (f.isDirectory()) {
                sb.append(f.getName()).append("/\n");
                sb.append(listTree(f));
            } else if (f.getName().endsWith(".md")) {
                sb.append(f.getName()).append("\n");
            }
        }
        return sb.toString();
    }

    private static void addText(ZipOutputStream zos, String name, String content) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes("UTF-8"));
        zos.closeEntry();
    }

    private static String readAll(File f) throws Exception {
        InputStream in = new FileInputStream(f);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        return bos.toString("UTF-8");
    }
}
