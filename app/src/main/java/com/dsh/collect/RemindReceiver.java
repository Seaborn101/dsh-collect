package com.dsh.collect;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 每日回顾提醒：到点发通知，并安排明天的。 */
public class RemindReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        // 先安排下一次，保证连续
        Reminder.schedule(ctx);

        Db db = Db.get(ctx);
        long startOfToday = startOfDay(System.currentTimeMillis());
        int today = db.countSince(startOfToday);
        if (today <= 0) {
            return; // 今天没收集，就不打扰
        }
        int pending = db.count() - db.countLayer("L1");
        String title = "今天收集了 " + today + " 篇";
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
        try {
            nm.notify(3001, b.build());
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
