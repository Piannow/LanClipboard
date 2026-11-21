package com.lanclip.sync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 机顶盒端：HTTP 服务接收手机发来的文字，写入固定文件 剪切板.txt，
 * 再（可选）由无障碍服务悄悄全选复制。
 */
public class ReceiverService extends Service {

    private static final String TAG = "LanClipReceiver";
    private static final String CHANNEL_ID = "lanclip_receiver";
    private static final int NOTIFICATION_ID = 1001;

    public static volatile boolean running = false;

    private ServerSocket serverSocket;
    private DatagramSocket udpSocket;
    private ExecutorService pool;
    private Thread acceptThread;
    private Thread udpThread;
    private final Object clipLock = new Object();
    private volatile String lastFrom = "";

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification("正在启动…"));
        getSharedPreferences(Util.PREFS, MODE_PRIVATE).edit()
                .putBoolean("receiver_running", true).apply();
        startServer();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            getSharedPreferences(Util.PREFS, MODE_PRIVATE).edit()
                    .putBoolean("receiver_running", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) {
            startServer();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (udpSocket != null) {
                udpSocket.close();
            }
        } catch (Throwable ignored) {
        }
        if (pool != null) {
            pool.shutdownNow();
        }
        serverSocket = null;
        udpSocket = null;
        stopForeground(true);
        broadcastState();
        super.onDestroy();
    }

    private void startServer() {
        if (running) {
            return;
        }
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(Util.HTTP_PORT));
        } catch (IOException e) {
            Log.e(TAG, "bind HTTP port failed", e);
            updateNotification("端口 " + Util.HTTP_PORT + " 被占用，接收服务未启动");
            broadcastState();
            return;
        }

        running = true;
        pool = Executors.newCachedThreadPool();

        acceptThread = new Thread(() -> {
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    pool.execute(() -> handleConnection(socket));
                } catch (Throwable t) {
                    if (running) {
                        Log.w(TAG, "accept failed", t);
                    }
                }
            }
        }, "lanclip-http");
        acceptThread.start();

        try {
            udpSocket = new DatagramSocket(null);
            udpSocket.setReuseAddress(true);
            udpSocket.bind(new InetSocketAddress(Util.UDP_PORT));
        } catch (Throwable t) {
            Log.w(TAG, "bind UDP port failed", t);
            udpSocket = null;
        }
        if (udpSocket != null) {
            udpThread = new Thread(this::udpLoop, "lanclip-udp");
            udpThread.start();
        }

        List<String> ips = Util.localIpv4();
        updateNotification("运行中 · " + (ips.isEmpty() ? "无局域网地址" : ips.get(0) + ":" + Util.HTTP_PORT));
        broadcastState();
    }

    private void udpLoop() {
        byte[] buffer = new byte[1024];
        while (running) {
            DatagramSocket socket = udpSocket;
            if (socket == null || socket.isClosed()) {
                return;
            }
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String message = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                if (!message.startsWith("LANCLIP_DISCOVER")) {
                    continue;
                }
                JSONObject json = new JSONObject();
                json.put("app", Util.APP_ID);
                json.put("name", Util.deviceName());
                json.put("port", Util.HTTP_PORT);
                List<String> ips = Util.localIpv4();
                json.put("ip", ips.isEmpty() ? "" : ips.get(0));
                byte[] reply = json.toString().getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(reply, reply.length, packet.getAddress(), packet.getPort()));
            } catch (Throwable t) {
                if (running) {
                    Log.w(TAG, "udp loop", t);
                }
            }
        }
    }

    private void handleConnection(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(6000);
            BufferedInputStream in = new BufferedInputStream(s.getInputStream());
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String path = parts.length > 1 ? parts[1] : "/";

            int contentLength = 0;
            String contentType = "";
            String header;
            while ((header = readLine(in)) != null && !header.isEmpty()) {
                int idx = header.indexOf(':');
                if (idx <= 0) {
                    continue;
                }
                String key = header.substring(0, idx).trim().toLowerCase();
                String value = header.substring(idx + 1).trim();
                if ("content-length".equals(key)) {
                    try {
                        contentLength = Integer.parseInt(value);
                    } catch (NumberFormatException ignored) {
                    }
                } else if ("content-type".equals(key)) {
                    contentType = value.toLowerCase();
                }
            }

            byte[] body = new byte[0];
            if (contentLength > 0) {
                int limit = Math.min(contentLength, 2 * 1024 * 1024);
                body = new byte[limit];
                int offset = 0;
                while (offset < limit) {
                    int read = in.read(body, offset, limit - offset);
                    if (read < 0) {
                        break;
                    }
                    offset += read;
                }
                if (offset < limit) {
                    body = Arrays.copyOf(body, offset);
                }
            }

            if (path.startsWith("/ping")) {
                JSONObject json = new JSONObject();
                json.put("app", Util.APP_ID);
                json.put("name", Util.deviceName());
                json.put("port", Util.HTTP_PORT);
                json.put("ver", 1);
                send(s, 200, "application/json; charset=utf-8",
                        json.toString().getBytes(StandardCharsets.UTF_8));
            } else if ("POST".equals(method) && path.startsWith("/clip")) {
                String text = new String(body, StandardCharsets.UTF_8);
                if (contentType.contains("x-www-form-urlencoded")) {
                    text = extractFormText(text);
                }
                handleClip(text, s.getInetAddress() == null ? "?" : s.getInetAddress().getHostAddress());
                send(s, 200, "text/plain; charset=utf-8",
                        "OK".getBytes(StandardCharsets.UTF_8));
            } else if (path.startsWith("/status")) {
                SharedPreferences sp = getSharedPreferences(Util.PREFS, MODE_PRIVATE);
                JSONObject json = new JSONObject();
                json.put("running", running);
                json.put("count", sp.getInt("count", 0));
                json.put("lastFrom", lastFrom);
                json.put("lastLength", sp.getString("last_text", "").length());
                send(s, 200, "application/json; charset=utf-8",
                        json.toString().getBytes(StandardCharsets.UTF_8));
            } else if ("GET".equals(method) && (path.equals("/") || path.startsWith("/index"))) {
                send(s, 200, "text/html; charset=utf-8",
                        buildPage().getBytes(StandardCharsets.UTF_8));
            } else {
                send(s, 404, "text/plain; charset=utf-8",
                        "Not Found".getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            Log.w(TAG, "handle connection failed", t);
        }
    }

    private static String extractFormText(String form) {
        for (String pair : form.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0 && "text".equals(pair.substring(0, idx))) {
                try {
                    return URLDecoder.decode(pair.substring(idx + 1), "UTF-8");
                } catch (Throwable ignored) {
                    return "";
                }
            }
        }
        return "";
    }

    private void handleClip(String text, String from) {
        if (text == null) {
            text = "";
        }
        synchronized (clipLock) {
            lastFrom = from;
            // 1. 固定文件覆盖写入（清空旧内容，不堆叠）
            Util.writeClipFile(this, text);
            // 2. 先直接写系统剪切板；API 23 以下的老旧机顶盒不启动无障碍桥接。
            SharedPreferences prefs = getSharedPreferences(Util.PREFS, MODE_PRIVATE);
            int count = prefs.getInt("count", 0) + 1;
            prefs.edit()
                    .putString("last_text", text)
                    .putLong("last_time", System.currentTimeMillis())
                    .putInt("count", count)
                    .apply();
            updateNotification("已接收 " + count + " 条 · 最新来自 " + from);
            // 3. 无障碍全选复制失败或不可用时，仍保留直接写入的剪切板。
            boolean copied = prefs.getBoolean("accessibility_copy", true)
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && launchCopyBridge(text);
            if (!copied) {
                Util.setClipText(this, text);
            }
            Intent intent = new Intent(Util.ACTION_CLIP_RECEIVED).setPackage(getPackageName());
            intent.putExtra("from", from);
            intent.putExtra("len", text.length());
            intent.putExtra("text", text);
            sendBroadcast(intent);
        }
    }

    private boolean launchCopyBridge(String text) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || !isAccessibilityEnabled()) {
            return false;
        }
        try {
            Intent intent = new Intent(this, CopyBridgeActivity.class);
            intent.putExtra("text", text);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            startActivity(intent);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "launch copy bridge failed", t);
            return false;
        }
    }

    private boolean isAccessibilityEnabled() {
        try {
            if (ClipAccessibilityService.isConnected()) {
                return true;
            }
            String enabled = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) {
                return false;
            }
            return enabled.contains(ClipAccessibilityService.class.getName())
                    || enabled.contains("com.lanclip.sync/.ClipAccessibilityService");
        } catch (Throwable t) {
            return false;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                buffer.write(c);
            }
            if (buffer.size() > 8192) {
                break;
            }
        }
        if (c == -1 && buffer.size() == 0) {
            return null;
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void send(Socket socket, int code, String type, byte[] body) throws IOException {
        OutputStream out = socket.getOutputStream();
        String status = code == 200 ? "200 OK" : "404 Not Found";
        String head = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private static String buildPage() {
        return "<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>局域网剪切板</title><style>"
                + "body{margin:0;padding:24px;background:#0b0e12;color:#e6edf3;font-family:system-ui,sans-serif}"
                + "h2{font-size:20px;margin:0 0 6px}p{color:#8b98a8;font-size:13px}"
                + "textarea{width:100%;height:180px;margin-top:14px;padding:12px;box-sizing:border-box;"
                + "background:#161c24;border:1px solid #26313d;border-radius:10px;color:#e6edf3;font-size:16px;resize:vertical}"
                + "button{width:100%;margin-top:14px;padding:14px;border:0;border-radius:10px;"
                + "background:#4ade80;color:#052e16;font-size:17px;font-weight:bold}"
                + "button:disabled{opacity:.6}#s{margin-top:12px;color:#4ade80;font-size:14px}"
                + "</style></head><body><h2>发送到机顶盒剪切板</h2>"
                + "<p>粘贴文字后点击发送，机顶盒会自动复制。</p>"
                + "<textarea id=\"t\" placeholder=\"在这里粘贴或输入文字…\"></textarea>"
                + "<button id=\"b\" onclick=\"send()\">发送</button><p id=\"s\"></p><script>"
                + "async function send(){const t=document.getElementById('t').value;const b=document.getElementById('b');"
                + "const s=document.getElementById('s');if(!t){s.textContent='内容为空';return;}"
                + "b.disabled=true;s.textContent='发送中…';try{const r=await fetch('/clip',{method:'POST',"
                + "headers:{'Content-Type':'text/plain;charset=utf-8'},body:t});"
                + "s.textContent=r.ok?'已发送 ✓':'发送失败';}catch(e){s.textContent='发送失败：'+e;}b.disabled=false;}"
                + "</script></body></html>";
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                        "剪切板接收服务", NotificationManager.IMPORTANCE_LOW);
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        Intent open = new Intent(this, ReceiverActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, Util.pendingFlags());
        return builder
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("局域网剪切板 · 接收端")
                .setContentText(text)
                .setContentIntent(pending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIFICATION_ID, buildNotification(text));
            }
        } catch (Throwable ignored) {
        }
    }

    private void broadcastState() {
        Intent intent = new Intent(Util.ACTION_RECEIVER_STATE).setPackage(getPackageName());
        intent.putExtra("running", running);
        sendBroadcast(intent);
    }
}
