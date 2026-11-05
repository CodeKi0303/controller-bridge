package dev.codek.xboxprobe;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/** One-use, expiring capabilities; caller identity is checked before consumption. */
final class BootstrapAuthorization<T> {
    private static final class Pending<T> {
        final T value; final long expires;
        Pending(T value, long expires) { this.value=value; this.expires=expires; }
    }
    private final Map<String,Pending<T>> pending=new HashMap<>();
    synchronized void add(String nonce,T value,long now,long ttl) {
        BootstrapProtocol.validateNonce(nonce);
        expire(now);
        if(ttl<=0||pending.size()>=8||pending.containsKey(nonce))throw new IllegalStateException("Bootstrap admission is unavailable");
        pending.put(nonce,new Pending<>(Objects.requireNonNull(value),Math.addExact(now,ttl)));
    }
    synchronized T consume(int callingUid,String nonce,long now) {
        requireUid(callingUid,2000);
        BootstrapProtocol.validateNonce(nonce);
        Pending<T> entry=pending.remove(nonce);
        return entry!=null&&now<entry.expires?entry.value:null;
    }
    synchronized void remove(String nonce) { pending.remove(nonce); }
    private void expire(long now) { Iterator<Pending<T>> entries=pending.values().iterator();while(entries.hasNext())if(now>=entries.next().expires)entries.remove(); }
    static void requireUid(int actual,int expected) {
        if(expected<0||actual!=expected)throw new SecurityException("Caller does not own this input session");
    }
}
