package com.dsh.collect;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

/** 收藏列表：主动打开 App 时看到的入口。 */
public class MainActivity extends Activity {

    private ListView list;
    private TextView empty;
    private TextView permBtn;
    private TextView pathText;
    private Adapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        list = findViewById(R.id.list);
        empty = findViewById(R.id.empty);
        permBtn = findViewById(R.id.perm_btn);
        pathText = findViewById(R.id.path_text);

        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Article a = (Article) adapter.getItem(position);
                if (a == null) {
                    return;
                }
                if (a.url.isEmpty()) {
                    toast("这条记录没有链接");
                    return;
                }
                Intent it = new Intent(MainActivity.this, ReaderActivity.class);
                it.putExtra("url", a.url);
                it.putExtra("title", a.title);
                startActivity(it);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                final Article a = (Article) adapter.getItem(position);
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(a.title)
                        .setItems(new String[]{"复制链接", "删除"}, new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                if (which == 0) {
                                    copy(a.url);
                                } else {
                                    Store.delete(MainActivity.this, a);
                                    refresh();
                                }
                            }
                        })
                        .show();
                return true;
            }
        });

        permBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askPermission();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean ok = Store.canUseShared(this);
        permBtn.setVisibility(ok ? View.GONE : View.VISIBLE);
        pathText.setText("收藏目录：" + Store.dir(this).getAbsolutePath());
        adapter.items = Store.list(this);
        adapter.notifyDataSetChanged();
        empty.setVisibility(adapter.items.isEmpty() ? View.VISIBLE : View.GONE);
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
            toast("请到 系统设置 → 应用 → DSH收集 → 权限 里开启「所有文件访问」");
        }
    }

    private void copy(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("link", text));
            toast("链接已复制");
        }
    }

    private void toast(String s) {
        Toast.makeText(getApplicationContext(), s, Toast.LENGTH_SHORT).show();
    }

    private class Adapter extends BaseAdapter {
        ArrayList<Article> items = new ArrayList<>();

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
            String meta = (a.account.isEmpty() ? "未知公众号" : a.account)
                    + " · " + Store.readable(a.time);
            ((TextView) v.findViewById(R.id.meta)).setText(meta);
            ((TextView) v.findViewById(R.id.url)).setText(a.url);
            return v;
        }
    }
}
