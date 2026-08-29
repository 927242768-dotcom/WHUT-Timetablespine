package com.whut.timetable;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class ReminderScheduler {
    static final String PREFS = "whut_timetable_prefs";
    static final String KEY_ENABLED = "class_reminders_enabled";
    static final String KEY_MINUTES = "class_reminder_minutes";
    private static final String KEY_IDS = "scheduled_alarm_ids";

    private ReminderScheduler() {}

    static void configure(Context context, boolean enabled, int minutesBefore, String scheduleJson) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_ENABLED, enabled).putInt(KEY_MINUTES, minutesBefore).apply();
        reschedule(context, scheduleJson);
    }

    static void reschedule(Context context, String scheduleJson) {
        cancelExisting(context);
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_ENABLED, true)) return;
        int minutesBefore = prefs.getInt(KEY_MINUTES, 15);
        List<ScheduleUtils.Occurrence> occurrences = ScheduleUtils.parseOccurrences(scheduleJson);
        long now = System.currentTimeMillis();
        long horizon = now + 120L * 24L * 60L * 60L * 1000L;
        Set<String> ids = new HashSet<>();
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        for (ScheduleUtils.Occurrence occurrence : occurrences) {
            long triggerAt = occurrence.startMillis - minutesBefore * 60_000L;
            if (triggerAt <= now || triggerAt > horizon) continue;
            int requestCode = stableRequestCode(occurrence.key);
            Intent intent = new Intent(context, AlarmReceiver.class);
            intent.putExtra("title", occurrence.title);
            intent.putExtra("room", occurrence.room);
            intent.putExtra("teacher", occurrence.teacher);
            intent.putExtra("time", occurrence.startTime);
            intent.putExtra("minutes", minutesBefore);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    context, requestCode, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            long window = 5L * 60L * 1000L;
            alarmManager.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, window, pendingIntent);
            ids.add(String.valueOf(requestCode));
        }
        prefs.edit().putStringSet(KEY_IDS, ids).apply();
    }

    static void cancelExisting(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> ids = prefs.getStringSet(KEY_IDS, new HashSet<>());
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            for (String value : new HashSet<>(ids)) {
                try {
                    int requestCode = Integer.parseInt(value);
                    PendingIntent pendingIntent = PendingIntent.getBroadcast(
                            context, requestCode, new Intent(context, AlarmReceiver.class),
                            PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
                    );
                    if (pendingIntent != null) alarmManager.cancel(pendingIntent);
                } catch (Exception ignored) {
                }
            }
        }
        prefs.edit().remove(KEY_IDS).apply();
    }

    private static int stableRequestCode(String key) {
        int hash = key == null ? 1 : key.hashCode();
        return hash == Integer.MIN_VALUE ? 1 : Math.abs(hash);
    }
}
