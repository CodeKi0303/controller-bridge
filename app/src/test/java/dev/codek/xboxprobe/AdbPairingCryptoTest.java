package dev.codek.xboxprobe;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.junit.Test;

public final class AdbPairingCryptoTest {
    @Test public void hkdfMatchesIndependentPythonHmacReference()throws Exception{
        byte[] material=new byte[64];for(int i=0;i<64;i++)material[i]=(byte)i;
        assertArrayEquals(hex("5e234c26fa41fb42e5d493b262c0def1"),AdbPairingCrypto.hkdf16(material));
    }
    private static byte[] hex(String value){byte[] b=new byte[value.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16);return b;}
    @Test public void publicKeyEncodingMatchesAndroidRsaStruct()throws Exception{
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);KeyPair pair=generator.generateKeyPair();
        RSAPublicKey key=(RSAPublicKey)pair.getPublic();byte[] encoded=AdbPublicKey.encode(key);assertEquals(524,encoded.length);
        ByteBuffer bytes=ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);assertEquals(64,bytes.getInt());
        int n0inv=bytes.getInt();byte[] modulus=new byte[256];bytes.get(modulus);byte[] big=modulus.clone();
        for(int i=0;i<128;i++){byte b=big[i];big[i]=big[255-i];big[255-i]=b;}
        assertEquals(key.getModulus(),new java.math.BigInteger(1,big));
        long low=ByteBuffer.wrap(modulus).order(ByteOrder.LITTLE_ENDIAN).getInt()&0xffffffffL;
        assertEquals(0xffffffffL,(low*(n0inv&0xffffffffL))&0xffffffffL);
        assertEquals(65537,ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN).getInt(520));
        byte[] rr=Arrays.copyOfRange(encoded,264,520);
        for(int i=0;i<128;i++){byte b=rr[i];rr[i]=rr[255-i];rr[255-i]=b;}
        assertEquals(java.math.BigInteger.ONE.shiftLeft(4096).mod(key.getModulus()),new java.math.BigInteger(1,rr));
        byte[] peer=AdbPublicKey.peerInfo(key);assertEquals(8192,peer.length);assertEquals(0,peer[0]);
        assertTrue(new String(peer,StandardCharsets.US_ASCII).contains(" ControllerBridge\0"));
    }
}
