package dev.codek.xboxprobe;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

/** ADB RSA public-key encoding, adapted from libadb 3.1.1 AndroidPubkey
 * (Apache-2.0), which references AOSP libcrypto_utils/android_pubkey.cpp. */
final class AdbPublicKey {
    private AdbPublicKey() {}
    static byte[] encode(RSAPublicKey key) throws InvalidKeyException {
        if (key.getModulus().bitLength() != 2048 || !key.getPublicExponent().equals(BigInteger.valueOf(65537)))
            throw new InvalidKeyException("ADB requires the configured RSA-2048 public key");
        BigInteger modulus = key.getModulus(), r32 = BigInteger.ONE.shiftLeft(32);
        ByteBuffer out = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(64);
        out.putInt(r32.subtract(modulus.mod(r32).modInverse(r32)).intValue());
        out.put(littleEndian(modulus, 256));
        out.put(littleEndian(BigInteger.ONE.shiftLeft(4096).mod(modulus), 256));
        out.putInt(65537);
        return out.array();
    }
    static byte[] peerInfo(RSAPublicKey key) throws InvalidKeyException {
        byte[] encoded = (Base64.getEncoder().encodeToString(encode(key)) + " ControllerBridge\0")
                .getBytes(StandardCharsets.UTF_8);
        byte[] peer = new byte[8192]; // AOSP PeerInfo: type byte + zero-padded data.
        peer[0] = 0; // ADB_RSA_PUB_KEY
        System.arraycopy(encoded, 0, peer, 1, encoded.length);
        return peer;
    }
    private static byte[] littleEndian(BigInteger number, int length) {
        byte[] big = number.toByteArray(), result = new byte[length];
        for (int i = 0; i < length && i < big.length; i++) result[i] = big[big.length - i - 1];
        return result;
    }
}
