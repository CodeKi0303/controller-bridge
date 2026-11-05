package dev.codek.xboxprobe.core;

import static org.junit.Assert.*;

import dev.codek.xboxprobe.UhidSourceCapabilities;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public final class GamepadSourceLayoutTest {
    private static final NativeGamepadProfile OUTPUT = NativeGamepadProfile.KISHI_ULTRA_IDENTITY;
    private static final Set<Integer> BUTTONS = Set.of(304,305,307,308,310,311,314,315,316,317,318);

    @Test public void parserToNativeAndXboxMappingKeepsRightStickAndTriggersIndependent() {
        // Constructed numeric getevent fixture using Linux xpad axis codes; no real-model claim.
        String inventory = "add device 0: /dev/input/event20\n"
                + "bus: 0005\nvendor 045e\nproduct 0001\nversion 0001\n"
                + "name: \"Layout fixture\"\nlocation: \"bluetooth-fixture/input0\"\nid: \"fixture\"\n"
                + "events:\nKEY (0001): 0130 0131 0133 0134 0136 0137 013a 013b 013c 013d 013e\n"
                + "ABS (0003): 0000 : value 0, min -32768, max 32767, fuzz 0, flat 128\n"
                + "0001 : value 0, min -32768, max 32767, fuzz 0, flat 128\n"
                + "0002 : value 0, min 0, max 1023, fuzz 0, flat 0\n"
                + "0003 : value 0, min -32768, max 32767, fuzz 0, flat 128\n"
                + "0004 : value 0, min -32768, max 32767, fuzz 0, flat 128\n"
                + "0005 : value 0, min 0, max 1023, fuzz 0, flat 0\n"
                + "0010 : value 0, min -1, max 1, fuzz 0, flat 0\n"
                + "0011 : value 0, min -1, max 1, fuzz 0, flat 0\ninput props:\n<none>\n";
        UhidSourceCapabilities.Device parsed = UhidSourceCapabilities.parse(inventory).get(0);
        assertTrue(parsed.problem, parsed.isSelectableSource());
        Map<Integer,AxisRange> ranges = new HashMap<>();
        for (int code : GamepadSourceLayout.supportedAxisCodes()) {
            UhidSourceCapabilities.Axis axis = parsed.axes.get(code);
            if (axis != null) ranges.put(code,new AxisRange(axis.min,axis.max,axis.flat));
        }
        assertEquals(BUTTONS,parsed.keyCodes);
        for (ControllerProfile.Kind kind : new ControllerProfile.Kind[]{ControllerProfile.Kind.NATIVE,ControllerProfile.Kind.XBOX}) {
            ControllerProfile profile = profile(kind,ControllerProfile.AxisConfig.DEFAULT);
            G8InputMapper mapper = profile.createMapper(ranges,parsed.keyCodes);
            assertNull(sync(mapper).getReport()); // range minima are not fabricated as initial inputs
            mapper.accept(abs(3,32767)); mapper.accept(abs(4,-32768));
            byte[] stickOnly = sync(mapper).getReport();
            assertArrayEquals(profile.encodeProbe(new GamepadState(0,0,1,-1,0,0,-1,0)),stickOnly);
            mapper.accept(abs(2,1023)); mapper.accept(abs(5,512));
            byte[] withTriggers = sync(mapper).getReport();
            assertArrayEquals(profile.encodeProbe(new GamepadState(0,0,1,-1,1,512f/1023,-1,0)),withTriggers);
            mapper.accept(abs(16,-1)); mapper.accept(abs(17,1)); mapper.accept(key(304,1));
            byte[] report = sync(mapper).getReport();
            assertArrayEquals(profile.encodeProbe(new GamepadState(0,0,1,-1,1,512f/1023,5,GamepadState.A)),report);
            if (kind == ControllerProfile.Kind.NATIVE) {
                NativeGamepadCoreTest.Descriptor d = new NativeGamepadCoreTest.Descriptor(profile.getDescriptor());
                assertEquals(127,d.value(report,1,0x32)); assertEquals(-127,d.value(report,1,0x35));
                assertEquals(255,d.value(report,2,0xc5)); assertEquals(128,d.value(report,2,0xc4));
                assertEquals(5,d.value(report,1,0x39)); assertEquals(1,d.value(report,9,1));
            }
            assertArrayEquals(profile.neutralReport(),mapper.disconnect().getReport());
        }
    }

    @Test public void fragmentedEvdevFramesUseTheResolvedLayoutBeforeAxisTransforms() throws Exception {
        ControllerProfile.AxisConfig config = new ControllerProfile.AxisConfig(true,true,false,false,true,true,0);
        ControllerProfile profile = profile(ControllerProfile.Kind.NATIVE,config);
        G8InputMapper mapper = profile.createMapper(rxRyAxes(),BUTTONS);
        int[][] inputs = {{3,0,32767},{3,1,-32768},{3,3,-32768},{3,4,32767},{3,2,1023},{3,5,0},{0,0,0}};
        ByteBuffer buffer = ByteBuffer.allocate(inputs.length*24).order(ByteOrder.LITTLE_ENDIAN);
        for (int[] item : inputs) buffer.putLong(0).putLong(0).putShort((short)item[0]).putShort((short)item[1]).putInt(item[2]);
        byte[] raw = buffer.array(); EvdevDecoder decoder = new EvdevDecoder(24);
        final byte[][] report = {null};
        for (int i=0;i<raw.length;i+=7) decoder.accept(raw,i,Math.min(7,raw.length-i),event -> {
            MappingUpdate update = mapper.accept(event); assertFalse(update.getError(),update.isFailed());
            if (update.getReport()!=null) { assertNull(report[0]); report[0]=update.getReport(); }
        });
        decoder.finish();
        assertArrayEquals(profile.encodeProbe(new GamepadState(1,1,1,1,0,1,-1,0)),report[0]);
        assertArrayEquals(profile.neutralReport(),mapper.accept(new EvdevEvent(0,3,0)).getReport());
    }

    @Test public void capturedG8RolesStayStableAndIncompleteOrAmbiguousLayoutsFailClosed() {
        Map<Integer,AxisRange> g8 = new HashMap<>();
        for (int code : new int[]{0,1,2,5}) g8.put(code,new AxisRange(-127,127,15));
        for (int code : new int[]{9,10}) g8.put(code,new AxisRange(0,255,15));
        for (int code : new int[]{16,17}) g8.put(code,new AxisRange(-1,1,0));
        GamepadSourceLayout layout = GamepadSourceLayout.resolve(g8);
        assertEquals(GamepadSourceLayout.Kind.Z_RZ_BRAKE_GAS,layout.getKind());
        assertEquals(2,layout.sourceCode(GamepadSourceLayout.Control.RIGHT_X));
        assertEquals(5,layout.sourceCode(GamepadSourceLayout.Control.RIGHT_Y));
        assertEquals(10,layout.sourceCode(GamepadSourceLayout.Control.LEFT_TRIGGER));
        assertEquals(9,layout.sourceCode(GamepadSourceLayout.Control.RIGHT_TRIGGER));
        g8.put(3,new AxisRange(-127,127,0)); g8.put(4,new AxisRange(-127,127,0));
        assertThrows(IllegalArgumentException.class,() -> GamepadSourceLayout.resolve(g8));
        for (int missing : new int[]{0,1,2,3,4,5,16,17}) {
            Map<Integer,AxisRange> incomplete = rxRyAxes(); incomplete.remove(missing);
            assertThrows(IllegalArgumentException.class,() -> GamepadSourceLayout.resolve(incomplete));
        }
        Map<Integer,AxisRange> invalidHat = rxRyAxes(); invalidHat.put(16,new AxisRange(0,255,0));
        assertThrows(IllegalArgumentException.class,() -> GamepadSourceLayout.resolve(invalidHat));
    }

    @Test public void resolvedRolesAreUniqueAndCapturedRangesCannotChangeBehindMapper() {
        Map<Integer,AxisRange> axes = rxRyAxes();
        GamepadSourceLayout layout = GamepadSourceLayout.resolve(axes);
        assertEquals(GamepadSourceLayout.Kind.RX_RY_Z_RZ,layout.getKind());
        Set<Integer> codes = new HashSet<>();
        for (GamepadSourceLayout.Control control : GamepadSourceLayout.Control.values()) assertTrue(codes.add(layout.sourceCode(control)));
        assertThrows(UnsupportedOperationException.class,() -> GamepadSourceLayout.supportedAxisCodes().clear());
        G8InputMapper mapper = new G8GamepadMapper(OUTPUT,axes,BUTTONS);
        axes.put(3,new AxisRange(0,1,0)); axes.clear();
        mapper.accept(abs(3,32767)); byte[] report = sync(mapper).getReport();
        assertEquals(127,new NativeGamepadCoreTest.Descriptor(OUTPUT.getDescriptor()).value(report,1,0x32));
        MappingUpdate failure = mapper.accept(abs(3,32768));
        assertEquals(MappingUpdate.Failure.AXIS_OUT_OF_RANGE,failure.getFailure());
        assertArrayEquals(OUTPUT.neutralReport(),failure.getReport());
        assertArrayEquals(OUTPUT.neutralReport(),mapper.accept(key(304,1)).getReport());
        G8InputMapper other = new G8GamepadMapper(OUTPUT,rxRyAxes(),BUTTONS);
        assertEquals(MappingUpdate.Failure.SOURCE_CAPABILITY_CHANGED,other.accept(key(306,1)).getFailure());
    }

    private static Map<Integer,AxisRange> rxRyAxes() {
        Map<Integer,AxisRange> result = new HashMap<>();
        for (int code : new int[]{0,1,3,4}) result.put(code,new AxisRange(-32768,32767,128));
        for (int code : new int[]{2,5}) result.put(code,new AxisRange(0,1023,0));
        for (int code : new int[]{16,17}) result.put(code,new AxisRange(-1,1,0));
        return result;
    }
    private static ControllerProfile profile(ControllerProfile.Kind kind,ControllerProfile.AxisConfig config) {
        return new ControllerProfile("layout-test","Layout test",kind,"Output fixture",1,2,3,Map.of(),Map.of(),config);
    }
    private static EvdevEvent abs(int code,int value) { return new EvdevEvent(3,code,value); }
    private static EvdevEvent key(int code,int value) { return new EvdevEvent(1,code,value); }
    private static MappingUpdate sync(G8InputMapper mapper) { return mapper.accept(new EvdevEvent(0,0,0)); }
}
