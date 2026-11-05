package dev.codek.xboxprobe;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** A fresh, single hardware press must finish before a mapping can be committed. */
final class ButtonCaptureGesture {
    enum Kind { NONE, PRESSED, CAPTURED, MULTIPLE, RETRY, UNAVAILABLE, EXCLUDED }
    static final class Result {
        final Kind kind;
        final int scanCode, keyCode;
        Result(Kind kind, int scanCode, int keyCode) {
            this.kind=kind;this.scanCode=scanCode;this.keyCode=keyCode;
        }
    }
    private static final Result NONE=new Result(Kind.NONE,0,0);
    private final Set<Integer> held=new HashSet<>();
    private final Set<Integer> excluded;
    private boolean armed, rejected;
    private long armedAt, pressTime;
    private int candidate, candidateKey;

    ButtonCaptureGesture(){this(Collections.emptySet());}
    ButtonCaptureGesture(Set<Integer> excludedRawCodes){
        Objects.requireNonNull(excludedRawCodes,"excluded raw codes");
        Set<Integer> copy=new HashSet<>();
        for(Integer code:excludedRawCodes){
            if(code==null||code<1||code>767)throw new IllegalArgumentException("Excluded source button must be 1..767");
            copy.add(code);
        }
        excluded=Collections.unmodifiableSet(copy);
    }

    void arm(long now) { cancel();armed=true;armedAt=now; }
    void cancel() { armed=false;rejected=false;candidate=0;candidateKey=0;held.clear(); }
    boolean isArmed() { return armed; }

    Result event(boolean down, int scanCode, int keyCode, int repeat,
                 long downTime, boolean cancelled) {
        if(!armed || downTime<armedAt) return NONE;
        if(scanCode<1 || scanCode>767) { cancel();return new Result(Kind.UNAVAILABLE,0,0); }
        if(excluded.contains(scanCode)){cancel();return new Result(Kind.EXCLUDED,0,0);}
        if(down) {
            if(cancelled){cancel();return new Result(Kind.RETRY,0,0);}
            if(repeat!=0 || !held.add(scanCode)) return NONE;
            if(rejected || held.size()>1) {
                rejected=true;candidate=0;candidateKey=0;
                return new Result(Kind.MULTIPLE,0,0);
            }
            candidate=scanCode;candidateKey=keyCode;pressTime=downTime;
            return new Result(Kind.PRESSED,scanCode,keyCode);
        }
        if(!held.remove(scanCode)) return NONE;
        if(rejected) {
            if(!held.isEmpty())return NONE;
            rejected=false;
            return new Result(Kind.RETRY,0,0);
        }
        if(candidate!=scanCode || cancelled || downTime!=pressTime || keyCode!=candidateKey) {
            candidate=0;candidateKey=0;
            return new Result(Kind.RETRY,0,0);
        }
        Result result=new Result(Kind.CAPTURED,candidate,candidateKey);
        cancel();return result;
    }
}
