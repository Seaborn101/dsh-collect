package com.dsh.collect;

import android.app.Activity;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** 「今日精选」页：让模型从最近收集的文章里挑出最值得看的几篇。 */
public class PicksPage {

    private final Activity act;
    private final View root;
    private final ListView list;
    private final TextView hint;
    private final Adapter adapter = new Adapter();

    public PicksPage(Activity act, ViewGroup container) {
        this.act = act;
        root = LayoutInflater.from(act).inflate(R.layout.page_picks, container, false);
        list = root.findViewById(R.id.picks_list);
        hint = root.findViewById(R.id.picks_hint);
        list.setAdapter(adapter);
        root.findViewById(R.id.btn_refresh_picks).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh(true);
            }
        });
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                AiPicks.Pick p = adapter.items.get(position);
                if (p == null) {
                    return;
                }
                Article a = Db.get(act).byUrl(p.url);
                if (a == null) {
                    return;
                }
                Intent it = new Intent(act, ReaderActivity.class);
                it.putExtra("id", a.id);
                act.startActivity(it);
            }
        });
        refresh(false);
    }

    public View getRoot() {
        return root;
    }

    /** 刷新精选 */
    public void refresh(boolean force) {
        final Settings st = Settings.load(act);
        if (!st.isConfigured()) {
            adapter.items.clear();
            adapter.notifyDataSetChanged();
            hint.setText(R.string.picks_need_model);
            return;
        }
        final List<Article> recent = new ArrayList<>();
        long since = System.currentTimeMillis() - 7L * 24 * 3600 * 1000;
        for (Article a : Db.get(act).all()) {
            if (a.collectedAt >= since) {
                recent.add(a);
            }
        }
        if (recent.isEmpty()) {
            hint.setText(R.string.picks_empty);
            return;
        }
        if (recent.size() > 12) {
            recent.subList(0, recent.size() - 12).clear();
        }
        hint.setText(R.string.picks_loading);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<AiPicks.Pick> picks = AiPicks.pick(act, recent, 5);
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (picks.isEmpty()) {
                            hint.setText(R.string.picks_failed);
                            return;
                        }
                        hint.setVisibility(View.GONE);
                        adapter.items = picks;
                        adapter.notifyDataSetChanged();
                    }
                });
            }
        }).start();
    }

    private class Adapter extends BaseAdapter {
        List<AiPicks.Pick> items = new ArrayList<>();

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
                v = LayoutInflater.from(act).inflate(R.layout.item_pick, parent, false);
            }
            AiPicks.Pick p = items.get(position);
            ((TextView) v.findViewById(R.id.pick_title)).setText(p.title);
            TextView r = v.findViewById(R.id.pick_reason);
            r.setText(p.reason);
            r.setVisibility(p.reason == null || p.reason.isEmpty() ? View.GONE : View.VISIBLE);
            TextView acc = v.findViewById(R.id.pick_account);
            acc.setText(p.account);
            acc.setVisibility(p.account == null || p.account.isEmpty() ? View.GONE : View.VISIBLE);
            return v;
        }
    }
}
