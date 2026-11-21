package com.lanclip.sync;

import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

public final class Util {

    private static final String TAG = "LanClipboard";

    public static final int HTTP_PORT = 9527;
    public static final int UDP_PORT = 9528;
    public static final String APP_ID = "LanClipboard";

    public static final String PREFS = "lanclip";
    public static final String ACTION_CLIP_RECEIVED = "com.lanclip.sync.CLIP_RECEIVED";
    public static final String ACTION_RECEIVER_STATE = "com.lanclip.sync.RECEIVER_STATE";
    public static final String ACTION_SENDER_STATE = "com.lanclip.sync.SENDER_STATE";

    private Util() {
    }

    /** FLAG_IMMUTABLE 是 API 23 才有的，Android 4.4 / 5.x 机顶盒上不能直接用。 */
    public static int pendingFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.FLAG_UPDATE_CURRENT;
    }

    public static String getClipText(Context ctx) {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) {
                return "";
            }
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                return "";
            }
            CharSequence text = clip.getItemAt(0).coerceToText(ctx);
            return text == null ? "" : text.toString();
        } catch (Throwable t) {
            Log.w(TAG, "read clipboard failed", t);
            return "";
        }
    }

    public static void setClipText(Context ctx, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("LanClipboard", text));
            }
        } catch (Throwable t) {
            Log.w(TAG, "write clipboard failed", t);
        }
    }

    /** 固定的暂存文件：剪切板.txt，每次接收都清空重写，不会堆叠。 */
    public static File clipFile(Context ctx) {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) {
            dir = ctx.getFilesDir();
        }
        if (dir != null && !dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return new File(dir, "剪切板.txt");
    }

    public static void writeClipFile(Context ctx, String text) {
        File file = clipFile(ctx);
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file, false), StandardCharsets.UTF_8)) {
            writer.write(text);
            writer.flush();
        } catch (Throwable t) {
            Log.w(TAG, "write clip file failed", t);
        }
    }

    public static String deviceName() {
        String name = (Build.MANUFACTURER + " " + Build.MODEL).trim();
        if (name.isEmpty() || "unknown".equalsIgnoreCase(name)) {
            name = "Android 设备";
        }
        return name.length() > 40 ? name.substring(0, 40) : name;
    }

    public static List<String> localIpv4() {
        List<String> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                String name = ni.getName() == null ? "" : ni.getName();
                if (name.startsWith("rmnet") || name.startsWith("p2p") || name.startsWith("tun")
                        || name.startsWith("dummy") || name.startsWith("v4-")) {
                    continue;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress addr = ia.getAddress();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        if (ip != null && !result.contains(ip)) {
                            result.add(ip);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "read interfaces failed", t);
        }
        return result;
    }

    public static InetAddress broadcastFor(String ip) throws Exception {
        String[] parts = ip.split("\\.");
        if (parts.length == 4) {
            return InetAddress.getByName(parts[0] + "." + parts[1] + "." + parts[2] + ".255");
        }
        return InetAddress.getByName("255.255.255.255");
    }

    public static boolean isPortOpen(String ip, int port, int timeoutMs) {
        Socket socket = new Socket();
        try {
            socket.connect(new java.net.InetSocketAddress(ip, port), timeoutMs);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 探测设备：null 表示端口不通；"" 表示端口开放但不是本软件；否则返回设备名。
     */
    public static String probeName(String ip, int port) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("http://" + ip + ":" + port + "/ping").openConnection();
            conn.setConnectTimeout(800);
            conn.setReadTimeout(900);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() != 200) {
                return "";
            }
            InputStream in = conn.getInputStream();
            byte[] buf = new byte[1024];
            int n = in.read(buf);
            String body = n <= 0 ? "" : new String(buf, 0, n, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(body);
            if (!APP_ID.equals(json.optString("app"))) {
                return "";
            }
            return json.optString("name");
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public static boolean postClip(String ip, int port, String text) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("http://" + ip + ":" + port + "/clip").openConnection();
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(3000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            byte[] data = text.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(data.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(data);
            }
            int code = conn.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Throwable t) {
            Log.w(TAG, "post clip failed", t);
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
