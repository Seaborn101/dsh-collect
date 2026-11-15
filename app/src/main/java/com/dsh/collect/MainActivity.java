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
    private TextView pathText;
    private Adapter adapter;

    private String filterStatus = "";
    private boolean onlyStar = false;
    private String tagFilter = "";

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

        buildChips();
        refresh();
        // 补抓还没正文的记录
        Fetcher.fetchPendingAsync(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    // ------------------------------------------------------------ 筛选

    private void buildChips() {
        chipBox.removeAllViews();
        addChip("全部", "", false);
        addChip("未读", Article.STATUS_NEW, false);
        addChip("已归档", Article.STATUS_ARCHIVED, false);
        addChip("星标", "", true);
    }

    private void addChip(String label, final String status, final boolean star) {
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
        final boolean active = (status == null || status.equals(filterStatus)) && (star == onlyStar);
        tv.setSelected(active);
        tv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                filterStatus = status == null ? "" : status;
                onlyStar = star;
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
        pathText.setText("导出目录：" + Store.collectedDir(this).getAbsolutePath());

        String kw = search.getText() == null ? "" : search.getText().toString();
        List<Article> all = db.query(kw, filterStatus, onlyStar, tagFilter);
        adapter.items = all;
        adapter.notifyDataSetChanged();
        empty.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);

        int total = db.count();
        int unread = db.countStatus(Article.STATUS_NEW);
        stats.setText(total + " 篇 · 未读 " + unread);
        buildTagChips(db.allTags());
    }

    // ------------------------------------------------------------ 权限

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
            } else if (a.isUnread()) {
                meta += " · 未读";
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
