package com.example.gateway;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) ||
            Intent.ACTION_QUICKBOOT_POWERON.equals(intent.getAction())) {
            Intent serviceIntent = new Intent(context, GatewayService.class);
            serviceIntent.setAction("AUTO_START");
            context.startForegroundService(serviceIntent);
        }
    }
}