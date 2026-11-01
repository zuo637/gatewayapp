package com.example.gateway;
import android.webkit.JavascriptInterface;
import android.util.Log;
import android.content.Context;
import android.content.ClipboardManager;
import android.content.ClipData;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
public class JsBridge {
    private static final String TAG = "JsBridge";
    private final GatewayService service;
    private final Context context;
    public JsBridge(GatewayService service) {
        this.service = service;
        this.context = service;
    }
    @JavascriptInterface
    public String getStatus() {
        if (service == null) return "{\"running\":false}";
        try {
            org.json.JSONObject obj = new org.json.JSONObject(service.getStatusJson());
            // 附加 Python 状态
            obj.put("pythonStarted", com.example.gateway.GatewayApplication.isPythonStarted());
            obj.put("pythonStartFailed", com.example.gateway.GatewayApplication.isPythonStartFailed());
            if (com.example.gateway.GatewayApplication.isPythonStartFailed()) {
                Throwable err = com.example.gateway.GatewayApplication.getPythonStartError();
                obj.put("pythonError", err != null ? err.getMessage() : "unknown");
            }
            return obj.toString();
        } catch (Exception e) {
            return service.getStatusJson();
        }
    }
    @JavascriptInterface
    public void startGateway() {
        if (service != null) service.startGateway();
    }
    @JavascriptInterface
    public void stopGateway() {
        if (service != null) service.stopGateway();
    }
    @JavascriptInterface
    public void reloadConfig() {
        if (service != null) service.reloadConfig();
    }
    @JavascriptInterface
    public String getLogs() {
        return service != null ? service.getLogsJson() : "[]";
    }
    @JavascriptInterface
    public void saveConfig(String json) {
        if (service != null) service.saveConfig(json);
    }
    @JavascriptInterface
    public String getCrashLog() {
        try {
            File logFile = new File(context.getExternalFilesDir(null), "gateway_crash.txt");
            if (!logFile.exists()) {
                // 兜底尝试内部存储
                logFile = new File(context.getFilesDir(), "gateway_crash.txt");
            }
            if (!logFile.exists()) return "(no crash log yet)";
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(logFile)))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Read crash log failed", e);
            return "(read error: " + e.getMessage() + ")";
        }
    }
    @JavascriptInterface
    public void copyCrashLogToClipboard() {
        String log = getCrashLog();
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("gateway_crash", log));
    }
}
