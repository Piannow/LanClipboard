package com.lanclip.sync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        boolean receiver = context.getSharedPreferences(Util.PREFS, Context.MODE_PRIVATE)
                .getBoolean("receiver_running", false);
        if (receiver) {
            Intent service = new Intent(context, ReceiverService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
        }
    }
}
