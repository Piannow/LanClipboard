package com.lanclip.sync;

import android.util.Base64;
import android.util.Log;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * 极简 ADB 客户端（纯 Java，不依赖 adb 二进制 / Kadb）。
 * 直接以 ADB 智能 socket 协议连盒子 IP:5555，完成：
 *   - RSA 公钥认证（首次需盒子侧放行；多数机顶盒默认放行）
 *   - shell 命令（pm list packages / pm install 等）
 *   - push（通过 shell + base64 写入 /data/local/tmp，再 pm install）
 *
 * 说明：当前实现聚焦「已授权/默认放行」场景的安装流程；
 * 首次配对若盒子弹窗，需人工在盒子上点允许。
 */
public final class AdbClient {

    private static final String TAG = "LanClipAdb";
    private static final int A_SYNC = 0x434e5953;      // "SYNC"
    private static final int A_CNXN = 0x4e584843;      // "CNXN"
    private static final int A_AUTH = 0x48545541;      // "AUTH"
    private static final int A_OPEN = 0x4e45504f;      // "OPEN"
    private static final int A_OKAY = 0x59414b4f;      // "OKAY"
    private static final int A_CLSE = 0x45534c43;      // "CLSE"
    private static final int A_WRTE = 0x45545257;      // "WRTE"
    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;
    private static final int VERSION = 0x01000000;

    private static final int DEFAULT_TIMEOUT = 15000;

    private final String host;
    private final int port;
    private final android.content.Context ctx;
    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private int localId = 1;
    private String peerBanner;

    /** 保存/加载的 RSA 密钥对（可复用，避免每次重新配对） */
    private static volatile KeyPair persistentKey;

    public AdbClient(android.content.Context ctx, String host, int port) {
        this.ctx = ctx;
        this.host = host;
        this.port = port;
    }

    /** 是否已连接 */
    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    /**
     * 连接并完成 RSA 认证。
     * 成功后进入传输层（transport:host:...），后续可 open smart socket 执行 shell。
     */
    public void connect() throws IOException, GeneralSecurityException {
        socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(host, port), DEFAULT_TIMEOUT);
        socket.setSoTimeout(DEFAULT_TIMEOUT);
        in = new DataInputStream(socket.getInputStream());
        out = socket.getOutputStream();

        // 发送 CNXN（hello）—— 头部 24 字节，offset 12 必须是 payload 长度
        byte[] banner = "host::lanclipboard-updater".getBytes(StandardCharsets.UTF_8);
        byte[] cnxn = new byte[24 + banner.length];
        writeInt(cnxn, 0, A_CNXN);       // command
        writeInt(cnxn, 4, VERSION);      // arg0 = 协议版本
        writeInt(cnxn, 8, 4096);         // arg1 = max payload
        writeInt(cnxn, 12, banner.length); // data_length（关键！必须=payload 字节数）
        writeInt(cnxn, 16, 0);           // data_check（>=0x01000000 忽略）
        writeInt(cnxn, 20, 0);           // magic（>=0x01000000 忽略）
        System.arraycopy(banner, 0, cnxn, 24, banner.length);
        writeRaw(cnxn);

        // 读响应：可能 AUTH（需认证）或直接 A_CNXN（已授权）
        // AOSP 认证流程：
        //   1) adbd 发 AUTH(TOKEN)，我们用私钥签名发 AUTH(SIGNATURE)
        //   2) 若 key 未配对，签名不被接受，adbd 再发 AUTH(TOKEN)
        //      前 2 次尝试签名；仍不过就发 AUTH(RSAPUBLICKEY) 推送公钥触发配对
        //   3) 配对后 adbd 发 AUTH(TOKEN)，我们签名，adbd 回 CNXN
        int tokenCount = 0;
        boolean pubkeySent = false;
        while (true) {
            int cmd = readInt();
            int arg0 = readInt();
            int arg1 = readInt();
            int len = readInt();
            byte[] payload = len > 0 ? readFully(len) : new byte[0];

            if (cmd == A_AUTH) {
                if (tokenCount++ > 6) {
                    throw new IOException("ADB 认证失败（多次尝试后仍未通过）");
                }
                pubkeySent = handleAuth(arg0, payload, tokenCount, pubkeySent);
            } else if (cmd == A_CNXN) {
                peerBanner = new String(payload, StandardCharsets.UTF_8);
                break;
            } else if (cmd == A_CLSE) {
                throw new IOException("连接被对方关闭");
            } else {
                // 其他命令忽略，继续等 CNXN
            }
        }

        // 请求传输层（smart socket 建立在 transport 之上）
        // 这里走 transport 后会先收到 OKAY(peer stream 0)
        // 为简化，交给 open() 时统一处理
    }

    private boolean handleAuth(int authType, byte[] payload, int tokenCount, boolean pubkeySent) throws IOException, GeneralSecurityException {
        if (authType == AUTH_TOKEN) {
            // 前 2 次 token 尝试签名；若未通过且未推公钥，则推公钥触发配对；
            // 推过公钥后，后续 token 一律签名（配对成功后的确认签名）。
            boolean sendPubkey = !pubkeySent && tokenCount > 2;
            if (sendPubkey) {
                String pub = encodePublicKey(ensureKey().getPublic());
                writeRaw(buildAuth(AUTH_RSAPUBLICKEY, pub.getBytes(StandardCharsets.UTF_8)));
                return true;
            }
            Signature sig = Signature.getInstance("SHA1withRSA");
            sig.initSign(ensureKey().getPrivate());
            sig.update(payload);
            writeRaw(buildAuth(AUTH_SIGNATURE, sig.sign()));
        }
        return pubkeySent;
    }

    private KeyPair ensureKey() throws GeneralSecurityException {
        KeyPair kp = persistentKey;
        if (kp != null) {
            return kp;
        }
        // 尝试加载已保存的密钥；无则生成新的一对
        KeyPair saved = loadKey();
        if (saved != null) {
            persistentKey = saved;
            return saved;
        }
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        kp = gen.generateKeyPair();
        persistentKey = kp;
        saveKey(kp);
        return kp;
    }

    /** 密钥存到应用私有目录（避免外置存储权限问题）。 */
    private java.io.File keyDir() {
        return new java.io.File(ctx.getFilesDir(), ".lanclip_adb");
    }

    private KeyPair loadKey() {
        try {
            java.io.File dir = keyDir();
            java.io.File priv = new java.io.File(dir, "adbd_private_key");
            java.io.File pub = new java.io.File(dir, "adbd_public_key");
            if (!priv.exists() || !pub.exists()) {
                return null;
            }
            byte[] privDer = readAll(priv);
            byte[] pubDer = readAll(pub);
            PKCS8EncodedKeySpec pk = new PKCS8EncodedKeySpec(privDer);
            X509EncodedKeySpec xk = new X509EncodedKeySpec(pubDer);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return new KeyPair(kf.generatePublic(xk), kf.generatePrivate(pk));
        } catch (Throwable t) {
            Log.w(TAG, "load adb key failed", t);
            return null;
        }
    }

    private void saveKey(KeyPair kp) {
        try {
            java.io.File dir = keyDir();
            if (!dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            writeAll(new java.io.File(dir, "adbd_private_key"), kp.getPrivate().getEncoded());
            writeAll(new java.io.File(dir, "adbd_public_key"), kp.getPublic().getEncoded());
        } catch (Throwable t) {
            Log.w(TAG, "save adb key failed", t);
        }
    }

    private static byte[] readAll(java.io.File file) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        java.io.FileInputStream fis = new java.io.FileInputStream(file);
        byte[] buf = new byte[4096];
        int n;
        while ((n = fis.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        fis.close();
        return bos.toByteArray();
    }

    private static void writeAll(java.io.File file, byte[] data) throws java.io.IOException {
        java.io.FileOutputStream fos = new java.io.FileOutputStream(file, false);
        fos.write(data);
        fos.flush();
        fos.close();
    }

    private static byte[] buildAuth(int type, byte[] payload) {
        byte[] pkt = new byte[24 + payload.length];
        writeInt(pkt, 0, A_AUTH);
        writeInt(pkt, 4, type);
        writeInt(pkt, 8, 0);
        writeInt(pkt, 12, payload.length);
        System.arraycopy(payload, 0, pkt, 16, payload.length);
        return pkt;
    }

    /**
     * 把 RSA 公钥编码成 ADB 要求的 OpenSSH ssh-rsa 格式：
     *   base64( string("ssh-rsa") + string(e) + string(n) ) + " " + 注释
     * 注意：ADB 用 SSH 公钥格式（rsa_public_key / adb_auth_sign 需要），
     * 不能用 X.509 DER。用 BigInteger 取 e/n 后按 SSH wire 格式打包。
     */
    private static String encodePublicKey(PublicKey pub) throws GeneralSecurityException {
        byte[] n = ((java.security.interfaces.RSAPublicKey) pub).getModulus().toByteArray();
        byte[] e = ((java.security.interfaces.RSAPublicKey) pub).getPublicExponent().toByteArray();
        byte[] type = "ssh-rsa".getBytes(StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream blob = new java.io.ByteArrayOutputStream();
        writeLen(blob, type.length);
        writeBytes(blob, type);
        writeLen(blob, e.length);
        writeBytes(blob, e);
        writeLen(blob, n.length);
        writeBytes(blob, n);
        String b64 = Base64.encodeToString(blob.toByteArray(), Base64.NO_WRAP);
        return b64 + " lanclip@phone";
    }

    /** 把 4 字节大端长度写入 blob（OpenSSH wire 格式）。 */
    private static void writeLen(java.io.ByteArrayOutputStream bos, int len) {
        bos.write((len >>> 24) & 0xFF);
        bos.write((len >>> 16) & 0xFF);
        bos.write((len >>> 8) & 0xFF);
        bos.write(len & 0xFF);
    }

    private static void writeBytes(java.io.ByteArrayOutputStream bos, byte[] data) {
        bos.write(data, 0, data.length);
    }

    private static void writeInt(byte[] buf, int off, int value) {
        buf[off]     = (byte) (value & 0xFF);
        buf[off + 1] = (byte) ((value >>> 8) & 0xFF);
        buf[off + 2] = (byte) ((value >>> 16) & 0xFF);
        buf[off + 3] = (byte) ((value >>> 24) & 0xFF);
    }

    private void writeRaw(byte[] data) throws IOException {
        out.write(data);
        out.flush();
    }

    private int readInt() throws IOException {
        int b0 = in.read();
        int b1 = in.read();
        int b2 = in.read();
        int b3 = in.read();
        if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) {
            throw new EOFException("连接已关闭");
        }
        return (b0 & 0xFF) | ((b1 & 0xFF) << 8) | ((b2 & 0xFF) << 16) | ((b3 & 0xFF) << 24);
    }

    private byte[] readFully(int len) throws IOException {
        byte[] data = new byte[len];
        in.readFully(data);
        return data;
    }

    /**
     * 执行一条 shell 命令，返回 stdout（去尾换行）。
     * @param command 例如 "pm list packages -3"
     */
    public String shell(String command) throws IOException {
        // open transport smart socket: "shell:" + command
        int streamId = openTransport("shell:" + command);
        StringBuilder result = new StringBuilder();
        // 读 OKAY + 数据，直到 CLSE
        while (true) {
            int cmd = readInt();
            int arg0 = readInt();
            int arg1 = readInt();
            int len = readInt();
            byte[] payload = len > 0 ? readFully(len) : new byte[0];
            if (cmd == A_OKAY) {
                // 继续
            } else if (cmd == A_WRTE) {
                result.append(new String(payload, StandardCharsets.UTF_8));
                // 回 ACK
                writeRaw(buildAck(A_OKAY, arg1, arg0));
            } else if (cmd == A_CLSE) {
                break;
            } else {
                // 忽略
            }
        }
        return result.toString();
    }

    private int openTransport(String service) throws IOException {
        // 发送 OPEN（localId, remote=0, 本地 smart socket id 由我们定）
        int id = localId++;
        byte[] payload = service.getBytes(StandardCharsets.UTF_8);
        byte[] pkt = new byte[24 + payload.length];
        writeInt(pkt, 0, A_OPEN);
        writeInt(pkt, 4, id);
        writeInt(pkt, 8, 0);
        writeInt(pkt, 12, payload.length);
        System.arraycopy(payload, 0, pkt, 16, payload.length);
        writeRaw(pkt);

        // 等 OKAY
        while (true) {
            int cmd = readInt();
            int arg0 = readInt();
            int arg1 = readInt();
            int len = readInt();
            if (len > 0) {
                readFully(len);
            }
            if (cmd == A_OKAY) {
                // 这里 arg0 是 remote id，返回我们记录的 local
                return id;
            } else if (cmd == A_CLSE) {
                throw new IOException("OPEN 被拒绝");
            } else if (cmd == A_WRTE) {
                // 意外数据，跳过
            }
        }
    }

    private static byte[] buildAck(int cmd, int arg0, int arg1) {
        byte[] pkt = new byte[24];
        writeInt(pkt, 0, cmd);
        writeInt(pkt, 4, arg0);
        writeInt(pkt, 8, arg1);
        writeInt(pkt, 12, 0);
        return pkt;
    }

    /**
     * 检查盒子是否已安装指定包名。
     */
    public boolean isPackageInstalled(String packageName) throws IOException {
        String outS = shell("pm list packages " + packageName);
        return outS.contains("package:" + packageName);
    }

    /**
     * 通过 shell 写入 base64 再 pm install 的方式安装 APK。
     * 兼容 Android 4.x（老机顶盒）：先 push 到 /data/local/tmp，再 pm install。
     * @param apkBytes APK 完整字节
     */
    public String install(byte[] apkBytes, String packageName) throws IOException {
        String b64 = Base64.encodeToString(apkBytes, Base64.NO_WRAP);
        String tmp = "/data/local/tmp/" + (packageName == null ? "lanclip.apk" : packageName.replace('.', '_') + ".apk");
        // 分块写 base64 到 /data/local/tmp（避免单条 shell 过长）
        shell("rm -f " + tmp);
        int chunk = 8000;
        for (int i = 0; i < b64.length(); i += chunk) {
            int end = Math.min(b64.length(), i + chunk);
            String part = b64.substring(i, end);
            shell("echo -n " + part + " >> " + tmp + " 2>/dev/null || printf '"+part+"' >> " + tmp);
        }
        // base64 -d 解码
        shell("base64 -d " + tmp + " > " + tmp + ".apk 2>/dev/null || busybox base64 -d " + tmp + " > " + tmp + ".apk");
        String result = shell("pm install -r " + tmp + ".apk");
        return result;
    }

    public void close() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
        socket = null;
    }
}