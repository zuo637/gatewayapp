package com.example.gateway;

import android.app.Application;
import android.content.Context;
import android.os.Environment;
import android.util.Log;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class GatewayApplication extends Application {
    private static final String TAG = "GatewayApplication";
    private static volatile boolean pythonStarted = false;
    private static volatile boolean pythonStartFailed = false;
    private static Throwable pythonStartError = null;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // 安装全局崩溃处理器（最早）
        installCrashHandler(base);
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    /** 懒加载启动 Python，供 Service / Bridge 调用 */
    public static synchronized boolean ensurePythonStarted(Context context) {
        if (pythonStarted) return true;
        if (pythonStartFailed) return false;
        try {
            if (!Python.isStarted()) {
                Python.start(new AndroidPlatform(context));
            }
            pythonStarted = true;
            Log.i(TAG, "Python started successfully");
            return true;
        } catch (Throwable t) {
            pythonStartFailed = true;
            pythonStartError = t;
            Log.e(TAG, "Python.start() failed", t);
            writeCrashLog(context, new RuntimeException("Python initialization failed", t));
            return false;
        }
    }

    public static boolean isPythonStarted() { return pythonStarted; }
    public static boolean isPythonStartFailed() { return pythonStartFailed; }
    public static Throwable getPythonStartError() { return pythonStartError; }

    private void installCrashHandler(Context context) {
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            private final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
            @Override
            public void uncaughtException(Thread thread, Throwable ex) {
                writeCrashLog(context, ex);
                if (defaultHandler != null) {
                    defaultHandler.uncaughtException(thread, ex);
                }
            }
        });
    }

    private static void writeCrashLog(Context context, Throwable ex) {
        try {
            // 写到 getExternalFilesDir —— 文件管理器能进 /sdcard/Android/data/包名/files/
            // 完全免权限、免授权、Android 11+ 也可见
            File dir = context.getExternalFilesDir(null);
            if (dir == null) {
                dir = context.getFilesDir(); // 兜底
            }
            File logFile = new File(dir, "gateway_crash.txt");
            try (FileWriter fw = new FileWriter(logFile, true);
                 PrintWriter pw = new PrintWriter(fw)) {
                pw.println("===== Crash @ " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()) + " =====");
                ex.printStackTrace(pw);
                pw.println();
            }
            Log.e(TAG, "Crash log written to: " + logFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Write crash log failed", e);
        }
    }
}
