package dev.codek.xboxprobe.core;

import java.util.Objects;

/** Encodes key state, never text, Android key codes, or screen coordinates. */
public final class HidKeyboardReportEncoder {
    public static final int REPORT_SIZE = 8;
    public static final int MAX_KEYS = 6;
    public static final int USAGE_A = 0x04;
    public static final int USAGE_D = 0x07;
    public static final int USAGE_J = 0x0d;
    public static final int USAGE_K = 0x0e;
    public static final int USAGE_S = 0x16;
    public static final int USAGE_U = 0x18;
    public static final int USAGE_W = 0x1a;
    public static final int USAGE_ENTER = 0x28;
    public static final int USAGE_ESCAPE = 0x29;
    public static final int USAGE_SPACE = 0x2c;

    private HidKeyboardReportEncoder() {}

    /** All-zero state releases all keys and modifiers. */
    public static byte[] neutral() { return new byte[REPORT_SIZE]; }

    public static byte[] reportForUsage(int usage) { return reportForUsages(usage); }

    /**
     * Accepts ordinary Keyboard/Keypad-page usages declared by this descriptor.
     * The caller owns its action allowlist. Reserved/error usages, modifiers and
     * more than six held keys fail explicitly rather than emitting a rollover or
     * silently dropping keys. Modifiers are intentionally always zero.
     */
    public static byte[] reportForUsages(int... usages) {
        Objects.requireNonNull(usages, "usages");
        if (usages.length > MAX_KEYS) throw new IllegalArgumentException("At most six held keys are supported");
        for (int i = 0; i < usages.length; i++) {
            if (usages[i] < 0x04 || usages[i] > 0x65) {
                throw new IllegalArgumentException("Keyboard usage must be 0x04..0x65: " + usages[i]);
            }
            for (int j = 0; j < i; j++) {
                if (usages[j] == usages[i]) throw new IllegalArgumentException("Duplicate keyboard usage: " + usages[i]);
            }
        }
        byte[] report = neutral();
        for (int i = 0; i < usages.length; i++) report[i + 2] = (byte) usages[i];
        return report;
    }
}
