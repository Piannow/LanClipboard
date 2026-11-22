package com.lanclip.sync;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 更新中心：本机自更新 + AstrBotLite 推送 + 局域网远程安装（ADB）。
 * 远程安装只推送本机已下载的 APK，不自动下载最新版。
 */
public class UpdateActivity extends Activity {

    private static final String SELF_REPO = "Piannow/LanClipboard";
    private static final String SELF_PREFIX = "LanClipboard";
    private static final String ASTR_REPO = "Piannow/AstrBotLite";
    private static final String ASTR_PREFIX = "AstrBotLite";

    private TextView tvCur;
    private TextView tvSelfStatus;
    private Button btnCheckSelf;
    private Button btnDownloadSelf;
    private TextView tvAstrStatus;
    private Button btnCheckAstr;
    private Button btnDownloadAstr;
    private TextView tvDevices;
    private TextView tvLog;
    private EditText etPort;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final StringBuilder logBuffer = new StringBuilder();

    private UpdateClient.ReleaseInfo selfRelease;
    private UpdateClient.ReleaseInfo astrRelease;
    private final List<String> foundEndpoints = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_update);

        tvCur = findViewById(R.id.tv_cur);
        tvSelfStatus = findViewById(R.id.tv_self_status);
        btnCheckSelf = findViewById(R.id.btn_check_self);
        btnDownloadSelf = findViewById(R.id.btn_download_self);
        tvAstrStatus = findViewById(R.id.tv_astr_status);
        btnCheckAstr = findViewById(R.id.btn_check_astr);
        btnDownloadAstr = findViewById(R.id.btn_download_astr);
        tvDevices = findViewById(R.id.tv_devices);
        tvLog = findViewById(R.id.tv_log);
        etPort = findViewById(R.id.et_port);

        tvCur.setText("当前版本：v" + versionName());

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        btnCheckSelf.setOnClickListener(v -> checkSelfUpdate());
        btnDownloadSelf.setOnClickListener(v -> downloadAndInstallSelf());
        btnCheckAstr.setOnClickListener(v -> checkAstrUpdate());
        btnDownloadAstr.setOnClickListener(v -> downloadAndInstallAstr());
        findViewById(R.id.btn_scan).setOnClickListener(v -> scanLan());
        btnCheckSelf.requestFocus();

        maybeRequestStorage();
    }

    private void maybeRequestStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 101);
            }
        }
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    // ---------- 本机自更新 ----------

    private void checkSelfUpdate() {
        tvSelfStatus.setText("检测中…");
        worker.execute(() -> {
            try {
                UpdateClient.ReleaseInfo rel = new UpdateClient.Checker(SELF_REPO, SELF_PREFIX).latest();
                int[] cur = UpdateClient.parseVersion(versionName());
                int[] latest = UpdateClient.parseVersion(rel.version);
                boolean hasNew = UpdateClient.greater(latest, cur);
                ui.post(() -> {
                    selfRelease = rel;
                    if (hasNew) {
                        tvSelfStatus.setText("发现新版本 " + rel.version + (rel.hasApk() ? "" : "（无 APK）"));
                        btnDownloadSelf.setVisibility(rel.hasApk() ? Button.VISIBLE : Button.GONE);
                    } else {
                        tvSelfStatus.setText("已是最新（" + rel.version + "）");
                        btnDownloadSelf.setVisibility(Button.GONE);
                    }
                });
            } catch (Throwable t) {
                ui.post(() -> {
                    tvSelfStatus.setText("检测失败：" + t.getMessage());
                    appendLog("自更新检测失败：" + t.getMessage());
                });
            }
        });
    }

    private void downloadAndInstallSelf() {
        if (selfRelease == null || !selfRelease.hasApk()) {
            Toast.makeText(this, "先检测更新", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!InstallHelper.canInstall(this)) {
            InstallHelper.openInstallSettings(this);
            Toast.makeText(this, "请允许「安装未知应用」后重试", Toast.LENGTH_SHORT).show();
            return;
        }
        btnDownloadSelf.setEnabled(false);
        tvSelfStatus.setText("下载中…");
        worker.execute(() -> {
            try {
                File apk = UpdateClient.download(this, selfRelease.apkUrl, "LanClipboard-new.apk",
                        (done, total) -> ui.post(() -> tvSelfStatus.setText("下载中 " + pct(done, total))));
                ui.post(() -> {
                    tvSelfStatus.setText("已下载 " + selfRelease.version + "，请点系统安装");
                    InstallHelper.promptInstall(this, apk);
                    btnDownloadSelf.setEnabled(true);
                });
            } catch (Throwable t) {
                ui.post(() -> {
                    tvSelfStatus.setText("下载失败：" + t.getMessage());
                    btnDownloadSelf.setEnabled(true);
                });
            }
        });
    }

    // ---------- AstrBotLite 推送 ----------

    private void checkAstrUpdate() {
        tvAstrStatus.setText("检测中…");
        worker.execute(() -> {
            try {
                UpdateClient.ReleaseInfo rel = new UpdateClient.Checker(ASTR_REPO, ASTR_PREFIX).latest();
                ui.post(() -> {
                    astrRelease = rel;
                    if (rel.hasApk()) {
                        tvAstrStatus.setText("AstrBotLite 最新 " + rel.version + "（" + rel.apkName + "）");
                        btnDownloadAstr.setVisibility(Button.VISIBLE);
                    } else {
                        tvAstrStatus.setText("AstrBotLite 未发布 APK");
                        btnDownloadAstr.setVisibility(Button.GONE);
                    }
                });
            } catch (Throwable t) {
                ui.post(() -> {
                    tvAstrStatus.setText("检测失败：" + t.getMessage());
                    appendLog("AstrBotLite 检测失败：" + t.getMessage());
                });
            }
        });
    }

    private void downloadAndInstallAstr() {
        if (astrRelease == null || !astrRelease.hasApk()) {
            Toast.makeText(this, "先检测更新", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!InstallHelper.canInstall(this)) {
            InstallHelper.openInstallSettings(this);
            Toast.makeText(this, "请允许「安装未知应用」后重试", Toast.LENGTH_SHORT).show();
            return;
        }
        btnDownloadAstr.setEnabled(false);
        tvAstrStatus.setText("下载中…");
        worker.execute(() -> {
            try {
                File apk = UpdateClient.download(this, astrRelease.apkUrl, "AstrBotLite.apk",
                        (done, total) -> ui.post(() -> tvAstrStatus.setText("下载中 " + pct(done, total))));
                ui.post(() -> {
                    tvAstrStatus.setText("已下载 AstrBotLite " + astrRelease.version + "，请点系统安装");
                    InstallHelper.promptInstall(this, apk);
                    btnDownloadAstr.setEnabled(true);
                });
            } catch (Throwable t) {
                ui.post(() -> {
                    tvAstrStatus.setText("下载失败：" + t.getMessage());
                    btnDownloadAstr.setEnabled(true);
                });
            }
        });
    }

    // ---------- 局域网远程安装（只推送本机 APK） ----------

    private void scanLan() {
        tvDevices.setText("扫描中…");
        foundEndpoints.clear();
        List<String> ips = Util.localIpv4();
        if (ips.isEmpty()) {
            tvDevices.setText("未连接局域网");
            return;
        }
        final String myIp = ips.get(0);
        String manual = etPort.getText() == null ? "" : etPort.getText().toString().trim();
        appendLog("开始扫描 " + myIp + " 所在网段…");
        AdbScan.Listener listener = new AdbScan.Listener() {
            @Override
            public void onFound(final String endpoint) {
                ui.post(() -> {
                    appendLog("发现 adb 设备：" + endpoint);
                    pushToBox(endpoint);
                });
            }

            @Override
            public void onDone(final int total) {
                ui.post(() -> tvDevices.setText("扫描完成，共 " + total + " 个 adb 端点"));
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

    /** 把本机已下载的剪切板 APK 推送到盒子（已装则跳过）。不自动下载最新版。 */
    private void pushToBox(final String endpoint) {
        worker.execute(() -> {
            int ci = endpoint.lastIndexOf(':');
            String ip = endpoint.substring(0, ci);
            int port = Integer.parseInt(endpoint.substring(ci + 1));
            try {
                File local = pickLocalApk();
                if (local == null) {
                    appendLog(ip + "：本地无 APK，请先在本机下载一次");
                    return;
                }
                AdbClient adb = new AdbClient(UpdateActivity.this, ip, port);
                appendLog("连接 " + ip + ":" + port + "…");
                adb.connect();
                boolean installed = adb.isPackageInstalled("com.lanclip.sync");
                if (installed) {
                    appendLog(ip + " 已安装剪切板，跳过");
                    adb.close();
                    return;
                }
                byte[] bytes = readAllBytes(local);
                appendLog("推送 " + local.getName() + " 到 " + ip + "…");
                String result = adb.install(bytes, "com.lanclip.sync");
                appendLog(ip + " 安装结果：" + result.trim());
                adb.close();
            } catch (Throwable t) {
                appendLog("远程安装 " + ip + " 失败：" + t.getMessage());
            }
        });
    }

    /** 从下载目录挑一个剪切板 APK（优先最新）。 */
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

    private static byte[] readAllBytes(File file) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        java.io.FileInputStream fis = new java.io.FileInputStream(file);
        byte[] buf = new byte[8192];
        int n;
        while ((n = fis.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        fis.close();
        return bos.toByteArray();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}