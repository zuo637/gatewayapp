package com.example.gateway;
import android.content.ContentProvider;
import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
public class CrashInitProvider extends ContentProvider {
    private static final String TAG = "CrashInitProvider";
    @Override
    public boolean onCreate() {
        installCrashHandler(getContext());
        return true;
    }
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
    private void writeCrashLog(Context context, Throwable ex) {
        try {
            // 写到内部存储：/data/data/com.example.gateway/files/gateway_crash.txt
            // 完全免权限、免授权、随时可读
            File dir = context.getFilesDir();
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
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
