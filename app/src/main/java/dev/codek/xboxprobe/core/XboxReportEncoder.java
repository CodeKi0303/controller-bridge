package dev.codek.xboxprobe.core;

import java.util.Objects;

/**
 * Xbox One S legacy Bluetooth input report ID 1, including its report-ID byte.
 * Packet semantics corroborated by SDL release-2.30.9:
 * https://github.com/libsdl-org/SDL/blob/release-2.30.9/src/joystick/hidapi/SDL_hidapi_xboxone.c
 * (HandleButtons16, firmware 3.1.1221). This is not an Xbox USB/GIP packet.
 */
public final class XboxReportEncoder {
    public static final int REPORT_ID = 1;
    public static final int REPORT_SIZE = 16;

    private XboxReportEncoder() {}

    public static byte[] encode(GamepadState state) {
        Objects.requireNonNull(state, "state");
        byte[] result = new byte[REPORT_SIZE];
        result[0] = REPORT_ID;
        put16(result, 1, axis(state.leftX));
        put16(result, 3, axis(state.leftY));
        put16(result, 5, axis(state.rightX));
        put16(result, 7, axis(state.rightY));
        put16(result, 9, Math.round(state.leftTrigger * 1023));
        put16(result, 11, Math.round(state.rightTrigger * 1023));
        result[13] = (byte) (state.hat + 1);
        put16(result, 14, state.buttons);
        return result;
    }

    private static int axis(float value) {
        // Piecewise scaling makes the exact neutral 0x8000 and both extrema exact.
        return value < 0 ? Math.round(32768 + value * 32768)
                         : Math.round(32768 + value * 32767);
    }

    private static void put16(byte[] buffer, int offset, int value) {
        buffer[offset] = (byte) value;
        buffer[offset + 1] = (byte) (value >>> 8);
    }
}
