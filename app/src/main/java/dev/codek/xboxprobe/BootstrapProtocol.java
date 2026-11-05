package dev.codek.xboxprobe;

import java.util.List;

/** Fixed entry point and validated arguments; never accepts caller-supplied executable code. */
final class BootstrapProtocol {
    static final String MAIN="dev.codek.xboxprobe.StandaloneHelperMain";
    static void validateNonce(String nonce) { if(nonce==null||!nonce.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid bootstrap capability"); }
    static void validateIdentity(int uid,String signer) {
        if(uid<10000||signer==null||!signer.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid installed application identity");
    }
    static List<String> arguments(String mode,int uid,String signer,String nonce) {
        if(!mode.equals("host")&&!mode.equals("session"))throw new IllegalArgumentException("Unknown helper role");
        validateIdentity(uid,signer);validateNonce(nonce);
        return List.of("/system/bin/app_process","/",MAIN,mode,Integer.toString(uid),signer,nonce);
    }
    static String command(String apk,int uid,String signer,String nonce) {
        if(apk==null||!apk.startsWith("/")||!apk.endsWith(".apk")||apk.indexOf('\0')>=0||apk.indexOf('\n')>=0||apk.indexOf('\r')>=0)
            throw new IllegalArgumentException("Invalid installed APK path");
        StringBuilder out=new StringBuilder("CLASSPATH=").append(quote(apk));
        for(String arg:arguments("host",uid,signer,nonce))out.append(' ').append(quote(arg));
        return out.append(" </dev/null >/dev/null 2>&1 &").toString();
    }
    private static String quote(String value) { return "'"+value.replace("'","'\\''")+"'"; }
}
