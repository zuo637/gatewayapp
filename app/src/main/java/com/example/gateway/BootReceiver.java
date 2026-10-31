package com.example.gateway;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            Intent serviceIntent = new Intent(context, GatewayService.class);
            serviceIntent.setAction("AUTO_START");
            context.startForegroundService(serviceIntent);
        }
    }
}
