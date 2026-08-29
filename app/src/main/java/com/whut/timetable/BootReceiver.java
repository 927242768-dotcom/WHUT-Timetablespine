package com.whut.timetable;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        UpdateSchedule.scheduleDaily(context);
        try {
            File file = new File(context.getFilesDir(), MainActivity.NATIVE_SCHEDULE_FILE_NAME);
            if (file.exists()) {
                String json = readUtf8(file);
                ReminderScheduler.reschedule(context, json);
                TodayWidgetProvider.updateAll(context);
            }
        } catch (Exception ignored) {
        }
    }

    private static String readUtf8(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}
