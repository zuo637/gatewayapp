package com.example.gateway;
import android.app.Application;
import android.content.Context;
import android.os.Environment;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
public class GatewayApplication extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(this));
        }
        installCrashHandler();
    }
    @Override
    public void onCreate() {
        super.onCreate();
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(this));
        }
    }
    private void installCrashHandler() {
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            private final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
            @Override
            public void uncaughtException(Thread thread, Throwable ex) {
                writeCrashLog(ex);
                if (defaultHandler != null) {
                    defaultHandler.uncaughtException(thread, ex);
                }
            }
        });
    }
    private void writeCrashLog(Throwable ex) {
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOAD);
            if (!dir.exists()) dir.mkdirs();
            File logFile = new File(dir, "gateway_crash.txt");
            try (FileWriter fw = new FileWriter(logFile, true);
                 PrintWriter pw = new PrintWriter(fw)) {
                pw.println("===== Crash @ " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()) + " =====");
                ex.printStackTrace(pw);
                pw.println();
            }
        } catch (Exception ignore) {}
    }
}
