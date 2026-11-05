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

public final class AdbNativeCryptoTest {
    @org.junit.BeforeClass public static void requireNativeTestLibrary(){
        org.junit.Assume.assumeTrue("Build the host JNI test library and set -P adbNativeTestDir (see third_party/adb-crypto/README.md)",Boolean.getBoolean("bridge.nativeTests"));
    }
    @Test public void matchesIndependentDesktopEdwardsAndHkdfReference()throws Exception{
        byte[] password=hex("353932373831e63dd959651c211600f3b6561d0b9d90af09d0a4a453ee2059a480cc7c5a94d4d48933f9fff5fe43317d52fa7bff8f8bc4f3488b8007330fec7c7edc91c20e5d");
        byte[] a=hex("47f6c458e5f062db8427d2d9bb20c954a76d6943959756a18d11d45e1ad190f980a86d185a93ca1d3025c5febe3aac4045b34a39b1f511385ca97fc4332137f3");
        byte[] b=hex("a6bf9f9bf7819e0ded8c2dd82a1aa38acb2f8a6403429cff33d64ea9c40439d5fd7029811a5f5a8f7c89c8b44ac0b421f6b24ca2ba18d2069995831730cd8c5a");
        byte[] client="adb pair client\0".getBytes(StandardCharsets.UTF_8),server="adb pair server\0".getBytes(StandardCharsets.UTF_8);
        try(AdbSpake alice=new AdbSpake(true,client,server);AdbSpake bob=new AdbSpake(false,server,client)){
            byte[] am=alice.message(password,a),bm=bob.message(password,b);
            // Reproduce with scripts/adb-pairing-reference.py. These fixtures
            // are independently computed, not labelled as official AOSP vectors.
            assertArrayEquals(hex("135d85fa69022bbc7445653e19047e5b6981aa5b9d309b0de6d2e704dfe1568c"),am);
            assertArrayEquals(hex("d5bd4ead287e42f0a073adcb8dc46acc0630c4925fe1d43350e7441a8b29e03a"),bm);
            byte[] expected=hex("059427b29f1aeaa08b73dd99f385a704dcd4abcc00f573b02b74346834c584f8d5d44eeefcbbb13a444ca639626b1e62c37ee5f9640147da5d8a919d0a73cb5e");
            assertArrayEquals(expected,alice.finish(bm));assertArrayEquals(expected,bob.finish(am));
            assertArrayEquals(hex("ed5ea7746bb0c6ffb5978bde0a12821a"),AdbPairingCrypto.hkdf16(expected));
        }
    }
    private static byte[] hex(String value){byte[] b=new byte[value.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16);return b;}
    @Test public void matchingPairingSecretAuthenticatesBothPeerInfos()throws Exception{
        byte[] secret="123456tls-exporter-test-material".getBytes(StandardCharsets.UTF_8);
        try(AdbPairingCrypto client=new AdbPairingCrypto(secret,true);AdbPairingCrypto server=new AdbPairingCrypto(secret,false)){
            client.finish(server.message());server.finish(client.message());
            byte[] clientPeer=new byte[8192],serverPeer=new byte[8192];serverPeer[0]=1;clientPeer[32]=42;serverPeer[8]=12;
            assertArrayEquals(clientPeer,server.decrypt(client.encrypt(clientPeer)));
            assertArrayEquals(serverPeer,client.decrypt(server.encrypt(serverPeer)));
            assertArrayEquals(new byte[]{1,2},client.decrypt(server.encrypt(new byte[]{1,2})));
        }
    }
    @Test public void wrongPairingCodeOrExporterCannotAuthenticate()throws Exception{
        try(AdbPairingCrypto client=new AdbPairingCrypto(new byte[]{1,2,3},true);AdbPairingCrypto server=new AdbPairingCrypto(new byte[]{1,2,4},false)){
            client.finish(server.message());server.finish(client.message());
            assertThrows(GeneralSecurityException.class,()->client.decrypt(server.encrypt(new byte[8192])));
        }
    }
    @Test public void tamperedCiphertextAndReplayedNonceFailAuthentication()throws Exception{
        try(AdbPairingCrypto client=new AdbPairingCrypto(new byte[]{5,6},true);AdbPairingCrypto server=new AdbPairingCrypto(new byte[]{5,6},false)){
            client.finish(server.message());server.finish(client.message());
            byte[] ciphertext=server.encrypt(new byte[]{4,3,2,1});assertArrayEquals(new byte[]{4,3,2,1},client.decrypt(ciphertext));
            assertThrows(GeneralSecurityException.class,()->client.decrypt(ciphertext));
        }
        try(AdbPairingCrypto client=new AdbPairingCrypto(new byte[]{5,6},true);AdbPairingCrypto server=new AdbPairingCrypto(new byte[]{5,6},false)){
            client.finish(server.message());server.finish(client.message());byte[] bytes=server.encrypt(new byte[]{4,3,2,1});bytes[0]^=1;
            assertThrows(GeneralSecurityException.class,()->client.decrypt(bytes));
        }
    }
    @Test public void disposedOrRepeatedHandshakeStateCannotBeReused()throws Exception{
        AdbPairingCrypto closed=new AdbPairingCrypto(new byte[]{1},true);closed.close();assertThrows(IllegalStateException.class,closed::message);
        try(AdbPairingCrypto client=new AdbPairingCrypto(new byte[]{1},true)){
            assertThrows(GeneralSecurityException.class,()->client.finish(new byte[31]));
        }
    }
}
