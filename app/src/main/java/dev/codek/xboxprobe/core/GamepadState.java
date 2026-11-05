package dev.codek.xboxprobe.core;

/** Logical controls, independent of Android key codes and HID wire positions. */
public final class GamepadState {
    public static final int A = 1;
    public static final int B = 1 << 1;
    public static final int X = 1 << 2;
    public static final int Y = 1 << 3;
    public static final int L1 = 1 << 4;
    public static final int R1 = 1 << 5;
    public static final int SELECT = 1 << 6;
    public static final int START = 1 << 7;
    public static final int L3 = 1 << 8;
    public static final int R3 = 1 << 9;
    public static final int SUPPORTED_BUTTONS = 0x3ff;
    public static final GamepadState NEUTRAL = new GamepadState(0, 0, 0, 0, 0, 0, -1, 0);

    public final float leftX;
    public final float leftY;
    public final float rightX;
    public final float rightY;
    public final float leftTrigger;
    public final float rightTrigger;
    /** -1 is centered; 0..7 are north, northeast, east, ..., northwest. */
    public final int hat;
    public final int buttons;

    /** Axes use -1..1, with negative Y pointing up; triggers use 0..1. */
    public GamepadState(float leftX, float leftY, float rightX, float rightY,
                        float leftTrigger, float rightTrigger, int hat, int buttons) {
        this.leftX = clampFinite(leftX, -1, 1);
        this.leftY = clampFinite(leftY, -1, 1);
        this.rightX = clampFinite(rightX, -1, 1);
        this.rightY = clampFinite(rightY, -1, 1);
        this.leftTrigger = clampFinite(leftTrigger, 0, 1);
        this.rightTrigger = clampFinite(rightTrigger, 0, 1);
        if (hat < -1 || hat > 7) {
            throw new IllegalArgumentException("Hat must be -1 or 0..7");
        }
        if ((buttons & ~SUPPORTED_BUTTONS) != 0) {
            throw new IllegalArgumentException("Unsupported button bits: " + buttons);
        }
        this.hat = hat;
        this.buttons = buttons;
    }

    private static float clampFinite(float value, float min, float max) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            throw new IllegalArgumentException("Control value must be finite");
        }
        return Math.max(min, Math.min(max, value));
    }
}
