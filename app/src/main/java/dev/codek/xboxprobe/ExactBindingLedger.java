package dev.codek.xboxprobe;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/** Identity and death observations are owned by each exact returned binding object. */
final class ExactBindingLedger<K,V> {
    enum Kind { PRIMARY, DUPLICATE, ADDITIONAL }
    static final class Admission<V> {
        final Kind kind;final V value;
        Admission(Kind kind,V value){this.kind=kind;this.value=value;}
    }
    private static final class Entry<V> {
        final V value;boolean dead;
        Entry(V value){this.value=value;}
    }
    private final IdentityHashMap<K,Entry<V>> entries=new IdentityHashMap<>();
    synchronized Admission<V> admit(K identity,V value){
        if(identity==null||value==null)throw new IllegalArgumentException("Binding identity/value is required");
        Entry<V> existing=entries.get(identity);
        if(existing!=null)return new Admission<>(Kind.DUPLICATE,existing.value);
        Kind kind=entries.isEmpty()?Kind.PRIMARY:Kind.ADDITIONAL;
        entries.put(identity,new Entry<>(value));return new Admission<>(kind,value);
    }
    synchronized boolean markDead(K identity){Entry<V> e=entries.get(identity);if(e==null)return false;e.dead=true;return true;}
    synchronized boolean isDead(K identity){Entry<V> e=entries.get(identity);return e!=null&&e.dead;}
    synchronized boolean allDead(){if(entries.isEmpty())return false;for(Entry<V> e:entries.values())if(!e.dead)return false;return true;}
    synchronized List<V> snapshot(){List<V> copy=new ArrayList<>();for(Entry<V> e:entries.values())copy.add(e.value);return copy;}
}
