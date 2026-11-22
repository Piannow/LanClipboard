package com.lanclip.sync;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * GitHub Release 检测与 APK 下载。
 * 不依赖 ADB，仅需网络权限。
 *
 * 用法：
 *   UpdateClient.Checker.check(LanClipboard) -> ReleaseInfo 或 null
 *   UpdateClient.downloadTo(apkUrl, destFile) -> 下载 APK 到本地
 */
public final class UpdateClient {

    private static final String TAG = "LanClipUpdate";

    /** 远程仓库的最新 Release 信息 */
    public static final class ReleaseInfo {
        public final String tag;            // 例如 v1.1
        public final String version;        // 去掉 v 的版本号，例如 1.1
        public final String apkName;        // 资产文件名
        public final String apkUrl;         // APK 下载直链
        public final long sizeBytes;        // 资产大小（字节）
        public final String body;           // Release 说明

        ReleaseInfo(JSONObject release, String preferAssetPrefix) {
            this.tag = release.optString("tag_name", "");
            this.version = this.tag.startsWith("v") ? this.tag.substring(1) : this.tag;
            this.body = release.optString("body", "");
            JSONArray assets = release.optJSONArray("assets");
            JSONObject chosen = null;
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a == null) continue;
                    String name = a.optString("name", "");
                    if (name.endsWith(".apk") && (preferAssetPrefix == null || name.startsWith(preferAssetPrefix))) {
                        chosen = a;
                        break;
                    }
                }
                if (chosen == null) {
                    // 没有匹配前缀就回退到第一个 .apk
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.optJSONObject(i);
                        if (a != null && a.optString("name", "").endsWith(".apk")) {
                            chosen = a;
                            break;
                        }
                    }
                }
            }
            if (chosen != null) {
                this.apkName = chosen.optString("name", "");
                this.apkUrl = chosen.optString("browser_download_url", "");
                this.sizeBytes = chosen.optLong("size", 0);
            } else {
                this.apkName = "";
                this.apkUrl = "";
                this.sizeBytes = 0;
            }
        }

        public boolean hasApk() {
            return apkUrl != null && !apkUrl.isEmpty();
        }
    }

    /**
     * GitHub Release 检查器。
     * @param repo 形如 "Piannow/LanClipboard" 或 "Piannow/AstrBotLite"
     * @param preferAssetPrefix 优先选取的资产前缀，如 "LanClipboard" / "AstrBotLite"；null 则取第一个 .apk
     */
    public static final class Checker {
        private final String repo;
        private final String preferAssetPrefix    ;

        public Checker(String repo, String preferAssetPrefix) {
            this.repo = repo;
            this.preferAssetPrefix = preferAssetPrefix;
        }

        /** 查询最新 Release；网络失败抛 IOException。 */
        public ReleaseInfo latest() throws Exception {
            String url = "https://api.github.com/repos/" + repo + "/releases/latest";
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "LanClipboard-Updater");
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            int code = conn.getResponseCode();
            if (code != 200) {
                InputStream es = conn.getErrorStream();
                String msg = es == null ? "" : readStream(es);
                conn.disconnect();
                throw new IOException("GitHub API HTTP " + code + ": " + msg);
            }
            InputStream in = conn.getInputStream();
            String json = readStream(in);
            conn.disconnect();
            return new ReleaseInfo(new JSONObject(json), preferAssetPrefix);
        }
    }

    /**
     * 下载 APK 到目标文件。
     * @param apkUrl GitHub 资产直链（browser_download_url，会 302 跳转）
     */
    public static File downloadTo(Context ctx, String apkUrl, String fileName, ProgressListener listener) throws Exception {
        File dir = ctx.getExternalFilesDir("downloads");
        if (dir == null) {
            dir = ctx.getFilesDir();
        }
        if (dir != null && !dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        File out = new File(dir, fileName);
        HttpURLConnection conn = (HttpURLConnection) new URL(apkUrl).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "LanClipboard-Updater");
        conn.setRequestProperty("Accept", "application/vnd.android.package-archive");
        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            throw new IOException("下载失败 HTTP " + code);
        }
        long total = conn.getContentLengthLong();
        InputStream in = conn.getInputStream();
        FileOutputStream fos = new FileOutputStream(out);
        byte[] buf = new byte[8192];
        long done = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            fos.write(buf, 0, n);
            done += n;
            if (listener != null) {
                listener.onProgress(done, total);
            }
        }
        fos.flush();
        fos.close();
        in.close();
        conn.disconnect();
        return out;
    }

    public interface ProgressListener {
        void onProgress(long done, long total);
    }

    /** 解析语义版本号，用于比较 "1.1" vs "1.2"。返回 null 表示解析失败。 */
    public static int[] parseVersion(String v) {
        if (v == null) return null;
        String s = v.trim();
        if (s.startsWith("v")) s = s.substring(1);
        String[] parts = s.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }

    /** 返回 a 是否严格大于 b（逐段比较，缺省补 0）。 */
    public static boolean greater(int[] a, int[] b) {
        if (a == null || b == null) return false;
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int va = i < a.length ? a[i] : 0;
            int vb = i < b.length ? b[i] : 0;
            if (va != vb) return va > vb;
        }
        return false;
    }

    private static String readStream(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), "UTF-8");
    }

    private UpdateClient() {
    }
}