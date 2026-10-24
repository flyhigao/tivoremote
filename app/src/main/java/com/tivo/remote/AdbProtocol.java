package com.tivo.remote;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.util.Base64;

public class AdbProtocol {
    private static final String TAG = "AdbProtocol";

    /**
     * 电视端“不参与中文联想/不转换标点”的备用输入法。
     *
     * 背景：adb shell input text 注入的是“硬件按键事件”，会被电视上正在使用的
     * 中文输入法（如 Gboard 拼音模式）当成拼音编码，导致：
     *   a -> 啊，  . -> 。   : -> ：   , -> ，   ? -> ？
     * 因此注入前先临时把默认输入法切到一个不联想的输入法，注入完成后再切回原输入法。
     */
    private static final String[] TEXT_SAFE_IMES = new String[] {
            // Google TV 语音输入法（TiVo Stream 4K / 多数 Android TV 上自带，实测最干净）
            "com.google.android.tts/com.google.android.apps.speech.tts.googletts.settings.asr.voiceime.VoiceInputMethodService",
            // 原生 AOSP 键盘（部分机顶盒自带，纯英文不联想）
            "com.android.inputmethod.latin/.LatinIME",
    };

    public static final int A_SYNC = 0x434e5953;
    public static final int A_CNXN = 0x4e584e43;
    public static final int A_OPEN = 0x4e45504f;
    public static final int A_OKAY = 0x59414b4f;
    public static final int A_CLSE = 0x45534c43;
    public static final int A_WRTE = 0x45545257;
    public static final int A_AUTH = 0x48545541;

    public static final int A_VERSION = 0x01000000;
    public static final int MAX_PAYLOAD = 4096;

    public static final int AUTH_TYPE_TOKEN = 1;
    public static final int AUTH_TYPE_SIGNATURE = 2;
    public static final int AUTH_TYPE_RSAPUBLICKEY = 3;

    public interface ConnectionListener {
        void onConnecting();
        void onConnected();
        void onAwaitingAuthorization();
        void onDisconnected(String reason);
    }

    public static class Message {
        public int command;
        public int arg0;
        public int arg1;
        public int dataLength;
        public int dataChecksum;
        public int magic;
        public byte[] payload;

        public static Message parse(InputStream is) throws Exception {
            byte[] header = new byte[24];
            int read = 0;
            while (read < 24) {
                int r = is.read(header, read, 24 - read);
                if (r < 0) throw new Exception("连接已关闭");
                read += r;
            }
            ByteBuffer bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            Message msg = new Message();
            msg.command = bb.getInt();
            msg.arg0 = bb.getInt();
            msg.arg1 = bb.getInt();
            msg.dataLength = bb.getInt();
            msg.dataChecksum = bb.getInt();
            msg.magic = bb.getInt();

            if ((msg.command ^ 0xFFFFFFFF) != msg.magic) {
                throw new Exception("消息校验错误: magic 不匹配");
            }

            if (msg.dataLength > 0) {
                msg.payload = new byte[msg.dataLength];
                int pRead = 0;
                while (pRead < msg.dataLength) {
                    int r = is.read(msg.payload, pRead, msg.dataLength - pRead);
                    if (r < 0) throw new Exception("连接在读取负载时关闭");
                    pRead += r;
                }
            } else {
                msg.payload = new byte[0];
            }
            return msg;
        }

        public void write(OutputStream os) throws Exception {
            ByteBuffer bb = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
            bb.putInt(command);
            bb.putInt(arg0);
            bb.putInt(arg1);
            bb.putInt(payload != null ? payload.length : 0);
            bb.putInt(calculateChecksum(payload));
            bb.putInt(command ^ 0xFFFFFFFF);

            synchronized (os) {
                os.write(bb.array());
                if (payload != null && payload.length > 0) {
                    os.write(payload);
                }
                os.flush();
            }
        }

        public static int calculateChecksum(byte[] payload) {
            if (payload == null) return 0;
            int sum = 0;
            for (byte b : payload) {
                sum += (b & 0xFF);
            }
            return sum;
        }

        public static Message create(int command, int arg0, int arg1, byte[] payload) {
            Message msg = new Message();
            msg.command = command;
            msg.arg0 = arg0;
            msg.arg1 = arg1;
            msg.payload = payload != null ? payload : new byte[0];
            msg.dataLength = msg.payload.length;
            msg.dataChecksum = calculateChecksum(msg.payload);
            msg.magic = command ^ 0xFFFFFFFF;
            return msg;
        }
    }

    private final AdbCrypto crypto;
    private final ConnectionListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    // Dedicated sender thread executor for non-blocking key event dispatch
    private final ExecutorService sendExecutor = Executors.newSingleThreadExecutor();
    private Thread connectionThread;

    private Socket socket;
    private OutputStream outputStream;
    private InputStream inputStream;
    private volatile boolean isConnected = false;
    /** 对端（电视）声明的单包最大长度，握手时从 A_CNXN 中读取 */
    private volatile int remoteMaxPayload = MAX_PAYLOAD;
    private int localIdCounter = 1;
    private String currentHost = "192.168.2.223";
    private int currentPort = 5555;

    public AdbProtocol(AdbCrypto crypto, ConnectionListener listener) {
        this.crypto = crypto;
        this.listener = listener;
    }

    public synchronized void connect(String host, int port) {
        this.currentHost = host;
        this.currentPort = port;

        if (connectionThread != null && connectionThread.isAlive()) {
            closeSocket();
            connectionThread.interrupt();
        }

        connectionThread = new Thread(this::runConnection, "AdbConnectionThread");
        connectionThread.start();
    }

    public synchronized void disconnect() {
        closeSocket();
        isConnected = false;
        if (connectionThread != null) {
            connectionThread.interrupt();
            connectionThread = null;
        }
        notifyDisconnected("已断开");
    }

    public boolean isConnected() {
        return isConnected && socket != null && !socket.isClosed();
    }

    public void sendKey(int keycode) {
        sendExecutor.execute(() -> {
            if (!isConnected() || outputStream == null) {
                Log.w(TAG, "Cannot send key: not connected");
                mainHandler.post(() -> listener.onDisconnected("未连接"));
                // Trigger auto-reconnect
                connect(currentHost, currentPort);
                return;
            }
            try {
                int localId = localIdCounter++;
                String cmd = "shell:input keyevent " + keycode + "\0";
                Message openMsg = Message.create(A_OPEN, localId, 0, cmd.getBytes());
                openMsg.write(outputStream);
                Log.d(TAG, "Sent keycode " + keycode + " successfully");
            } catch (Exception e) {
                Log.e(TAG, "Failed to send key", e);
                closeSocket();
                isConnected = false;
                notifyDisconnected("发送失败: " + e.getMessage());
            }
        });
    }

    /**
     * 手机打字直通电视输入框。
     *
     * 实现要点：
     * 1. 文本用 Base64 传输，彻底规避 shell 引号/空格/%s/&/# 等特殊字符问题；
     * 2. 注入前把电视默认输入法临时切到不联想的输入法，避免中文输入法把
     *    英文和 ASCII 标点转成中文（. -> 。, : -> ：）；
     * 3. 注入完自动切回电视原本的输入法（用户无感知）；
     * 4. 文本超过单包上限时按上限截断（防止 ADB 包被设备拒绝，整条命令失效）。
     *
     * @return 实际注入的字符数（< text.length() 表示因过长被截断）
     */
    public int sendText(String text) {
        final String clean = text.replace("\r", "").replace("\n", " ");

        // 先按单包上限估算可用长度：Base64 膨胀 4/3，带协议头链接还要额外发
        // “守卫空格 + 左移按键”，最坏情况约占 4.5 倍，这里统一按保守值算
        int budget = remoteMaxPayload > 2048 ? remoteMaxPayload : MAX_PAYLOAD;
        int maxChars = Math.max(64, (budget - 512) * 2 / 9);
        final String payload = clean.length() > maxChars ? clean.substring(0, maxChars) : clean;

        final String script = buildTextScript(payload);
        int sentChars = payload.length();

        sendExecutor.execute(() -> {
            if (!isConnected() || outputStream == null) {
                Log.w(TAG, "Cannot send text: not connected");
                connect(currentHost, currentPort);
                return;
            }
            try {
                int localId = localIdCounter++;
                String cmd = "shell:" + script + "\0";
                Message openMsg = Message.create(A_OPEN, localId, 0, cmd.getBytes(StandardCharsets.UTF_8));
                openMsg.write(outputStream);
                Log.d(TAG, "Sent text [" + payload + "] (" + cmd.length() + " bytes command)");
            } catch (Exception e) {
                Log.e(TAG, "Failed to send text", e);
            }
        });

        return sentChars;
    }

    /**
     * 组装在电视端执行的注入脚本（单条 shell 命令，保证顺序执行）。
     */
    private String buildTextScript(String text) {
        StringBuilder sb = new StringBuilder(256 + text.length() * 2);

        // 1. 记住电视当前输入法，并挑一个“不联想”的输入法
        sb.append("o=$(settings get secure default_input_method);a=;for c in ");
        for (String ime : TEXT_SAFE_IMES) {
            sb.append('\'').append(ime).append("' ");
        }
        sb.append("; do if [ \"$c\" != \"$o\" ] && ime list -s | grep -qxF \"$c\"; then a=\"$c\"; break; fi; done;");

        // 2. 切换输入法（切换后需要一点时间让新输入法绑定生效）
        sb.append("if [ -n \"$a\" ]; then ime set \"$a\" >/dev/null 2>&1; sleep 0.7; fi;");

        // 3. 注入文本：按 "%s" 切分，因为 input text 会把 %s 当成空格，
        //    只有拆成独立的 "%" 与 "s" 才能原样输入
        boolean urlLike = isUrlLike(text);
        if (urlLike) {
            // 影视 / TVBox 系的【配置地址】输入框会把开头的单个 a/f/h 自动补全成
            // assets:// file:// http://，先注入一个空格占位，让输入框全程不为单字符状态，
            // 注入完再把守卫空格删掉（光标左移 len 次后 DEL，正好只删掉守卫）
            appendInjectCall(sb, " ");
        }

        String[] parts = text.split("%s", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                appendInjectCall(sb, "%");
                appendInjectCall(sb, "s");
            }
            appendInjectCall(sb, parts[i]);
        }

        if (urlLike) {
            sb.append("input keyevent ");
            for (int i = 0; i < text.length(); i++) {
                sb.append("21 ");   // KEYCODE_DPAD_LEFT：光标左移，回到守卫空格右侧
            }
            sb.append(";sleep 0.3;input keyevent 67;");   // KEYCODE_DEL：删掉守卫空格
        }

        // 4. 还原电视原本的输入法
        sb.append("if [ -n \"$a\" ] && [ -n \"$o\" ]; then sleep 0.3; ime set \"$o\" >/dev/null 2>&1; fi");
        return sb.toString();
    }

    /** 形如 http:// https:// file:// clan:// 等带协议头的链接才需要防“首字母自动补全” */
    private boolean isUrlLike(String text) {
        if (text.length() < 5) {
            return false;
        }
        int colon = text.indexOf("://");
        if (colon < 1 || colon > 16) {
            return false;
        }
        for (int i = 0; i < colon; i++) {
            char c = text.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '+' || c == '.' || c == '-';
            if (!ok) {
                return false;
            }
        }
        // 纯 ASCII 才能保证“注入字符数 == 文本长度”，光标左移次数才不会算错
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 127) {
                return false;
            }
        }
        return true;
    }

    private void appendInjectCall(StringBuilder sb, String piece) {
        if (piece == null || piece.isEmpty()) {
            return;
        }
        String b64 = Base64.encodeToString(piece.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        sb.append("input text \"$(printf %s ").append(b64).append(" | base64 -d)\";");
    }

    private void runConnection() {
        closeSocket();
        isConnected = false;
        notifyConnecting();

        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(currentHost, currentPort), 4000);
            socket.setTcpNoDelay(true);
            outputStream = socket.getOutputStream();
            inputStream = socket.getInputStream();

            // 1. Send A_CNXN
            String banner = "host::TiVoRemoteApp\0";
            Message cnxn = Message.create(A_CNXN, A_VERSION, MAX_PAYLOAD, banner.getBytes());
            cnxn.write(outputStream);

            boolean authorized = false;
            boolean sentSignature = false;

            while (!authorized && !Thread.currentThread().isInterrupted()) {
                Message resp = Message.parse(inputStream);
                if (resp.command == A_CNXN) {
                    if (resp.arg1 > 0) {
                        remoteMaxPayload = resp.arg1;
                        Log.d(TAG, "Remote max payload: " + remoteMaxPayload);
                    }
                    authorized = true;
                    isConnected = true;
                    notifyConnected();
                    break;
                } else if (resp.command == A_AUTH) {
                    if (resp.arg0 == AUTH_TYPE_TOKEN) {
                        if (!sentSignature) {
                            try {
                                byte[] sig = crypto.signToken(resp.payload);
                                Message authSig = Message.create(A_AUTH, AUTH_TYPE_SIGNATURE, 0, sig);
                                authSig.write(outputStream);
                            } catch (Exception e) {
                                Log.e(TAG, "Signature failed", e);
                            }
                            sentSignature = true;
                        } else {
                            // If signature was rejected, send RSA public key
                            byte[] pubKey = crypto.getAdbPublicKeyBytes("phone@TiVoRemote");
                            Message authPub = Message.create(A_AUTH, AUTH_TYPE_RSAPUBLICKEY, 0, pubKey);
                            authPub.write(outputStream);
                            notifyAwaitingAuth();
                        }
                    }
                } else {
                    Log.w(TAG, "Unexpected command during handshake: " + Integer.toHexString(resp.command));
                }
            }

            // Continuous packet reader loop (handles A_OKAY, A_CLSE stream acks)
            while (isConnected && socket != null && !socket.isClosed() && !Thread.currentThread().isInterrupted()) {
                Message m = Message.parse(inputStream);
                if (m.command == A_CLSE) {
                    // Stream closed by remote side, acknowledge if needed
                    try {
                        Message ack = Message.create(A_CLSE, m.arg1, m.arg0, null);
                        ack.write(outputStream);
                    } catch (Exception ignored) {}
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Connection error", e);
            closeSocket();
            isConnected = false;
            notifyDisconnected(e.getMessage());
        }
    }

    private synchronized void closeSocket() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (Exception ignored) {}
        socket = null;
        outputStream = null;
        inputStream = null;
    }

    private void notifyConnecting() {
        mainHandler.post(listener::onConnecting);
    }

    private void notifyConnected() {
        mainHandler.post(listener::onConnected);
    }

    private void notifyAwaitingAuth() {
        mainHandler.post(listener::onAwaitingAuthorization);
    }

    private void notifyDisconnected(String reason) {
        mainHandler.post(() -> listener.onDisconnected(reason));
    }
}
