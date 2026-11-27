package com.lanclip.sync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * APK 推送服务：把本地 APK 通过 adb 装到盒子。脱离 Activity 生命周期，
 * 切页面/退后台不中断。结果通过 ACTION_PUSH 广播汇报。
 */
public class PushService extends Service {

    private static final String TAG = "LanClipPush";
    private static final String CHANNEL_ID = "lanclip_push";
    private static final int NOTIFICATION_ID = 1005;

    public static final String ACTION_PUSH = "com.lanclip.sync.PUSH";
    public static final String EXTRA_APK = "apk";
    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_LABEL = "label";

    /** 正在推送的 apk 名 → "running" */
    private static final Map<String, String> RUNNING = new HashMap<>();
    /** apk 名 → 结果文案 */
    private static final Map<String, String> RESULT = new HashMap<>();

    public static boolean isPushing(String apkName) {
        return RUNNING.containsKey(apkName);
    }

    public static Map<String, String> resultMap() {
        return RESULT;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }
        final String apkPath = intent.getStringExtra(EXTRA_APK);
        final String host = intent.getStringExtra(EXTRA_HOST);
        final int port = intent.getIntExtra(EXTRA_PORT, 5555);
        final String label = intent.getStringExtra(EXTRA_LABEL);
        if (apkPath == null || host == null) {
            return START_NOT_STICKY;
        }
        final File apk = new File(apkPath);
        final String key = apk.getName();
        if (RUNNING.containsKey(key)) {
            return START_NOT_STICKY;
        }
        RUNNING.put(key, "running");
        startForeground(NOTIFICATION_ID, buildNotification("推送 " + key + " 到 " + host, true));

        new Thread(() -> {
            AdbRunner runner = null;
            try {
                runner = new AdbRunner(this, host, port);
                String result = runner.install(apk.getAbsolutePath());
                String text = result == null ? "" : result.trim();
                boolean ok = text.contains("Success");
                RESULT.put(key, (ok ? "成功：" : "失败：") + text);
                broadcast(key, ok, text);
            } catch (Throwable t) {
                String msg = t.getMessage() == null ? t.toString() : t.getMessage();
                RESULT.put(key, "失败：" + msg);
                broadcast(key, false, msg);
                Log.w(TAG, "push failed", t);
            } finally {
                RUNNING.remove(key);
                if (runner != null) {
                    try {
                        runner.disconnect();
                    } catch (Throwable ignored) {
                    }
                }
                stopSelf();
            }
        }, "lanclip-push").start();

        return START_NOT_STICKY;
    }

    private void broadcast(String key, boolean ok, String text) {
        Intent i = new Intent(ACTION_PUSH).setPackage(getPackageName());
        i.putExtra("apk", key);
        i.putExtra("ok", ok);
        i.putExtra("text", text);
        sendBroadcast(i);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                        "APK 推送", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
    }

    private Notification buildNotification(String text, boolean indeterminate) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        Intent open = new Intent(this, UpdateActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, Util.pendingFlags());
        return b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("局域网剪切板 · 推送中")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(0, 0, indeterminate)
                .build();
    }

    @Override
    public void onDestroy() {
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}