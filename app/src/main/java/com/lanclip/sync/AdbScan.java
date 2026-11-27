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
 * 重要：整个扫描在独立后台线程执行，绝不阻塞调用方（UI）线程。
 */
public final class AdbScan {

    public static final int ADB_DEFAULT_PORT = 5555;
    public static final int[] PORT_RANGE = {5555, 5585};

    /** 跨设备 TCP 握手在 WiFi 下需要更宽容的超时 */
    private static final int TIMEOUT_MS = 300;
    private static final int THREADS = 128;

    public interface Listener {
        void onFound(String endpoint);
        void onDone(int total);
    }

    private AdbScan() {
    }

    public static void scan(String localIp, Listener listener) {
        scanRange(localIp, PORT_RANGE[0], PORT_RANGE[1], listener);
    }

    public static void scanPort(String localIp, int port, Listener listener) {
        scanRange(localIp, port, port, listener);
    }

    private static void scanRange(final String localIp, int portStart, int portEnd, final Listener listener) {
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

        // 全部丢到后台线程，调用方（UI 线程）立即返回
        new Thread(() -> {
            final List<String> results = Collections.synchronizedList(new ArrayList<String>());
            final Set<String> hitIps = Collections.synchronizedSet(new HashSet<String>());
            final AtomicInteger found = new AtomicInteger(0);
            final ExecutorService pool = Executors.newFixedThreadPool(THREADS);
            try {
                int dp = (ps <= ADB_DEFAULT_PORT && ADB_DEFAULT_PORT <= pe) ? ADB_DEFAULT_PORT : ps;
                // 先扫 5555，快速出结果
                probe(pool, prefix, dp, dp, results, hitIps, found, listener);
                // 再扫其余端口
                if (ps < dp) {
                    probe(pool, prefix, ps, dp - 1, results, hitIps, found, listener);
                }
                if (pe > dp) {
                    probe(pool, prefix, dp + 1, pe, results, hitIps, found, listener);
                }
            } catch (Throwable ignored) {
            } finally {
                pool.shutdownNow();
            }
            if (listener != null) {
                listener.onDone(found.get());
            }
        }, "lanclip-scan").start();
    }

    /** 并发探测某端口区间内的全部 /24 地址；仅在后台线程中调用。 */
    private static void probe(ExecutorService pool, String prefix, int ps, int pe,
                              final List<String> results, final Set<String> hitIps,
                              final AtomicInteger found, final Listener listener) {
        final int portCount = pe - ps + 1;
        if (portCount <= 0) {
            return;
        }
        final CountDownLatch latch = new CountDownLatch(254 * portCount);
        for (int i = 1; i <= 254; i++) {
            final String ip = prefix + "." + i;
            for (int p = ps; p <= pe; p++) {
                final int port = p;
                pool.execute(() -> {
                    Socket s = null;
                    try {
                        if (hitIps.contains(ip)) {
                            return;
                        }
                        s = new Socket();
                        s.connect(new InetSocketAddress(ip, port), TIMEOUT_MS);
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
                        if (s != null) {
                            try {
                                s.close();
                            } catch (Throwable ignored) {
                            }
                        }
                        latch.countDown();
                    }
                });
            }
        }
        try {
            // 254 地址 / 128 线程 * 300ms ≈ 1.2s，留足余量
            latch.await(30, TimeUnit.SECONDS);
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