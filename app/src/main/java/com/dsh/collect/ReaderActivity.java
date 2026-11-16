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
import android.widget.TextView;
import android.widget.Toast;

/**
 * 阅读器：两种看法
 * <ul>
 *   <li>网页版 —— 内嵌 WebView 打开原文（普通浏览器 UA，头像等资源正常显示）</li>
 *   <li>笔记版 —— AI 加工后的 markdown 笔记（摘要 / 要点 / 可复用结论 / 金句 / 点评）</li>
 * </ul>
 */
public class ReaderActivity extends Activity {

    private WebView web;
    private TextView barTitle;
    private TextView modeWeb;
    private TextView modeNote;
    private TextView modeMeta;

    private long id = 0L;
    private Article article;
    private boolean noteMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reader);

        web = findViewById(R.id.web);
        barTitle = findViewById(R.id.bar_title);
        modeWeb = findViewById(R.id.mode_web);
        modeNote = findViewById(R.id.mode_note);
        modeMeta = findViewById(R.id.mode_meta);

        id = getIntent().getLongExtra("id", 0L);
        Db db = Db.get(this);
        article = db.byId(id);
        if (article == null) {
            Toast.makeText(getApplicationContext(), "记录不存在", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        barTitle.setText(article.title);
        setupWeb();
        showWeb();

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!noteMode && web.canGoBack()) {
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
        modeNote.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showNote();
            }
        });
        findViewById(R.id.btn_vault).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openInObsidian();
            }
        });
    }

    /** 用 Obsidian 的 URI 打开对应的知识库笔记 */
    private void openInObsidian() {
        String cat = (article.category == null || article.category.isEmpty())
                ? "未分类" : article.category;
        String rel = "L1/" + cat + "/" + Store.titleSlug(article.title) + ".md";
        java.io.File f = new java.io.File(Store.VAULT_DIR, rel);
        if (!f.exists()) {
            Toast.makeText(getApplicationContext(), getString(R.string.no_note_yet),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String vault = new java.io.File(Store.VAULT_DIR).getName();
            Intent it = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("obsidian://open?vault=" + Uri.encode(vault)
                            + "&file=" + Uri.encode(rel)));
            startActivity(it);
        } catch (Exception e) {
            Toast.makeText(getApplicationContext(), "没找到 Obsidian 应用",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshMeta() {
        if (article == null) {
            return;
        }
        barTitle.setText(article.title);
        if (noteMode) {
            showNote();
        }
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
        // 普通浏览器 UA：微信文章页在 MicroMessenger UA 下头像等资源不显示
        s.setUserAgentString(Meta.BROWSER_UA);
        CookieManager.getInstance().setAcceptCookie(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String requestUrl) {
                if (noteMode) {
                    return true; // 笔记版不跳转
                }
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
                if (!noteMode) {
                    modeMeta.setText("加载中…");
                }
            }

            @Override
            public void onPageFinished(WebView view, String pageUrl) {
                if (!noteMode) {
                    updateMeta();
                }
            }
        });
    }

    private void updateMeta() {
        String acc = article.account == null || article.account.isEmpty() ? "未知公众号" : article.account;
        modeMeta.setText(acc + " · " + Store.readable(article.collectedAt));
    }

    private void showWeb() {
        noteMode = false;
        modeWeb.setSelected(true);
        modeNote.setSelected(false);
        updateMeta();
        web.getSettings().setUserAgentString(Meta.BROWSER_UA);
        web.loadUrl(article.url);
    }

    /** 笔记版：渲染 AI 加工出的 markdown 笔记 */
    private void showNote() {
        noteMode = true;
        modeNote.setSelected(true);
        modeWeb.setSelected(false);
        updateMeta();
        web.loadDataWithBaseURL(null, buildNoteHtml(article), "text/html", "UTF-8", null);
    }

    /** 把 Article 的 AI 字段渲染成一页笔记 */
    static String buildNoteHtml(Article a) {
        StringBuilder h = new StringBuilder();
        h.append("<html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        h.append("<style>");
        h.append("body{font-family:-apple-system,'PingFang SC','Noto Sans CJK SC',sans-serif;");
        h.append("line-height:1.75;color:#1a1a1e;background:#fff;margin:0;padding:18px;font-size:16px}");
        h.append("h1{font-size:20px;line-height:1.4;margin:0 0 6px}");
        h.append("h2{font-size:16px;margin:22px 0 8px;padding-left:9px;border-left:3px solid #07C160}");
        h.append(".meta{color:#8b8f99;font-size:12px;margin-bottom:16px}");
        h.append(".abs{background:#f2fbf6;border-left:3px solid #07C160;padding:10px 12px;");
        h.append("border-radius:6px;margin:0 0 6px;font-size:15px}");
        h.append(".note{background:#eef3ff;border-left:3px solid #2F6BFF;padding:10px 12px;");
        h.append("border-radius:6px;margin:0 0 6px;font-size:14px;color:#243046}");
        h.append("ul{padding-left:20px;margin:6px 0}");
        h.append("li{margin:6px 0}");
        h.append("blockquote{margin:8px 0;padding:8px 12px;background:#f6f7f9;border-radius:6px;");
        h.append("color:#3c4149}");
        h.append(".tags{margin:12px 0 0}");
        h.append(".tag{display:inline-block;background:#e9f0ff;color:#2F6BFF;border-radius:6px;");
        h.append("padding:2px 8px;margin:0 6px 6px 0;font-size:12px}");
        h.append(".empty{color:#8b8f99;text-align:center;padding:40px 10px}");
        h.append("</style></head><body>");

        h.append("<h1>").append(esc(a.title)).append("</h1>");
        h.append("<div class=\"meta\">")
                .append(esc(a.account.isEmpty() ? "未知公众号" : a.account))
                .append(" · ").append(Store.readable(a.collectedAt));
        if (a.publishedAt > 0) {
            h.append(" · 发布于 ").append(Store.readable(a.publishedAt));
        }
        h.append("</div>");

        boolean empty = true;
        if (a.summary != null && !a.summary.trim().isEmpty()) {
            h.append("<div class=\"abs\">").append(esc(a.summary)).append("</div>");
            empty = false;
        }
        if (notEmpty(a.keyPoints)) {
            h.append("<h2>要点</h2><ul>");
            for (String line : a.keyPoints.split("\n")) {
                if (!line.trim().isEmpty()) {
                    h.append("<li>").append(esc(line.trim())).append("</li>");
                }
            }
            h.append("</ul>");
            empty = false;
        }
        if (notEmpty(a.takeaways)) {
            h.append("<h2>可复用的结论</h2><ul>");
            for (String line : a.takeaways.split("\n")) {
                if (!line.trim().isEmpty()) {
                    h.append("<li>").append(esc(line.trim())).append("</li>");
                }
            }
            h.append("</ul>");
            empty = false;
        }
        if (notEmpty(a.quote)) {
            h.append("<h2>原文金句</h2><blockquote>").append(esc(a.quote)).append("</blockquote>");
            empty = false;
        }
        if (notEmpty(a.note)) {
            h.append("<div class=\"note\">").append(esc(a.note)).append("</div>");
            empty = false;
        }
        if (empty) {
            h.append("<div class=\"empty\">这篇文章还没有 AI 笔记。<br>"
                    + "回到列表点「批量加工」，或用 ⋯ 菜单里的导出。</div>");
        }
        if (notEmpty(a.tags)) {
            h.append("<div class=\"tags\">");
            for (String t : a.tags.split("[\\s,，]+")) {
                if (!t.trim().isEmpty()) {
                    h.append("<span class=\"tag\">#").append(esc(t.trim())).append("</span>");
                }
            }
            h.append("</div>");
        }
        h.append("</body></html>");
        return h.toString();
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    @Override
    public void onBackPressed() {
        if (!noteMode && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
