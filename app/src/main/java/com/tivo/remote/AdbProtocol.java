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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AdbProtocol {
    private static final String TAG = "AdbProtocol";

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
                if (r < 0) throw new Exception("Socket closed while reading header");
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
                throw new Exception("Invalid message magic: " + msg.magic);
            }

            if (msg.dataLength > 0) {
                msg.payload = new byte[msg.dataLength];
                int pRead = 0;
                while (pRead < msg.dataLength) {
                    int r = is.read(msg.payload, pRead, msg.dataLength - pRead);
                    if (r < 0) throw new Exception("Socket closed while reading payload");
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
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private Socket socket;
    private OutputStream outputStream;
    private InputStream inputStream;
    private volatile boolean isConnected = false;
    private int localIdCounter = 1;
    private String currentHost = "192.168.2.223";
    private int currentPort = 5555;

    public AdbProtocol(AdbCrypto crypto, ConnectionListener listener) {
        this.crypto = crypto;
        this.listener = listener;
    }

    public void connect(String host, int port) {
        this.currentHost = host;
        this.currentPort = port;
        executor.execute(this::doConnect);
    }

    public void disconnect() {
        executor.execute(() -> {
            closeSocket();
            isConnected = false;
            notifyDisconnected("已断开");
        });
    }

    public boolean isConnected() {
        return isConnected;
    }

    public void sendKey(int keycode) {
        executor.execute(() -> {
            if (!isConnected || outputStream == null) {
                Log.w(TAG, "Cannot send key, not connected");
                return;
            }
            try {
                int localId = localIdCounter++;
                String cmd = "shell:input keyevent " + keycode + "\0";
                Message openMsg = Message.create(A_OPEN, localId, 0, cmd.getBytes());
                openMsg.write(outputStream);
            } catch (Exception e) {
                Log.e(TAG, "Failed to send key", e);
                closeSocket();
                isConnected = false;
                notifyDisconnected("发送失败: " + e.getMessage());
            }
        });
    }

    private void doConnect() {
        closeSocket();
        notifyConnecting();

        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(currentHost, currentPort), 4000);
            socket.setTcpNoDelay(true);
            outputStream = socket.getOutputStream();
            inputStream = socket.getInputStream();

            // Send A_CNXN
            String banner = "host::TiVoRemoteApp\0";
            Message cnxn = Message.create(A_CNXN, A_VERSION, MAX_PAYLOAD, banner.getBytes());
            cnxn.write(outputStream);

            boolean authorized = false;
            boolean sentPublicKey = false;

            while (!authorized) {
                Message resp = Message.parse(inputStream);
                if (resp.command == A_CNXN) {
                    authorized = true;
                    isConnected = true;
                    notifyConnected();
                    break;
                } else if (resp.command == A_AUTH) {
                    if (resp.arg0 == AUTH_TYPE_TOKEN) {
                        if (!sentPublicKey) {
                            try {
                                byte[] sig = crypto.signToken(resp.payload);
                                Message authSig = Message.create(A_AUTH, AUTH_TYPE_SIGNATURE, 0, sig);
                                authSig.write(outputStream);
                            } catch (Exception e) {
                                Log.e(TAG, "Signature failed", e);
                            }
                            sentPublicKey = true;
                        } else {
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

            // Reader loop
            while (isConnected && socket != null && !socket.isClosed()) {
                Message.parse(inputStream);
            }

        } catch (Exception e) {
            Log.e(TAG, "Connection error", e);
            closeSocket();
            isConnected = false;
            notifyDisconnected(e.getMessage());
        }
    }

    private void closeSocket() {
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
