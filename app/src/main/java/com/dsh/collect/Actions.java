package com.dsh.collect;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import java.util.List;

/** 文章操作菜单（列表长按、阅读器「⋯」共用）。 */
final class Actions {

    private Actions() {
    }

    static void show(final Activity act, final Article a, final Runnable onChanged) {
        final Db db = Db.get(act);
        final boolean archived = Article.STATUS_ARCHIVED.equals(a.status);

        String[] items = {
                "复制链接",
                "用 AI 加工这篇",
                "导出 Markdown",
                "加入待整理队列",
                archived ? "取消归档" : "归档",
                a.starred ? "取消星标" : "设为星标",
                "编辑标签",
                "删除"
        };

        new AlertDialog.Builder(act)
                .setTitle(a.title)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        switch (which) {
                            case 0:
                                copy(act, a.url);
                                break;
                            case 1:
                                toast(act, "开始 AI 加工…");
                                AutoPipeline.processAsync(act, a.id);
                                break;
                            case 2:
                                java.io.File f = Store.exportMd(act, a);
                                toast(act, "已导出：" + f.getAbsolutePath());
                                break;
                            case 3:
                                java.io.File f2 = Store.addToInbox(act, a);
                                toast(act, "已加入队列：" + f2.getAbsolutePath());
                                break;
                            case 4:
                                db.updateStatus(a.id, archived ? Article.STATUS_READ : Article.STATUS_ARCHIVED);
                                a.status = archived ? Article.STATUS_READ : Article.STATUS_ARCHIVED;
                                break;
                            case 5:
                                db.updateStar(a.id, !a.starred);
                                a.starred = !a.starred;
                                break;
                            case 6:
                                editTags(act, db, a, onChanged);
                                return;
                            case 7:
                                new AlertDialog.Builder(act)
                                        .setTitle("确认删除？")
                                        .setMessage("将从 App 与导出目录中移除这篇文章")
                                        .setNegativeButton("取消", null)
                                        .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface d, int w) {
                                                db.delete(a.id);
                                                toast(act, "已删除");
                                                syncExport(act);
                                                if (onChanged != null) {
                                                    onChanged.run();
                                                }
                                            }
                                        })
                                        .show();
                                return;
                            default:
                                break;
                        }
                        syncExport(act);
                        if (onChanged != null) {
                            onChanged.run();
                        }
                    }
                })
                .show();
    }

    private static void editTags(final Activity act, final Db db, final Article a,
                                  final Runnable onChanged) {
        final EditText input = new EditText(act);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(act.getString(R.string.tags_hint));
        input.setText(a.tags);
        new AlertDialog.Builder(act)
                .setTitle("标签")
                .setView(input)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Article tmp = new Article();
                        tmp.tags = a.tags;
                        List<String> list = tmp.tagList();
                        list.clear();
                        String raw = input.getText() == null ? "" : input.getText().toString();
                        for (String s : raw.split("[\\s,，]+")) {
                            if (!s.trim().isEmpty()) {
                                list.add(s.trim());
                            }
                        }
                        tmp.setTags(list);
                        db.updateTags(a.id, tmp.tags);
                        a.tags = tmp.tags;
                        syncExport(act);
                        if (onChanged != null) {
                            onChanged.run();
                        }
                    }
                })
                .show();
    }

    private static void syncExport(Activity act) {
        Db db = Db.get(act);
        Store.exportJsonl(act, db.all());
    }

    private static void copy(Activity act, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) act.getSystemService(Activity.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("link", text));
            toast(act, "链接已复制");
        }
    }

    private static void toast(Activity act, String s) {
        Toast.makeText(act.getApplicationContext(), s, Toast.LENGTH_SHORT).show();
    }
}
