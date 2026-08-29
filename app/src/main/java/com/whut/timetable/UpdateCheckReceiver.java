package com.whut.timetable;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class UpdateCheckReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        UpdateManager.check(context, UpdateManager.currentVersion(context), false, null);
        UpdateSchedule.scheduleDaily(context);
    }
}
