package dev.codek.xboxprobe.core;

import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public final class NativeGamepadCoreTest {
    private static final NativeGamepadProfile PROFILE=NativeGamepadProfile.KISHI_IDENTITY;
    private static final byte[] NEUTRAL={0,0,0,0,0,0,8,0,0};

    @Test public void authoredDescriptorIndependentlyDefinesExactObservedGamepadSemantics() {
        Descriptor d=new Descriptor(PROFILE.getDescriptor());
        assertTrue(d.gamepadApplication);
        assertEquals(72,d.inputBits);
        assertEquals(0,d.outputBits);
        assertField(d,1,0x30,0,8,-127,127);
        assertField(d,1,0x31,8,8,-127,127);
        assertField(d,1,0x32,16,8,-127,127);
        assertField(d,1,0x35,24,8,-127,127);
        assertField(d,2,0xc5,32,8,0,255); // ABS_BRAKE, left trigger
        assertField(d,2,0xc4,40,8,0,255); // ABS_GAS, right trigger
        assertField(d,1,0x39,48,8,0,7);
        assertTrue((d.field(1,0x39).flags&0x40)!=0); // out-of-range 8 is neutral
        for(int button=1;button<=15;button++)assertField(d,9,button,55+button,1,0,1);
        Field padding=d.fields.get(d.fields.size()-1);
        assertEquals(71,padding.offset);assertEquals(1,padding.bits);assertTrue(padding.constant);
    }

    @Test public void explicitIdentitiesShareLayoutAndReportsWithoutBorrowingLegacyButtonMeaning() {
        NativeGamepadProfile g8=NativeGamepadProfile.G8_IDENTITY;
        assertEquals("kishi-identity",PROFILE.getId());
        assertEquals("Razer Kishi Ultra",PROFILE.getName());
        assertEquals(0x1532,PROFILE.getVendorId());assertEquals(0x071a,PROFILE.getProductId());
        assertEquals("g8-identity",g8.getId());assertEquals("GAMESIR GameSir-G8",g8.getName());
        assertEquals(0x3537,g8.getVendorId());assertEquals(0x0108,g8.getProductId());
        assertEquals(3,PROFILE.getBus());assertEquals(3,g8.getBus());
        assertArrayEquals(PROFILE.getDescriptor(),g8.getDescriptor());
        assertArrayEquals(NEUTRAL,PROFILE.neutralReport());
        NativeGamepadState state=new NativeGamepadState(-127,127,10,-11,255,128,7,0x7fff);
        assertArrayEquals(new byte[]{(byte)0x81,0x7f,10,-11,(byte)255,(byte)128,7,(byte)255,0x7f},PROFILE.encode(state));
        assertArrayEquals(PROFILE.encode(state),g8.encode(state));
        Descriptor d=new Descriptor(PROFILE.getDescriptor());
        assertEquals(-127,d.value(PROFILE.encode(state),1,0x30));
        assertEquals(255,d.value(PROFILE.encode(state),2,0xc5));
        // The separately retained legacy state still rejects native-only bits.
        assertThrows(IllegalArgumentException.class,()->new GamepadState(0,0,0,0,0,0,-1,0x400));
        assertThrows(IllegalArgumentException.class,()->new NativeGamepadState(0,0,0,0,0,0,-1,0x8000));
        assertThrows(IllegalArgumentException.class,()->new NativeGamepadState(-128,0,0,0,0,0,-1,0));
        assertThrows(IllegalArgumentException.class,()->new NativeGamepadState(0,0,0,0,256,0,-1,0));
    }

    @Test public void fragmentedEvdevFrameForwardsBothSticksTriggersHatAndButtons() throws Exception {
        G8InputMapper mapper=new G8GamepadMapper(PROFILE,capturedAxes(),capturedButtons());
        int[][] inputs={{3,0,-50},{3,1,25},{3,2,127},{3,5,-127},{3,10,128},{3,9,255},
                {3,16,1},{3,17,-1},{1,304,1},{1,318,1},{0,0,0}};
        ByteBuffer raw=ByteBuffer.allocate(inputs.length*24).order(ByteOrder.LITTLE_ENDIAN);
        for(int[] e:inputs)raw.putLong(42).putLong(500).putShort((short)e[0]).putShort((short)e[1]).putInt(e[2]);
        EvdevDecoder decoder=new EvdevDecoder(24);List<byte[]> reports=new ArrayList<>();
        byte[] source=raw.array();
        for(int offset=0;offset<source.length;offset+=5) {
            decoder.accept(source,offset,Math.min(5,source.length-offset),event->{
                MappingUpdate update=mapper.accept(event);assertFalse(update.getError(),update.isFailed());
                if(update.getReport()!=null)reports.add(update.getReport());
            });
        }
        decoder.finish();assertEquals(1,reports.size());
        byte[] report=reports.get(0);Descriptor d=new Descriptor(PROFILE.getDescriptor());
        assertEquals(-50,d.value(report,1,0x30));assertEquals(25,d.value(report,1,0x31));
        assertEquals(127,d.value(report,1,0x32));assertEquals(-127,d.value(report,1,0x35));
        assertEquals(128,d.value(report,2,0xc5));assertEquals(255,d.value(report,2,0xc4));
        assertEquals(1,d.value(report,1,0x39));assertEquals(1,d.value(report,9,1));assertEquals(1,d.value(report,9,15));
        assertEquals(0,report[8]&0x80);
    }

    @Test public void actualRangeNormalizationHasNoKeyboardThresholdOrAddedDeadZone() {
        G8InputMapper mapper=new G8GamepadMapper(PROFILE,capturedAxes(),capturedButtons());
        mapper.accept(new EvdevEvent(3,0,10));
        assertEquals(10,sync(mapper).getReport()[0]); // below captured flat=15 and keyboard threshold
        mapper.accept(new EvdevEvent(3,0,11));
        assertEquals(11,sync(mapper).getReport()[0]);
        Map<Integer,AxisRange> axes=capturedAxes();
        axes.put(0,new AxisRange(0,2000,10));axes.put(10,new AxisRange(100,1100,0));
        mapper=new G8GamepadMapper(PROFILE,axes,capturedButtons());
        mapper.accept(new EvdevEvent(3,0,0));mapper.accept(new EvdevEvent(3,10,1100));
        byte[] minimum=sync(mapper).getReport();assertEquals(-127,minimum[0]);assertEquals(255,minimum[4]&255);
        mapper.accept(new EvdevEvent(3,0,1000));mapper.accept(new EvdevEvent(3,10,600));
        byte[] center=sync(mapper).getReport();assertEquals(0,center[0]);assertEquals(128,center[4]&255);
        mapper.accept(new EvdevEvent(3,0,2000));mapper.accept(new EvdevEvent(3,10,100));
        byte[] maximum=sync(mapper).getReport();assertEquals(127,maximum[0]);assertEquals(0,maximum[4]&255);
    }

    @Test public void allFifteenButtonsAndReleasesRemainIndependentWithoutSixKeyRollover() {
        G8InputMapper mapper=new G8GamepadMapper(PROFILE,capturedAxes(),capturedButtons());
        Descriptor d=new Descriptor(PROFILE.getDescriptor());
        for(int code=304;code<=318;code++) {
            mapper.accept(new EvdevEvent(1,code,1));byte[] report=sync(mapper).getReport();
            for(int usage=1;usage<=15;usage++)assertEquals(usage<=code-303?1:0,d.value(report,9,usage));
        }
        mapper.accept(new EvdevEvent(1,306,0));byte[] report=sync(mapper).getReport();
        assertEquals(0,d.value(report,9,3));
        for(int usage=1;usage<=15;usage++)if(usage!=3)assertEquals(1,d.value(report,9,usage));
        for(int code=304;code<=318;code++)mapper.accept(new EvdevEvent(1,code,0));
        assertArrayEquals(NEUTRAL,sync(mapper).getReport());
    }

    @Test public void allEightHatDirectionsAndNeutralAreEncodedAtFrameBoundary() {
        G8InputMapper mapper=new G8GamepadMapper(PROFILE,capturedAxes(),capturedButtons());
        int[][] hats={{0,-1},{1,-1},{1,0},{1,1},{0,1},{-1,1},{-1,0},{-1,-1},{0,0}};
        for(int direction=0;direction<hats.length;direction++) {
            assertNull(mapper.accept(new EvdevEvent(3,16,hats[direction][0])).getReport());
            assertNull(mapper.accept(new EvdevEvent(3,17,hats[direction][1])).getReport());
            assertEquals(direction,sync(mapper).getReport()[6]);
        }
    }

    @Test public void sourceLossAndDroppedEventsReleaseUsingNativeNeutralAndStayFailed() {
        for(boolean drop:new boolean[]{false,true}) {
            G8InputMapper mapper=new G8GamepadMapper(PROFILE,capturedAxes(),capturedButtons());
            mapper.accept(new EvdevEvent(3,0,127));mapper.accept(new EvdevEvent(1,304,1));sync(mapper);
            MappingUpdate failure=drop?mapper.accept(new EvdevEvent(0,3,0)):mapper.disconnect();
            assertTrue(failure.isFailed());assertArrayEquals(NEUTRAL,failure.getReport());
            assertEquals(drop?MappingUpdate.Failure.SYN_DROPPED:MappingUpdate.Failure.SOURCE_LOST,failure.getFailure());
            assertArrayEquals(NEUTRAL,mapper.accept(new EvdevEvent(1,305,1)).getReport());
            assertArrayEquals(NEUTRAL,sync(mapper).getReport());
        }
        G8InputMapper keyboard=new G8KeyboardMapper(null,null,null,null,Map.of(304,0x0d));
        assertArrayEquals(new byte[8],keyboard.disconnect().getReport());
        assertArrayEquals(new byte[8],keyboard.neutralReport());
    }

    @Test public void unknownAndOutOfRangeCapabilitiesNeverProduceFabricatedInput() {
        Map<Integer,AxisRange> missing=capturedAxes();missing.remove(9);
        assertThrows(IllegalArgumentException.class,()->new G8GamepadMapper(PROFILE,missing,capturedButtons()));
        Map<Integer,AxisRange> wrongHat=capturedAxes();wrongHat.put(16,new AxisRange(0,255,0));
        assertThrows(IllegalArgumentException.class,()->new G8GamepadMapper(PROFILE,wrongHat,capturedButtons()));
        G8InputMapper mapper=new G8GamepadMapper(PROFILE,capturedAxes(),capturedButtons());
        assertNull(sync(mapper).getReport()); // no state fabricated from capability minima
        assertNull(mapper.accept(new EvdevEvent(1,304,2)).getReport());
        assertNull(sync(mapper).getReport()); // repeat alone is not a press
        MappingUpdate invalid=mapper.accept(new EvdevEvent(3,0,128));
        assertTrue(invalid.isFailed());assertArrayEquals(NEUTRAL,invalid.getReport());
        Set<Integer> buttons=capturedButtons();buttons.remove(318);
        mapper=new G8GamepadMapper(PROFILE,capturedAxes(),buttons);
        MappingUpdate changed=mapper.accept(new EvdevEvent(1,318,1));
        assertEquals(MappingUpdate.Failure.SOURCE_CAPABILITY_CHANGED,changed.getFailure());
        assertArrayEquals(NEUTRAL,changed.getReport());
        mapper=new G8GamepadMapper(PROFILE,capturedAxes(),capturedButtons());
        assertEquals(MappingUpdate.Failure.INVALID_KEY_VALUE,mapper.accept(new EvdevEvent(1,304,3)).getFailure());
    }

    @Test public void capturedCollectionsAndReturnedReportsCannotBeMutatedByConsumers() {
        Map<Integer,AxisRange> axes=capturedAxes();Set<Integer> buttons=capturedButtons();
        G8InputMapper mapper=new G8GamepadMapper(PROFILE,axes,buttons);
        axes.put(0,new AxisRange(0,2000,0));buttons.clear();
        mapper.accept(new EvdevEvent(3,0,-127));mapper.accept(new EvdevEvent(1,304,1));
        MappingUpdate update=sync(mapper);byte[] report=update.getReport();assertEquals(-127,report[0]);
        report[0]=100;assertEquals(-127,update.getReport()[0]);
        assertNull(sync(mapper).getReport()); // caller mutation did not change lastReport
        byte[] descriptor=PROFILE.getDescriptor();descriptor[0]=0;assertEquals(5,PROFILE.getDescriptor()[0]);
        byte[] neutral=mapper.neutralReport();neutral[6]=0;assertArrayEquals(NEUTRAL,mapper.neutralReport());
    }

    @Test public void uhidOwnerLocationHasExactPhysOffsetAndNoTruncationAcrossAllOutputKinds() {
        String marker="g8-input-bridge:12345678-1234-1234-1234-123456789012";
        byte[][] events={UhidEventBuilder.create2(HidProfile.XBOX_ONE_S,marker),
                UhidEventBuilder.createKeyboard(HidKeyboardProfile.INSTANCE,marker),UhidEventBuilder.createGamepad(PROFILE,marker)};
        byte[] utf8=marker.getBytes(StandardCharsets.UTF_8);
        for(byte[] event:events) {
            assertEquals(4376,event.length);assertEquals(11,event[0]);
            assertArrayEquals(utf8,Arrays.copyOfRange(event,132,132+utf8.length));
            for(int i=132+utf8.length;i<260;i++)assertEquals(0,event[i]); // NUL/pad + uniq[64]
        }
        byte[] nativeEvent=events[2];ByteBuffer b=ByteBuffer.wrap(nativeEvent).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(PROFILE.getDescriptor().length,b.getShort(260)&65535);
        assertEquals(3,b.getShort(262)&65535);assertEquals(0x1532,b.getInt(264));assertEquals(0x071a,b.getInt(268));
        assertArrayEquals(PROFILE.getDescriptor(),Arrays.copyOfRange(nativeEvent,280,280+PROFILE.getDescriptor().length));
        String exact="가".repeat(21);assertEquals(63,exact.getBytes(StandardCharsets.UTF_8).length);
        byte[] maximum=UhidEventBuilder.createGamepad(PROFILE,exact);assertEquals(0,maximum[195]);
        assertArrayEquals(exact.getBytes(StandardCharsets.UTF_8),Arrays.copyOfRange(maximum,132,195));
        UhidEventBuilder.create2(HidProfile.XBOX_ONE_S,"x".repeat(63));
        assertThrows(IllegalArgumentException.class,()->UhidEventBuilder.createGamepad(PROFILE,"x".repeat(64)));
        assertThrows(IllegalArgumentException.class,()->UhidEventBuilder.createKeyboard(HidKeyboardProfile.INSTANCE,"가".repeat(22)));
        assertThrows(IllegalArgumentException.class,()->UhidEventBuilder.create2(HidProfile.XBOX_ONE_S,"a\0b"));
        assertThrows(NullPointerException.class,()->UhidEventBuilder.createGamepad(PROFILE,null));
        byte[] legacy=UhidEventBuilder.create2(HidProfile.XBOX_ONE_S);
        for(int i=132;i<260;i++)assertEquals(0,legacy[i]);
    }

    private static Map<Integer,AxisRange> capturedAxes() {
        Map<Integer,AxisRange> axes=new HashMap<>();
        for(int code:new int[]{0,1,2,5})axes.put(code,new AxisRange(-127,127,15));
        for(int code:new int[]{9,10})axes.put(code,new AxisRange(0,255,15));
        for(int code:new int[]{16,17})axes.put(code,new AxisRange(-1,1,0));
        return axes;
    }
    private static Set<Integer> capturedButtons() {
        Set<Integer> keys=new HashSet<>();for(int code=304;code<=318;code++)keys.add(code);return keys;
    }
    private static MappingUpdate sync(G8InputMapper mapper) { return mapper.accept(new EvdevEvent(0,0,0)); }
    private static void assertField(Descriptor d,int page,int usage,int offset,int bits,int min,int max) {
        Field f=d.field(page,usage);assertEquals(offset,f.offset);assertEquals(bits,f.bits);assertEquals(min,f.min);assertEquals(max,f.max);
    }
    static final class Field { int page,usage,offset,bits,min,max,flags;boolean constant; }

    /** Independent HID short-item interpretation; never reads production encoding offsets. */
    static final class Descriptor {
        final List<Field> fields=new ArrayList<>();
        int inputBits,outputBits;
        boolean gamepadApplication;
        Descriptor(byte[] bytes) {
            int page=0,min=0,max=0,size=0,count=0,usageMin=-1,usageMax=-1,depth=0;
            List<Integer> usages=new ArrayList<>();
            for(int pos=0;pos<bytes.length;) {
                int prefix=bytes[pos++]&255;assertNotEquals(0xfe,prefix);
                int len=(prefix&3)==3?4:prefix&3;assertTrue(pos+len<=bytes.length);
                int value=0;for(int i=0;i<len;i++)value|=(bytes[pos++]&255)<<(8*i);
                int type=(prefix>>>2)&3,tag=prefix>>>4;
                if(type==1) {
                    switch(tag) {
                        case 0:page=value;break;
                        case 1:min=signed(value,len*8);break;
                        case 2:max=min<0?signed(value,len*8):value;break;
                        case 7:size=value;break;
                        case 8:fail("Native report must be unnumbered");break;
                        case 9:count=value;break;
                        case 3:case 4:case 5:case 6:break; // physical range/units do not change bits
                        default:fail("Unexpected global item");
                    }
                } else if(type==2) {
                    if(tag==0)usages.add(value);else if(tag==1)usageMin=value;else if(tag==2)usageMax=value;else fail("Unexpected local item");
                } else if(type==0) {
                    if(tag==10) {depth++;if(value==1&&page==1&&usages.contains(5))gamepadApplication=true;}
                    else if(tag==12) {depth--;assertTrue(depth>=0);}
                    else if(tag==8 || tag==9) {
                        assertTrue(size>0&&size<=32&&count>0&&count<=64);
                        if(tag==8)for(int i=0;i<count;i++) {
                            Field f=new Field();f.page=page;f.offset=inputBits+i*size;f.bits=size;f.min=min;f.max=max;f.flags=value;f.constant=(value&1)!=0;
                            f.usage=i<usages.size()?usages.get(i):usageMin>=0&&usageMin+i<=usageMax?usageMin+i:-1;fields.add(f);
                        }
                        if(tag==8)inputBits+=size*count;else outputBits+=size*count;
                    } else fail("Unexpected main item");
                    usages.clear();usageMin=usageMax=-1;
                } else fail("Reserved HID item");
            }
            assertEquals(0,depth);
        }
        Field field(int page,int usage) {
            for(Field f:fields)if(!f.constant&&f.page==page&&f.usage==usage)return f;
            throw new AssertionError("Missing usage "+page+":"+usage);
        }
        int value(byte[] report,int page,int usage) {
            Field f=field(page,usage);int value=0;
            for(int bit=0;bit<f.bits;bit++){int at=f.offset+bit;value|=((report[at/8]>>>(at%8))&1)<<bit;}
            return f.min<0?signed(value,f.bits):value;
        }
        private static int signed(int value,int bits) {return bits==0?0:bits==32?value:(value<<(32-bits))>>(32-bits);}
    }
}
