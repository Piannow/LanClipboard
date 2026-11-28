package com.lanclip.sync;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 局域网扫描：探测 /24 网段内开放 ADB 无线调试端口的设备。
 *
 * 优化策略：
 *  1) 先扫最常见的 5555（单端口并发，1 秒内出结果）
 *  2) 再扫 5556-5585 的偏移端口，且某 IP 一旦命中就不再探测其余端口
 *  3) 高并发 + 短超时，整体控制在数秒内
 */
public final class AdbScan {

    public static final int ADB_DEFAULT_PORT = 5555;
    /** 覆盖绝大多数机顶盒使用的 adb 端口段（部分设备端口会偏移） */
    public static final int[] PORT_RANGE = {5555, 5585};

    private static final int TIMEOUT_MS = 1000;
    private static final int THREADS = 64;

    public interface Listener {
        /** found: 发现一个 adb 端点，格式 "ip:port"。 */
        void onFound(String endpoint);
        void onDone(int total);
    }

    private AdbScan() {
    }

    /** 扫描 /24 网段内的端口段。 */
    public static void scan(String localIp, Listener listener) {
        scanRange(localIp, PORT_RANGE[0], PORT_RANGE[1], listener);
    }

    /** 只扫单个端口（手动指定时用）。 */
    public static void scanPort(String localIp, int port, Listener listener) {
        scanRange(localIp, port, port, listener);
    }

    private static void scanRange(String localIp, int portStart, int portEnd, Listener listener) {
        final String prefix = prefixOf(localIp);
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
        final int ps = portStart;
        final int pe = portEnd;
        final List<String> results = Collections.synchronizedList(new ArrayList<String>());
        /** 已命中的 IP，命中后跳过其剩余端口 */
        final Set<String> hitIps = Collections.synchronizedSet(new HashSet<String>());
        final AtomicInteger found = new AtomicInteger(0);

        final ExecutorService pool = Executors.newFixedThreadPool(THREADS);

        // 阶段一：先扫默认端口 5555（若在范围内），快速出结果
        final int defaultPort = (ps <= ADB_DEFAULT_PORT && ADB_DEFAULT_PORT <= pe) ? ADB_DEFAULT_PORT : ps;
        runRound(pool, prefix, defaultPort, defaultPort, results, hitIps, found, listener);

        // 阶段二：扫剩余端口（跳过已不复存在的默认端口那一段）
        final int dp = defaultPort;
        Thread rest = new Thread(() -> {
            if (ps != dp || pe != dp) {
                if (ps < dp) {
                    runRound(pool, prefix, ps, dp - 1, results, hitIps, found, listener);
                }
                if (pe > dp) {
                    runRound(pool, prefix, dp + 1, pe, results, hitIps, found, listener);
                }
            }
            pool.shutdown();
            try {
                pool.awaitTermination(20, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            if (listener != null) {
                listener.onDone(found.get());
            }
        }, "lanclip-scan");
        rest.start();
    }

    /** 对给定端口区间跑一轮全 /24 并发探测。 */
    private static void runRound(ExecutorService pool, String prefix, int ps, int pe,
                                 final List<String> results, final Set<String> hitIps,
                                 final AtomicInteger found, final Listener listener) {
        final int portCount = pe - ps + 1;
        final CountDownLatch latch = new CountDownLatch(254 * portCount);
        for (int i = 1; i <= 254; i++) {
            final String ip = prefix + "." + i;
            for (int p = ps; p <= pe; p++) {
                final int port = p;
                pool.execute(() -> {
                    try {
                        // 该 IP 已命中则跳过，省掉剩余端口探测
                        if (hitIps.contains(ip)) {
                            return;
                        }
                        Socket s = new Socket();
                        s.connect(new InetSocketAddress(ip, port), TIMEOUT_MS);
                        s.close();
                        String ep = ip + ":" + port;
                        if (hitIps.add(ip) && !results.contains(ep)) {
                            results.add(ep);
                            found.incrementAndGet();
                            if (listener != null) {
                                listener.onFound(ep);
                            }
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        latch.countDown();
                    }
                });
            }
        }
        try {
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
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