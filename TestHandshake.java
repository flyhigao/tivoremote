import java.io.*;
import java.net.*;
import java.nio.*;
import java.security.*;
import java.security.interfaces.*;
import java.math.BigInteger;
import javax.crypto.Cipher;

public class TestHandshake {
    public static void main(String[] args) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        RSAPrivateKey priv = (RSAPrivateKey) kp.getPrivate();
        RSAPublicKey pub = (RSAPublicKey) kp.getPublic();

        Socket socket = new Socket("192.168.2.223", 5555);
        socket.setTcpNoDelay(true);
        OutputStream os = socket.getOutputStream();
        InputStream is = socket.getInputStream();

        // 1. Send CNXN
        byte[] banner = "host::TestJava\0".getBytes();
        sendMsg(os, 0x4e584e43, 0x01000000, 4096, banner);

        // 2. Read first AUTH TOKEN
        Message m1 = readMsg(is);
        System.out.printf("M1: cmd=0x%08X, arg0=%d, len=%d\n", m1.cmd, m1.arg0, m1.len);

        // 3. Sign token
        byte[] digestInfo = new byte[]{
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        };
        byte[] payload = new byte[digestInfo.length + m1.payload.length];
        System.arraycopy(digestInfo, 0, payload, 0, digestInfo.length);
        System.arraycopy(m1.payload, 0, payload, digestInfo.length, m1.payload.length);

        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, priv);
        byte[] sig = cipher.doFinal(payload);

        // 4. Send signature
        sendMsg(os, 0x48545541, 2, 0, sig); // AUTH_TYPE_SIGNATURE

        // 5. Read response to signature
        Message m2 = readMsg(is);
        System.out.printf("M2: cmd=0x%08X, arg0=%d, len=%d\n", m2.cmd, m2.arg0, m2.len);

        if (m2.cmd == 0x48545541 && m2.arg0 == 1) { // Second AUTH TOKEN -> need public key
            byte[] pubKeyBytes = getAdbPublicKey(pub, "phone@test");
            System.out.println("Sending RSA Public Key (len=" + pubKeyBytes.length + ")...");
            sendMsg(os, 0x48545541, 3, 0, pubKeyBytes); // AUTH_TYPE_RSAPUBLICKEY

            System.out.println("Waiting for device prompt / authorization...");
            Message m3 = readMsg(is);
            System.out.printf("M3: cmd=0x%08X, arg0=%d, len=%d\n", m3.cmd, m3.arg0, m3.len);
            if (m3.cmd == 0x4e584e43) {
                System.out.println("AUTHORIZED! Device accepted key!");
            }
        }
        socket.close();
    }

    static class Message {
        int cmd, arg0, arg1, len, checksum, magic;
        byte[] payload;
    }

    private static Message readMsg(InputStream is) throws Exception {
        byte[] header = new byte[24];
        readFull(is, header);
        ByteBuffer bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.cmd = bb.getInt();
        m.arg0 = bb.getInt();
        m.arg1 = bb.getInt();
        m.len = bb.getInt();
        m.checksum = bb.getInt();
        m.magic = bb.getInt();
        if (m.len > 0) {
            m.payload = new byte[m.len];
            readFull(is, m.payload);
        } else {
            m.payload = new byte[0];
        }
        return m;
    }

    private static void sendMsg(OutputStream os, int cmd, int arg0, int arg1, byte[] payload) throws Exception {
        ByteBuffer bb = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(cmd);
        bb.putInt(arg0);
        bb.putInt(arg1);
        bb.putInt(payload != null ? payload.length : 0);
        int sum = 0;
        if (payload != null) for (byte b : payload) sum += (b & 0xFF);
        bb.putInt(sum);
        bb.putInt(cmd ^ 0xFFFFFFFF);
        os.write(bb.array());
        if (payload != null && payload.length > 0) os.write(payload);
        os.flush();
    }

    private static void readFull(InputStream is, byte[] b) throws Exception {
        int read = 0;
        while (read < b.length) {
            int r = is.read(b, read, b.length - read);
            if (r < 0) throw new EOFException();
            read += r;
        }
    }

    private static byte[] getAdbPublicKey(RSAPublicKey publicKey, String username) {
        BigInteger N = publicKey.getModulus();
        BigInteger E = publicKey.getPublicExponent();
        ByteBuffer bb = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(64);
        BigInteger b2_32 = BigInteger.valueOf(2).pow(32);
        BigInteger n0 = N.mod(b2_32);
        BigInteger n0inv = n0.modInverse(b2_32).negate().mod(b2_32);
        bb.putInt((int) n0inv.longValue());

        BigInteger temp = N;
        for (int i = 0; i < 64; i++) {
            bb.putInt((int) temp.mod(b2_32).longValue());
            temp = temp.shiftRight(32);
        }

        BigInteger R = BigInteger.valueOf(2).pow(2048);
        BigInteger RR = R.multiply(R).mod(N);
        temp = RR;
        for (int i = 0; i < 64; i++) {
            bb.putInt((int) temp.mod(b2_32).longValue());
            temp = temp.shiftRight(32);
        }
        bb.putInt(E.intValue());

        String b64 = java.util.Base64.getEncoder().encodeToString(bb.array());
        return (b64 + " " + username + "\0").getBytes();
    }
}
