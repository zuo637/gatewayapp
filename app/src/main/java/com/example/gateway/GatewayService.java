package com.example.gateway;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.chaquo.python.Python;
import com.chaquo.python.PyObject;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class GatewayService extends Service {
    public class LocalBinder extends android.os.Binder {
        public GatewayService getService() {
            return GatewayService.this;
        }
    }
    private final IBinder binder = new LocalBinder();

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
    private static final String TAG = "GatewayService";
    private static final String CHANNEL_ID = "gateway_channel";
    private static final int NOTIFICATION_ID = 1001;

    private Python py;
    private PyObject gatewayModule;
    private PyObject gatewayInstance;
    private Thread gatewayThread;
    private volatile boolean running = false;
    private SharedPreferences prefs;
    private NotificationManager notificationManager;
    private JsBridge jsBridge;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("gateway_config", MODE_PRIVATE);
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
        jsBridge = new JsBridge(this);
        extractPythonScript();
        initPython();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "API 网关", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("多Key聚合API网关后台服务");
            notificationManager.createNotificationChannel(channel);
        }
    }

    private void extractPythonScript() {
        try {
            File destDir = new File(getFilesDir(), "python");
            if (!destDir.exists()) destDir.mkdirs();
            File destFile = new File(destDir, "gateway.py");
            if (!destFile.exists()) {
                InputStream is = getAssets().open("python/gateway.py");
                FileOutputStream fos = new FileOutputStream(destFile);
                byte[] buffer = new byte[8192];
                int len;
                while ((len = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, len);
                }
                fos.close();
                is.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Extract python script failed", e);
        }
    }

    private void initPython() {
        try {
            if (!Python.isStarted()) {
                Python.start(new com.chaquo.python.android.AndroidPlatform(this));
            }
            py = Python.getInstance();
            gatewayModule = py.getModule("gateway");
        } catch (Exception e) {
            Log.e(TAG, "Python init failed", e);
        }
    }

    private void startGatewayInternal() {
        if (running) return;
        running = true;
        
        gatewayThread = new Thread(() -> {
            try {
                if (gatewayModule == null) {
                    initPython();
                }
                if (gatewayModule != null) {
                    String configJson = prefs.getString("config", "{}");
                    gatewayInstance = gatewayModule.callAttr("Gateway", configJson);
                    gatewayInstance.callAttr("run");
                }
            } catch (Exception e) {
                Log.e(TAG, "Gateway runtime error", e);
            } finally {
                running = false;
            }
        });
        gatewayThread.start();
        
        showNotification("API 网关运行中", "点击打开管理页面");
    }

    private void stopGatewayInternal() {
        if (!running) return;
        running = false;
        try {
            if (gatewayInstance != null) {
                gatewayInstance.callAttr("stop");
            }
        } catch (Exception e) {
            Log.e(TAG, "Stop gateway error", e);
        }
        if (gatewayThread != null) {
            gatewayThread.interrupt();
        }
        notificationManager.cancel(NOTIFICATION_ID);
    }

    public void startGateway() {
        new Handler(Looper.getMainLooper()).post(this::startGatewayInternal);
    }

    public void stopGateway() {
        new Handler(Looper.getMainLooper()).post(this::stopGatewayInternal);
    }

    public void reloadConfig() {
        if (running) {
            stopGatewayInternal();
            startGatewayInternal();
        }
    }

    public void saveConfig(String json) {
        prefs.edit().putString("config", json).apply();
        if (running) reloadConfig();
    }

    public String getStatusJson() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("running", running);
            obj.put("port", 8128);
            return obj.toString();
        } catch (Exception e) {
            return "{\"running\":false}";
        }
    }

    public String getLogsJson() {
        try {
            if (gatewayInstance != null) {
                PyObject logs = gatewayInstance.callAttr("get_logs");
                return logs.toString();
            }
        } catch (Exception e) {
            Log.e(TAG, "Get logs error", e);
        }
        return "[]";
    }

    private void showNotification(String title, String text) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, intent, Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_IMMUTABLE : 0);
        
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();
        
        startForeground(NOTIFICATION_ID, notification);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "AUTO_START".equals(intent.getAction())) {
            startGatewayInternal();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopGatewayInternal();
        super.onDestroy();
    }

    public JsBridge getJsBridge() {
        return jsBridge;
    }
}