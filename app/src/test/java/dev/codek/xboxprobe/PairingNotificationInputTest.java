package dev.codek.xboxprobe;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class PairingNotificationInputTest {
    @Test public void preservesLeadingZeroCodeAndAcceptsOnlyBoundedAsciiSpacing() {
        try (PairingNotificationInput value = PairingNotificationInput.parse(" \t37123\t 001234 \t")) {
            assertEquals(37123, value.port);
            assertArrayEquals("001234".getBytes(StandardCharsets.US_ASCII), value.code);
        }
        try (PairingNotificationInput value = PairingNotificationInput.parse("1 000000")) {
            assertEquals(1, value.port);
        }
        try (PairingNotificationInput value = PairingNotificationInput.parse("65535 999999")) {
            assertEquals(65535, value.port);
        }
    }

    @Test public void invalidPortsUseExistingAdbValueBoundary() {
        for (String value : new String[]{"0 123456", "65536 123456", "99999 123456", "00001 123456", "123456 123456"})
            assertThrows(IllegalArgumentException.class, () -> PairingNotificationInput.parse(value));
    }

    @Test public void rejectsAddressesCommandsLinesAndNonAsciiInput() {
        for (String value : new String[]{"", " \t ", "37123", "37123 12345", "37123 1234567", "37123 12 456",
                "127.0.0.1:37123 123456", "adb pair 37123 123456", "37123:123456", "37123\n123456",
                "37123 123456\n", "\r37123 123456", "37123\u000b123456", "37123\u00a0123456",
                "３７１２３ 123456", "37123 １２３４５６", "37123 ١٢٣٤٥٦", "37123 12a456", "37123 123456 extra"})
            assertThrows(IllegalArgumentException.class, () -> PairingNotificationInput.parse(value));
        assertThrows(IllegalArgumentException.class, () -> PairingNotificationInput.parse(null));
    }

    @Test public void rejectsOversizeBeforeReadingOrCopyingAndNeverCallsInputToString() {
        CharSequence oversized = new CharSequence() {
            @Override public int length() { return 33; }
            @Override public char charAt(int i) { throw new AssertionError("Oversize input must not be read"); }
            @Override public CharSequence subSequence(int a, int b) { throw new AssertionError("No unchecked copies"); }
            @Override public String toString() { throw new AssertionError("No unchecked copies"); }
        };
        assertThrows(IllegalArgumentException.class, () -> PairingNotificationInput.parse(oversized));
        CharSequence bounded = new CharSequence() {
            private final String value = "37123 001234";
            @Override public int length() { return value.length(); }
            @Override public char charAt(int i) { return value.charAt(i); }
            @Override public CharSequence subSequence(int a, int b) { throw new AssertionError("No unchecked copies"); }
            @Override public String toString() { throw new AssertionError("No unchecked copies"); }
        };
        try (PairingNotificationInput value = PairingNotificationInput.parse(bounded)) {
            assertEquals(37123, value.port);
            assertArrayEquals("001234".getBytes(StandardCharsets.US_ASCII), value.code);
        }
    }

    @Test public void closingClearsOwnedCodeAndErrorsNeverEchoSubmittedInput() {
        PairingNotificationInput value = PairingNotificationInput.parse("37123 918273");
        byte[] owned = value.code;
        assertFalse(value.toString().contains("918273"));
        value.close(); value.close();
        assertArrayEquals(new byte[6], owned);
        String bad = "37123 918273; command";
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> PairingNotificationInput.parse(bad));
        assertFalse(error.getMessage().contains("918273"));
        assertFalse(error.getMessage().contains(bad));
    }
}
