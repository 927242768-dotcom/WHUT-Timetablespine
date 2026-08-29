package com.whut.timetable;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

final class UpdateSchedule {
    private UpdateSchedule() {}

    static void scheduleDaily(Context context) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) return;
        Intent intent = new Intent(context, UpdateCheckReceiver.class);
        PendingIntent pending = PendingIntent.getBroadcast(
                context, 9021, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        long first = System.currentTimeMillis() + 24L * 60L * 60L * 1000L;
        manager.setInexactRepeating(AlarmManager.RTC_WAKEUP, first,
                AlarmManager.INTERVAL_DAY, pending);
    }
}
