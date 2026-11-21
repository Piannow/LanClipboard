package com.lanclip.sync;

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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 手机端：常驻监听剪切板，一有变化就把文字 POST 给机顶盒。
 */
public class SenderService extends Service {

    private static final String CHANNEL_ID = "lanclip_sender";
    private static final int NOTIFICATION_ID = 1002;

    public static volatile boolean running = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean sending = new AtomicBoolean(false);
    private ExecutorService network;
    private volatile String lastSent = "";
    private String status = "等待剪切板变化";
    private boolean ticking;

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification(status));
        network = Executors.newSingleThreadExecutor();
        lastSent = Util.getClipText(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            getSharedPreferences(Util.PREFS, MODE_PRIVATE).edit()
                    .putBoolean("autosync", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        startTicking();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startTicking() {
        if (ticking) {
            return;
        }
        ticking = true;
        handler.post(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                pollClipboard();
            } catch (Throwable ignored) {
            }
            handler.postDelayed(this, 700);
        }
    };

    private void pollClipboard() {
        SharedPreferences prefs = getSharedPreferences(Util.PREFS, MODE_PRIVATE);
        final String ip = prefs.getString("target_ip", "");
        final int port = prefs.getInt("target_port", Util.HTTP_PORT);
        if (ip.isEmpty()) {
            return;
        }
        String text = Util.getClipText(this);
        if (text.isEmpty() || text.equals(lastSent)) {
            return;
        }
        if (!sending.compareAndSet(false, true)) {
            return;
        }
        final String payload = text;
        network.execute(() -> {
            boolean ok = Util.postClip(ip, port, payload);
            sending.set(false);
            if (ok) {
                lastSent = payload;
                status = "已发送 " + payload.length() + " 字 → " + ip;
                updateNotification(status);
                broadcast("已发送 " + payload.length() + " 字 → " + ip);
            }
        });
    }

    private void broadcast(String line) {
        Intent intent = new Intent(Util.ACTION_SENDER_STATE).setPackage(getPackageName());
        intent.putExtra("status", line);
        sendBroadcast(intent);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                        "剪切板自动同步", NotificationManager.IMPORTANCE_LOW);
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        SharedPreferences prefs = getSharedPreferences(Util.PREFS, MODE_PRIVATE);
        String ip = prefs.getString("target_ip", "");
        Intent open = new Intent(this, SenderActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, Util.pendingFlags());
        Intent stop = new Intent(this, SenderService.class).setAction("STOP");
        PendingIntent stopPending = PendingIntent.getService(this, 1, stop, Util.pendingFlags());
        return builder
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("局域网剪切板 · 手机端")
                .setContentText(ip.isEmpty() ? text : text + "（目标 " + ip + "）")
                .setContentIntent(pending)
                // addAction(int, ...) 从 API 16 就可用；Notification.Action.Builder 是 API 20+，
                // 在 Android 4.4 机顶盒上会崩溃
                .addAction(R.drawable.ic_stat, "停止自动同步", stopPending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIFICATION_ID, buildNotification(text));
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        ticking = false;
        handler.removeCallbacksAndMessages(null);
        if (network != null) {
            network.shutdownNow();
        }
        stopForeground(true);
        broadcast("自动同步已停止");
        super.onDestroy();
    }
}
