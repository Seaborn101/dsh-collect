package com.dsh.collect;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机后重新安排每日提醒（AlarmManager 的 alarm 不会跨重启保留）。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent != null && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Reminder.schedule(ctx);
        }
    }
}
