package dev.codek.xboxprobe;

import java.util.Arrays;

/** Bounded notification reply: ASCII port, spaces/tabs, then a six-digit code.
 * The caller owns the returned code and must close it after use. No reply text
 * is retained, formatted into an error, or exposed through toString(). */
final class PairingNotificationInput implements AutoCloseable {
    private static final int MAX_CHARS = 32;
    final int port;
    final byte[] code;

    private PairingNotificationInput(int port, byte[] code) {
        this.port = port;
        this.code = code;
    }

    static PairingNotificationInput parse(CharSequence input) {
        if (input == null) throw malformed();
        int length = input.length();
        if (length == 0 || length > MAX_CHARS) throw malformed();
        // Inspect before making strings. Even an unusual CharSequence cannot
        // force an unbounded toString()/subSequence() copy through this parser.
        for (int i = 0; i < length; i++) {
            char value = input.charAt(i);
            if (!digit(value) && !space(value)) throw malformed();
        }
        int start = 0, end = length;
        while (start < end && space(input.charAt(start))) start++;
        while (end > start && space(input.charAt(end - 1))) end--;
        int portEnd = start;
        while (portEnd < end && digit(input.charAt(portEnd))) portEnd++;
        if (portEnd == start || portEnd - start > 5 || portEnd == end) throw malformed();
        int codeStart = portEnd;
        while (codeStart < end && space(input.charAt(codeStart))) codeStart++;
        if (end - codeStart != 6) throw malformed();
        for (int i = codeStart; i < end; i++) if (!digit(input.charAt(i))) throw malformed();

        // WirelessAdbClient remains the owner of port/code value validation.
        int port = WirelessAdbClient.port(copy(input, start, portEnd));
        byte[] code = WirelessAdbClient.pairingCode(copy(input, codeStart, end));
        return new PairingNotificationInput(port, code);
    }

    private static boolean digit(char value) { return value >= '0' && value <= '9'; }
    private static boolean space(char value) { return value == ' ' || value == '\t'; }
    private static String copy(CharSequence input, int start, int end) {
        char[] result = new char[end - start];
        for (int i = start; i < end; i++) result[i - start] = input.charAt(i);
        try { return new String(result); }
        finally { Arrays.fill(result, '\0'); }
    }
    private static IllegalArgumentException malformed() {
        return new IllegalArgumentException("페어링 포트와 6자리 코드를 띄어 써 주세요. 예: 37123 001234");
    }
    @Override public void close() { Arrays.fill(code, (byte) 0); }
}
