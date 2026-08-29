package com.whut.timetable;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

public class AlarmReceiver extends BroadcastReceiver {
    private static final String CHANNEL_ID = "class_reminders";

    @Override
    public void onReceive(Context context, Intent intent) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "上课提醒", NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("在课程开始前提醒你");
            manager.createNotificationChannel(channel);
        }

        String title = intent.getStringExtra("title");
        String room = intent.getStringExtra("room");
        String teacher = intent.getStringExtra("teacher");
        String time = intent.getStringExtra("time");
        int minutes = intent.getIntExtra("minutes", 15);
        StringBuilder text = new StringBuilder();
        if (minutes > 0) text.append(minutes).append(" 分钟后上课");
        if (room != null && !room.isBlank()) text.append(text.length() == 0 ? "" : " · ").append(room);
        if (time != null && !time.isBlank()) text.append(text.length() == 0 ? "" : " · ").append(time);
        if (teacher != null && !teacher.isBlank()) text.append(text.length() == 0 ? "" : " · ").append(teacher);

        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                context, 11, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title == null || title.isBlank() ? "课程提醒" : title)
                .setContentText(text.toString())
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text.toString()))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(contentIntent);

        int id = Math.abs((String.valueOf(title) + String.valueOf(time)).hashCode());
        manager.notify(id, builder.build());
    }
}
