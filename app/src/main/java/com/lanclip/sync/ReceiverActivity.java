package com.lanclip.sync;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;

public class ReceiverActivity extends Activity {

    private TextView tvState;
    private TextView tvAddr;
    private TextView tvFile;
    private TextView tvDevice;
    private TextView tvCount;
    private TextView tvLast;
    private TextView tvLog;
    private TextView tvWeb;
    private TextView tvA11y;
    private Button btnToggle;
    private Switch swCompat;

    private final StringBuilder logBuffer = new StringBuilder();
    private boolean registered;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Util.ACTION_CLIP_RECEIVED.equals(intent.getAction())) {
                String from = intent.getStringExtra("from");
                appendLog("收到 " + from + " 发来的 " + intent.getIntExtra("len", 0) + " 字，已写入 剪切板.txt 并复制");
            }
            refresh();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_receiver);

        tvState = findViewById(R.id.tv_state);
        tvAddr = findViewById(R.id.tv_addr);
        tvFile = findViewById(R.id.tv_file);
        tvDevice = findViewById(R.id.tv_device);
        tvCount = findViewById(R.id.tv_count);
        tvLast = findViewById(R.id.tv_last);
        tvLog = findViewById(R.id.tv_log);
        tvWeb = findViewById(R.id.tv_web);
        tvA11y = findViewById(R.id.tv_a11y_state);
        btnToggle = findViewById(R.id.btn_toggle);
        swCompat = findViewById(R.id.sw_compat);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        btnToggle.setOnClickListener(v -> toggleService());
        findViewById(R.id.btn_access).setOnClickListener(v -> openAccessibilitySettings());
        findViewById(R.id.btn_update).setOnClickListener(v ->
                startActivity(new Intent(this, UpdateActivity.class)));
        // 遥控器：进入页面即聚焦主按钮，避免某些机顶盒没有默认焦点
        btnToggle.requestFocus();

        swCompat.setChecked(prefs().getBoolean("accessibility_copy", true));
        swCompat.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs().edit().putBoolean("accessibility_copy", isChecked).apply());

        tvFile.setText(Util.clipFile(this).getAbsolutePath());
        tvDevice.setText("设备：" + Build.MANUFACTURER + " " + Build.MODEL
                + " · Android " + Build.VERSION.RELEASE + "（API " + Build.VERSION.SDK_INT + "）");
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!registered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Util.ACTION_CLIP_RECEIVED);
            filter.addAction(Util.ACTION_RECEIVER_STATE);
            registerReceiver(receiver, filter);
            registered = true;
        }
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (registered) {
            unregisterReceiver(receiver);
            registered = false;
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(Util.PREFS, MODE_PRIVATE);
    }

    private void toggleService() {
        if (ReceiverService.running) {
            prefs().edit().putBoolean("receiver_running", false).apply();
            Intent stop = new Intent(this, ReceiverService.class).setAction("STOP");
            startService(stop);
            appendLog("正在停止接收服务…");
        } else {
            Intent start = new Intent(this, ReceiverService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(start);
            } else {
                startService(start);
            }
            appendLog("正在启动接收服务…");
        }
        tvState.postDelayed(this::refresh, 600);
    }

    private void refresh() {
        boolean running = ReceiverService.running;
        if (running) {
            tvState.setText("运行中 · 等待手机发送");
            tvState.setTextColor(getResources().getColor(R.color.accent));
            btnToggle.setText("停止接收服务");
            btnToggle.setBackgroundResource(R.drawable.bg_btn_secondary);
            btnToggle.setTextColor(getResources().getColor(R.color.text));
        } else {
            tvState.setText("未运行");
            tvState.setTextColor(getResources().getColor(R.color.warn));
            btnToggle.setText("启动接收服务");
            btnToggle.setBackgroundResource(R.drawable.bg_btn_primary);
            btnToggle.setTextColor(getResources().getColor(R.color.accent_dark));
        }

        List<String> ips = Util.localIpv4();
        String ip = ips.isEmpty() ? "-" : ips.get(0);
        StringBuilder addr = new StringBuilder();
        for (String item : ips) {
            if (addr.length() > 0) {
                addr.append("    ");
            }
            addr.append(item).append(':').append(Util.HTTP_PORT);
        }
        tvAddr.setText("本机地址：" + (addr.length() == 0 ? "未连接局域网" : addr.toString()));
        tvWeb.setText("提示：手机浏览器打开 http://" + ip + ":" + Util.HTTP_PORT + " 也能直接发送文字。");

        int count = prefs().getInt("count", 0);
        tvCount.setText("累计接收 " + count + " 条");
        String last = prefs().getString("last_text", "");
        tvLast.setText(last.isEmpty() ? "最近内容：-" : "最近内容：" + shorten(last, 200));

        boolean a11y = isAccessibilityEnabled();
        tvA11y.setText(a11y ? "无障碍服务已开启" : "未开启无障碍服务（点下方按钮开启）");
        tvA11y.setTextColor(getResources().getColor(a11y ? R.color.accent : R.color.dim));
        swCompat.setChecked(prefs().getBoolean("accessibility_copy", true));
    }

    /** 部分机顶盒精简了无障碍设置页，ACTION_ACCESSIBILITY_SETTINGS 可能不存在，逐级回退。 */
    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        } catch (Throwable ignored) {
        }
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        } catch (Throwable ignored) {
        }
    }

    private boolean isAccessibilityEnabled() {
        try {
            if (ClipAccessibilityService.isConnected()) {
                return true;
            }
            String enabled = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) {
                return false;
            }
            return enabled.contains(ClipAccessibilityService.class.getName())
                    || enabled.contains("com.lanclip.sync/.ClipAccessibilityService");
        } catch (Throwable t) {
            return false;
        }
    }

    private static String shorten(String text, int max) {
        String oneLine = text.replace("\n", " ");
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }

    private void appendLog(String line) {
        logBuffer.append(line).append('\n');
        String[] lines = logBuffer.toString().split("\n");
        if (lines.length > 8) {
            StringBuilder trimmed = new StringBuilder();
            for (int i = lines.length - 8; i < lines.length; i++) {
                trimmed.append(lines[i]).append('\n');
            }
            logBuffer.setLength(0);
            logBuffer.append(trimmed);
        }
        if (tvLog != null) {
            tvLog.setText(logBuffer.toString().trim());
        }
    }
}
