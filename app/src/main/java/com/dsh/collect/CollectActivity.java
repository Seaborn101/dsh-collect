package com.dsh.collect;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 收集入口：微信「用DSH收集打开」时触发。
 *
 * <p>无界面：拿到链接立刻落盘，然后退出；标题 / 公众号名在后台补齐后再更新同一条记录。
 */
public class CollectActivity extends Activity {

    private static final Pattern URL_RE = Pattern.compile("https?://[^\\s，。；、）)】\\]\"'<>]+");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    private void handle(Intent intent) {
        Store.debug(this, "Collect " + Store.describeIntent(intent));

        final String url = extractUrl(intent);
        if (url == null || url.isEmpty()) {
            toast("没有识别到链接");
            finish();
            return;
        }

        final Article article = new Article(url, fallbackTitle(url), "", System.currentTimeMillis());
        Store.save(this, article); // 先落盘，保证链接不丢
        toast("已收藏链接，正在获取标题…");
        finish();

        // 后台补齐标题与公众号名
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                Meta.Info info = Meta.fetch(url);
                String title = info.title == null ? "" : info.title;
                if (!title.isEmpty()) {
                    article.title = title;
                }
                String account = info.account == null ? "" : info.account;
                if (!account.isEmpty()) {
                    article.account = account;
                }
                Store.save(CollectActivity.this, article);
                Store.debug(CollectActivity.this, "saved " + article.file + " title=" + article.title);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        toast("已收藏：" + article.title);
                    }
                });
            }
        }).start();
    }

    /** 从 VIEW 的 data 或 SEND 的文本里取链接 */
    private String extractUrl(Intent intent) {
        if (intent == null) {
            return null;
        }
        String data = intent.getDataString();
        if (data != null && data.startsWith("http")) {
            return data;
        }
        if (intent.getExtras() != null) {
            Object t = intent.getExtras().get(Intent.EXTRA_TEXT);
            if (t != null) {
                Matcher m = URL_RE.matcher(String.valueOf(t));
                if (m.find()) {
                    return m.group();
                }
            }
            Object s = intent.getExtras().get(Intent.EXTRA_SUBJECT);
            if (s != null) {
                Matcher m = URL_RE.matcher(String.valueOf(s));
                if (m.find()) {
                    return m.group();
                }
            }
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

    private void toast(final String msg) {
        Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
    }
}
