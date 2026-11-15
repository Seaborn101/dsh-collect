package com.dsh.collect;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.List;

/** 后台抓取正文：抓完写回数据库并同步导出文件。 */
public final class Fetcher {

    private Fetcher() {
    }

    /** 抓取单篇（同步，供后台线程调用） */
    public static void fetchNow(Context ctx, Article a) {
        Meta.Result r = Meta.fetch(a.url);
        Db db = Db.get(ctx);
        if (r.ok) {
            String title = r.title.isEmpty() ? a.title : r.title;
            String account = r.account.isEmpty() ? a.account : r.account;
            String text = r.text;
            String summary = Html.summary(text, 120);
            db.updateContent(a.id, r.html, text, title, account, summary);
            if (!r.author.isEmpty() || r.publishedAt > 0) {
                db.updateAuthor(a.id, r.author, r.publishedAt);
                a.author = r.author;
                a.publishedAt = r.publishedAt;
            }
            a.title = title;
            a.account = account;
            a.contentText = text;
            a.contentHtml = r.html;
            a.summary = summary;
            a.fetchState = Article.FETCH_OK;
        } else {
            db.updateMeta(a.id, a.title, a.account, a.summary, Article.FETCH_FAILED);
            a.fetchState = Article.FETCH_FAILED;
            Store.debug(ctx, "fetch failed " + a.url + " -> " + r.error);
        }
        // 同步导出
        List<Article> all = db.all();
        Store.exportAll(ctx, all);
        for (Article x : all) {
            if (x.id == a.id && x.exportName != null && !x.exportName.isEmpty()) {
                db.updateExportName(x.id, x.exportName);
                a.exportName = x.exportName;
                break;
            }
        }
    }

    /** 异步抓取一篇 */
    public static void fetchAsync(final Context ctx, final Article a) {
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                fetchNow(app, a);
            }
        }).start();
    }

    /** 抓取所有还没正文的记录（App 打开时调用） */
    public static void fetchPendingAsync(final Context ctx) {
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                Db db = Db.get(app);
                for (Article a : db.pendingFetch()) {
                    fetchNow(app, a);
                }
            }
        }).start();
    }

    /** 回到主线程执行 */
    public static void onMain(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }
}
