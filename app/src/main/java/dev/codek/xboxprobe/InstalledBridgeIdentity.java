package dev.codek.xboxprobe;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;

final class InstalledBridgeIdentity {
    final int uid; final String signer,apk;
    private InstalledBridgeIdentity(int uid,String signer,String apk) { this.uid=uid;this.signer=signer;this.apk=apk; }
    static InstalledBridgeIdentity read(Context context) throws Exception {
        PackageInfo info=context.getPackageManager().getPackageInfo(BuildConfig.APPLICATION_ID,PackageManager.GET_SIGNING_CERTIFICATES);
        if(info.applicationInfo==null||info.signingInfo==null)throw new IOException("Installed bridge identity is unavailable");
        Signature[] signatures=info.signingInfo.getApkContentsSigners();
        if(signatures==null||signatures.length!=1)throw new IOException("Exactly one current APK signer is required");
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray());
        StringBuilder hex=new StringBuilder();for(byte b:digest)hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        return new InstalledBridgeIdentity(info.applicationInfo.uid,hex.toString(),new File(info.applicationInfo.sourceDir).getCanonicalPath());
    }
    void require(int expectedUid,String expectedSigner) {
        BootstrapProtocol.validateIdentity(expectedUid,expectedSigner);
        if(uid!=expectedUid||!MessageDigest.isEqual(signer.getBytes(java.nio.charset.StandardCharsets.US_ASCII),expectedSigner.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
            throw new SecurityException("Installed bridge UID or signing certificate changed");
    }
}
