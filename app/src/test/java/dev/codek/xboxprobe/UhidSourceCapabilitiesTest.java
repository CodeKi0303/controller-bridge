package dev.codek.xboxprobe;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

/** Sanitized numeric getevent -i grammar; no device serials or Bluetooth addresses. */
public final class UhidSourceCapabilitiesTest {
    private static final String X = "    ABS (0003): 0000  : value 0, min -127, max 127, fuzz 0, flat 15, resolution 0\n";
    private static final String Y = "                0001  : value 0, min -127, max 127, fuzz 0, flat 15, resolution 0\n";
    private static final String G8 = "add device 2: /dev/input/event14\n"
            + "  bus:      0003\n  vendor    3537\n  product   0108\n  version   0111\n"
            + "  name:     \"GAMESIR GameSir-G8\"\n"
            + "  location: \"usb-fixture/input0\"\n  id:       \"fixture-id\"\n  version:  1.0.1\n"
            + "  events:\n"
            + "    KEY (0001): 0130  0131  0132  0133  0134  0135  0136  0137 \n"
            + "                0138  0139  013a  013b  013c  013d  013e \n"
            + X + Y
            + "                0002  : value 0, min -127, max 127, fuzz 0, flat 15, resolution 0\n"
            + "                0005  : value 0, min -127, max 127, fuzz 0, flat 15, resolution 0\n"
            + "                0009  : value 0, min 0, max 255, fuzz 0, flat 15, resolution 0\n"
            + "                000a  : value 0, min 0, max 255, fuzz 0, flat 15, resolution 0\n"
            + "                0010  : value 0, min -1, max 1, fuzz 0, flat 0, resolution 0\n"
            + "                0011  : value 0, min -1, max 1, fuzz 0, flat 0, resolution 0\n"
            + "    MSC (0004): 0004 \n  input props:\n    <none>\n";
    private static final String CONSUMER = "add device 1: /dev/input/event15\n"
            + "  bus:      0003\n  vendor    3537\n  product   0108\n  version   0111\n"
            + "  name:     \"GAMESIR GameSir-G8 Consumer Control\"\n"
            + "  location: \"usb-fixture/input0\"\n  id:       \"fixture-id\"\n  version:  1.0.1\n"
            + "  events:\n    KEY (0001): 0072  0073  0074 \n"
            + "    MSC (0004): 0004 \n  input props:\n    <none>\n";
    private static final String KEYBOARD = "add device 3: /dev/input/event13\n"
            + "  bus:      0003\n  vendor    0000\n  product   0000\n  version   0000\n"
            + "  name:     \"G8 Keyboard Probe\"\n"
            + "  location: \"\"\n  id:       \"\"\n  version:  1.0.1\n"
            + "  events:\n    KEY (0001): 0001  0002  0011  001e  001f  0020 \n"
            + "                0024  0025  0016  0039 \n"
            + "    MSC (0004): 0004 \n    LED (0011): 0000  0001  0002  0003  0004 \n"
            + "  input props:\n    <none>\n";

    @Test public void capturedG8NumericGrammarSelectsOnlyTheGamepadInterface() {
        List<UhidSourceCapabilities.Device> devices = UhidSourceCapabilities.parse(CONSUMER + G8 + KEYBOARD);
        assertEquals(3, devices.size());
        assertFalse(devices.get(0).isGamepadCandidate());
        assertTrue(devices.get(1).problem, devices.get(1).isGamepadCandidate());
        assertFalse(devices.get(2).isGamepadCandidate());
        assertEquals(1, devices.stream().filter(UhidSourceCapabilities.Device::isGamepadCandidate).count());
        UhidSourceCapabilities.Device g8 = devices.get(1);
        assertEquals("/dev/input/event14", g8.path);
        assertEquals("GAMESIR GameSir-G8", g8.name);
        assertEquals("usb-fixture/input0", g8.location);
        assertEquals("fixture-id", g8.uniqueId);
        assertEquals(Integer.valueOf(3), g8.bus);
        assertEquals(Integer.valueOf(0x3537), g8.vendorId);
        assertEquals(Integer.valueOf(0x0108), g8.productId);
        assertEquals(Integer.valueOf(0x0111), g8.version);
        assertEquals(15, g8.keyCodes.size());
        for (int code=0x130; code<=0x13e; code++) assertTrue(g8.keyCodes.contains(code));
        assertEquals(8, g8.axes.size());
        UhidSourceCapabilities.Axis x = g8.axes.get(0);
        assertEquals(-127, x.min); assertEquals(127, x.max); assertEquals(0, x.value);
        assertEquals(15, x.flat); assertEquals(0, x.fuzz); assertEquals(Integer.valueOf(0), x.resolution);
        assertEquals(255, g8.axes.get(9).max);
        assertEquals(-1, g8.axes.get(16).min);
        assertEquals(1, g8.axes.get(17).max);
    }

    @Test public void sameG8IdentityDoesNotMakeOurVirtualOutputASelectableSource() {
        UhidSourceCapabilities.Device physical = one(G8);
        UhidSourceCapabilities.Device generated = one(G8.replace("usb-fixture/input0",
                "g8-input-bridge:7a938c39-0598-4a99-914c-dab49976f57a"));
        assertEquals(physical.name, generated.name);
        assertEquals(physical.vendorId, generated.vendorId);
        assertEquals(physical.productId, generated.productId);
        assertEquals(physical.keyCodes, generated.keyCodes);
        // USB gamepad structure and eligibility as a bridge input are different facts.
        assertTrue(physical.problem, physical.isGamepadCandidate());
        assertTrue(generated.problem, generated.isGamepadCandidate());
        assertFalse(physical.isBridgeOutputLocation());
        assertTrue(physical.isSelectableSource());
        assertTrue(generated.isBridgeOutputLocation());
        assertFalse(generated.isSelectableSource());
    }

    @Test public void explicitEmptyLocationIsKnownWhileMissingLocationRemainsUnselectable() {
        UhidSourceCapabilities.Device missing = one(G8.replace(
                "  location: \"usb-fixture/input0\"\n", ""));
        UhidSourceCapabilities.Device empty = one(G8.replace("usb-fixture/input0", ""));
        assertNull(missing.location);
        assertEquals("", empty.location);
        assertTrue(missing.problem, missing.isGamepadCandidate());
        assertTrue(empty.problem, empty.isGamepadCandidate());
        assertFalse(missing.isBridgeOutputLocation());
        assertFalse(empty.isBridgeOutputLocation());
        assertFalse(missing.isSelectableSource());
        assertNotNull(missing.sourceInventoryProblem());
        assertTrue(empty.sourceSelectionProblem(), empty.isSelectableSource());
        assertNull(empty.sourceInventoryProblem());
        assertNotEquals(missing.capabilitySignature(), empty.capabilitySignature());
    }

    @Test public void bridgeOutputMarkerUsesTheLocationPrefixOnly() {
        UhidSourceCapabilities.Device embeddedText = one(G8.replace("usb-fixture/input0",
                "usb-fixture/g8-input-bridge:not-a-marker"));
        UhidSourceCapabilities.Device productText = one(G8.replace("GAMESIR GameSir-G8",
                "g8-input-bridge:product-label"));
        for (UhidSourceCapabilities.Device device : new UhidSourceCapabilities.Device[] {embeddedText, productText}) {
            assertTrue(device.problem, device.isGamepadCandidate());
            assertFalse(device.isBridgeOutputLocation());
            assertTrue(device.isSelectableSource());
        }
    }

    @Test public void missingFactsRemainUnknownInsteadOfBecomingZeroOrASelectableDevice() {
        UhidSourceCapabilities.Device missing = one(G8.replace("  vendor    3537\n", ""));
        assertNull(missing.vendorId);
        assertEquals(Integer.valueOf(0x0108), missing.productId);
        assertFalse(missing.isGamepadCandidate());
        assertNotNull(missing.sourceInventoryProblem());
        // Partial or unusual layouts are selected explicitly and validated by
        // source-role resolution; capability eligibility does not invent axes.
        assertTrue(one(G8.replace(Y, "")).isGamepadCandidate());
        assertNull(one(G8.replace(Y, "")).sourceInventoryProblem());
        UhidSourceCapabilities.Device noResolution = one(G8.replace(", resolution 0", ""));
        assertNull(noResolution.axes.get(0).resolution);
        assertTrue(noResolution.problem, noResolution.isGamepadCandidate());
        assertNotEquals(one(G8).capabilitySignature(), noResolution.capabilitySignature());
    }

    @Test public void partialGamepadFactsProduceUncertaintyWithoutNoisyNonGamepadWarnings() {
        for (String fixture : new String[]{G8,CONSUMER,KEYBOARD}) assertNull(one(fixture).sourceInventoryProblem());
        String broken = G8.replace("0130  0131", "0130  not-a-code 0131");
        UhidSourceCapabilities.Device partial = one(broken);
        assertFalse(partial.isSelectableSource());
        assertTrue(partial.keyCodes.contains(304));
        assertTrue(partial.keyCodes.contains(305));
        assertNotNull(partial.sourceInventoryProblem());
        assertNotNull(one(G8.replace("bus:      0003", "bus:      unknown")).sourceInventoryProblem());
        assertNotNull(one(G8.replace("  location: \"usb-fixture/input0\"\n", "")).sourceInventoryProblem());
        assertNull(one(broken.replace("usb-fixture/input0", "g8-input-bridge:fixture")).sourceInventoryProblem());
        assertNull(one(G8.replace("bus:      0003", "bus:      0006")).sourceInventoryProblem());
        assertTrue(one(G8.replace("0130  0131", "001e 0130 0131")).sourceInventoryProblem().contains("Mixed keyboard/gamepad"));
    }

    @Test public void keyboardAndConsumerCapabilitiesAreExcludedEvenWithConvincingNames() {
        assertFalse(one(CONSUMER.replace("GAMESIR GameSir-G8 Consumer Control", "GAMESIR GameSir-G8")).isGamepadCandidate());
        assertFalse(one(G8.replace("0130  0131", "001e  0130  0131")).isGamepadCandidate());
        // A display name cannot override the actual gamepad capabilities or
        // establish generated-output ownership. The physical location is kept.
        UhidSourceCapabilities.Device renamedPhysical = one(G8.replace("GAMESIR GameSir-G8", "G8 Keyboard Probe"));
        assertTrue(renamedPhysical.isGamepadCandidate());
        assertTrue(renamedPhysical.isSelectableSource());
        assertFalse(one(G8.replace("bus:      0003", "bus:      0006")).isGamepadCandidate());
    }

    @Test public void bluetoothTransportUsesTheSameCapabilityAndSourceIdentityGuards() {
        // Synthetic transport variant of the sanitized G8 grammar, not a capture of a Bluetooth model.
        String bluetooth = G8.replace("bus:      0003", "bus:      0005")
                .replace("GAMESIR GameSir-G8", "Gamepad fixture")
                .replace("usb-fixture/input0", "bluetooth-fixture/input0");
        UhidSourceCapabilities.Device source = one(bluetooth);
        assertEquals(Integer.valueOf(5), source.bus);
        assertTrue(source.problem, source.isGamepadCandidate());
        assertTrue(source.isSelectableSource());
        assertNotEquals(one(G8).capabilitySignature(), source.capabilitySignature());
        UhidSourceCapabilities.Device output = one(bluetooth.replace("bluetooth-fixture/input0", "g8-input-bridge:fixture"));
        assertTrue(output.isGamepadCandidate());
        assertTrue(output.isBridgeOutputLocation());
        assertFalse(output.isSelectableSource());
        assertTrue(one(bluetooth.replace("bluetooth-fixture/input0", "")).isSelectableSource());
        assertFalse(one(bluetooth.replace("  location: \"bluetooth-fixture/input0\"\n", "")).isSelectableSource());
        assertFalse(one(bluetooth.replace("  vendor    3537\n", "")).isSelectableSource());
        assertFalse(one(bluetooth.replace("0130  0131", "001e  0130  0131")).isSelectableSource());
        for (String bus : new String[]{"0000", "0006", "0018", "ffff"})
            assertFalse(one(bluetooth.replace("bus:      0005", "bus:      " + bus)).isSelectableSource());
    }

    @Test public void signedNumbersAndAbsentResolutionParseWithoutChangingCapabilityMeaning() {
        UhidSourceCapabilities.Device negative = one(G8.replace("value 0, min -127", "value -127, min -127"));
        assertTrue(negative.problem, negative.isGamepadCandidate());
        assertEquals(-127, negative.axes.get(0).value);
        assertEquals(one(G8).capabilitySignature(), negative.capabilitySignature());
        UhidSourceCapabilities.Device positive = one(G8.replace("value 0, min -127", "value 127, min -127"));
        assertEquals(one(G8).capabilitySignature(), positive.capabilitySignature());
    }

    @Test public void capabilitySignatureExcludesLiveValuesButIncludesIdentityAndRanges() {
        String original = one(G8).capabilitySignature();
        assertEquals(original, one(G8.replace("value 0", "value 1")).capabilitySignature());
        assertNotEquals(original, one(G8.replace("fixture-id", "different-fixture")).capabilitySignature());
        assertNotEquals(original, one(G8.replace("usb-fixture/input0", "usb-other/input0")).capabilitySignature());
        UhidSourceCapabilities.Device missingId = one(G8.replace("  id:       \"fixture-id\"\n", ""));
        UhidSourceCapabilities.Device emptyId = one(G8.replace("fixture-id", ""));
        assertNull(missingId.uniqueId);
        assertEquals("", emptyId.uniqueId);
        assertNotEquals(missingId.capabilitySignature(), emptyId.capabilitySignature());
        assertNotEquals(original, one(G8.replace("event14", "event20")).capabilitySignature());
        assertNotEquals(original, one(G8.replace("product   0108", "product   0109")).capabilitySignature());
        assertNotEquals(original, one(G8.replace("min -127", "min -128")).capabilitySignature());
        assertNotEquals(original, one(G8.replace("013d  013e", "013d  013e  013f")).capabilitySignature());
    }

    @Test public void repeatedIdentityKeysAxesAndNamesFailClosed() {
        assertRejected(G8.replace("  vendor    3537\n", "  vendor    3537\n  vendor    3537\n"));
        assertRejected(G8.replace("  bus:      0003\n", "  bus:      0003\n  bus:      0003\n"));
        assertRejected(G8.replace("  product   0108\n", "  product   0108\n  product   0108\n"));
        assertRejected(G8.replace("  version   0111\n", "  version   0111\n  version   0111\n"));
        assertRejected(G8.replace("  name:     \"GAMESIR GameSir-G8\"\n", "  name: \"first\"\n  name: \"second\"\n"));
        assertRejected(G8.replace("0130  0131", "0130  0130  0131"));
        assertRejected(G8.replace(X, X + X));
        assertRejected(G8.replace("  id:       \"fixture-id\"\n", "  id: \"first\"\n  id: \"second\"\n"));
        assertRejected(G8.replace("  location: \"usb-fixture/input0\"\n", "  location: malformed\n"));
        assertRejected(G8.replace("  location: \"usb-fixture/input0\"\n", "  location: \"\"\n  location: \"\"\n"));
        assertRejected(G8.replace("  location: \"usb-fixture/input0\"\n", "  location: malformed\n  location: \"\"\n"));
        assertThrows(IllegalArgumentException.class, () -> UhidSourceCapabilities.parse(G8 + G8));
    }

    @Test public void malformedNumbersAndKnownCapabilitySyntaxAreNotSilentlyIgnored() {
        assertRejected(G8.replace("0130  0131", "0130  nope"));
        assertRejected(G8.replace("0130  0131", "0300  0131"));
        assertRejected(G8.replace("value 0, min -127", "value 2147483648, min -127"));
        assertRejected(G8.replace("value 0, min -127", "value 128, min -127"));
        assertRejected(G8.replace("min -127, max 127", "min 127, max -127"));
        assertRejected(G8.replace("fuzz 0", "fuzz -1"));
        assertRejected(G8.replace("  vendor    3537\n", "  vendor    3537\n  vendor    BAD!\n"));
        assertRejected(G8.replace("  product   0108\n", "  product   0108\n  product   10000\n"));
    }

    @Test public void paddedSwitchAndForceFeedbackHeadersDoNotBecomeAbsContinuationLines() {
        UhidSourceCapabilities.Device device = one(G8.replace("    MSC (0004): 0004 \n",
                "    SW  (0005): 000f \n    FF  (0015): 0051  005a \n"));
        assertEquals("", device.problem);
        assertTrue(device.isGamepadCandidate());
        assertEquals(8, device.axes.size());
        assertEquals(15, device.keyCodes.size());
    }

    @Test public void malformedBlockDoesNotContaminateTheFollowingGoodDevice() {
        String bad = G8.replace("event14", "event99").replace("0130  0131", "0130  BAD!");
        List<UhidSourceCapabilities.Device> devices = UhidSourceCapabilities.parse(bad + G8);
        assertEquals(2, devices.size());
        assertFalse(devices.get(0).isGamepadCandidate());
        assertTrue(devices.get(1).problem, devices.get(1).isGamepadCandidate());
        assertTrue(UhidSourceCapabilities.parse(G8.replace("/dev/input/event14", "/dev/input/../other")).isEmpty());
    }

    @Test public void parsedCollectionsAreImmutableAndInventoryGrowthIsBounded() {
        List<UhidSourceCapabilities.Device> devices = UhidSourceCapabilities.parse(G8);
        assertThrows(UnsupportedOperationException.class, devices::clear);
        assertThrows(UnsupportedOperationException.class, () -> devices.get(0).keyCodes.add(1));
        assertThrows(UnsupportedOperationException.class, () -> devices.get(0).axes.clear());
        assertThrows(IllegalArgumentException.class, () -> UhidSourceCapabilities.parse(" ".repeat(262145)));
        assertThrows(IllegalArgumentException.class, () -> UhidSourceCapabilities.parse((String)null));
        StringBuilder many = new StringBuilder();
        for (int i=0; i<129; i++) many.append("add device ").append(i).append(": /dev/input/event").append(i).append('\n');
        assertThrows(IllegalArgumentException.class, () -> UhidSourceCapabilities.parse(many.toString()));
    }

    private static UhidSourceCapabilities.Device one(String fixture) {
        List<UhidSourceCapabilities.Device> devices = UhidSourceCapabilities.parse(fixture);
        assertEquals(1, devices.size());
        return devices.get(0);
    }

    @Test public void digitalGamepadAndJoystickButtonsAreEligibleButKeyboardMouseAndMixedNodesAreNot() {
        String base="add device 0: /dev/input/event8\nbus: 0005\nvendor 1234\nproduct 5678\nversion 0001\n"
                +"name: \"Digital fixture\"\nlocation: \"bluetooth-fixture/input0\"\nid: \"fixture\"\nevents:\nKEY (0001): ";
        for(String keys:new String[]{"0130 0138 0139 0220 0221 0222 0223","0120 0121 0122","02c0 02c1"}) {
            UhidSourceCapabilities.Device device=one(base+keys+"\n");
            assertTrue(device.sourceSelectionProblem(),device.isSelectableSource());
            assertTrue(device.axes.isEmpty());
        }
        for(String keys:new String[]{"001e 0020","0110 0111 0112","0072 0073"})assertFalse(one(base+keys+"\n").isSelectableSource());
        UhidSourceCapabilities.Device mixed=one(base+"0130 001e\n");
        assertFalse(mixed.isSelectableSource());assertTrue(mixed.sourceSelectionProblem().contains("Mixed keyboard/gamepad"));
        assertFalse(one(base+"0130\nABS (0003): 0000 : value 0, min 0, max 0, fuzz 0, flat 0\n").isSelectableSource());
        assertFalse(one(base+"0130\nABS (0003): 0000 : value 0, min -1, max 1, fuzz 0, flat 3\n").isSelectableSource());
    }
    private static void assertRejected(String fixture) {
        UhidSourceCapabilities.Device device = one(fixture);
        assertFalse(device.isGamepadCandidate());
        assertFalse("Malformed facts require a visible problem", device.problem.isEmpty());
    }
}
