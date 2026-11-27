package com.lanclip.sync;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

/**
 * mDNS 发现 Android 11+ 无线调试（adb-tls-connect / adb-tls-pairing）随机端口设备。
 * 盒子上开「无线调试」后会通过 mDNS 广播服务，这里据此拿到真实 ip:port。
 */
public final class AdbMdns {

    private static final String TAG = "LanClipMdns";
    private static final String SERVICE_TYPE = "_adb-tls-connect._tcp.";

    public interface Listener {
        void onFound(String endpoint);
        void onDone();
    }

    private AdbMdns() {
    }

    /**
     * 启动 mDNS 发现。约 3 秒后回调 onDone。
     */
    public static void discover(Context ctx, final Listener listener) {
        final NsdManager nsd;
        try {
            nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
        } catch (Throwable t) {
            if (listener != null) {
                listener.onDone();
            }
            return;
        }
        if (nsd == null) {
            if (listener != null) {
                listener.onDone();
            }
            return;
        }

        final NsdManager.DiscoveryListener dl = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.w(TAG, "start discovery failed " + errorCode);
                stop();
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
                Log.i(TAG, "discovery started");
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
            }

            @Override
            public void onServiceFound(NsdServiceInfo info) {
                try {
                    if (!info.getServiceType().startsWith("_adb-tls-connect")) {
                        return;
                    }
                    nsd.resolveService(info, new NsdManager.ResolveListener() {
                        @Override
                        public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                            Log.w(TAG, "resolve failed " + errorCode);
                        }

                        @Override
                        public void onServiceResolved(NsdServiceInfo resolved) {
                            try {
                                String host = resolved.getHost() == null
                                        ? null : resolved.getHost().getHostAddress();
                                int port = resolved.getPort();
                                if (host != null && port > 0 && listener != null) {
                                    listener.onFound(host + ":" + port);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                } catch (Throwable ignored) {
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
            }

            private void stop() {
                try {
                    nsd.stopServiceDiscovery(this);
                } catch (Throwable ignored) {
                }
                if (listener != null) {
                    listener.onDone();
                }
            }
        };

        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, dl);
        } catch (Throwable t) {
            Log.w(TAG, "discoverServices failed", t);
            if (listener != null) {
                listener.onDone();
            }
            return;
        }

        // 3 秒后停止
        new Thread(() -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignored) {
            }
            try {
                nsd.stopServiceDiscovery(dl);
            } catch (Throwable ignored) {
            }
            if (listener != null) {
                listener.onDone();
            }
        }, "lanclip-mdns").start();
    }
}