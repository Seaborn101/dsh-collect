package com.dsh.collect;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.Toast;

/** 阅读器：内嵌一个轻量 WebView 打开微信文章。 */
public class ReaderActivity extends Activity {

    private WebView web;
    private String url = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reader);

        web = findViewById(R.id.web);
        url = getIntent().getStringExtra("url");
        String title = getIntent().getStringExtra("title");
        if (title != null && !title.isEmpty()) {
            ((TextView) findViewById(R.id.bar_title)).setText(title);
        }

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
                ((TextView) findViewById(R.id.bar_title)).setText("加载中…");
            }

            @Override
            public void onPageFinished(WebView view, String pageUrl) {
                TextView t = findViewById(R.id.bar_title);
                if (t.getText().toString().equals("加载中…")) {
                    t.setText(pageUrl);
                }
            }
        });

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (web.canGoBack()) {
                    web.goBack();
                } else {
                    finish();
                }
            }
        });
        findViewById(R.id.btn_copy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("link", url));
                    Toast.makeText(getApplicationContext(), "链接已复制", Toast.LENGTH_SHORT).show();
                }
            }
        });

        if (url != null && !url.isEmpty()) {
            web.loadUrl(url);
        } else {
            Toast.makeText(getApplicationContext(), "没有链接", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
