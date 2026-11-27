package com.lanclip.sync;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
    private static final String ASTR_REPO = "Piannow/AstrBotLite";
    private static final String ASTR_PREFIX = "AstrBotLite";
    private static final String SELF_PACKAGE = "com.lanclip.sync";
    private static final String ASTR_PACKAGE = "com.astrbot.lite";

    private TextView tvDevices;
    private ListView list;
    private EditText etPort;
    private TextView tvConnected;
    private TextView tvRemoteVer;
    private Button btnCheckRemote;
    private TextView tvLog;
    private TextView tvVerStatus;
    private ListView listVersions;
    private final List<UpdateClient.ReleaseInfo> versions = new ArrayList<>();
    private ArrayAdapter<UpdateClient.ReleaseInfo> versionAdapter;
    private final java.util.Map<String, String> downloading = new java.util.HashMap<>();

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newFixedThreadPool(3);
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
        tvLog = findViewById(R.id.tv_log);
        tvVerStatus = findViewById(R.id.tv_ver_status);
        listVersions = findViewById(R.id.list_versions);

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
        findViewById(R.id.btn_open_dir).setOnClickListener(v -> openApkFolder());
        findViewById(R.id.btn_copy_log).setOnClickListener(v -> copyLog());
        findViewById(R.id.btn_list_versions).setOnClickListener(v -> loadVersions());

        versionAdapter = new ArrayAdapter<UpdateClient.ReleaseInfo>(this,
                R.layout.item_version, versions) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = convertView;
                if (v == null) {
                    v = getLayoutInflater().inflate(R.layout.item_version, parent, false);
                }
                UpdateClient.ReleaseInfo r = versions.get(position);
                TextView t1 = v.findViewById(R.id.item_title);
                TextView t2 = v.findViewById(R.id.item_sub);
                Button del = v.findViewById(R.id.item_delete);
                t1.setText(r.version + "    " + r.apkName);
                final File f = localApkFor(r);
                boolean has = f.exists() && f.length() > 0;
                t2.setText(has ? "已下载 · 点此行推送到盒子" : "未下载 · 点此行下载 (" + sizeText(r.sizeBytes) + ")");
                t2.setTextColor(getResources().getColor(has ? R.color.accent : R.color.dim));
                if (has) {
                    del.setVisibility(View.VISIBLE);
                    del.setFocusable(false);
                    del.setFocusableInTouchMode(false);
                    del.setOnClickListener(view -> confirmDeleteApk(f));
                } else {
                    del.setVisibility(View.GONE);
                }
                v.setOnClickListener(view -> onVersionClick(r));
                return v;
            }
        };
        listVersions.setAdapter(versionAdapter);
        listVersions.setOnItemClickListener((parent, view, position, id) -> onVersionClick(versions.get(position)));
        listVersions.setOnItemLongClickListener((parent, view, position, id) -> {
            UpdateClient.ReleaseInfo r = versions.get(position);
            final File f = localApkFor(r);
            if (!f.exists()) {
                Toast.makeText(this, "该版本未下载", Toast.LENGTH_SHORT).show();
                return true;
            }
            confirmDeleteApk(f);
            return true;
        });

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

    // ---------- 版本库：拉列表 / 下载 / 本机安装 ----------

    private void loadVersions() {
        tvVerStatus.setText("拉取版本列表…");
        worker.execute(() -> {
            java.util.List<UpdateClient.ReleaseInfo> all = new java.util.ArrayList<>();
            StringBuilder err = new StringBuilder();
            try {
                all.addAll(new UpdateClient.Checker(SELF_REPO, SELF_PREFIX).list());
            } catch (Throwable t) {
                err.append("剪切板：").append(t.getMessage()).append(" ");
            }
            try {
                all.addAll(new UpdateClient.Checker(ASTR_REPO, ASTR_PREFIX).list());
            } catch (Throwable t) {
                err.append("AstrBotLite：").append(t.getMessage()).append(" ");
            }
            final String errText = err.toString().trim();
            ui.post(() -> {
                versions.clear();
                versions.addAll(all);
                versionAdapter.notifyDataSetChanged();
                int local = 0;
                for (UpdateClient.ReleaseInfo r : versions) {
                    if (localApkFor(r).exists()) {
                        local++;
                    }
                }
                if (versions.isEmpty()) {
                    tvVerStatus.setText(errText.isEmpty() ? "没有可下载的版本" : "拉取失败：" + errText);
                } else {
                    tvVerStatus.setText("共 " + versions.size() + " 个版本，本机已下载 " + local + " 个"
                            + (errText.isEmpty() ? "" : "（部分失败：" + errText + "）"));
                }
                appendLog("版本列表：" + versions.size() + " 个");
            });
        });
    }

    private void onVersionClick(final UpdateClient.ReleaseInfo r) {
        final File local = localApkFor(r);
        if (local.exists() && local.length() > 0) {
            // 已下载 → 只推送到盒子
            pushLocalToBox(local);
        } else {
            downloadVersion(r);
        }
    }

    private void downloadVersion(final UpdateClient.ReleaseInfo r) {
        if (DownloadService.isDownloading(r.version)) {
            Toast.makeText(this, "正在下载 " + r.version + "…", Toast.LENGTH_SHORT).show();
            return;
        }
        tvVerStatus.setText("下载 " + r.version + "…");
        appendLog("开始下载 " + r.version);
        Intent svc = new Intent(this, DownloadService.class)
                .putExtra(DownloadService.EXTRA_URL, r.apkUrl)
                .putExtra(DownloadService.EXTRA_NAME, localName(r))
                .putExtra(DownloadService.EXTRA_VERSION, r.version);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
    }

    /** 下载进度广播接收器。 */
    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String version = intent.getStringExtra("version");
            int pct = intent.getIntExtra("pct", -1);
            String done = intent.getStringExtra("done");
            if (done != null) {
                if (done.startsWith("失败")) {
                    tvVerStatus.setText(version + " " + done);
                    appendLog("下载 " + version + " " + done);
                } else {
                    tvVerStatus.setText(version + " 已下载，可推送");
                    appendLog("已下载 " + version + "：" + done);
                }
                versionAdapter.notifyDataSetChanged();
            } else if (version != null) {
                tvVerStatus.setText("下载 " + version + " " + (pct < 0 ? "" : pct + "%"));
            }
        }
    };

    /** 弹出行操作：推送到盒子 / 安装到本机。 */
    private void showApkActions(final File f) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(f.getName())
                .setMessage("路径：" + f.getAbsolutePath()
                        + "\n大小：" + sizeText(f.length()))
                .setItems(new CharSequence[]{"推送到已连接盒子", "安装到本机手机"}, (dd, sel) -> {
                    if (sel == 0) {
                        pushLocalToBox(f);
                    } else {
                        installLocalApk(f);
                    }
                })
                .show();
    }

    private void pushLocalToBox(final File apk) {
        if (!apk.exists()) {
            Toast.makeText(this, "文件不存在", Toast.LENGTH_SHORT).show();
            return;
        }
        if (connected == null) {
            if (endpoints.isEmpty()) {
                Toast.makeText(this, "请先扫描并连接盒子", Toast.LENGTH_SHORT).show();
                appendLog("推送失败：尚未连接盒子");
                return;
            }
            appendLog("尚未连接，自动连接 " + endpoints.get(0) + "…");
            connectDevice(endpoints.get(0));
            Toast.makeText(this, "正在连接盒子，请稍后再次点击推送", Toast.LENGTH_LONG).show();
            return;
        }
        if (PushService.isPushing(apk.getName())) {
            Toast.makeText(this, "正在推送…", Toast.LENGTH_SHORT).show();
            return;
        }
        appendLog("推送 " + apk.getName() + " 到 " + connected + "…");
        Toast.makeText(this, "正在推送（可切页面）…", Toast.LENGTH_SHORT).show();
        Intent svc = new Intent(this, PushService.class)
                .putExtra(PushService.EXTRA_APK, apk.getAbsolutePath())
                .putExtra(PushService.EXTRA_HOST, connectedIp)
                .putExtra(PushService.EXTRA_PORT, connectedPort)
                .putExtra(PushService.EXTRA_LABEL, apk.getName());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
    }

    /** 推送结果广播接收器。 */
    private final BroadcastReceiver pushReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String apk = intent.getStringExtra("apk");
            boolean ok = intent.getBooleanExtra("ok", false);
            String text = intent.getStringExtra("text");
            appendLog((ok ? "推送成功：" : "推送失败：") + apk + " " + (text == null ? "" : text));
            Toast.makeText(UpdateActivity.this,
                    ok ? ("推送成功：" + apk) : ("推送失败：" + text), Toast.LENGTH_LONG).show();
        }
    };

    /** 本地目录中的 APK 文件（按版本命名）。 */
    private File localApkFor(UpdateClient.ReleaseInfo r) {
        File dir = getExternalFilesDir("downloads");
        if (dir == null) {
            dir = getFilesDir();
        }
        return new File(dir, localName(r));
    }

    private static String localName(UpdateClient.ReleaseInfo r) {
        String n = r.apkName == null || r.apkName.isEmpty() ? "LanClipboard" : r.apkName;
        if (!n.endsWith(".apk")) {
            n = n + ".apk";
        }
        // 版本前缀，避免不同版本互相覆盖
        return r.version + "-" + n;
    }

    private static String sizeText(long bytes) {
        if (bytes <= 0) {
            return "未知大小";
        }
        if (bytes < 1024 * 1024) {
            return (bytes / 1024) + "KB";
        }
        return String.format(java.util.Locale.ROOT, "%.1fMB", bytes / 1048576.0);
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
        appendLog("开始连接 " + endpoint + "…");
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
                    // 持久化连接信息，切页面回来后自动恢复
                    getSharedPreferences(Util.PREFS, MODE_PRIVATE).edit()
                            .putString("adb_endpoint", endpoint)
                            .putString("adb_ip", ip)
                            .putInt("adb_port", port)
                            .apply();
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
                final String msg = t.getMessage() == null ? t.toString() : t.getMessage();
                ui.post(() -> {
                    tvConnected.setText("连接失败：" + endpoint);
                    appendLog("连接 " + endpoint + " 失败：" + msg);
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
                String astrVer = runner.remoteVersion(ASTR_PACKAGE);
                remoteVersion = ver;
                final String lc = (ver == null ? "未安装" : ver);
                final String ab = (astrVer == null ? "未安装" : astrVer);
                ui.post(() -> {
                    tvRemoteVer.setText("剪切板：" + lc + "\nAstrBotLite：" + ab);
                    appendLog("被控端 剪切板=" + lc + "，AstrBotLite=" + ab);
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

    // ---------- 打开 APK 文件夹 ----------

    private void openApkFolder() {
        File dir = getExternalFilesDir("downloads");
        if (dir == null) {
            dir = getFilesDir();
        }
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        final File d = dir;

        // 实时数据源：始终以磁盘为准
        final java.util.List<File> apks = new java.util.ArrayList<>();
        reloadLocalApks(d, apks);

        if (apks.isEmpty()) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("APK 文件夹")
                    .setMessage("目录为空\n\n" + d.getAbsolutePath()
                            + "\n\n点「拉取版本列表」下载 APK 后会出现在这里。")
                    .setPositiveButton("知道了", null)
                    .show();
            appendLog("APK 目录（空）：" + d.getAbsolutePath());
            return;
        }

        final android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<String>(
                this, R.layout.item_version, R.id.item_title, new java.util.ArrayList<String>()) {
            @Override
            public int getCount() {
                return apks.size();
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = convertView;
                if (v == null) {
                    v = getLayoutInflater().inflate(R.layout.item_version, parent, false);
                }
                final File f = apks.get(position);
                TextView t1 = v.findViewById(R.id.item_title);
                TextView t2 = v.findViewById(R.id.item_sub);
                Button del = v.findViewById(R.id.item_delete);
                t1.setText(f.getName());
                t2.setText(sizeText(f.length()) + "  ·  点此行推送/安装");
                del.setVisibility(View.VISIBLE);
                del.setFocusable(false);
                del.setFocusableInTouchMode(false);
                del.setOnClickListener(view -> confirmDeleteLocal(f, apks, this, d));
                v.setOnClickListener(view -> showApkActions(f));
                return v;
            }
        };

        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("本地 APK（" + apks.size() + " 个）")
                .setAdapter(ad, (dialog, which) -> {
                    if (which < 0 || which >= apks.size()) {
                        return;
                    }
                    final File f = apks.get(which);
                    new android.app.AlertDialog.Builder(this)
                            .setTitle(f.getName())
                            .setMessage("路径：" + f.getAbsolutePath()
                                    + "\n大小：" + sizeText(f.length()))
                            .setItems(new CharSequence[]{"推送到已连接盒子", "安装到本机手机"}, (dd, sel) -> {
                                if (sel == 0) {
                                    pushLocalToBox(f);
                                } else {
                                    installLocalApk(f);
                                }
                            })
                            .show();
                })
                .setNegativeButton("关闭", null)
                .create();
        dlg.show();
        appendLog("本地 APK：" + apks.size() + " 个（" + d.getAbsolutePath() + "）");
    }

    /** 从磁盘重新加载 APK 列表（按修改时间倒序）。 */
    private void reloadLocalApks(File dir, java.util.List<File> out) {
        out.clear();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().toLowerCase().endsWith(".apk")) {
                    out.add(f);
                }
            }
        }
        java.util.Collections.sort(out, new java.util.Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
    }

    /** 删除本地 APK 后实时刷新弹窗列表。 */
    private void confirmDeleteLocal(final File apk,
                                    final java.util.List<File> apks,
                                    final android.widget.ArrayAdapter<String> ad,
                                    final File dir) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("删除 APK")
                .setMessage("确定删除？\n\n" + apk.getName() + "\n" + sizeText(apk.length()))
                .setPositiveButton("删除", (dlg, w) -> {
                    boolean ok = UpdateClient.deleteApkWithResidue(UpdateActivity.this, apk);
                    if (!ok) {
                        Toast.makeText(this, "删除失败（文件可能被占用）", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    appendLog("已删除：" + apk.getName());
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                    // 实时刷新：从列表移除、刷新行、更新版本库状态
                    apks.remove(apk);
                    ad.notifyDataSetChanged();
                    versionAdapter.notifyDataSetChanged();
                    if (apks.isEmpty()) {
                        appendLog("本地 APK 已清空");
                        openApkFolder();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 确认并删除本地 APK。 */
    private void confirmDeleteApk(final File apk) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("删除 APK")
                .setMessage("确定删除？\n\n" + apk.getName() + "\n" + sizeText(apk.length()))
                .setPositiveButton("删除", (d, w) -> {
                    boolean ok = UpdateClient.deleteApkWithResidue(UpdateActivity.this, apk);
                    if (ok) {
                        appendLog("已删除：" + apk.getName());
                        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                        versionAdapter.notifyDataSetChanged();
                    } else {
                        appendLog("删除失败：" + apk.getName());
                        Toast.makeText(this, "删除失败（文件可能被占用）", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 安装本地 APK 到本机手机。 */
    private void installLocalApk(File apk) {
        if (!InstallHelper.canInstall(this)) {
            InstallHelper.openInstallSettings(this);
            Toast.makeText(this, "请允许「安装未知应用」后重试", Toast.LENGTH_SHORT).show();
            return;
        }
        appendLog("安装到本机：" + apk.getName());
        InstallHelper.promptInstall(this, apk);
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

    private void appendLog(final String line) {
        // 可能在 worker 线程被调用：切回主线程再更新 View，避免
        // "Only the original thread that created a view hierarchy can touch its views"
        if (Looper.myLooper() != Looper.getMainLooper()) {
            ui.post(() -> appendLog(line));
            return;
        }
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
        try {
            unregisterReceiver(downloadReceiver);
        } catch (Throwable ignored) {
        }
        try {
            unregisterReceiver(pushReceiver);
        } catch (Throwable ignored) {
        }
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 重新绑定下载/推送进度（切页面回来仍能看到）
        try {
            unregisterReceiver(downloadReceiver);
        } catch (Throwable ignored) {
        }
        try {
            unregisterReceiver(pushReceiver);
        } catch (Throwable ignored) {
        }
        try {
            registerReceiver(downloadReceiver,
                    new android.content.IntentFilter(DownloadService.ACTION_DOWNLOAD));
        } catch (Throwable ignored) {
        }
        try {
            registerReceiver(pushReceiver,
                    new android.content.IntentFilter(PushService.ACTION_PUSH));
        } catch (Throwable ignored) {
        }
        // 恢复上次的连接信息（只恢复状态，不在这里重连以免干扰扫描）
        if (connected == null) {
            android.content.SharedPreferences sp = getSharedPreferences(Util.PREFS, MODE_PRIVATE);
            String ep = sp.getString("adb_endpoint", "");
            if (!ep.isEmpty()) {
                connected = ep;
                connectedIp = sp.getString("adb_ip", "");
                connectedPort = sp.getInt("adb_port", 5555);
                if (tvConnected != null) {
                    tvConnected.setText("已连接：" + ep + "（推送前会自动重连）");
                }
            }
        }
        // 视图可能尚未就绪，做空值保护，避免 NPE 导致页面异常
        if (versionAdapter == null || tvVerStatus == null) {
            return;
        }
        try {
            for (UpdateClient.ReleaseInfo r : versions) {
                if (DownloadService.isDownloading(r.version)) {
                    Integer p = DownloadService.progressMap().get(r.version);
                    tvVerStatus.setText("下载 " + r.version + " " + (p == null || p < 0 ? "" : p + "%"));
                    break;
                }
            }
            versionAdapter.notifyDataSetChanged();
        } catch (Throwable ignored) {
        }
    }
}