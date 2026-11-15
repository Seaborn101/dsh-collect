package com.dsh.collect;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ScrollView;
import android.widget.TextView;

/** 阅读器：内嵌 WebView（网页版）+ 离线正文。 */
public class ReaderActivity extends Activity {

    private WebView web;
    private ScrollView offlineScroll;
    private TextView offlineText;
    private TextView barTitle;
    private TextView modeWeb;
    private TextView modeOffline;
    private TextView modeMeta;

    private long id = 0L;
    private Article article;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reader);

        web = findViewById(R.id.web);
        offlineScroll = findViewById(R.id.offline_scroll);
        offlineText = findViewById(R.id.offline_text);
        barTitle = findViewById(R.id.bar_title);
        modeWeb = findViewById(R.id.mode_web);
        modeOffline = findViewById(R.id.mode_offline);
        modeMeta = findViewById(R.id.mode_meta);

        id = getIntent().getLongExtra("id", 0L);
        Db db = Db.get(this);
        article = db.byId(id);
        if (article == null) {
            finish();
            return;
        }

        barTitle.setText(article.title);
        modeMeta.setText((article.account.isEmpty() ? "未知公众号" : article.account)
                + " · " + Store.readable(article.collectedAt));

        setupWeb();
        showWeb();

        // 打开即已读
        if (article.isUnread()) {
            db.updateStatus(article.id, Article.STATUS_READ);
            article.status = Article.STATUS_READ;
        }

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (web.getVisibility() == View.VISIBLE && web.canGoBack()) {
                    web.goBack();
                } else {
                    finish();
                }
            }
        });
        findViewById(R.id.btn_more).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Actions.show(ReaderActivity.this, article, new Runnable() {
                    @Override
                    public void run() {
                        refreshMeta();
                    }
                });
            }
        });
        modeWeb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showWeb();
            }
        });
        modeOffline.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showOffline();
            }
        });
    }

    private void refreshMeta() {
        if (article == null) {
            return;
        }
        barTitle.setText(article.title);
    }

    private void setupWeb() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(Meta.UA);
        CookieManager.getInstance().setAcceptCookie(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String requestUrl) {
                if (requestUrl != null
                        && (requestUrl.startsWith("http://") || requestUrl.startsWith("https://"))) {
                    view.loadUrl(requestUrl);
                    return true;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(requestUrl)));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String pageUrl, Bitmap favicon) {
                modeMeta.setText("加载中…");
            }

            @Override
            public void onPageFinished(WebView view, String pageUrl) {
                if (article != null) {
                    modeMeta.setText((article.account.isEmpty() ? "未知公众号" : article.account)
                            + " · " + Store.readable(article.collectedAt));
                }
            }
        });
        web.loadUrl(article.url);
    }

    private void showWeb() {
        web.setVisibility(View.VISIBLE);
        offlineScroll.setVisibility(View.GONE);
        modeWeb.setSelected(true);
        modeOffline.setSelected(false);
    }

    private void showOffline() {
        if (article.contentText == null || article.contentText.isEmpty()) {
            modeMeta.setText(getString(R.string.no_offline));
            return;
        }
        offlineText.setText(article.contentText);
        offlineScroll.setVisibility(View.VISIBLE);
        web.setVisibility(View.GONE);
        modeOffline.setSelected(true);
        modeWeb.setSelected(false);
        offlineScroll.scrollTo(0, 0);
    }

    @Override
    public void onBackPressed() {
        if (web.getVisibility() == View.VISIBLE && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
