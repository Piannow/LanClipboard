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
 * APK 下载服务：脱离 Activity 生命周期，切页面/退后台下载都不中断。
 * 进度通过广播 ACTION_DOWNLOAD 汇报，Activity 回来可重新绑定查看。
 */
public class DownloadService extends Service {

    private static final String TAG = "LanClipDownload";
    private static final String CHANNEL_ID = "lanclip_download";
    private static final int NOTIFICATION_ID = 1004;

    public static final String ACTION_DOWNLOAD = "com.lanclip.sync.DOWNLOAD";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_VERSION = "version";

    /** 正在下载的版本 → 进度百分比（-1 表示未知），供 Activity 恢复显示 */
    private static final Map<String, Integer> PROGRESS = new HashMap<>();
    /** 已完成/失败的版本 → 结果文案 */
    private static final Map<String, String> RESULT = new HashMap<>();

    public static Map<String, Integer> progressMap() {
        return PROGRESS;
    }

    public static Map<String, String> resultMap() {
        return RESULT;
    }

    public static boolean isDownloading(String version) {
        return PROGRESS.containsKey(version);
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
        final String url = intent.getStringExtra(EXTRA_URL);
        final String name = intent.getStringExtra(EXTRA_NAME);
        final String version = intent.getStringExtra(EXTRA_VERSION);
        if (url == null || name == null) {
            return START_NOT_STICKY;
        }
        if (PROGRESS.containsKey(version)) {
            return START_NOT_STICKY;
        }
        PROGRESS.put(version, 0);
        startForeground(NOTIFICATION_ID, buildNotification("准备下载 " + version, 0, true));

        new Thread(() -> {
            try {
                File apk = UpdateClient.download(this, url, name, (done, total) -> {
                    int pct = total <= 0 ? -1 : (int) (done * 100 / total);
                    PROGRESS.put(version, pct);
                    updateNotification("下载 " + version + " " + (pct < 0 ? "" : pct + "%"), pct, false);
                    broadcast(version, pct, null);
                });
                PROGRESS.remove(version);
                RESULT.put(version, apk.getName());
                broadcast(version, 100, apk.getAbsolutePath());
                stopSelf();
            } catch (Throwable t) {
                PROGRESS.remove(version);
                String msg = t.getMessage() == null ? t.toString() : t.getMessage();
                RESULT.put(version, "失败：" + msg);
                broadcast(version, -1, "失败：" + msg);
                Log.w(TAG, "download failed", t);
                stopSelf();
            }
        }, "lanclip-download").start();

        return START_NOT_STICKY;
    }

    private void broadcast(String version, int pct, String donePath) {
        Intent i = new Intent(ACTION_DOWNLOAD).setPackage(getPackageName());
        i.putExtra("version", version);
        i.putExtra("pct", pct);
        if (donePath != null) {
            i.putExtra("done", donePath);
        }
        sendBroadcast(i);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                        "APK 下载", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
    }

    private Notification buildNotification(String text, int pct, boolean indeterminate) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        Intent open = new Intent(this, UpdateActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, Util.pendingFlags());
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("局域网剪切板 · 下载中")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        if (indeterminate || pct < 0) {
            b.setProgress(0, 0, true);
        } else {
            b.setProgress(100, pct, false);
        }
        return b.build();
    }

    private void updateNotification(String text, int pct, boolean indeterminate) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIFICATION_ID, buildNotification(text, pct, indeterminate));
            }
        } catch (Throwable ignored) {
        }
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