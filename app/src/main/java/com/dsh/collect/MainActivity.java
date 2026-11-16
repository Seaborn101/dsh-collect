package com.dsh.collect;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** 收藏列表：搜索、筛选、标签、进入阅读器。 */
public class MainActivity extends Activity {

    private Db db;
    private ListView list;
    private EditText search;
    private LinearLayout chipBox;
    private LinearLayout tagBox;
    private TextView stats;
    private TextView empty;
    private TextView permBtn;
    private TextView batchBtn;
    private LinearLayout reviewRow;
    private TextView pathText;
    private Adapter adapter;

    private String filterStatus = "";
    private boolean onlyStar = false;
    private String tagFilter = "";
    /** "" 全部 / "L0" 待加工 / "L1" 已加工 */
    private String filterLayer = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        db = Db.get(this);

        list = findViewById(R.id.list);
        search = findViewById(R.id.search);
        chipBox = findViewById(R.id.chips);
        tagBox = findViewById(R.id.tag_chips);
        stats = findViewById(R.id.stats);
        empty = findViewById(R.id.empty);
        permBtn = findViewById(R.id.perm_btn);
        batchBtn = findViewById(R.id.batch_btn);
        reviewRow = findViewById(R.id.review_row);
        pathText = findViewById(R.id.path_text);

        adapter = new Adapter();
        list.setAdapter(adapter);

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                refresh();
            }
        });

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Article a = (Article) adapter.getItem(position);
                if (a == null) {
                    return;
                }
                Intent it = new Intent(MainActivity.this, ReaderActivity.class);
                it.putExtra("id", a.id);
                startActivity(it);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                Article a = (Article) adapter.getItem(position);
                if (a != null) {
                    Actions.show(MainActivity.this, a, new Runnable() {
                        @Override
                        public void run() {
                            refresh();
                        }
                    });
                }
                return true;
            }
        });

        permBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askPermission();
            }
        });

        batchBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runBatch();
            }
        });

        findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });

        buildChips();
        refresh();
        // 自愈导入：App 被卸载重装后，从共享目录把素材读回来
        runImport();
        // 补抓还没正文的记录
        Fetcher.fetchPendingAsync(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        // 授权后回到 App 也要能立刻导入（否则必须重启才生效）
        runImport();
    }

    private boolean batching = false;

    /** 批量把所有没加工过的文章送去 AI 加工 */
    private void runBatch() {
        if (batching) {
            return;
        }
        final List<Article> todo = db.query(null, "", false, "", "L0");
        if (todo.isEmpty()) {
            toast("没有待加工的文章");
            return;
        }
        batching = true;
        batchBtn.setEnabled(false);
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int[] counters = new int[]{0, 0}; // 0=成功 1=失败
                for (int i = 0; i < todo.size(); i++) {
                    final int idx = i;
                    final long aid = todo.get(i).id;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            batchBtn.setText(getString(R.string.batch_running, idx + 1, todo.size()));
                        }
                    });
                    try {
                        Article before = Db.get(app).byId(aid);
                        AutoPipeline.process(app, aid);
                        Article after = Db.get(app).byId(aid);
                        if (after != null && "L1".equals(after.layer)) {
                            counters[0]++;
                        } else {
                            counters[1]++;
                        }
                        if (before != null && !before.title.equals(
                                after == null ? "" : after.title)) {
                            // 标题可能被 AI 修正过，忽略
                        }
                    } catch (Throwable t) {
                        counters[1]++;
                    }
                    try {
                        Thread.sleep(800L);
                    } catch (InterruptedException ignored) {
                    }
                }
                batching = false;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        batchBtn.setEnabled(true);
                        toast(getString(R.string.batch_done, counters[0], counters[1]));
                        refresh();
                    }
                });
            }
        }).start();
    }

    /** 是否有导入任务正在进行 */
    private volatile boolean importing = false;

    private void runImport() {
        if (importing) {
            return;
        }
        importing = true;
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Import.Result r = Import.restore(app, db);
                importing = false;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (r.added > 0) {
                            toast("已从共享目录恢复 " + r.added + " 篇");
                        }
                        refresh();
                    }
                });
            }
        }).start();
    }

    // ------------------------------------------------------------ 筛选

    /** 回顾卡片：今日 / 本周 / 待加工 / 星标，点击可切换筛选 */
    private void buildReview(int total, int done) {
        reviewRow.removeAllViews();
        long dayStart = startOfDay(System.currentTimeMillis());
        long weekStart = dayStart - 6L * 24 * 3600 * 1000;
        int today = db.countSince(dayStart);
        int week = db.countSince(weekStart);
        int pending = total - done;
        int starred = db.countStarred();

        addStat(reviewRow, "今日", "+" + today, "", false);
        addStat(reviewRow, "本周", "+" + week, "", false);
        addStat(reviewRow, "待加工", String.valueOf(pending), "L0", true);
        addStat(reviewRow, "星标", String.valueOf(starred), "STAR", true);
    }

    private void addStat(LinearLayout box, String label, String value, String action,
                         boolean clickable) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        box.addView(col, lp);

        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(18);
        v.setTextColor(getResources().getColor(R.color.text_primary));
        v.setGravity(android.view.Gravity.CENTER);
        col.addView(v);

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(11);
        l.setTextColor(getResources().getColor(R.color.text_tertiary));
        l.setGravity(android.view.Gravity.CENTER);
        col.addView(l);

        if (clickable) {
            col.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if ("L0".equals(action)) {
                        filterStatus = "";
                        onlyStar = false;
                        filterLayer = "L0";
                    } else if ("STAR".equals(action)) {
                        filterStatus = "";
                        onlyStar = true;
                        filterLayer = "";
                    }
                    buildChips();
                    refresh();
                }
            });
            col.setBackgroundResource(R.drawable.chip_bg);
            col.setPadding(dp(6), dp(6), dp(6), dp(6));
        }
    }

    private long startOfDay(long ts) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ts);
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private void buildChips() {
        chipBox.removeAllViews();
        addChip("全部", "", false, "");
        addChip("待加工", "", false, "L0");
        addChip("已加工", "", false, "L1");
        addChip("星标", "", true, "");
        addChip("已归档", Article.STATUS_ARCHIVED, false, "");
    }

    private void addChip(String label, final String status, final boolean star, final String layer) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(13);
        tv.setTextColor(getResources().getColor(R.color.text_primary));
        tv.setBackgroundResource(R.drawable.chip_bg);
        tv.setPadding(dp(14), dp(6), dp(14), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        tv.setLayoutParams(lp);
        final boolean active = status.equals(filterStatus) && (star == onlyStar)
                && layer.equals(filterLayer);
        tv.setSelected(active);
        tv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                filterStatus = status;
                onlyStar = star;
                filterLayer = layer;
                buildChips();
                refresh();
            }
        });
        chipBox.addView(tv);
    }

    private void buildTagChips(List<String> tags) {
        tagBox.removeAllViews();
        tagBox.setVisibility(tags.isEmpty() ? View.GONE : View.VISIBLE);
        for (final String t : tags) {
            TextView tv = new TextView(this);
            tv.setText("#" + t);
            tv.setTextSize(12);
            tv.setTextColor(getResources().getColor(R.color.text_primary));
            tv.setBackgroundResource(R.drawable.chip_bg);
            tv.setPadding(dp(12), dp(5), dp(12), dp(5));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            tv.setLayoutParams(lp);
            tv.setSelected(t.equals(tagFilter));
            tv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    tagFilter = t.equals(tagFilter) ? "" : t;
                    refresh();
                }
            });
            tagBox.addView(tv);
        }
    }

    private void refresh() {
        boolean ok = Store.canUseShared(this);
        permBtn.setVisibility(ok ? View.GONE : View.VISIBLE);
        if (!ok) {
            permBtn.setText("授予文件夹访问权限（恢复已保存的素材）");
        }
        pathText.setText("导出目录：" + Store.collectedDir(this).getAbsolutePath()
                + " · 目录内 " + Store.countMaterialFiles(this) + " 篇");

        syncLayers();

        String kw = search.getText() == null ? "" : search.getText().toString();
        List<Article> all = db.query(kw, filterStatus, onlyStar, tagFilter, filterLayer);
        adapter.items = all;
        adapter.notifyDataSetChanged();
        boolean isEmpty = all.isEmpty();
        list.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        empty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        if (isEmpty) {
            empty.setText(ok
                    ? getString(R.string.empty_hint)
                    : getString(R.string.empty_no_perm));
        }

        int total = db.count();
        int done = db.countLayer("L1");
        stats.setText(total + " 篇 · 已加工 " + done + " · 待加工 " + (total - done));
        buildReview(total, done);
        if (!batching) {
            int pending = total - done;
            batchBtn.setVisibility(pending > 0 ? View.VISIBLE : View.GONE);
            batchBtn.setText(getString(R.string.batch_process, pending));
        }
        buildTagChips(db.allTags());
    }

    // ------------------------------------------------------------ 权限

    /**
     * 从共享目录的 articles.jsonl 读回 agent 的加工状态（L0/L1/L2），
     * 这样 App 里能看出「这篇已经被 AI 加工过」。
     */
    private void syncLayers() {
        if (!Store.canUseShared(this)) {
            return;
        }
        java.io.File f = new java.io.File(Store.collectedDir(this), "articles.jsonl");
        if (!f.exists()) {
            return;
        }
        java.io.BufferedReader r = null;
        try {
            r = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new java.io.FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.indexOf("\"layer\"") < 0) {
                    continue;
                }
                try {
                    org.json.JSONObject o = new org.json.JSONObject(line);
                    String url = o.optString("url", "");
                    String layer = o.optString("layer", "");
                    if (url.isEmpty() || layer.isEmpty()) {
                        continue;
                    }
                    Article a = db.byUrl(url);
                    if (a != null && !layer.equals(a.layer)) {
                        db.updateLayer(a.id, layer);
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        } finally {
            Store.close(r);
        }
    }

    private void askPermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Intent it = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(it);
                return;
            } catch (Exception ignored) {
            }
        }
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        } catch (Exception e) {
            toast("请到 系统设置 → 应用 → DSH收集 → 权限，开启「所有文件访问」");
        }
    }

    void toast(String s) {
        Toast.makeText(getApplicationContext(), s, Toast.LENGTH_SHORT).show();
    }

    int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    void copy(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("link", text));
            toast("链接已复制");
        }
    }

    // ------------------------------------------------------------ 适配器

    private class Adapter extends BaseAdapter {
        List<Article> items = new ArrayList<>();

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_article, parent, false);
            }
            Article a = items.get(position);
            ((TextView) v.findViewById(R.id.title)).setText(a.title);

            TextView star = v.findViewById(R.id.star);
            star.setVisibility(a.starred ? View.VISIBLE : View.GONE);

            String meta = (a.account.isEmpty() ? "未知公众号" : a.account)
                    + " · " + Store.readable(a.collectedAt);
            if (Article.STATUS_ARCHIVED.equals(a.status)) {
                meta += " · 已归档";
            } else if (a.isProcessed()) {
                meta += " · 已加工 ✓";
            }
            ((TextView) v.findViewById(R.id.meta)).setText(meta);

            TextView summary = v.findViewById(R.id.summary);
            String s = a.summary.isEmpty() ? "" : a.summary;
            summary.setText(s);
            summary.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);

            LinearLayout tagsRow = v.findViewById(R.id.tags_row);
            tagsRow.removeAllViews();
            List<String> tl = a.tagList();
            tagsRow.setVisibility(tl.isEmpty() ? View.GONE : View.VISIBLE);
            for (String t : tl) {
                TextView chip = new TextView(MainActivity.this);
                chip.setText("#" + t);
                chip.setTextSize(11);
                chip.setTextColor(getResources().getColor(R.color.accent_blue));
                chip.setBackgroundResource(R.drawable.tag_bg);
                chip.setPadding(dp(8), dp(3), dp(8), dp(3));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = dp(6);
                chip.setLayoutParams(lp);
                tagsRow.addView(chip);
            }

            ((TextView) v.findViewById(R.id.url)).setText(a.url);
            return v;
        }
    }
}
