package dev.codek.xboxprobe.core;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Single mapping between the native Linux/HID button domain and legacy Xbox logical bits. */
final class GamepadButtons {
    private static final int[] XBOX_CODES={304,305,307,308,310,311,314,315,317,318};
    private static final int[] XBOX_BITS={GamepadState.A,GamepadState.B,GamepadState.X,GamepadState.Y,
            GamepadState.L1,GamepadState.R1,GamepadState.SELECT,GamepadState.START,GamepadState.L3,GamepadState.R3};
    static final Set<Integer> XBOX_TARGETS;
    static final Set<Integer> NATIVE_TARGETS;
    static {
        LinkedHashSet<Integer> xbox=new LinkedHashSet<>(),nativeButtons=new LinkedHashSet<>();
        for(int code:XBOX_CODES)xbox.add(code);
        for(int code=304;code<=318;code++)nativeButtons.add(code);
        XBOX_TARGETS=Collections.unmodifiableSet(xbox);NATIVE_TARGETS=Collections.unmodifiableSet(nativeButtons);
    }
    private GamepadButtons(){}
    static int toXbox(int nativeMask) {
        int result=0,remaining=nativeMask;
        for(int i=0;i<XBOX_CODES.length;i++){int raw=1<<(XBOX_CODES[i]-304);if((nativeMask&raw)!=0){result|=XBOX_BITS[i];remaining&=~raw;}}
        if(remaining!=0)throw new IllegalArgumentException("Mapped buttons are not representable in the legacy Xbox report");
        return result;
    }
    static int fromXbox(int logicalMask) {
        if((logicalMask&~GamepadState.SUPPORTED_BUTTONS)!=0)throw new IllegalArgumentException("Unknown legacy Xbox button bits");
        int result=0;for(int i=0;i<XBOX_CODES.length;i++)if((logicalMask&XBOX_BITS[i])!=0)result|=1<<(XBOX_CODES[i]-304);return result;
    }
}
