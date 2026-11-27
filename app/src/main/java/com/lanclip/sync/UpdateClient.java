package com.lanclip.sync;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.List;

/**
 * GitHub Release 检测 + APK 下载。
 * 下载采用 4 线程分段 + 断点续传 + 进度回调。
 */
public final class UpdateClient {

    private static final String TAG = "LanClipUpdate";
    private static final int THREADS = 4;

    /** 远程仓库的最新 Release 信息 */
    public static final class ReleaseInfo {
        public final String tag;
        public final String version;
        public final String apkName;
        public final String apkUrl;
        public final long sizeBytes;
        public final String body;

        ReleaseInfo(JSONObject release, String preferAssetPrefix) {
            this.tag = release.optString("tag_name", "");
            this.version = this.tag.startsWith("v") ? this.tag.substring(1) : this.tag;
            this.body = release.optString("body", "");
            JSONArray assets = release.optJSONArray("assets");
            JSONObject chosen = null;
            if (assets != null) {
                for (int i = 0; i < assets.length() && chosen == null; i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a != null && a.optString("name", "").endsWith(".apk")
                            && (preferAssetPrefix == null || a.optString("name", "").startsWith(preferAssetPrefix))) {
                        chosen = a;
                    }
                }
                if (chosen == null) {
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

    public static final class Checker {
        private final String repo;
        private final String preferAssetPrefix;

        public Checker(String repo, String preferAssetPrefix) {
            this.repo = repo;
            this.preferAssetPrefix = preferAssetPrefix;
        }

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

        /** 拉取全部 Release（不含草稿/预发布），按 tag 倒序，每个取 APK 资产。 */
        public List<ReleaseInfo> list() throws Exception {
            String url = "https://api.github.com/repos/" + repo + "/releases?per_page=30";
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
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
            JSONArray arr = new JSONArray(json);
            List<ReleaseInfo> out = new java.util.ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject rel = arr.optJSONObject(i);
                if (rel == null) {
                    continue;
                }
                if (rel.optBoolean("draft") || rel.optBoolean("prerelease")) {
                    continue;
                }
                ReleaseInfo info = new ReleaseInfo(rel, preferAssetPrefix);
                if (info.hasApk()) {
                    out.add(info);
                }
            }
            return out;
        }
    }

    public interface ProgressListener {
        void onProgress(long done, long total);
    }

    /**
     * 4 线程分段下载，支持断点续传（基于 Range 请求）。
     * GitHub 资产直链支持 Range 请求。
     *
     * @return 下载完成的文件；若已存在同版本完整文件则直接返回。
     */
    public static File download(Context ctx, String apkUrl, String fileName, ProgressListener listener) throws Exception {
        File dir = ctx.getExternalFilesDir("downloads");
        if (dir == null) {
            dir = ctx.getFilesDir();
        }
        if (dir != null && !dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        File out = new File(dir, fileName);
        File part = new File(dir, fileName + ".part");

        long total = contentLength(apkUrl);
        if (total <= 0) {
            // 服务器不支持分段，退回单线程普通下载
            return simpleDownload(ctx, apkUrl, fileName, listener);
        }

        if (part.exists() && part.length() == total) {
            // 已完整，改名返回
            rename(part, out);
            if (listener != null) {
                listener.onProgress(total, total);
            }
            return out;
        }

        // 确定每段范围
        long seg = total / THREADS;
        long[] starts = new long[THREADS];
        long[] ends = new long[THREADS];
        for (int i = 0; i < THREADS; i++) {
            starts[i] = i * seg;
            ends[i] = (i == THREADS - 1) ? total - 1 : (i + 1) * seg - 1;
        }
        // 断点续传：用 sidecar 文件记录每段已写位置（第一行起始位置，之后每行 "segmentIndex,pos"）
        File segFile = new File(dir, fileName + ".seg");
        long[] segPos = readSegments(segFile, THREADS, starts);
        final AtomicLong done = new AtomicLong(0);
        for (int i = 0; i < THREADS; i++) {
            done.addAndGet(segPos[i] - starts[i]);
        }
        final RandomAccessFile raf = new RandomAccessFile(part, "rw");
        final CountDownLatch latch = new CountDownLatch(THREADS);
        final Object lock = new Object();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);

        for (int t = 0; t < THREADS; t++) {
            final int idx = t;
            final long start = starts[idx];
            final long end = ends[idx];
            pool.execute(() -> {
                long pos = segPos[idx];
                try {
                    while (pos <= end) {
                        HttpURLConnection conn = (HttpURLConnection) new URL(apkUrl).openConnection();
                        conn.setConnectTimeout(10000);
                        conn.setReadTimeout(30000);
                        conn.setRequestProperty("Range", "bytes=" + pos + "-" + end);
                        conn.setRequestProperty("User-Agent", "LanClipboard-Updater");
                        int code = conn.getResponseCode();
                        if (code != 206 && code != 200) {
                            throw new IOException("下载失败 HTTP " + code);
                        }
                        InputStream in = conn.getInputStream();
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = in.read(buf)) != -1 && pos <= end) {
                            int want = (int) Math.min(n, end - pos + 1);
                            synchronized (lock) {
                                raf.seek(pos);
                                raf.write(buf, 0, want);
                                segPos[idx] = pos + want;
                                segFile.setLastModified(System.currentTimeMillis());
                            }
                            pos += want;
                            done.addAndGet(want);
                        }
                        in.close();
                        conn.disconnect();
                    }
                } catch (Throwable e) {
                    Log.w(TAG, "thread " + idx + " failed", e);
                } finally {
                    latch.countDown();
                }
            });
        }

        // 进度上报线程
        final long finalTotal = total;
        Thread reporter = new Thread(() -> {
            long lastDone = -1;
            while (latch.getCount() > 0) {
                long d = done.get();
                if (d != lastDone && listener != null) {
                    listener.onProgress(Math.min(d, finalTotal), finalTotal);
                    lastDone = d;
                }
                try {
                    Thread.sleep(120);
                } catch (InterruptedException ignored) {
                }
            }
            if (listener != null) {
                listener.onProgress(finalTotal, finalTotal);
            }
        });
        reporter.start();

        latch.await();
        reporter.join();
        pool.shutdownNow();
        raf.close();
        writeSegments(segFile, segPos);

        if (part.length() < total) {
            throw new IOException("下载不完整：" + part.length() + "/" + total);
        }
        // 完成后清理断点信息
        if (segFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            segFile.delete();
        }
        rename(part, out);
        return out;
    }

    /** 读取每段已下载位置（断点续传）。返回每段当前 pos，无记录则回到段起点。 */
    private static long[] readSegments(File segFile, int threads, long[] starts) {
        long[] pos = starts.clone();
        if (segFile == null || !segFile.exists()) {
            return pos;
        }
        try {
            String text = new String(readAllBytes(segFile), "UTF-8");
            String[] lines = text.split("\n");
            for (String line : lines) {
                String s = line.trim();
                if (s.isEmpty()) {
                    continue;
                }
                int idxComma = s.indexOf(',');
                if (idxComma > 0) {
                    int seg = Integer.parseInt(s.substring(0, idxComma).trim());
                    long p = Long.parseLong(s.substring(idxComma + 1).trim());
                    if (seg >= 0 && seg < threads) {
                        pos[seg] = Math.max(starts[seg], p);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "read segments failed", t);
        }
        return pos;
    }

    /** 写入每段已下载位置到 sidecar。 */
    private static void writeSegments(File segFile, long[] pos) {
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < pos.length; i++) {
                sb.append(i).append(',').append(pos[i]).append('\n');
            }
            java.io.FileOutputStream fos = new java.io.FileOutputStream(segFile, false);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.flush();
            fos.close();
        } catch (Throwable t) {
            Log.w(TAG, "write segments failed", t);
        }
    }

    private static byte[] readAllBytes(File file) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        InputStream in = new java.io.FileInputStream(file);
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        in.close();
        return bos.toByteArray();
    }

    private static long contentLength(String apkUrl) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(apkUrl).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestMethod("HEAD");
        conn.setRequestProperty("User-Agent", "LanClipboard-Updater");
        int code = conn.getResponseCode();
        long len = conn.getContentLengthLong();
        conn.disconnect();
        // HEAD 可能 405，GET 一把只拿 header
        if (code != 200 && code != 206) {
            HttpURLConnection c2 = (HttpURLConnection) new URL(apkUrl).openConnection();
            c2.setRequestMethod("GET");
            c2.setRequestProperty("User-Agent", "LanClipboard-Updater");
            c2.setConnectTimeout(8000);
            c2.setReadTimeout(8000);
            long l = c2.getContentLengthLong();
            c2.disconnect();
            return l;
        }
        return len;
    }

    private static File simpleDownload(Context ctx, String apkUrl, String fileName, ProgressListener listener) throws Exception {
        File dir = ctx.getExternalFilesDir("downloads");
        if (dir == null) {
            dir = ctx.getFilesDir();
        }
        File out = new File(dir, fileName);
        HttpURLConnection conn = (HttpURLConnection) new URL(apkUrl).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);
        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            throw new IOException("下载失败 HTTP " + code);
        }
        long total = conn.getContentLengthLong();
        InputStream in = conn.getInputStream();
        java.io.FileOutputStream fos = new java.io.FileOutputStream(out, false);
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

    private static void rename(File from, File to) {
        if (from.exists()) {
            if (to.exists()) {
                //noinspection ResultOfMethodCallIgnored
                to.delete();
            }
            //noinspection ResultOfMethodCallIgnored
            from.renameTo(to);
        }
    }

    /** 解析语义版本号，用于比较 "1.1" vs "1.2"。返回 null 表示解析失败。 */
    public static int[] parseVersion(String v) {
        if (v == null) {
            return null;
        }
        String s = v.trim();
        if (s.startsWith("v")) {
            s = s.substring(1);
        }
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
        if (a == null || b == null) {
            return false;
        }
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int va = i < a.length ? a[i] : 0;
            int vb = i < b.length ? b[i] : 0;
            if (va != vb) {
                return va > vb;
            }
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