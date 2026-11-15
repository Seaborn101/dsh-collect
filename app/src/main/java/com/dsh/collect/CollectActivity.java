package com.dsh.collect;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 收集入口：微信「用DSH收集打开」时触发。
 *
 * <p>完全静默：主题透明 + 无启动预览 + 不进最近任务，收完立刻 finish，用户看不到任何界面变化。
 * 微信实际发的是 {@code ACTION_SEND + text/plain}，文本里带标题和链接。
 */
public class CollectActivity extends Activity {

    private static final Pattern URL_RE =
            Pattern.compile("https?://[^\\s，。；、）)】\\]\"'<>]+");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 立刻结束这次转发：真正的处理放到后台线程，主线程不阻塞
        final Intent intent = getIntent();
        final Context app = getApplicationContext();
        final String debug = Store.describeIntent(intent);
        new Thread(new Runnable() {
            @Override
            public void run() {
                handle(app, intent, debug);
            }
        }).start();
        finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        final Context app = getApplicationContext();
        final String debug = Store.describeIntent(intent);
        new Thread(new Runnable() {
            @Override
            public void run() {
                handle(app, intent, debug);
            }
        }).start();
        finish();
    }

    private void handle(Context app, Intent intent, String debug) {
        Store.debug(app, "Collect " + debug);

        String sharedText = extractText(intent);
        String url = extractUrl(intent, sharedText);
        if (url == null || url.isEmpty()) {
            Store.debug(app, "ignore: no url in " + debug);
            return;
        }
        String title = Meta.guessTitle(sharedText);

        Db db = Db.get(app);
        Article existing = db.byUrl(url);
        Article a = existing != null ? existing : new Article();
        boolean isNew = existing == null;
        a.url = url;
        if (!title.isEmpty()) {
            a.title = title;
        } else if (a.title == null || a.title.isEmpty()) {
            a.title = fallbackTitle(url);
        }
        a.account = a.account == null ? "" : a.account;
        a.tags = a.tags == null ? "" : a.tags;
        a.status = a.status == null || a.status.isEmpty() ? Article.STATUS_NEW : a.status;
        a.source = "wechat";
        a.collectedAt = isNew ? System.currentTimeMillis() : a.collectedAt;
        if (isNew) {
            a.fetchState = Article.FETCH_PENDING;
        }
        db.upsert(a);
        Store.debug(app, "saved id=" + a.id + " title=" + a.title + " url=" + a.url);

        // 立即导出（先存链接，后台补正文）
        Store.exportMd(app, a);
        db.updateExportName(a.id, a.exportName);
        Store.exportJsonl(app, db.all());

        // 后台抓正文
        Fetcher.fetchNow(app, a);
        Store.debug(app, "fetched id=" + a.id + " title=" + a.title
                + " account=" + a.account + " len=" + a.contentLength());
    }

    /** Intent 里的原始文本 */
    private String extractText(Intent intent) {
        if (intent == null || intent.getExtras() == null) {
            return "";
        }
        Object t = intent.getExtras().get(Intent.EXTRA_TEXT);
        if (t == null) {
            t = intent.getExtras().get(Intent.EXTRA_SUBJECT);
        }
        return t == null ? "" : String.valueOf(t);
    }

    /** 取链接：优先 intent data，其次文本里的 URL */
    private String extractUrl(Intent intent, String sharedText) {
        if (intent != null) {
            String data = intent.getDataString();
            if (data != null && (data.startsWith("http://") || data.startsWith("https://"))) {
                return data;
            }
        }
        Matcher m = URL_RE.matcher(sharedText == null ? "" : sharedText);
        if (m.find()) {
            return m.group();
        }
        return null;
    }

    private String fallbackTitle(String url) {
        String s = url;
        int q = s.indexOf('?');
        if (q > 0) {
            s = s.substring(0, q);
        }
        int slash = s.lastIndexOf('/');
        String tail = slash >= 0 && slash + 1 < s.length() ? s.substring(slash + 1) : s;
        return tail.isEmpty() ? url : tail;
    }
}
