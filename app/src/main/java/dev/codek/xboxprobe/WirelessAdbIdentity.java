package dev.codek.xboxprobe;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.security.interfaces.RSAPublicKey;
import javax.crypto.Cipher;
import javax.security.auth.x500.X500Principal;

/** The ADB client identity stays in Android Keystore; no private-key file or backup. */
final class WirelessAdbIdentity {
    private static final String ALIAS = "controller_bridge_wireless_adb_v2";
    final PrivateKey privateKey;
    final X509Certificate certificate;
    private WirelessAdbIdentity(PrivateKey key, X509Certificate certificate) {
        privateKey = key; this.certificate = certificate;
    }
    static synchronized WirelessAdbIdentity loadOrCreate() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(2048)
                    // Conscrypt 2.8 performs TLS 1.3 RSA-PSS encoding itself and
                    // delegates the raw private operation to RSA/ECB/NoPadding.
                    // Android Keystore maps private ENCRYPT to SIGN + DIGEST_NONE.
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                    .setCertificateSubject(new X500Principal("CN=Controller Bridge"))
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateNotBefore(new Date(0))
                    .setCertificateNotAfter(new Date(4102444800000L))
                    .build());
            generator.generateKeyPair();
        }
        KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) store.getEntry(ALIAS, null);
        if (entry == null || !(entry.getCertificate() instanceof X509Certificate))
            throw new IllegalStateException("ADB identity could not be loaded from Android Keystore");
        verifyTlsPrivateOperation(entry);
        return new WirelessAdbIdentity(entry.getPrivateKey(), (X509Certificate) entry.getCertificate());
    }
    private static void verifyTlsPrivateOperation(KeyStore.PrivateKeyEntry entry) throws Exception {
        // Exercise exactly the opaque-key primitive used by Conscrypt, with a
        // public, non-secret test block. Fail before pairing if an OEM rejects it.
        byte[] block = new byte[256]; block[255] = 2;
        Cipher signer = Cipher.getInstance("RSA/ECB/NoPadding");
        signer.init(Cipher.ENCRYPT_MODE, entry.getPrivateKey());
        byte[] signed = signer.doFinal(block);
        RSAPublicKey publicKey=(RSAPublicKey)entry.getCertificate().getPublicKey();
        if (!new BigInteger(1,signed).modPow(publicKey.getPublicExponent(),publicKey.getModulus()).equals(BigInteger.valueOf(2)))
            throw new IllegalStateException("Android Keystore RSA operation failed");
    }
    static synchronized void forget() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS);
    }
}
