package com.tivo.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

public class AdbCrypto {
    private static final String TAG = "AdbCrypto";
    private static final String PREF_NAME = "adb_crypto_prefs";
    private static final String KEY_PRIV = "priv_key";
    private static final String KEY_PUB = "pub_key";

    public static final int KEY_LENGTH_BITS = 2048;
    public static final int KEY_LENGTH_WORDS = KEY_LENGTH_BITS / 32; // 64

    private final RSAPrivateKey privateKey;
    private final RSAPublicKey publicKey;

    public AdbCrypto(RSAPrivateKey privateKey, RSAPublicKey publicKey) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
    }

    public static AdbCrypto loadOrGenerate(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String privStr = prefs.getString(KEY_PRIV, null);
        String pubStr = prefs.getString(KEY_PUB, null);

        if (privStr != null && pubStr != null) {
            try {
                KeyFactory kf = KeyFactory.getInstance("RSA");
                byte[] privBytes = Base64.decode(privStr, Base64.DEFAULT);
                byte[] pubBytes = Base64.decode(pubStr, Base64.DEFAULT);
                RSAPrivateKey priv = (RSAPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(privBytes));
                RSAPublicKey pub = (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(pubBytes));
                return new AdbCrypto(priv, pub);
            } catch (Exception e) {
                Log.e(TAG, "Failed to load stored keys, generating new...", e);
            }
        }

        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(KEY_LENGTH_BITS);
            KeyPair kp = kpg.generateKeyPair();
            RSAPrivateKey priv = (RSAPrivateKey) kp.getPrivate();
            RSAPublicKey pub = (RSAPublicKey) kp.getPublic();

            prefs.edit()
                    .putString(KEY_PRIV, Base64.encodeToString(priv.getEncoded(), Base64.DEFAULT))
                    .putString(KEY_PUB, Base64.encodeToString(pub.getEncoded(), Base64.DEFAULT))
                    .apply();

            return new AdbCrypto(priv, pub);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate RSA key pair", e);
        }
    }

    public byte[] signToken(byte[] token) throws Exception {
        // DigestInfo header for SHA-1 in PKCS#1 v1.5
        byte[] digestInfo = new byte[]{
                0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        };
        byte[] payload = new byte[digestInfo.length + token.length];
        System.arraycopy(digestInfo, 0, payload, 0, digestInfo.length);
        System.arraycopy(token, 0, payload, digestInfo.length, token.length);

        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, privateKey);
        return cipher.doFinal(payload);
    }

    public byte[] getAdbPublicKeyBytes(String username) {
        BigInteger N = publicKey.getModulus();
        BigInteger E = publicKey.getPublicExponent();

        // struct RSAPublicKey {
        //     uint32_t len; // 64
        //     uint32_t n0inv; // -1/N[0] mod 2^32
        //     uint32_t n[64]; // little endian words
        //     uint32_t rr[64]; // (2^2048)^2 mod N
        //     uint32_t exponent; // 65537
        // };
        ByteBuffer bb = ByteBuffer.allocate(524);
        bb.order(ByteOrder.LITTLE_ENDIAN);

        bb.putInt(KEY_LENGTH_WORDS);

        // n0inv = -1 / N[0] mod 2^32
        BigInteger b2_32 = BigInteger.valueOf(2).pow(32);
        BigInteger n0 = N.mod(b2_32);
        BigInteger n0inv = n0.modInverse(b2_32).negate().mod(b2_32);
        bb.putInt((int) n0inv.longValue());

        // n words
        writeBigIntegerToLittleEndianWords(bb, N, KEY_LENGTH_WORDS);

        // rr = (2^2048)^2 mod N
        BigInteger R = BigInteger.valueOf(2).pow(KEY_LENGTH_BITS);
        BigInteger RR = R.multiply(R).mod(N);
        writeBigIntegerToLittleEndianWords(bb, RR, KEY_LENGTH_WORDS);

        // exponent
        bb.putInt(E.intValue());

        String b64Key = Base64.encodeToString(bb.array(), Base64.NO_WRAP);
        String fullKeyStr = b64Key + " " + username + "\0";
        return fullKeyStr.getBytes();
    }

    private static void writeBigIntegerToLittleEndianWords(ByteBuffer bb, BigInteger val, int numWords) {
        BigInteger b2_32 = BigInteger.valueOf(2).pow(32);
        BigInteger temp = val;
        for (int i = 0; i < numWords; i++) {
            BigInteger word = temp.mod(b2_32);
            bb.putInt((int) word.longValue());
            temp = temp.shiftRight(32);
        }
    }
}
