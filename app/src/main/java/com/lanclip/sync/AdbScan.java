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
 * 局域网扫描：探测 /24 网段内开放 ADB 无线调试端口(默认 5555-5585) 的设备。
 * 用并发 TCP connect，单端口超时约 250ms，避免阻塞 UI。
 * 返回格式：ip:port（找到的 adb 端点）。
 */
public final class AdbScan {

    public static final int ADB_DEFAULT_PORT = 5555;
    /** 覆盖绝大多数机顶盒使用的 adb 端口段（部分设备端口会偏移） */
    public static final int[] PORT_RANGE = {5555, 5585};

    public interface Listener {
        /** found: 发现一个 adb 端点，格式 "ip:port"。 */
        void onFound(String endpoint);
        void onDone(int total);
    }

    private AdbScan() {
    }

    /**
     * 扫描 /24 网段内的端口段。
     * @param localIp 本机 IPv4
     */
    public static void scan(String localIp, Listener listener) {
        scanRange(localIp, PORT_RANGE[0], PORT_RANGE[1], listener);
    }

    /** 只扫单个端口（手动指定时用）。 */
    public static void scanPort(String localIp, int port, Listener listener) {
        scanRange(localIp, port, port, listener);
    }

    private static void scanRange(String localIp, int portStart, int portEnd, Listener listener) {
        String prefix = prefixOf(localIp);
        if (prefix == null) {
            if (listener != null) {
                listener.onDone(0);
            }
            return;
        }
        if (portStart > portEnd) {
            int tmp = portStart;
            portStart = portEnd;
            portEnd = tmp;
        }
        final List<String> results = Collections.synchronizedList(new ArrayList<String>());
        // 组合所有 ip:port
        List<String> targets = new ArrayList<>();
        for (int i = 1; i <= 254; i++) {
            String ip = prefix + "." + i;
            for (int p = portStart; p <= portEnd; p++) {
                targets.add(ip + ":" + p);
            }
        }
        final int total = targets.size();
        final CountDownLatch latch = new CountDownLatch(total);
        ExecutorService pool = Executors.newFixedThreadPool(60);
        for (final String target : targets) {
            pool.execute(() -> {
                int ci = target.lastIndexOf(':');
                String ip = target.substring(0, ci);
                int port = Integer.parseInt(target.substring(ci + 1));
                try {
                    Socket s = new Socket();
                    s.connect(new InetSocketAddress(ip, port), 250);
                    s.close();
                    String ep = ip + ":" + port;
                    if (listener != null && !results.contains(ep)) {
                        results.add(ep);
                        listener.onFound(ep);
                    }
                } catch (Throwable ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }
        new Thread(() -> {
            try {
                latch.await(60, TimeUnit.SECONDS);
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