package com.dsh.collect;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** 「主题综述」页：列出 Obsidian 里的 L2 综述，点开直达 Obsidian。 */
public class L2Page {

    private final Activity act;
    private final View root;
    private final ListView list;
    private final TextView hint;
    private final Adapter adapter = new Adapter();

    public L2Page(Activity act, ViewGroup container) {
        this.act = act;
        root = LayoutInflater.from(act).inflate(R.layout.page_l2, container, false);
        list = root.findViewById(R.id.l2_list);
        hint = root.findViewById(R.id.l2_hint);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Item it = adapter.items.get(position);
                if (it == null) {
                    return;
                }
                try {
                    String vault = new File(Store.VAULT_DIR).getName();
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(
                            "obsidian://open?vault=" + Uri.encode(vault)
                                    + "&file=" + Uri.encode(it.relPath)));
                    act.startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(act, "没找到 Obsidian 应用", Toast.LENGTH_SHORT).show();
                }
            }
        });
        root.findViewById(R.id.btn_make_l2).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (act instanceof MainActivity) {
                    ((MainActivity) act).pickCategoryForReview();
                }
            }
        });
        refresh();
    }

    public View getRoot() {
        return root;
    }

    public void refresh() {
        List<Item> items = new ArrayList<>();
        File dir = new File(Store.VAULT_DIR, "L2");
        File[] fs = dir.listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (!f.isFile() || !f.getName().endsWith(".md")) {
                    continue;
                }
                Article a = Import.parseMd(f);
                Item it = new Item();
                it.relPath = "L2/" + f.getName();
                it.title = a == null || a.title.isEmpty() ? f.getName() : a.title;
                it.category = a == null ? "" : a.category;
                it.summary = a == null ? "" : a.summary;
                it.collected = a == null || a.collectedAt <= 0 ? "" : Store.readable(a.collectedAt);
                items.add(it);
            }
        }
        java.util.Collections.sort(items, new Comparator<Item>() {
            @Override
            public int compare(Item x, Item y) {
                return y.collected.compareTo(x.collected);
            }
        });
        adapter.items = items;
        adapter.notifyDataSetChanged();
        hint.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        if (items.isEmpty()) {
            hint.setText(R.string.l2_empty);
        }
    }

    static class Item {
        String title = "";
        String category = "";
        String summary = "";
        String collected = "";
        String relPath = "";
    }

    private class Adapter extends BaseAdapter {
        List<Item> items = new ArrayList<>();

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
                v = LayoutInflater.from(act).inflate(R.layout.item_l2, parent, false);
            }
            Item it = items.get(position);
            ((TextView) v.findViewById(R.id.l2_title)).setText(it.title);
            TextView meta = v.findViewById(R.id.l2_meta);
            StringBuilder sb = new StringBuilder();
            if (!it.category.isEmpty()) {
                sb.append(it.category).append(" · ");
            }
            sb.append(it.collected);
            meta.setText(sb.toString());
            TextView sum = v.findViewById(R.id.l2_summary);
            sum.setText(it.summary);
            sum.setVisibility(it.summary.isEmpty() ? View.GONE : View.VISIBLE);
            return v;
        }
    }
}
