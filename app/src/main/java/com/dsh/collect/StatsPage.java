package com.dsh.collect;

import android.app.Activity;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 「统计」页：库存、分类分布、标签分布。 */
public class StatsPage {

    private final Activity act;
    private final View root;

    public StatsPage(Activity act, ViewGroup container) {
        this.act = act;
        root = LayoutInflater.from(act).inflate(R.layout.page_stats, container, false);
        refresh();
    }

    public View getRoot() {
        return root;
    }

    public void refresh() {
        Db db = Db.get(act);
        List<Article> all = db.all();

        // 数字卡片
        LinearLayout cards = root.findViewById(R.id.stats_cards);
        cards.removeAllViews();
        int done = db.countLayer("L1");
        int star = db.countStarred();
        addCard(cards, String.valueOf(all.size()), "总收藏");
        addCard(cards, String.valueOf(done), "已加工");
        addCard(cards, String.valueOf(all.size() - done), "待加工");
        addCard(cards, String.valueOf(star), "星标");

        // 分类分布
        LinearLayout cats = root.findViewById(R.id.stats_categories);
        cats.removeAllViews();
        Map<String, Integer> byCat = new LinkedHashMap<>();
        for (Article a : all) {
            String c = a.category == null || a.category.isEmpty() ? "未分类" : a.category;
            Integer n = byCat.get(c);
            byCat.put(c, n == null ? 1 : n + 1);
        }
        addBars(cats, byCat);

        // 标签分布
        LinearLayout tags = root.findViewById(R.id.stats_tags);
        tags.removeAllViews();
        Map<String, Integer> byTag = new LinkedHashMap<>();
        for (Article a : all) {
            for (String t : a.tagList()) {
                Integer n = byTag.get(t);
                byTag.put(t, n == null ? 1 : n + 1);
            }
        }
        addBars(tags, byTag);

        TextView paths = root.findViewById(R.id.stats_paths);
        paths.setText("导出目录：" + Store.collectedDir(act).getAbsolutePath()
                + "\n知识库：" + Store.VAULT_DIR);
    }

    private void addCard(LinearLayout box, String value, String label) {
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setBackgroundResource(R.drawable.card_bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = (int) (8 * act.getResources().getDisplayMetrics().density);
        box.addView(col, lp);

        TextView v = new TextView(act);
        v.setText(value);
        v.setTextSize(20);
        v.setTextColor(act.getResources().getColor(R.color.text_primary));
        v.setGravity(Gravity.CENTER);
        col.addView(v);

        TextView l = new TextView(act);
        l.setText(label);
        l.setTextSize(11);
        l.setTextColor(act.getResources().getColor(R.color.text_tertiary));
        l.setGravity(Gravity.CENTER);
        col.addView(l);
    }

    /** 简易条形分布 */
    private void addBars(LinearLayout box, Map<String, Integer> data) {
        box.removeAllViews();
        if (data.isEmpty()) {
            TextView t = new TextView(act);
            t.setText(R.string.no_data);
            t.setTextSize(13);
            t.setTextColor(act.getResources().getColor(R.color.text_tertiary));
            box.addView(t);
            return;
        }
        List<Map.Entry<String, Integer>> list = new ArrayList<>(data.entrySet());
        java.util.Collections.sort(list, new java.util.Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> x, Map.Entry<String, Integer> y) {
                return y.getValue() - x.getValue();
            }
        });
        int max = list.get(0).getValue();
        for (Map.Entry<String, Integer> e : list) {
            if (list.size() > 12 && e != list.get(0) && list.indexOf(e) > 11) {
                break;
            }
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView name = new TextView(act);
            name.setText(e.getKey());
            name.setTextSize(13);
            name.setTextColor(act.getResources().getColor(R.color.text_primary));
            name.setMaxLines(1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(name, lp);

            View bar = new View(act);
            bar.setBackgroundResource(R.drawable.bar);
            int w = (int) (110 * e.getValue() / (float) max);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    Math.max(w, 4), (int) (6 * act.getResources().getDisplayMetrics().density));
            blp.leftMargin = (int) (8 * act.getResources().getDisplayMetrics().density);
            blp.rightMargin = (int) (8 * act.getResources().getDisplayMetrics().density);
            row.addView(bar, blp);

            TextView num = new TextView(act);
            num.setText(String.valueOf(e.getValue()));
            num.setTextSize(12);
            num.setTextColor(act.getResources().getColor(R.color.text_secondary));
            row.addView(num);

            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = (int) (7 * act.getResources().getDisplayMetrics().density);
            box.addView(row, rlp);
        }
    }
}
