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
import org.json.JSONArray;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
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
        syncPythonScript();
            // 把内部 python 目录加入 sys.path，否则 getModule 找不到模块
            try {
                File internalDir = getInternalPythonFile().getParentFile();
                py.getModule("sys").get("path").callAttr("append", internalDir.getAbsolutePath());
                Log.i(TAG, "Added to sys.path: " + internalDir.getAbsolutePath());
            } catch (Exception e) {
                Log.w(TAG, "Add sys.path failed", e);
            }
        Log.i(TAG, "Service onCreate done");
    }
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "API 网关", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("多Key聚合API网关后台服务");
            notificationManager.createNotificationChannel(channel);
        }
    }
    // 外部存储可见目录（用户可编辑）
    private File getExternalPythonFile() {
        File destDir = new File(getExternalFilesDir(null), "python");
        if (!destDir.exists()) destDir.mkdirs();
        return new File(destDir, "gateway.py");
    }
    // 内部存储目录（Chaquopy 加载用）
    private File getInternalPythonFile() {
        File destDir = new File(getFilesDir(), "python");
        if (!destDir.exists()) destDir.mkdirs();
        return new File(destDir, "gateway.py");
    }
    // 首次安装：assets -> 外部存储；之后外部存储 -> 内部存储（热更新）
    private void syncPythonScript() {
        try {
            File externalFile = getExternalPythonFile();
            File internalFile = getInternalPythonFile();
            
            // 首次安装：外部文件不存在，从 assets 复制到外部
            if (!externalFile.exists()) {
                InputStream is = getAssets().open("python/gateway.py");
                FileOutputStream fos = new FileOutputStream(externalFile);
                byte[] buffer = new byte[8192];
                int len;
                while ((len = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, len);
                }
                fos.close();
                is.close();
                Log.i(TAG, "Python script extracted to external: " + externalFile.getAbsolutePath());
            }
            // 每次启动/重载：外部 -> 内部（实现热更新）
            if (externalFile.exists()) {
                FileInputStream fis = new FileInputStream(externalFile);
                FileOutputStream fos = new FileOutputStream(internalFile);
                byte[] buffer = new byte[8192];
                int len;
                while ((len = fis.read(buffer)) != -1) {
                    fos.write(buffer, 0, len);
                }
                fos.close();
                fis.close();
                Log.i(TAG, "Python script synced to internal: " + internalFile.getAbsolutePath());
            }
        } catch (Exception e) {
            Log.e(TAG, "Sync python script failed", e);
        }
    }
    private boolean initPython() {
        Log.i(TAG, "Initializing Python...");
        if (!GatewayApplication.ensurePythonStarted(this)) {
            String err = "Python failed to start: " + GatewayApplication.getPythonStartError();
            Log.e(TAG, err);
            return false;
        }
        try {
            py = Python.getInstance();
            // 同步外部脚本到内部，然后标准加载
            syncPythonScript();
            // 把内部 python 目录加入 sys.path，否则 getModule 找不到模块
            try {
                File internalDir = getInternalPythonFile().getParentFile();
                py.getModule("sys").get("path").callAttr("append", internalDir.getAbsolutePath());
                Log.i(TAG, "Added to sys.path: " + internalDir.getAbsolutePath());
            } catch (Exception e) {
                Log.w(TAG, "Add sys.path failed", e);
            }
            File internalFile = getInternalPythonFile();
            if (internalFile.exists()) {
                // 从文件加载模块（Chaquopy 支持从文件路径加载）
                gatewayModule = py.getModule("gateway");
                Log.i(TAG, "Python module loaded from " + internalFile.getAbsolutePath());
            } else {
                Log.e(TAG, "Internal python script not found after sync");
                return false;
            }
            Log.i(TAG, "Python initialized successfully");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Python init failed", e);
            return false;
        }
    }
    public void reloadPython() {
        Log.i(TAG, "reloadPython() called from JS");
        new Handler(Looper.getMainLooper()).post(() -> {
            if (running) stopGatewayInternal();
            initPython();
            startGatewayInternal();
        });
    }
    private void startGatewayInternal() {
        if (running) {
            Log.i(TAG, "Gateway already running");
            return;
        }
        Log.i(TAG, "Starting gateway...");
        if (!initPython()) {
            Log.e(TAG, "Cannot start gateway: Python unavailable");
            running = false;
            return;
        }
        running = true;
        gatewayThread = new Thread(() -> {
            try {
                Log.i(TAG, "Gateway thread started, loading config...");
                if (gatewayModule != null) {
                    String configJson = prefs.getString("config", "{}");
                    Log.i(TAG, "Config: " + configJson);
                    gatewayInstance = gatewayModule.callAttr("Gateway", configJson);
                    Log.i(TAG, "Gateway instance created, calling run()...");
                    gatewayInstance.callAttr("run");
                    Log.i(TAG, "Gateway.run() returned (server started)");
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
        Log.i(TAG, "Stopping gateway...");
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
        Log.i(TAG, "startGateway() called from JS");
        new Handler(Looper.getMainLooper()).post(this::startGatewayInternal);
    }
    public void stopGateway() {
        Log.i(TAG, "stopGateway() called from JS");
        new Handler(Looper.getMainLooper()).post(this::stopGatewayInternal);
    }
    public void reloadConfig() {
        Log.i(TAG, "reloadConfig() called");
        if (running) {
            stopGatewayInternal();
            startGatewayInternal();
        } else {
            startGatewayInternal();
        }
    }
    public void saveConfig(String json) {
        prefs.edit().putString("config", json).apply();
        Log.i(TAG, "Config saved: " + json);
        reloadConfig();
    }
    public String getStatusJson() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("running", running);
            obj.put("port", prefs.getInt("port", 8128));
            if (gatewayInstance != null) {
                try {
                    String pyStatus = gatewayInstance.callAttr("get_status").toString();
                    JSONObject pyObj = new JSONObject(pyStatus);
                    obj.put("providers", pyObj.getJSONArray("providers"));
                    obj.put("master_key", pyObj.optString("master_key", ""));
                } catch (Exception e) {
                    Log.w(TAG, "Get python status failed", e);
                    obj.put("providers", new JSONArray());
                }
            } else {
                obj.put("providers", new JSONArray());
            }
            return obj.toString();
        } catch (Exception e) {
            Log.e(TAG, "getStatusJson error", e);
            return "{\"running\":false,\"providers\":[]}";
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
