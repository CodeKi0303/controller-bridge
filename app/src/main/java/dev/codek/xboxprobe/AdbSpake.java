package dev.codek.xboxprobe;

import java.security.SecureRandom;
import java.util.Arrays;

/** Bounded owner of the pinned BoringSSL-derived SPAKE2 native context. */
final class AdbSpake implements AutoCloseable {
    static { System.loadLibrary("bridgespake"); }
    static final int MESSAGE_SIZE=32;
    private long context;
    AdbSpake(boolean client,byte[] myName,byte[] theirName) {
        context=create(client,myName,theirName);
        if(context==0)throw new IllegalStateException("SPAKE2 context unavailable");
    }
    synchronized byte[] message(byte[] password) {
        byte[] entropy=new byte[64];new SecureRandom().nextBytes(entropy);
        try{return message(password,entropy);}finally{Arrays.fill(entropy,(byte)0);}
    }
    // Package-private deterministic entropy seam for pinned interoperability vectors.
    synchronized byte[] message(byte[] password,byte[] entropy) {
        requireOpen();
        if(password.length<1||password.length>1024||entropy.length!=64)throw new IllegalArgumentException("Invalid SPAKE2 input size");
        byte[] result=generate(context,password,entropy);
        if(result==null)throw new IllegalStateException("SPAKE2 message unavailable");
        return result;
    }
    synchronized byte[] finish(byte[] peer) {
        requireOpen();if(peer.length!=MESSAGE_SIZE)throw new IllegalArgumentException("Invalid SPAKE2 peer length");
        byte[] result=process(context,peer);
        if(result==null)throw new IllegalArgumentException("Invalid SPAKE2 peer message");
        return result;
    }
    private void requireOpen(){if(context==0)throw new IllegalStateException("SPAKE2 context closed");}
    @Override public synchronized void close(){if(context!=0){destroy(context);context=0;}}
    private static native long create(boolean client,byte[] myName,byte[] theirName);
    private static native byte[] generate(long context,byte[] password,byte[] entropy);
    private static native byte[] process(long context,byte[] peer);
    private static native void destroy(long context);
}
