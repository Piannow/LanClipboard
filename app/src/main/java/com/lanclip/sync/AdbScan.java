package com.lanclip.sync;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 局域网扫描：探测 /24 网段内开放 ADB 无线调试端口(5555) 的设备。
 * 用并发 TCP connect，单端口超时约 200ms，避免阻塞 UI。
 */
public final class AdbScan {

    public interface Listener {
        /** found: 扫描出的设备 IP 列表（实时追加）；done: 扫描结束。 */
        void onFound(String ip);
        void onDone(int total);
    }

    private AdbScan() {
    }

    /**
     * 扫描指定本地 IP 所在 /24 网段。
     * @param localIp 本机 IPv4，例如 192.168.1.5
     */
    public static void scan(String localIp, int port, Listener listener) {
        String prefix = prefixOf(localIp);
        if (prefix == null) {
            if (listener != null) {
                listener.onDone(0);
            }
            return;
        }
        final List<String> results = Collections.synchronizedList(new ArrayList<String>());
        List<String> all = new ArrayList<>();
        for (int i = 1; i <= 254; i++) {
            all.add(prefix + "." + i);
        }
        final int total = all.size();
        final CountDownLatch latch = new CountDownLatch(total);
        ExecutorService pool = Executors.newFixedThreadPool(40);
        for (final String ip : all) {
            pool.execute(() -> {
                try {
                    Socket s = new Socket();
                    s.connect(new InetSocketAddress(ip, port), 250);
                    s.close();
                    if (listener != null && !results.contains(ip)) {
                        results.add(ip);
                        listener.onFound(ip);
                    }
                } catch (Throwable ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }
        // 后台等待全部完成
        new Thread(() -> {
            try {
                latch.await(40, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            pool.shutdownNow();
            if (listener != null) {
                listener.onDone(results.size());
            }
        }).start();
    }

    private static String prefixOf(String ip) {
        if (ip == null) {
            return null;
        }
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return null;
        }
        return parts[0] + "." + parts[1] + "." + parts[2];
    }
}