package com.dsh.collect;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.List;

/**
 * 每日回顾提醒：到点发通知，并安排明天的。
 *
 * <p>通知内容不只报数字——如果配了模型，会让模型从今天收集的文章里挑出最值得看的几篇，
 * 直接写进通知里，点开就能知道「今天该看什么」。
 */
public class RemindReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context ctx, final Intent intent) {
        // 先安排下一次，保证连续
        Reminder.schedule(ctx);

        final PendingResult pending = goAsync();
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    postNotification(app);
                } catch (Throwable ignored) {
                } finally {
                    pending.finish();
                }
            }
        }).start();
    }

    private void postNotification(Context ctx) {
        Db db = Db.get(ctx);
        long startOfToday = startOfDay(System.currentTimeMillis());
        List<Article> today = db.query(null, "", false, "", "");
        int todayCount = 0;
        List<Article> todayList = new java.util.ArrayList<>();
        for (Article a : today) {
            if (a.collectedAt >= startOfToday) {
                todayCount++;
                todayList.add(a);
            }
        }
        if (todayCount <= 0) {
            return; // 今天没收集，就不打扰
        }
        int pending = db.count() - db.countLayer("L1");

        String title = "今天收集了 " + todayCount + " 篇";
        String text = pending > 0 ? ("还有 " + pending + " 篇待加工 · 点开继续")
                : "都已加工完，点开回顾一下";

        Reminder.ensureChannel(ctx);
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        open.putExtra("filter", "L0");
        PendingIntent pi = PendingIntent.getActivity(ctx, 2202, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(ctx, Reminder.CHANNEL_ID);
        } else {
            b = new Notification.Builder(ctx);
        }
        b.setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(pi);

        // 先把基础通知发出去，保证用户一定看得到（广播窗口约 10 秒，不能等模型）
        try {
            nm.notify(3001, b.build());
        } catch (Exception ignored) {
            return;
        }

        // 再让模型挑「今天最值得看的几篇」，拿到后更新同一条通知
        List<String> picks = Reminder.aiPicks(ctx, todayList);
        if (picks.isEmpty()) {
            return;
        }
        StringBuilder big = new StringBuilder();
        big.append(text).append("\n\n今日精选：");
        for (String p : picks) {
            big.append("\n").append(p);
        }
        Notification.Builder b2;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b2 = new Notification.Builder(ctx, Reminder.CHANNEL_ID);
        } else {
            b2 = new Notification.Builder(ctx);
        }
        b2.setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(big.toString()))
                .setAutoCancel(true)
                .setContentIntent(pi);
        try {
            nm.notify(3001, b2.build());
        } catch (Exception ignored) {
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
}
