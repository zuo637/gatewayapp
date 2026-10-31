package com.example.gateway;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.KeyEvent;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private GatewayService gatewayService;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());

        // 从 Service 获取 JS Bridge
        Intent intent = new Intent(this, GatewayService.class);
        bindService(intent, new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                GatewayService.LocalBinder binder = (GatewayService.LocalBinder) service;
                gatewayService = binder.getService();
                if (gatewayService != null) {
                    webView.addJavascriptInterface(gatewayService.getJsBridge(), "AndroidBridge");
                    loadManagementPage();
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                gatewayService = null;
            }
        }, BIND_AUTO_CREATE);

        // 确保服务启动
        startService(new Intent(this, GatewayService.class));
    }

    private void loadManagementPage() {
        webView.loadUrl("file:///android_asset/gateway.html");
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindService(connection);
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            GatewayService.LocalBinder binder = (GatewayService.LocalBinder) service;
            gatewayService = binder.getService();
            if (gatewayService != null) {
                webView.addJavascriptInterface(gatewayService.getJsBridge(), "AndroidBridge");
                loadManagementPage();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            gatewayService = null;
        }
    };
}