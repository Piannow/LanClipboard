package com.lanclip.sync;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * 悬浮球：点一下开始/停止自动同步，长按退出悬浮球。
 * 颜色：停止=红色，运行中=绿色。
 */
public class FloatingButtonService extends Service {

    private static final String TAG = "LanClipFloating";
    private static final String CHANNEL_ID = "lanclip_floating";
    private static final int NOTIFICATION_ID = 1003;

    /** 悬浮球是否在显示 */
    public static volatile boolean running = false;

    private WindowManager windowManager;
    private TextView bubble;
    private WindowManager.LayoutParams params;

    private int initialX, initialY;
    private float initialTouchX, initialTouchY;
    private long touchDownTime;
    private boolean moved;

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createBubble();
        startForeground(NOTIFICATION_ID, buildNotification());
    }

    private void createBubble() {
        bubble = new TextView(this);
        bubble.setText("同步");
        bubble.setTextSize(11);
        bubble.setTextColor(Color.WHITE);
        bubble.setGravity(Gravity.CENTER);
        applyStateColor();

        int size = dp(56);
        params = new WindowManager.LayoutParams(
                size, size,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(16);
        params.y = dp(200);

        bubble.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        touchDownTime = System.currentTimeMillis();
                        moved = false;
                        v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(80).start();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - initialTouchX);
                        int dy = (int) (event.getRawY() - initialTouchY);
                        if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) {
                            moved = true;
                        }
                        params.x = initialX + dx;
                        params.y = initialY + dy;
                        try {
                            windowManager.updateViewLayout(bubble, params);
                        } catch (Throwable ignored) {
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        v.animate().scaleX(1f).scaleY(1f).setDuration(80).start();
                        long held = System.currentTimeMillis() - touchDownTime;
                        if (moved) {
                            return true;
                        }
                        if (held >= 600) {
                            // 长按：先关闭自动同步，再退出悬浮球
                            stopAutoSync();
                            stopSelf();
                        } else {
                            // 点击：切换自动同步
                            toggleAutoSync();
                        }
                        return true;
                }
                return false;
            }
        });

        try {
            windowManager.addView(bubble, params);
        } catch (Throwable t) {
            Log.w(TAG, "addView failed", t);
            stopSelf();
        }
    }

    private void applyStateColor() {
        boolean running = SenderService.running;
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(running ? Color.parseColor("#4ADE80") : Color.parseColor("#EF4444"));
        shape.setStroke(dp(2), Color.parseColor("#80FFFFFF"));
        bubble.setBackground(shape);
        bubble.setTextColor(running ? Color.parseColor("#052E16") : Color.WHITE);
        bubble.setText(running ? "同步中" : "已停止");
    }

    /** 停止自动同步（关服务 + 清开关 + 清状态）。 */
    private void stopAutoSync() {
        try {
            getSharedPreferences(Util.PREFS, MODE_PRIVATE).edit()
                    .putBoolean("autosync", false).apply();
            startService(new Intent(this, SenderService.class).setAction("STOP"));
        } catch (Throwable ignored) {
        }
    }

    private void toggleAutoSync() {
        boolean running = SenderService.running;
        if (running) {
            startService(new Intent(this, SenderService.class).setAction("STOP"));
            getSharedPreferences(Util.PREFS, MODE_PRIVATE).edit().putBoolean("autosync", false).apply();
        } else {
            String ip = getSharedPreferences(Util.PREFS, MODE_PRIVATE).getString("target_ip", "");
            if (ip.isEmpty()) {
                android.widget.Toast.makeText(this, "请先在应用中选好接收端", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            getSharedPreferences(Util.PREFS, MODE_PRIVATE).edit().putBoolean("autosync", true).apply();
            Intent intent = new Intent(this, SenderService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        }
        // 稍微延迟后刷新颜色（等服务状态生效）
        bubble.postDelayed(this::applyStateColor, 350);
    }

    private android.app.Notification buildNotification() {
        android.app.Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(new android.app.NotificationChannel(
                        CHANNEL_ID, "悬浮球", android.app.NotificationManager.IMPORTANCE_MIN));
            }
            b = new android.app.Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new android.app.Notification.Builder(this);
        }
        Intent open = new Intent(this, SenderActivity.class);
        return b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("局域网剪切板 · 悬浮球")
                .setContentText("点按开始/停止，长按退出")
                .setContentIntent(android.app.PendingIntent.getActivity(this, 0, open, Util.pendingFlags()))
                .setOngoing(true)
                .build();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroy() {
        running = false;
        // 悬浮球消失时确保自动同步一并停止
        stopAutoSync();
        try {
            if (bubble != null && windowManager != null) {
                windowManager.removeView(bubble);
            }
        } catch (Throwable ignored) {
        }
        bubble = null;
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}