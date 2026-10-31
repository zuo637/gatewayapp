package com.example.gateway;

import android.webkit.JavascriptInterface;
import android.util.Log;

public class JsBridge {
    private static final String TAG = "JsBridge";
    private final GatewayService service;

    public JsBridge(GatewayService service) {
        this.service = service;
    }

    @JavascriptInterface
    public String getStatus() {
        return service != null ? service.getStatusJson() : "{\"running\":false}";
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
}