package dev.codek.xboxprobe.core;

import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class CoreProtocolTest {
    @Test public void neutralMatchesLegacyXboxPacket() {
        assertArrayEquals(new byte[] {1, 0, (byte) 0x80, 0, (byte) 0x80,
                0, (byte) 0x80, 0, (byte) 0x80, 0, 0, 0, 0, 0, 0, 0},
                XboxReportEncoder.encode(GamepadState.NEUTRAL));
    }

    @Test public void descriptorDefinesEveryEncodedControlAndPadding() {
        Descriptor descriptor = new Descriptor(HidProfile.XBOX_ONE_S.getDescriptor());
        // Descriptor independently declares 120 payload bits + one report-ID byte.
        assertEquals(120, (int) descriptor.inputBits.get(1));
        assertEquals(8, (int) descriptor.inputBits.get(2));
        assertEquals(8, (int) descriptor.inputBits.get(4));
        assertEquals(64, (int) descriptor.outputBits.get(3));
        assertEquals(306, HidProfile.XBOX_ONE_S.getDescriptor().length);

        byte[] packet = XboxReportEncoder.encode(new GamepadState(
                -1, 1, -0.5f, 0.5f, 1, 0.5f, 7, GamepadState.X | GamepadState.R3));
        assertField(descriptor, packet, 1, 0x30, 0, 16, 0, 65535, 0);
        assertField(descriptor, packet, 1, 0x31, 16, 16, 0, 65535, 65535);
        assertField(descriptor, packet, 1, 0x33, 32, 16, 0, 65535, 16384);
        assertField(descriptor, packet, 1, 0x34, 48, 16, 0, 65535, 49152);
        assertField(descriptor, packet, 1, 0x32, 64, 10, 0, 1023, 1023);
        assertField(descriptor, packet, 1, 0x35, 80, 10, 0, 1023, 512);
        assertField(descriptor, packet, 1, 0x39, 96, 4, 1, 8, 8);
        assertField(descriptor, packet, 9, 3, 106, 1, 0, 1, 1); // X
        assertField(descriptor, packet, 9, 10, 113, 1, 0, 1, 1); // R3
        for (Field f : descriptor.fields) {
            if (f.report == 1 && f.constant) assertEquals(0, readBits(packet, f.offset, f.bits));
        }
    }

    @Test public void allButtonAndHatPositionsMatchLegacyXboxContract() {
        int[] buttons = {GamepadState.A, GamepadState.B, GamepadState.X, GamepadState.Y,
                GamepadState.L1, GamepadState.R1, GamepadState.SELECT, GamepadState.START,
                GamepadState.L3, GamepadState.R3};
        Descriptor descriptor = new Descriptor(HidProfile.XBOX_ONE_S.descriptor());
        for (int i = 0; i < buttons.length; i++) {
            byte[] report = XboxReportEncoder.encode(new GamepadState(0,0,0,0,0,0,-1,buttons[i]));
            for (int usage = 1; usage <= 10; usage++) {
                Field field = descriptor.field(1, 9, usage);
                assertEquals(usage == i + 1 ? 1 : 0, readBits(report, field.offset, field.bits));
            }
        }
        for (int hat = -1; hat <= 7; hat++) {
            byte[] report = XboxReportEncoder.encode(new GamepadState(0,0,0,0,0,0,hat,0));
            assertEquals(hat + 1, report[13]);
        }
        assertTrue((descriptor.field(1, 1, 0x39).flags & 0x40) != 0); // null-state support
    }

    @Test public void stateClampsFiniteControlsAndRejectsUnknownValues() {
        byte[] report = XboxReportEncoder.encode(new GamepadState(-2,2,0,0,-1,2,-1,0));
        assertEquals(0, u16(report, 1));
        assertEquals(65535, u16(report, 3));
        assertEquals(0, u16(report, 9));
        assertEquals(1023, u16(report, 11));
        assertThrows(IllegalArgumentException.class, () -> new GamepadState(Float.NaN,0,0,0,0,0,-1,0));
        assertThrows(IllegalArgumentException.class, () -> new GamepadState(0,0,0,0,Float.POSITIVE_INFINITY,0,-1,0));
        assertThrows(IllegalArgumentException.class, () -> new GamepadState(0,0,0,0,0,0,8,0));
        assertThrows(IllegalArgumentException.class, () -> new GamepadState(0,0,0,0,0,0,-1,1024));
    }

    @Test public void createEventUsesPackedUapiOffsetsAndPreservesIdentity() {
        HidProfile profile = HidProfile.XBOX_ONE_S.withIdentity("Xbox 테스트", 0x1234, 0xabcd, 5);
        byte[] event = UhidEventBuilder.create2(profile);
        ByteBuffer b = ByteBuffer.wrap(event).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(4376, event.length);
        assertEquals(11, b.getInt(0));
        byte[] name = profile.getName().getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(name, Arrays.copyOfRange(event, 4, 4 + name.length));
        assertEquals(0, event[4 + name.length]);
        for (int i = 132; i < 260; i++) assertEquals(0, event[i]); // phys and uniq
        assertEquals(306, u16(event, 260));
        assertEquals(5, u16(event, 262));
        assertEquals(0x1234, b.getInt(264));
        assertEquals(0xabcd, b.getInt(268));
        assertEquals(0, b.getInt(272)); // unknown version
        assertEquals(0, b.getInt(276));
        assertArrayEquals(profile.descriptor(), Arrays.copyOfRange(event, 280, 586));
        for (int i = 586; i < event.length; i++) assertEquals(0, event[i]);
    }

    @Test public void uhidBoundsAndRepliesPreserveOpaqueRequestIds() {
        byte[] max = new byte[4096];
        max[4095] = 0x5a;
        byte[] input = UhidEventBuilder.input2(max);
        assertEquals(12, input[0]);
        assertEquals(4096, u16(input, 4));
        assertEquals(0x5a, input[4101]);
        assertEquals(0x5a, UhidEventBuilder.create2(HidProfile.XBOX_ONE_S, max)[4375]);
        assertThrows(IllegalArgumentException.class, () -> UhidEventBuilder.input2(new byte[4097]));
        assertThrows(IllegalArgumentException.class, () -> UhidEventBuilder.input2(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> UhidEventBuilder.create2(HidProfile.XBOX_ONE_S, new byte[4097]));
        assertThrows(IllegalArgumentException.class, () -> UhidEventBuilder.create2(HidProfile.XBOX_ONE_S, new byte[0]));
        byte[] reply = UhidEventBuilder.getReportReply(0xf1234567, 0, new byte[] {1, 2});
        assertEquals(10, reply[0]);
        assertEquals(0xf1234567, ByteBuffer.wrap(reply).order(ByteOrder.LITTLE_ENDIAN).getInt(4));
        assertEquals(0, u16(reply, 8));
        assertEquals(2, u16(reply, 10));
        assertArrayEquals(new byte[] {1, 2}, Arrays.copyOfRange(reply, 12, 14));
        assertEquals(5, u16(UhidEventBuilder.getReportReply(7, 5), 8));
        assertEquals(14, UhidEventBuilder.setReportReply(7, 0)[0]);
        assertEquals(1, UhidEventBuilder.destroy()[0]);
        assertThrows(IllegalArgumentException.class, () -> UhidEventBuilder.getReportReply(1, 5, new byte[] {1}));
        assertThrows(IllegalArgumentException.class, () -> UhidEventBuilder.setReportReply(1, -5));
    }

    @Test public void profileValidatesUtf8WithoutTruncationAndDefendsDescriptor() {
        new HidProfile(repeat("a", 127), 0, 65535, 5);
        new HidProfile(repeat("가", 42) + "x", 0, 0, 5); // 127 UTF-8 bytes
        assertThrows(IllegalArgumentException.class, () -> new HidProfile(repeat("가", 43),0,0,5));
        assertThrows(IllegalArgumentException.class, () -> new HidProfile("a\0b",0,0,5));
        assertThrows(IllegalArgumentException.class, () -> new HidProfile(" ",0,0,5));
        assertThrows(IllegalArgumentException.class, () -> new HidProfile("x",65536,0,5));
        byte[] copy = HidProfile.XBOX_ONE_S.descriptor();
        copy[0] = 0;
        assertEquals(5, HidProfile.XBOX_ONE_S.descriptor()[0]);
    }

    private static String repeat(String s, int count) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < count; i++) b.append(s);
        return b.toString();
    }

    private static int u16(byte[] b, int offset) {
        return (b[offset] & 255) | ((b[offset + 1] & 255) << 8);
    }

    private static int readBits(byte[] packet, int offset, int count) {
        int result = 0;
        for (int bit = 0; bit < count; bit++) {
            int index = offset + bit;
            result |= ((packet[1 + index / 8] >>> (index % 8)) & 1) << bit;
        }
        return result;
    }

    private static void assertField(Descriptor d, byte[] report, int page, int usage,
                                    int offset, int bits, int min, int max, int value) {
        Field f = d.field(1, page, usage);
        assertEquals(offset, f.offset);
        assertEquals(bits, f.bits);
        assertEquals(min, f.min);
        assertEquals(max, f.max);
        assertEquals(value, readBits(report, f.offset, f.bits));
    }

    static final class Field {
        int report, page, usage, offset, bits, min, max, flags;
        boolean constant;
    }

    /** Independent HID short-item decoder; verifies the contract, not encoder constants. */
    static final class Descriptor {
        final List<Field> fields = new ArrayList<>();
        final Map<Integer,Integer> inputBits = new HashMap<>();
        final Map<Integer,Integer> outputBits = new HashMap<>();

        Descriptor(byte[] bytes) {
            int page=0, report=0, size=0, count=0, min=0, max=0, usageMin=-1, usageMax=-1;
            int collections=0;
            List<Integer> usages = new ArrayList<>();
            for (int pos=0; pos<bytes.length;) {
                int prefix = bytes[pos++] & 255;
                assertNotEquals("long items outside this fixed descriptor", 0xfe, prefix);
                int len = (prefix & 3) == 3 ? 4 : prefix & 3;
                assertTrue("truncated HID item", pos + len <= bytes.length);
                int value=0;
                for (int i=0; i<len; i++) value |= (bytes[pos++] & 255) << (8*i);
                int type=(prefix >>> 2) & 3, tag=prefix >>> 4;
                if (type==1) {
                    switch(tag) {
                        case 0: page=value; break;
                        case 1: min=value; break;
                        case 2: max=value; break;
                        case 7: size=value; break;
                        case 8: report=value; break;
                        case 9: count=value; break;
                        default: break;
                    }
                } else if (type==2) {
                    if(tag==0) usages.add(value);
                    if(tag==1) usageMin=value;
                    if(tag==2) usageMax=value;
                } else if(type==0) {
                    if(tag==10) collections++;
                    else if(tag==12) { collections--; assertTrue(collections>=0); }
                    else if(tag==8 || tag==9) {
                        Map<Integer,Integer> offsets=tag==8?inputBits:outputBits;
                        int offset=offsets.containsKey(report)?offsets.get(report):0;
                        for(int i=0; i<count; i++) {
                            if(tag==8) {
                                Field f=new Field();
                                f.report=report; f.page=page; f.bits=size; f.offset=offset+i*size;
                                f.min=min; f.max=max; f.flags=value; f.constant=(value&1)!=0;
                                f.usage=i<usages.size()?usages.get(i):
                                        usageMin>=0 && usageMin+i<=usageMax?usageMin+i:-1;
                                fields.add(f);
                            }
                        }
                        offsets.put(report,offset+size*count);
                    } else fail("Unexpected main item " + prefix);
                    usages.clear(); usageMin=-1; usageMax=-1;
                } else fail("Reserved HID item type");
            }
            assertEquals("balanced collections",0,collections);
        }

        Field field(int report,int page,int usage) {
            for(Field f:fields) if(f.report==report && f.page==page && f.usage==usage && !f.constant) return f;
            throw new AssertionError("Missing report/usage " + report + "/" + page + "/" + usage);
        }
    }
}
