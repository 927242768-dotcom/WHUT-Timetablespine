package com.whut.timetable;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

public class TodayWidgetProvider extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) updateOne(context, appWidgetManager, appWidgetId);
    }

    static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName component = new ComponentName(context, TodayWidgetProvider.class);
        int[] ids = manager.getAppWidgetIds(component);
        for (int id : ids) updateOne(context, manager, id);
    }

    private static void updateOne(Context context, AppWidgetManager manager, int id) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_today);
        String json = readSchedule(context);
        ScheduleUtils.Occurrence occurrence = json == null ? null : ScheduleUtils.nextTodayOccurrence(json);
        int count = json == null ? 0 : ScheduleUtils.todayCount(json);
        views.setTextViewText(R.id.widget_title, count > 0 ? "今天 · " + count + " 节" : "今天");
        if (occurrence == null) {
            views.setTextViewText(R.id.widget_course, count > 0 ? "今天课程已结束" : "今天没有课");
            views.setTextViewText(R.id.widget_meta, "点一下打开武理课表");
        } else {
            views.setTextViewText(R.id.widget_course, occurrence.title);
            String meta = occurrence.startTime;
            if (occurrence.endTime != null && !occurrence.endTime.isBlank()) meta += "–" + occurrence.endTime;
            if (occurrence.room != null && !occurrence.room.isBlank()) meta += " · " + occurrence.room;
            views.setTextViewText(R.id.widget_meta, meta);
        }
        Intent open = new Intent(context, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(context, 50, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, pending);
        manager.updateAppWidget(id, views);
    }

    private static String readSchedule(Context context) {
        try {
            File file = new File(context.getFilesDir(), MainActivity.NATIVE_SCHEDULE_FILE_NAME);
            if (!file.exists()) return null;
            try (FileInputStream input = new FileInputStream(file);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                return output.toString(StandardCharsets.UTF_8.name());
            }
        } catch (Exception e) {
            return null;
        }
    }
}
