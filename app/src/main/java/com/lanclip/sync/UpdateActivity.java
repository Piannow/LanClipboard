package com.lanclip.sync;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 更新中心：本机自更新 + 推送 AstrBotLite 更新 + 局域网远程安装（ADB）。
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

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final StringBuilder logBuffer = new StringBuilder();

    private UpdateClient.ReleaseInfo selfRelease;
    private UpdateClient.ReleaseInfo astrRelease;

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

        tvCur.setText("当前版本：v" + versionName());

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        btnCheckSelf.setOnClickListener(v -> checkSelfUpdate());
        btnDownloadSelf.setOnClickListener(v -> downloadAndInstallSelf());
        btnCheckAstr.setOnClickListener(v -> checkAstrUpdate());
        btnDownloadAstr.setOnClickListener(v -> downloadAndInstallAstr());
        findViewById(R.id.btn_scan).setOnClickListener(v -> scanLan());
        btnCheckSelf.requestFocus();
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
                File apk = UpdateClient.downloadTo(this, selfRelease.apkUrl, "LanClipboard-new.apk", null);
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
                File apk = UpdateClient.downloadTo(this, astrRelease.apkUrl, "AstrBotLite.apk", null);
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

    // ---------- 局域网远程安装 ----------

    private void scanLan() {
        tvDevices.setText("扫描中…");
        List<String> ips = Util.localIpv4();
        if (ips.isEmpty()) {
            tvDevices.setText("未连接局域网");
            return;
        }
        final String myIp = ips.get(0);
        appendLog("开始扫描 " + myIp + " 所在网段…");
        AdbScan.scan(myIp, 5555, new AdbScan.Listener() {
            @Override
            public void onFound(final String ip) {
                ui.post(() -> {
                    appendLog("发现 adb 设备：" + ip + ":5555");
                    remoteInstall(ip);
                });
            }

            @Override
            public void onDone(final int total) {
                ui.post(() -> tvDevices.setText("扫描完成，共 " + total + " 台开放 5555"));
            }
        });
    }

    /** 对找到的盒子：检测已装则跳过，否则下载并 ADB 安装剪切板。 */
    private void remoteInstall(final String ip) {
        worker.execute(() -> {
            try {
                AdbClient adb = new AdbClient(UpdateActivity.this, ip, 5555);
                appendLog("连接 " + ip + "…");
                adb.connect();
                boolean installed = adb.isPackageInstalled("com.lanclip.sync");
                if (installed) {
                    appendLog(ip + " 已安装剪切板，跳过");
                    adb.close();
                    return;
                }
                // 下载剪切板 APK
                ui.post(() -> tvDevices.setText("下载剪切板 APK…"));
                UpdateClient.ReleaseInfo rel = new UpdateClient.Checker(SELF_REPO, SELF_PREFIX).latest();
                if (!rel.hasApk()) {
                    appendLog(ip + " 无可用 APK");
                    adb.close();
                    return;
                }
                File apk = UpdateClient.downloadTo(UpdateActivity.this, rel.apkUrl, "remote-lanclip.apk", null);
                byte[] bytes = readAllBytes(apk);
                appendLog("通过 ADB 安装 " + rel.version + " 到 " + ip + "…");
                String result = adb.install(bytes, "com.lanclip.sync");
                appendLog(ip + " 安装结果：" + result.trim());
                adb.close();
            } catch (Throwable t) {
                appendLog("远程安装 " + ip + " 失败：" + t.getMessage());
            }
        });
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

    /** 读取整个文件为字节数组（兼容 API 19，不用 java.nio.file）。 */
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