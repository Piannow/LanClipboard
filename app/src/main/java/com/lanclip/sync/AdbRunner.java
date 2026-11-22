package com.lanclip.sync;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 用「真 adb 二进制」执行 ADB 操作（手机控制机顶盒）。
 *
 * 把 assets/bin/adb 解压到应用私有目录并赋予执行权限，再通过 ProcessBuilder
 * 执行官方 adb 的 connect / shell / install。官方 adb 的 RSA 认证、握手、
 * SSH 公钥编码都是久经考验的，避免手写协议的兼容问题。
 */
public final class AdbRunner {

    private static final String TAG = "LanClipAdb";
    private static final int ADB_SERVER_PORT = 5038; // 避开默认 5037 冲突
    private static final long CMD_TIMEOUT_MS = 20000;

    private final Context ctx;
    private File adbFile;   // 解压后的 adb 可执行文件
    private final String serial;  // "ip:port"
    private volatile String lastOutput = "";  // 最近一次 adb 命令的原始输出

    public AdbRunner(Context ctx, String host, int port) {
        this.ctx = ctx;
        this.serial = host + ":" + port;
    }

    /** 最近一次 adb 命令的原始输出（诊断用）。 */
    public String lastOutput() {
        return lastOutput;
    }

    /**
     * 确保 adb 二进制已解压并可执行。
     */
    private synchronized File ensureBinary() throws Exception {
        if (adbFile != null && adbFile.exists()) {
            return adbFile;
        }
        File dir = new File(ctx.getFilesDir(), "bin");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        File out = new File(dir, "adb");
        // 从 assets 解压（幂等：已存在且大小正确则跳过）
        if (!out.exists() || out.length() < 100000) {
            InputStream in = ctx.getAssets().open("bin/adb");
            FileOutputStream fos = new FileOutputStream(out);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) {
                fos.write(buf, 0, n);
            }
            fos.flush();
            fos.close();
            in.close();
        }
        // 赋予执行权限
        out.setExecutable(true, true);
        out.setReadable(true, false);
        adbFile = out;
        return out;
    }

    /** 连接盒子。adb connect 不带 -s，直接连 host:port。 */
    public boolean connect() throws Exception {
        String out = runRaw("connect", serial);
        // 官方 adb connect 成功会输出 "connected to <host>:<port>" 或 "already connected to ..."
        return out != null && (out.contains("connected to") || out.contains("already connected"));
    }

    /** 断开。 */
    public void disconnect() throws Exception {
        runRaw("disconnect", serial);
    }

    /** 执行 shell 命令（已连接的盒子），返回 stdout。 */
    public String shell(String command) throws Exception {
        return runSerial("shell", command);
    }

    /** 检查盒子是否已安装指定包。 */
    public boolean isPackageInstalled(String pkg) throws Exception {
        String out = shell("pm list packages " + pkg);
        return out != null && out.contains("package:" + pkg);
    }

    /** 读盒子已装 App 的 versionName；未安装返回 null。 */
    public String remoteVersion(String pkg) throws Exception {
        String out = shell("dumpsys package " + pkg);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("versionName=([0-9.]+)").matcher(out);
        return m.find() ? m.group(1) : null;
    }

    /** 安装 APK 到盒子。 */
    public String install(String localApkPath) throws Exception {
        return runSerial("install", "-r", localApkPath);
    }

    /** 不带 -s 的 adb 命令（connect/disconnect/start-server）。 */
    private String runRaw(String... args) throws Exception {
        File adb = ensureBinary();
        execCmd(adb, null, "start-server");
        List<String> cmd = new ArrayList<>();
        cmd.add(adb.getAbsolutePath());
        for (String a : args) {
            cmd.add(a);
        }
        return exec(cmd);
    }

    /** 带 -s <serial> 的 adb 命令（shell/install/等，需要先 connect）。 */
    private String runSerial(String... args) throws Exception {
        File adb = ensureBinary();
        execCmd(adb, null, "start-server");
        List<String> cmd = new ArrayList<>();
        cmd.add(adb.getAbsolutePath());
        cmd.add("-s");
        cmd.add(serial);
        for (String a : args) {
            cmd.add(a);
        }
        return exec(cmd);
    }

    private void execCmd(File adb, String unused, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(adb.getAbsolutePath());
        for (String a : args) {
            cmd.add(a);
        }
        exec(cmd);
    }

    private String exec(List<String> cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        // HOME 指向应用私有目录，让 adb 生成/复用 ~/.android/adbkey
        pb.environment().put("HOME", ctx.getFilesDir().getAbsolutePath());
        // adb 日志/临时文件默认写 /tmp，Android 上 /tmp 不存在会导致 daemon 起不来。
        // 用 TMPDIR 指到应用私有目录（一定存在且可写）。
        File tmp = new File(ctx.getFilesDir(), "tmp");
        if (!tmp.exists()) {
            //noinspection ResultOfMethodCallIgnored
            tmp.mkdirs();
        }
        pb.environment().put("TMPDIR", tmp.getAbsolutePath());
        pb.environment().put("TMP", tmp.getAbsolutePath());
        pb.environment().put("TEMP", tmp.getAbsolutePath());
        pb.environment().put("ANDROID_ADB_SERVER_PORT", String.valueOf(ADB_SERVER_PORT));
        pb.redirectErrorStream(true);
        Process p = pb.start();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        InputStream in = p.getInputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        boolean finished = p.waitFor(CMD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (!finished) {
            p.destroy();
            Log.w(TAG, "adb 命令超时: " + cmd);
        }
        in.close();
        lastOutput = new String(bos.toByteArray(), "UTF-8");
        return lastOutput;
    }

    private AdbRunner() {
        this.ctx = null;
        this.serial = null;
    }
}