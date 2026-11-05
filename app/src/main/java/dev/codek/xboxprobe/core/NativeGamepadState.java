package dev.codek.xboxprobe.core;

import java.util.Objects;

/**
 * Canonical control state before an output layout encodes it. Button bits use the
 * meanings of Linux BTN_GAMEPAD 304..318, NOT GamepadState's ten logical bits or
 * the output identity's wire usages. NativeGamepadLayout owns that conversion.
 */
public final class NativeGamepadState {
    public static final int SUPPORTED_BUTTONS = 0x7fff;
    public static final NativeGamepadState NEUTRAL = new NativeGamepadState(0,0,0,0,0,0,-1,0);
    public final int leftX, leftY, rightX, rightY, leftTrigger, rightTrigger, hat, buttons;

    /** Hat -1 is neutral; 0..7 are north, northeast, east, ..., northwest. */
    public NativeGamepadState(int leftX, int leftY, int rightX, int rightY,
                              int leftTrigger, int rightTrigger, int hat, int buttons) {
        requireRange(leftX,-127,127,"leftX");
        requireRange(leftY,-127,127,"leftY");
        requireRange(rightX,-127,127,"rightX");
        requireRange(rightY,-127,127,"rightY");
        requireRange(leftTrigger,0,255,"leftTrigger");
        requireRange(rightTrigger,0,255,"rightTrigger");
        requireRange(hat,-1,7,"hat");
        if ((buttons & ~SUPPORTED_BUTTONS) != 0) throw new IllegalArgumentException("Unsupported native button bits");
        this.leftX=leftX; this.leftY=leftY; this.rightX=rightX; this.rightY=rightY;
        this.leftTrigger=leftTrigger; this.rightTrigger=rightTrigger; this.hat=hat; this.buttons=buttons;
    }

    /** Convert logical controls to canonical native units and semantic button bits. */
    public static NativeGamepadState fromGamepadState(GamepadState state) {
        Objects.requireNonNull(state, "state");
        return new NativeGamepadState(Math.round(state.leftX * 127), Math.round(state.leftY * 127),
                Math.round(state.rightX * 127), Math.round(state.rightY * 127),
                Math.round(state.leftTrigger * 255), Math.round(state.rightTrigger * 255),
                state.hat, GamepadButtons.fromXbox(state.buttons));
    }

    private static void requireRange(int value,int min,int max,String field) {
        if(value<min || value>max) throw new IllegalArgumentException(field+" must be "+min+".."+max);
    }
}
