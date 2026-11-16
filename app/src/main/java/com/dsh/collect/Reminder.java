package com.dsh.collect;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;

/** 每日回顾提醒：定时 + 通知。 */
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
        // 不用 setExact：Android 12+ 需要额外权限，且每日回顾不差这几分钟
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
}
