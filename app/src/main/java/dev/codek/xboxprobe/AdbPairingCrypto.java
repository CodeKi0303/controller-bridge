package dev.codek.xboxprobe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** ADB PAKE envelope, adapted from libadb 3.1.1 PairingAuthCtx (Apache-2.0).
 * Protocol constants follow AOSP pairing_auth. JCA supplies HKDF and AES-GCM;
 * SPAKE2 is the retained upstream implementation, not a new curve implementation.
 */
final class AdbPairingCrypto implements AutoCloseable {
    private static final byte[] CLIENT = utf8("adb pair client\0"), SERVER = utf8("adb pair server\0");
    private static final byte[] INFO = utf8("adb pairing_auth aes-128-gcm key");
    private final AdbSpake spake;
    private final byte[] message;
    private byte[] key;
    private long encryptionCounter, decryptionCounter;
    private boolean closed;

    AdbPairingCrypto(byte[] password, boolean client) {
        spake = new AdbSpake(client,
                client ? CLIENT : SERVER, client ? SERVER : CLIENT);
        try { message = spake.message(password); }
        catch(RuntimeException | Error error){spake.close();throw error;}
    }
    byte[] message() { requireOpen(); return message.clone(); }
    void finish(byte[] peerMessage) throws GeneralSecurityException {
        requireOpen();
        if (key != null || peerMessage.length != AdbSpake.MESSAGE_SIZE)
            throw new GeneralSecurityException("Invalid pairing exchange state");
        byte[] material = spake.finish(peerMessage);
        try { key = hkdf16(material); }
        finally { Arrays.fill(material, (byte) 0); }
    }
    byte[] encrypt(byte[] plain) throws GeneralSecurityException { return crypt(true, plain, encryptionCounter++); }
    byte[] decrypt(byte[] encrypted) throws GeneralSecurityException { return crypt(false, encrypted, decryptionCounter++); }
    private byte[] crypt(boolean encrypt, byte[] input, long counter) throws GeneralSecurityException {
        requireOpen();
        if (key == null || counter < 0) throw new GeneralSecurityException("Pairing cipher unavailable");
        byte[] iv = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(counter).array();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
                new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        return cipher.doFinal(input);
    }
    static byte[] hkdf16(byte[] material) throws GeneralSecurityException {
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
        byte[] prk = hmac.doFinal(material);
        try {
            hmac.init(new SecretKeySpec(prk, "HmacSHA256"));
            hmac.update(INFO); hmac.update((byte) 1);
            byte[] expanded = hmac.doFinal();
            try { return Arrays.copyOf(expanded, 16); }
            finally { Arrays.fill(expanded, (byte) 0); }
        } finally { Arrays.fill(prk, (byte) 0); }
    }
    private void requireOpen() { if (closed) throw new IllegalStateException("Pairing exchange closed"); }
    @Override public void close() {
        closed = true;
        Arrays.fill(message, (byte) 0);
        if (key != null) Arrays.fill(key, (byte) 0);
        spake.close();
    }
    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
