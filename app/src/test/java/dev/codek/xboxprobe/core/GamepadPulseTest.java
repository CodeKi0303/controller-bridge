package dev.codek.xboxprobe.core;

import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public final class GamepadPulseTest {
    private static final NativeGamepadProfile PROFILE = NativeGamepadProfile.KISHI_ULTRA_IDENTITY;

    @Test public void correctedNameChangesOnlyIdentityAndPreservesPreviousProbeIds() {
        assertSame(NativeGamepadProfile.G8_IDENTITY, NativeGamepadProfile.forProbeId(0));
        assertSame(NativeGamepadProfile.KISHI_IDENTITY, NativeGamepadProfile.forProbeId(1));
        assertSame(PROFILE, NativeGamepadProfile.forProbeId(2));
        for (int id = 0; id < 3; id++) assertEquals(id, NativeGamepadProfile.forProbeId(id).getProbeId());
        assertEquals("Razer Kishi Ultra", NativeGamepadProfile.forProbeId(1).getName());
        assertEquals("Razer Razer Kishi Ultra", PROFILE.getName());
        assertArrayEquals(NativeGamepadProfile.KISHI_IDENTITY.getDescriptor(), PROFILE.getDescriptor());
        assertArrayEquals(NativeGamepadProfile.KISHI_IDENTITY.neutralReport(), PROFILE.neutralReport());
        byte[] create = UhidEventBuilder.createGamepad(PROFILE, "diagnostic-test");
        byte[] name = PROFILE.getName().getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(name, Arrays.copyOfRange(create, 4, 4 + name.length));
        assertEquals(0, create[4 + name.length]);
        ByteBuffer fields = ByteBuffer.wrap(create).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(3, fields.getShort(262));
        assertEquals(0x1532, fields.getInt(264));
        assertEquals(0x071a, fields.getInt(268));
        for (int id : new int[]{-1, 3, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> NativeGamepadProfile.forProbeId(id));
        assertThrows(IllegalStateException.class, () -> PROFILE.withIdentity("Custom", 1, 2, 3).getProbeId());
    }

    @Test public void stablePulseIdsProduceOneIntendedHidControlAndFiniteHold() {
        // Independently interpret the descriptor, not production byte offsets or button masks.
        // Columns: Binder ID, HID usage page, usage, active value, finite duration in ms.
        int[][] expected = {
                {1,9,1,1,250}, {2,1,0x30,76,1500}, {3,2,0xc4,255,250},
                {4,1,0x30,-76,1500}, {5,1,0x31,-76,1500}, {6,1,0x31,76,1500},
                {7,9,2,1,250}, {8,9,4,1,250}, {9,9,5,1,250},
                {10,1,0x39,0,250}, {11,1,0x39,2,250}, {12,1,0x39,4,250}, {13,1,0x39,6,250},
                {14,9,7,1,250}, {15,9,8,1,250}, {16,9,14,1,250}, {17,9,15,1,250},
                {18,9,12,1,250}, {19,9,11,1,250}, {20,2,0xc5,255,250},
                {21,1,0x32,76,1500}, {22,1,0x32,-76,1500},
                {23,1,0x35,-76,1500}, {24,1,0x35,76,1500}
        };
        NativeGamepadCoreTest.Descriptor descriptor = new NativeGamepadCoreTest.Descriptor(PROFILE.getDescriptor());
        Set<Integer> seen = new HashSet<>();
        assertEquals(expected.length, GamepadPulse.values().length);
        for (int[] item : expected) {
            GamepadPulse pulse = GamepadPulse.forId(item[0]);
            assertTrue(seen.add(pulse.getId()));
            assertFalse(pulse.getLabel().isEmpty());
            assertEquals(item[4], pulse.getHoldMs());
            byte[] report = PROFILE.encode(pulse.getNativeState());
            for (NativeGamepadCoreTest.Field field : descriptor.fields) {
                if (field.constant) continue;
                int wanted = field.page == 1 && field.usage == 0x39 ? 8 : 0;
                if (field.page == item[1] && field.usage == item[2]) wanted = item[3];
                assertEquals("Pulse " + item[0] + " HID " + field.page + ":" + field.usage,
                        wanted, descriptor.value(report, field.page, field.usage));
            }
        }
        for (int id : new int[]{-1,0,25,Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> GamepadPulse.forId(id));
    }

    @Test public void configuredAndDirectDiagnosticsShareConversionAndNeutralReportsAreIndependent() {
        ControllerProfile configured = new ControllerProfile("test", "test", ControllerProfile.Kind.NATIVE,
                PROFILE.getName(), PROFILE.getVendorId(), PROFILE.getProductId(), PROFILE.getBus(),
                Map.of(304,305), Map.of(), new ControllerProfile.AxisConfig(true,true,true,true,true,true,40));
        NativeGamepadCoreTest.Descriptor descriptor = new NativeGamepadCoreTest.Descriptor(PROFILE.getDescriptor());
        for (GamepadPulse pulse : GamepadPulse.values()) {
            byte[] direct = PROFILE.encode(pulse.getNativeState());
            // Diagnostic output does not depend on physical-device source mappings.
            assertArrayEquals(direct, configured.encodeProbe(pulse.getState()));
            assertArrayEquals(direct, NativeGamepadProfile.KISHI_IDENTITY.encode(pulse.getNativeState()));
            byte[] neutral = PROFILE.neutralReport();
            for (NativeGamepadCoreTest.Field field : descriptor.fields) {
                if (field.constant) continue;
                assertEquals(field.page == 1 && field.usage == 0x39 ? 8 : 0,
                        descriptor.value(neutral, field.page, field.usage));
            }
            Arrays.fill(direct, (byte)0xff);
            Arrays.fill(neutral, (byte)0xff);
            assertArrayEquals(configured.neutralReport(), PROFILE.neutralReport());
            assertFalse(Arrays.equals(direct, PROFILE.encode(pulse.getNativeState())));
        }
        assertThrows(NullPointerException.class, () -> NativeGamepadState.fromGamepadState(null));
    }
}
