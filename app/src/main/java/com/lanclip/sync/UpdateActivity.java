package com.lanclip.sync;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 盒子管理（手机端）：扫描局域网 → 点选连接盒子 → 检测被控端版本 → 下载 APK 推送安装。
 */
public class UpdateActivity extends Activity {

    private static final String SELF_REPO = "Piannow/LanClipboard";
    private static final String SELF_PREFIX = "LanClipboard";
    private static final String SELF_PACKAGE = "com.lanclip.sync";

    private TextView tvDevices;
    private ListView list;
    private EditText etPort;
    private TextView tvConnected;
    private TextView tvRemoteVer;
    private Button btnCheckRemote;
    private Button btnDownload;
    private Button btnInstall;
    private TextView tvLog;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final StringBuilder logBuffer = new StringBuilder();

    private final List<String> endpoints = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private volatile String connected = null;      // 当前连接的 ip:port
    private volatile String connectedIp = null;
    private volatile int connectedPort = 0;
    private volatile String remoteVersion = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_update);

        tvDevices = findViewById(R.id.tv_devices);
        list = findViewById(R.id.list_devices);
        etPort = findViewById(R.id.et_port);
        tvConnected = findViewById(R.id.tv_connected);
        tvRemoteVer = findViewById(R.id.tv_remote_ver);
        btnCheckRemote = findViewById(R.id.btn_check_remote);
        btnDownload = findViewById(R.id.btn_download);
        btnInstall = findViewById(R.id.btn_install);
        tvLog = findViewById(R.id.tv_log);

        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, endpoints) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                TextView tv = (TextView) v;
                String ep = endpoints.get(position);
                if (ep.equals(connected)) {
                    tv.setText(ep + "  [已连接]");
                } else {
                    tv.setText(ep);
                }
                return v;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> connectDevice(endpoints.get(position)));

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_scan).setOnClickListener(v -> scanLan());
        btnCheckRemote.setOnClickListener(v -> checkRemoteVersion());
        btnDownload.setOnClickListener(v -> downloadApk());
        btnInstall.setOnClickListener(v -> pushInstall());
        findViewById(R.id.btn_copy_log).setOnClickListener(v -> copyLog());
        findViewById(R.id.btn_scan).requestFocus();
    }

    /** 一键复制运行日志（含 adb 原始输出）到剪贴板。 */
    private void copyLog() {
        String text = logBuffer.toString().trim();
        if (text.isEmpty()) {
            Toast.makeText(this, "日志为空", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("LanClipboardLog", text));
                Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            Toast.makeText(this, "复制失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- 扫描并列出 ----------

    private void scanLan() {
        tvDevices.setText("扫描中…");
        endpoints.clear();
        adapter.notifyDataSetChanged();
        List<String> ips = Util.localIpv4();
        if (ips.isEmpty()) {
            tvDevices.setText("未连接局域网");
            return;
        }
        final String myIp = ips.get(0);
        String manual = etPort.getText() == null ? "" : etPort.getText().toString().trim();
        appendLog("扫描 " + myIp + " 所在网段…");
        AdbScan.Listener listener = new AdbScan.Listener() {
            @Override
            public void onFound(final String endpoint) {
                ui.post(() -> {
                    if (!endpoints.contains(endpoint)) {
                        endpoints.add(endpoint);
                        adapter.notifyDataSetChanged();
                    }
                });
            }

            @Override
            public void onDone(final int total) {
                ui.post(() -> tvDevices.setText("找到 " + total + " 个盒子，点选连接"));
            }
        };
        if (manual.isEmpty()) {
            AdbScan.scan(myIp, listener);
        } else {
            try {
                int port = Integer.parseInt(manual);
                AdbScan.scanPort(myIp, port, listener);
            } catch (Throwable t) {
                tvDevices.setText("端口格式错误");
            }
        }
    }

    // ---------- 点选连接 ----------

    private void connectDevice(final String endpoint) {
        tvDevices.setText("连接 " + endpoint + "…");
        worker.execute(() -> {
            int ci = endpoint.lastIndexOf(':');
            final String ip = endpoint.substring(0, ci);
            final int port = Integer.parseInt(endpoint.substring(ci + 1));
            try {
                AdbRunner runner = new AdbRunner(UpdateActivity.this, ip, port);
                boolean ok = runner.connect();
                String adbOut = runner.lastOutput();
                // 保持连接，供后续检测版本/安装使用（不要 disconnect）
                if (ok) {
                    connected = endpoint;
                    connectedIp = ip;
                    connectedPort = port;
                    remoteVersion = null;
                    ui.post(() -> {
                        tvConnected.setText("已连接：" + endpoint);
                        tvRemoteVer.setText("被控端版本：-（点「检测被控端版本」）");
                        appendLog("已连接 " + endpoint);
                    });
                } else {
                    final String out = adbOut == null ? "" : adbOut.trim();
                    ui.post(() -> {
                        tvConnected.setText("连接失败：" + endpoint);
                        appendLog("连接 " + endpoint + " 失败：adb connect 未成功");
                        if (!out.isEmpty()) {
                            appendLog("【adb 输出】" + out);
                        }
                    });
                }
            } catch (Throwable t) {
                ui.post(() -> {
                    tvConnected.setText("连接失败：" + endpoint);
                    appendLog("连接 " + endpoint + " 失败：" + t.getMessage());
                });
            }
        });
    }

    // ---------- 检测被控端版本（ADB 读 versionName） ----------

    private void checkRemoteVersion() {
        if (connected == null) {
            Toast.makeText(this, "先连接盒子", Toast.LENGTH_SHORT).show();
            return;
        }
        tvRemoteVer.setText("读取中…");
        worker.execute(() -> {
            AdbRunner runner = null;
            try {
                runner = new AdbRunner(UpdateActivity.this, connectedIp, connectedPort);
                String ver = runner.remoteVersion(SELF_PACKAGE);
                remoteVersion = ver;
                ui.post(() -> {
                    tvRemoteVer.setText("被控端版本：" + (ver == null ? "未安装" : ver));
                    appendLog("被控端版本：" + (ver == null ? "未安装" : ver));
                });
            } catch (Throwable t) {
                ui.post(() -> {
                    tvRemoteVer.setText("读取失败：" + t.getMessage());
                    appendLog("读被控端版本失败：" + t.getMessage());
                });
            } finally {
                if (runner != null) {
                    try {
                        runner.disconnect();
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    // ---------- 下载 APK 到本机 ----------

    private void downloadApk() {
        btnDownload.setEnabled(false);
        tvConnected.setText("下载剪切板 APK…");
        worker.execute(() -> {
            try {
                UpdateClient.ReleaseInfo rel = new UpdateClient.Checker(SELF_REPO, SELF_PREFIX).latest();
                if (!rel.hasApk()) {
                    ui.post(() -> tvConnected.setText("远程无 APK"));
                    btnDownload.setEnabled(true);
                    return;
                }
                File apk = UpdateClient.download(UpdateActivity.this, rel.apkUrl, "LanClipboard.apk",
                        (done, total) -> ui.post(() -> tvConnected.setText("下载 " + pct(done, total))));
                ui.post(() -> {
                    tvConnected.setText("已下载：" + apk.getName() + "（可推送安装）");
                    appendLog("已下载 " + rel.version + " 到本机");
                });
            } catch (Throwable t) {
                ui.post(() -> {
                    tvConnected.setText("下载失败：" + t.getMessage());
                    appendLog("下载失败：" + t.getMessage());
                });
            } finally {
                btnDownload.setEnabled(true);
            }
        });
    }

    // ---------- 推送安装到已连接盒子 ----------

    private void pushInstall() {
        if (connected == null) {
            Toast.makeText(this, "先连接盒子", Toast.LENGTH_SHORT).show();
            return;
        }
        btnInstall.setEnabled(false);
        worker.execute(() -> {
            AdbRunner runner = null;
            try {
                File local = pickLocalApk();
                if (local == null) {
                    ui.post(() -> appendLog("本地无 APK，请先下载一次"));
                    return;
                }
                runner = new AdbRunner(UpdateActivity.this, connectedIp, connectedPort);
                boolean installed = runner.isPackageInstalled(SELF_PACKAGE);
                if (installed && remoteVersion == null) {
                    remoteVersion = runner.remoteVersion(SELF_PACKAGE);
                }
                appendLog("推送 " + local.getName() + " 到 " + connected + "…");
                String result = runner.install(local.getAbsolutePath());
                appendLog("安装结果：" + result.trim());
            } catch (Throwable t) {
                ui.post(() -> appendLog("推送失败：" + t.getMessage()));
            } finally {
                if (runner != null) {
                    try {
                        runner.disconnect();
                    } catch (Throwable ignored) {
                    }
                }
                ui.post(() -> btnInstall.setEnabled(true));
            }
        });
    }

    private File pickLocalApk() {
        File dir = getExternalFilesDir("downloads");
        if (dir == null) {
            return null;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return null;
        }
        File best = null;
        long bestMtime = -1;
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(".apk") && f.lastModified() > bestMtime) {
                best = f;
                bestMtime = f.lastModified();
            }
        }
        return best;
    }

    private static String pct(long done, long total) {
        if (total <= 0) {
            return done + "B";
        }
        return (done * 100 / total) + "%";
    }

    private void appendLog(String line) {
        logBuffer.append(line).append('\n');
        String[] lines = logBuffer.toString().split("\n");
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, lines.length - 12);
        for (int i = start; i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
        }
        tvLog.setText("运行日志：\n" + sb.toString().trim());
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}