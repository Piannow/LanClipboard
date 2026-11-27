package com.lanclip.sync;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 手机端：扫描局域网开放端口设备，把手机剪切板推送到机顶盒。
 */
public class SenderActivity extends Activity {

    private static final String TAG = "LanClipSender";

    static class Device {
        final String ip;
        final int port;
        String name;
        boolean known;

        Device(String ip, int port, String name) {
            this.ip = ip;
            this.port = port;
            this.name = name;
            this.known = name != null && !name.isEmpty();
        }

        String title() {
            if (known) {
                return name == null || name.isEmpty() ? "局域网剪切板接收端" : name;
            }
            return "开放端口设备";
        }

        String subtitle() {
            return ip + ":" + port + (known ? " · 本软件接收端" : " · 非本软件");
        }
    }

    private TextView tvTarget;
    private TextView tvScan;
    private TextView tvClip;
    private TextView tvLog;
    private ListView list;
    private EditText etIp;
    private EditText etText;
    private Button btnScan;
    private Switch swAuto;

    private final List<Device> devices = new ArrayList<>();
    private ArrayAdapter<Device> adapter;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder logBuffer = new StringBuilder();
    private final AtomicInteger scannedCount = new AtomicInteger();

    private volatile boolean scanning;
    private ExecutorService scanPool;
    private boolean bindingSwitch;
    private boolean registered;

    private final Runnable clipPreview = new Runnable() {
        @Override
        public void run() {
            String text = Util.getClipText(SenderActivity.this);
            tvClip.setText(text.isEmpty() ? "空（请先在手机上复制文字）"
                    : text.length() > 400 ? text.substring(0, 400) + "…" : text);
            ui.postDelayed(this, 1000);
        }
    };

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra("status");
            if (status != null) {
                appendLog(status);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sender);

        tvTarget = findViewById(R.id.tv_target);
        tvScan = findViewById(R.id.tv_scan);
        tvClip = findViewById(R.id.tv_clip);
        tvLog = findViewById(R.id.tv_log);
        list = findViewById(R.id.list_devices);
        etIp = findViewById(R.id.et_ip);
        etText = findViewById(R.id.et_text);
        btnScan = findViewById(R.id.btn_scan);
        swAuto = findViewById(R.id.sw_auto);

        adapter = new ArrayAdapter<Device>(this, android.R.layout.simple_list_item_2,
                android.R.id.text1, devices) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                Device device = getItem(position);
                TextView first = view.findViewById(android.R.id.text1);
                TextView second = view.findViewById(android.R.id.text2);
                first.setText(device.title());
                first.setTextSize(16);
                second.setText(device.subtitle());
                second.setTextSize(12);
                second.setTextColor(getResources().getColor(R.color.dim));
                return view;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) ->
                selectDevice(devices.get(position)));

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        btnScan.setOnClickListener(v -> scan());
        findViewById(R.id.btn_send).setOnClickListener(v -> sendNow());
        findViewById(R.id.btn_connect).setOnClickListener(v -> connectManual());

        bindingSwitch = true;
        swAuto.setChecked(SenderService.running);
        bindingSwitch = false;
        swAuto.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (bindingSwitch) {
                return;
            }
            onAutoSwitch(isChecked);
        });

        updateTargetUi();
        btnScan.requestFocus();
        appendLog("提示：机顶盒端启动接收服务后，在手机上点扫描。");
    }

    /** 开启/关闭悬浮球（需「在其他应用上层显示」权限）。 */
    private void toggleFloatingLegacy() {
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!registered) {
            registerReceiver(stateReceiver, new IntentFilter(Util.ACTION_SENDER_STATE));
            registered = true;
        }
        updateTargetUi();
        bindingSwitch = true;
        swAuto.setChecked(SenderService.running);
        bindingSwitch = false;
        ui.post(clipPreview);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(clipPreview);
        if (registered) {
            unregisterReceiver(stateReceiver);
            registered = false;
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(Util.PREFS, MODE_PRIVATE);
    }

    private void updateTargetUi() {
        String ip = prefs().getString("target_ip", "");
        if (ip.isEmpty()) {
            tvTarget.setText("未选择设备");
            tvTarget.setTextColor(getResources().getColor(R.color.warn));
        } else {
            String name = prefs().getString("target_name", "");
            int port = prefs().getInt("target_port", Util.HTTP_PORT);
            tvTarget.setText((name.isEmpty() ? "接收端" : name) + "\n" + ip + ":" + port);
            tvTarget.setTextColor(getResources().getColor(R.color.accent));
        }
    }

    private void onAutoSwitch(boolean enabled) {
        prefs().edit().putBoolean("autosync", enabled).apply();
        if (enabled) {
            if (prefs().getString("target_ip", "").isEmpty()) {
                toast("请先扫描并选择接收端设备");
                bindingSwitch = true;
                swAuto.setChecked(false);
                bindingSwitch = false;
                prefs().edit().putBoolean("autosync", false).apply();
                return;
            }
            Intent intent = new Intent(this, SenderService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            appendLog("自动同步已开启：手机复制后会自动发送");
            ensureFloating(true);
        } else {
            startService(new Intent(this, SenderService.class).setAction("STOP"));
            appendLog("自动同步已关闭");
            ensureFloating(false);
        }
    }

    /** 自动同步开/关时同步显示或收起悬浮球。 */
    private void ensureFloating(boolean show) {
        if (show) {
            if (FloatingButtonService.running) {
                return;
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
                    && !android.provider.Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请允许「在其他应用上层显示」以显示悬浮球", Toast.LENGTH_LONG).show();
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:" + getPackageName())));
                } catch (Throwable ignored) {
                }
                return;
            }
            Intent svc = new Intent(this, FloatingButtonService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } else {
            stopService(new Intent(this, FloatingButtonService.class));
        }
    }

    private void selectDevice(Device device) {
        prefs().edit()
                .putString("target_ip", device.ip)
                .putInt("target_port", device.port)
                .putString("target_name", device.title())
                .apply();
        updateTargetUi();
        appendLog("已选择 " + device.ip + ":" + device.port
                + (device.known ? "" : "（该设备未识别为本软件，发送可能失败）"));
        toast("已连接 " + device.ip);
    }

    private void sendNow() {
        String ip = prefs().getString("target_ip", "");
        if (ip.isEmpty()) {
            toast("请先扫描并选择接收端设备");
            return;
        }
        final int port = prefs().getInt("target_port", Util.HTTP_PORT);
        String manual = etText.getText().toString().trim();
        final String text = manual.isEmpty() ? Util.getClipText(this) : manual;
        if (text.isEmpty()) {
            toast("手机剪切板为空");
            return;
        }
        appendLog("发送中 · " + text.length() + " 字 → " + ip + ":" + port);
        new Thread(() -> {
            final boolean ok = Util.postClip(ip, port, text);
            ui.post(() -> {
                appendLog(ok ? "发送成功 ✓" : "发送失败，请检查机顶盒端接收服务与网络");
                toast(ok ? "已发送到机顶盒" : "发送失败");
            });
        }, "manual-send").start();
    }

    private void connectManual() {
        final String ip = etIp.getText().toString().trim();
        if (!ip.matches("^(\\d{1,3}\\.){3}\\d{1,3}$")) {
            toast("请输入正确的 IPv4 地址");
            return;
        }
        appendLog("正在连接 " + ip + " …");
        new Thread(() -> {
            final String name = Util.probeName(ip, Util.HTTP_PORT);
            ui.post(() -> {
                if (name == null) {
                    toast("无法连接 " + ip + ":" + Util.HTTP_PORT);
                    appendLog("连接失败：" + ip + " 端口未开放");
                    return;
                }
                selectDevice(new Device(ip, Util.HTTP_PORT, name));
                if (name.isEmpty()) {
                    appendLog("端口开放，但未识别为本软件接收端");
                }
            });
        }, "manual-probe").start();
    }

    private void scan() {
        if (scanning) {
            return;
        }
        List<String> ips = Util.localIpv4();
        if (ips.isEmpty()) {
            tvScan.setText("未连接局域网，请先连接 WiFi");
            return;
        }
        scanning = true;
        scannedCount.set(0);
        btnScan.setEnabled(false);
        tvScan.setText("扫描中…");
        synchronized (devices) {
            devices.clear();
        }
        adapter.notifyDataSetChanged();
        appendLog("开始扫描 " + ips.get(0) + " 所在网段（端口 " + Util.HTTP_PORT + "）");

        String base = ips.get(0).substring(0, ips.get(0).lastIndexOf('.') + 1);
        scanPool = Executors.newCachedThreadPool();
        scanPool.execute(this::udpDiscover);

        final ExecutorService tcpPool = Executors.newFixedThreadPool(48);
        for (int i = 1; i <= 254; i++) {
            final String ip = base + i;
            tcpPool.execute(() -> {
                if (!scanning) {
                    return;
                }
                try {
                    if (Util.isPortOpen(ip, Util.HTTP_PORT, 220)) {
                        String name = Util.probeName(ip, Util.HTTP_PORT);
                        if (name != null) {
                            addDevice(ip, name);
                        }
                    }
                } catch (Throwable ignored) {
                }
                int done = scannedCount.incrementAndGet();
                if (done % 25 == 0 || done == 254) {
                    ui.post(() -> tvScan.setText("扫描中 " + done + "/254，已发现 " + devices.size() + " 台"));
                }
            });
        }
        tcpPool.shutdown();
        scanPool.execute(() -> {
            try {
                tcpPool.awaitTermination(25, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            ui.post(this::finishScan);
        });
    }

    private void udpDiscover() {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setBroadcast(true);
            socket.setSoTimeout(250);
            byte[] probe = "LANCLIP_DISCOVER".getBytes("UTF-8");
            List<InetAddress> targets = new ArrayList<>();
            targets.add(InetAddress.getByName("255.255.255.255"));
            for (String ip : Util.localIpv4()) {
                try {
                    InetAddress broadcast = Util.broadcastFor(ip);
                    if (!targets.contains(broadcast)) {
                        targets.add(broadcast);
                    }
                } catch (Throwable ignored) {
                }
            }
            long deadline = System.currentTimeMillis() + 1500;
            byte[] buffer = new byte[1024];
            while (scanning && System.currentTimeMillis() < deadline) {
                for (InetAddress target : targets) {
                    try {
                        socket.send(new DatagramPacket(probe, probe.length, target, Util.UDP_PORT));
                    } catch (Throwable ignored) {
                    }
                }
                long until = Math.min(deadline, System.currentTimeMillis() + 320);
                while (System.currentTimeMillis() < until) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    try {
                        socket.receive(packet);
                    } catch (SocketTimeoutException e) {
                        break;
                    }
                    try {
                        String jsonText = new String(packet.getData(), 0, packet.getLength(), "UTF-8");
                        JSONObject json = new JSONObject(jsonText);
                        if (Util.APP_ID.equals(json.optString("app"))) {
                            addDevice(packet.getAddress().getHostAddress(), json.optString("name"));
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    private void addDevice(String ip, String name) {
        boolean known = name != null && !name.isEmpty();
        boolean added = false;
        synchronized (devices) {
            for (Device device : devices) {
                if (device.ip.equals(ip)) {
                    if (known && !device.known) {
                        device.known = true;
                        device.name = name;
                    }
                    ui.post(() -> adapter.notifyDataSetChanged());
                    return;
                }
            }
            devices.add(new Device(ip, Util.HTTP_PORT, name));
            added = true;
        }
        if (added) {
            ui.post(() -> {
                adapter.notifyDataSetChanged();
                tvScan.setText("扫描中，已发现 " + devices.size() + " 台设备");
            });
        }
    }

    private void finishScan() {
        scanning = false;
        btnScan.setEnabled(true);
        if (scanPool != null) {
            scanPool.shutdown();
        }
        synchronized (devices) {
            Collections.sort(devices, new Comparator<Device>() {
                @Override
                public int compare(Device a, Device b) {
                    if (a.known != b.known) {
                        return a.known ? -1 : 1;
                    }
                    return lastOctet(a.ip) - lastOctet(b.ip);
                }
            });
        }
        adapter.notifyDataSetChanged();
        if (devices.isEmpty()) {
            tvScan.setText("未发现设备：请确认机顶盒已启动接收服务，且与手机在同一 WiFi");
        } else {
            int known = 0;
            synchronized (devices) {
                for (Device device : devices) {
                    if (device.known) {
                        known++;
                    }
                }
            }
            tvScan.setText("扫描完成，发现 " + devices.size() + " 台，其中本软件接收端 " + known + " 台");
            appendLog("扫描完成：共 " + devices.size() + " 台设备");
        }
    }

    private static int lastOctet(String ip) {
        try {
            return Integer.parseInt(ip.substring(ip.lastIndexOf('.') + 1));
        } catch (Throwable t) {
            return 0;
        }
    }

    private void appendLog(String line) {
        logBuffer.append(line).append('\n');
        String[] lines = logBuffer.toString().split("\n");
        if (lines.length > 10) {
            StringBuilder trimmed = new StringBuilder();
            for (int i = lines.length - 10; i < lines.length; i++) {
                trimmed.append(lines[i]).append('\n');
            }
            logBuffer.setLength(0);
            logBuffer.append(trimmed);
        }
        if (tvLog != null) {
            tvLog.setText(logBuffer.toString().trim());
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
