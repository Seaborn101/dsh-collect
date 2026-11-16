package com.dsh.collect;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** 每日回顾提醒：定时 + 通知 + 「今天最值得看的几篇」。 */
public final class Reminder {

    private Reminder() {
    }

    public static final String CHANNEL_ID = "dsh_collect_daily";
    public static final String ACTION_DAILY = "com.dsh.collect.DAILY_REVIEW";
    private static final int REQUEST = 2101;

    /** 通知渠道（Android 8+ 必须） */
    public static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                "每日回顾", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("提醒你回顾今天收集的文章");
        nm.createNotificationChannel(ch);
    }

    /** 安排下一次提醒（每天同一时刻） */
    public static void schedule(Context ctx) {
        Settings st = Settings.load(ctx);
        if (!st.autoRemind) {
            cancel(ctx);
            return;
        }
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        Intent i = new Intent(ctx, RemindReceiver.class).setAction(ACTION_DAILY);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, REQUEST, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, st.remindHour);
        c.set(Calendar.MINUTE, st.remindMinute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) {
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(), pi);
    }

    /** 取消提醒 */
    public static void cancel(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        Intent i = new Intent(ctx, RemindReceiver.class).setAction(ACTION_DAILY);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, REQUEST, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
    }

    /** 人类可读的时间 */
    public static String timeText(int hour, int minute) {
        return String.format("%02d:%02d", hour, minute);
    }

    /**
     * 让模型从今天收集的文章里挑出最值得看的几篇。
     *
     * @return 每条形如「① 标题 —— 理由」；失败或未配置时返回空列表
     */
    static List<String> aiPicks(Context ctx, List<Article> today) {
        List<String> out = new ArrayList<>();
        if (today.isEmpty()) {
            return out;
        }
        Settings st = Settings.load(ctx);
        if (!st.isConfigured()) {
            return out;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("下面是用户今天收集的文章（部分是未读未加工的）。请挑出最值得他今天花时间读的 3 篇，"
                + "并给每篇一句话理由（不超过 30 字，说明『为什么值得看』）。\n");
        sb.append("输出严格 JSON，不要解释文字：{\"picks\":[{\"title\":\"原文标题\","
                + "\"reason\":\"理由\"}]}\n\n");
        for (Article a : today) {
            sb.append("- ").append(a.title);
            if (!a.account.isEmpty()) {
                sb.append("（").append(a.account).append("）");
            }
            if (!a.summary.isEmpty()) {
                sb.append("：").append(a.summary);
            }
            sb.append("\n");
        }
        try {
            LlmClient.Result r = LlmClient.complete(st.endpoint, st.apiKey, st.model, "",
                    sb.toString(), 0.6f, 20);
            if (!r.ok) {
                return out;
            }
            JSONObject o = LlmClient.parseJson(r.content);
            if (o == null) {
                return out;
            }
            JSONArray picks = o.optJSONArray("picks");
            if (picks == null) {
                return out;
            }
            int i = 0;
            for (int k = 0; k < picks.length() && i < 3; k++) {
                JSONObject p = picks.optJSONObject(k);
                if (p == null) {
                    continue;
                }
                String t = p.optString("title", "").trim();
                String why = p.optString("reason", "").trim();
                if (t.isEmpty()) {
                    continue;
                }
                i++;
                out.add((i) + ". " + t + (why.isEmpty() ? "" : " —— " + why));
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }
}
