package dev.codek.xboxprobe.core;

import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public final class ControllerProfileCoreTest {
    @Test public void defaultsAreExplicitAndPreserveTheWorkingKeyboardMappings() {
        assertEquals(15,ControllerProfile.defaultButtons(ControllerProfile.Kind.NATIVE).size());
        assertEquals(15,ControllerProfile.defaultButtons(ControllerProfile.Kind.XBOX).size());
        assertEquals(10,ControllerProfile.supportedTargetButtons(ControllerProfile.Kind.XBOX).size());
        for(int code:new int[]{306,309,312,313,316})assertEquals(Integer.valueOf(0),ControllerProfile.defaultButtons(ControllerProfile.Kind.XBOX).get(code));
        assertTrue(ControllerProfile.defaultButtons(ControllerProfile.Kind.KEYBOARD).isEmpty());
        Map<Integer,Integer> keyboard=ControllerProfile.defaultKeyboardBindings();
        assertEquals(Map.of(304,0x0d,305,0x0e,307,0x2c,308,0x18,310,0x1e,311,0x1f,314,0x29,315,0x29),keyboard);
        assertThrows(UnsupportedOperationException.class,()->keyboard.put(304,0));
        assertThrows(UnsupportedOperationException.class,()->ControllerProfile.supportedTargetButtons(ControllerProfile.Kind.NATIVE).clear());
    }

    @Test public void profileNormalizesOnlyDocumentedDefaultsAndPreservesExplicitDisabledValues() {
        Map<Integer,Integer> map=new HashMap<>();map.put(304,0);map.put(305,308);
        ControllerProfile p=profile(ControllerProfile.Kind.NATIVE,map,Map.of(),ControllerProfile.AxisConfig.DEFAULT);
        map.put(304,307);assertEquals(Integer.valueOf(0),p.getButtonMappings().get(304));
        assertEquals(Integer.valueOf(308),p.getButtonMappings().get(305));
        assertEquals(Integer.valueOf(307),p.getButtonMappings().get(307));
        assertThrows(UnsupportedOperationException.class,()->p.getButtonMappings().clear());
        ControllerProfile keyboard=profile(ControllerProfile.Kind.KEYBOARD,Map.of(),Map.of(700,0,701,0x0d),ControllerProfile.AxisConfig.DEFAULT);
        assertEquals(Integer.valueOf(0),keyboard.getKeyboardBindings().get(700));
        assertEquals(Integer.valueOf(0x0d),keyboard.getKeyboardBindings().get(701));
        assertTrue(profile(ControllerProfile.Kind.KEYBOARD,Map.of(),Map.of(),ControllerProfile.AxisConfig.DEFAULT).getKeyboardBindings().isEmpty());
    }

    @Test public void immutableValueEqualityIncludesIdentityMappingsAndAxisSettings() {
        ControllerProfile.AxisConfig axes=new ControllerProfile.AxisConfig(true,true,false,false,true,true,20);
        ControllerProfile a=profile(ControllerProfile.Kind.NATIVE,Map.of(304,308),Map.of(),axes);
        ControllerProfile b=profile(ControllerProfile.Kind.NATIVE,Map.of(304,308),Map.of(),new ControllerProfile.AxisConfig(true,true,false,false,true,true,20));
        assertEquals(a,b);assertEquals(a.hashCode(),b.hashCode());
        assertNotEquals(a,profile(ControllerProfile.Kind.NATIVE,Map.of(304,307),Map.of(),axes));
        assertEquals(a.getIdentity(),new HidIdentity(a.getDeviceName(),a.getVendorId(),a.getProductId(),a.getBus()));
    }

    @Test public void unsupportedTargetsAndInapplicableAxisSettingsFailAtProfileBoundary() {
        assertThrows(IllegalArgumentException.class,()->profile(ControllerProfile.Kind.XBOX,Map.of(304,306),Map.of(),ControllerProfile.AxisConfig.DEFAULT));
        assertThrows(IllegalArgumentException.class,()->profile(ControllerProfile.Kind.NATIVE,Map.of(768,304),Map.of(),ControllerProfile.AxisConfig.DEFAULT));
        assertThrows(IllegalArgumentException.class,()->profile(ControllerProfile.Kind.KEYBOARD,Map.of(),Map.of(768,0x0d),ControllerProfile.AxisConfig.DEFAULT));
        assertThrows(IllegalArgumentException.class,()->profile(ControllerProfile.Kind.KEYBOARD,Map.of(),Map.of(304,3),ControllerProfile.AxisConfig.DEFAULT));
        ControllerProfile.AxisConfig nondefault=new ControllerProfile.AxisConfig(false,false,false,false,false,false,1);
        for(ControllerProfile.Kind kind:new ControllerProfile.Kind[]{ControllerProfile.Kind.DIRECT,ControllerProfile.Kind.KEYBOARD})
            assertThrows(IllegalArgumentException.class,()->profile(kind,Map.of(),Map.of(),nondefault));
        assertThrows(IllegalArgumentException.class,()->profile(ControllerProfile.Kind.DIRECT,Map.of(),Map.of(304,0),ControllerProfile.AxisConfig.DEFAULT));
        assertThrows(IllegalArgumentException.class,()->new ControllerProfile.AxisConfig(false,false,false,false,false,false,41));
    }

    @Test public void identityIsIndependentOfReportKindAndKeyboardIdentityIsEditable() {
        ControllerProfile keyboard=new ControllerProfile("custom","맞춤 키보드",ControllerProfile.Kind.KEYBOARD,
                " Custom keyboard ",0x1532,0x071a,5,Map.of(),Map.of(),ControllerProfile.AxisConfig.DEFAULT);
        assertEquals(" Custom keyboard ",keyboard.getDeviceName());
        assertArrayEquals(HidKeyboardProfile.keyboardDescriptor(),keyboard.getDescriptor());
        byte[] created=UhidEventBuilder.createConfigured(keyboard,"g8-input-bridge:test");
        ByteBuffer b=ByteBuffer.wrap(created).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(5,b.getShort(262)&65535);assertEquals(0x1532,b.getInt(264));assertEquals(0x071a,b.getInt(268));
        assertEquals(63,b.getShort(260)&65535);assertArrayEquals(new byte[8],keyboard.neutralReport());
        byte[] name=keyboard.getDeviceName().getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(name,Arrays.copyOfRange(created,4,4+name.length));
        HidKeyboardProfile custom=HidKeyboardProfile.INSTANCE.withIdentity("Other keyboard",0,0,3);
        assertEquals("Other keyboard",custom.getName());assertEquals(0,custom.getVendorId());
        ControllerProfile xbox=new ControllerProfile("x","x",ControllerProfile.Kind.XBOX,"same metadata",0x3537,0x0108,3,
                Map.of(),Map.of(),ControllerProfile.AxisConfig.DEFAULT);
        assertArrayEquals(HidProfile.XBOX_ONE_S.getDescriptor(),xbox.getDescriptor());
        assertEquals(16,xbox.neutralReport().length);
    }

    @Test public void directModeCannotAccidentallyCreateVirtualOutputOrMappings() {
        ControllerProfile direct=profile(ControllerProfile.Kind.DIRECT,Map.of(),Map.of(),ControllerProfile.AxisConfig.DEFAULT);
        assertThrows(IllegalStateException.class,direct::getDescriptor);
        assertThrows(IllegalStateException.class,direct::neutralReport);
        assertThrows(IllegalStateException.class,()->direct.encodeProbe(GamepadState.NEUTRAL));
        assertThrows(IllegalStateException.class,()->direct.createMapper(Map.of(),Set.of()));
        assertThrows(IllegalStateException.class,()->UhidEventBuilder.createConfigured(direct,"g8-input-bridge:test"));
    }

    @Test public void manySourcesMappedToOneNativeButtonRetainTheOtherHeldSource() {
        ControllerProfile p=profile(ControllerProfile.Kind.NATIVE,Map.of(304,307,305,307,308,0),Map.of(),ControllerProfile.AxisConfig.DEFAULT);
        G8InputMapper mapper=p.createMapper(axes(),buttons());
        mapper.accept(key(304,1));mapper.accept(key(305,1));byte[] report=sync(mapper).getReport();
        NativeGamepadCoreTest.Descriptor d=new NativeGamepadCoreTest.Descriptor(p.getDescriptor());
        assertEquals(1,d.value(report,9,4));assertEquals(0,d.value(report,9,1));
        mapper.accept(key(304,0));assertNull(sync(mapper).getReport());
        mapper.accept(key(308,1));assertNull(sync(mapper).getReport());
        mapper.accept(key(305,0));assertArrayEquals(p.neutralReport(),sync(mapper).getReport());
    }

    @Test public void xboxRemappingUsesLogicalTenButtonDomainAndSupportsExtraSourceRemaps() {
        ControllerProfile p=profile(ControllerProfile.Kind.XBOX,Map.of(304,308,305,308,306,304),Map.of(),ControllerProfile.AxisConfig.DEFAULT);
        G8InputMapper mapper=p.createMapper(axes(),buttons());
        CoreProtocolTest.Descriptor d=new CoreProtocolTest.Descriptor(p.getDescriptor());
        mapper.accept(key(304,1));mapper.accept(key(305,1));mapper.accept(key(306,1));
        byte[] report=sync(mapper).getReport();
        assertEquals(1,xboxValue(d,report,9,1));assertEquals(1,xboxValue(d,report,9,4));
        assertEquals(0,xboxValue(d,report,9,3)); // target Y is not legacy X bit
        mapper.accept(key(304,0));assertNull(sync(mapper).getReport());
        mapper.accept(key(305,0));report=sync(mapper).getReport();assertEquals(1,xboxValue(d,report,9,1));assertEquals(0,xboxValue(d,report,9,4));
        mapper.accept(key(306,0));assertArrayEquals(p.neutralReport(),sync(mapper).getReport());
        mapper.accept(key(316,1));assertNull(sync(mapper).getReport()); // explicit default disabled
        byte[] probe=p.encodeProbe(new GamepadState(0,0,0,0,0,0,-1,GamepadState.A));
        assertEquals(1,xboxValue(d,probe,9,1));assertEquals(0,xboxValue(d,probe,9,4)); // output probe bypasses source map
    }

    @Test public void swapsInversionsAndDeadzoneApplyOnceInDocumentedOrderForBothEncoders() {
        ControllerProfile.AxisConfig config=new ControllerProfile.AxisConfig(true,true,false,false,true,true,20);
        Map<Integer,AxisRange> ranges=axes();for(int code:new int[]{0,1,2,5})ranges.put(code,new AxisRange(-100,100,0));
        for(ControllerProfile.Kind kind:new ControllerProfile.Kind[]{ControllerProfile.Kind.NATIVE,ControllerProfile.Kind.XBOX}) {
            ControllerProfile p=profile(kind,Map.of(),Map.of(),config);G8InputMapper mapper=p.createMapper(ranges,buttons());
            for(int[] event:new int[][]{{0,-100},{1,50},{2,100},{5,-50},{10,0},{9,255}})mapper.accept(abs(event[0],event[1]));
            byte[] report=sync(mapper).getReport();
            if(kind==ControllerProfile.Kind.NATIVE) {
                NativeGamepadCoreTest.Descriptor d=new NativeGamepadCoreTest.Descriptor(p.getDescriptor());
                assertEquals(-127,d.value(report,1,0x30));assertEquals(-48,d.value(report,1,0x31));
                assertEquals(-127,d.value(report,1,0x32));assertEquals(-48,d.value(report,1,0x35));
                assertEquals(255,d.value(report,2,0xc5));assertEquals(0,d.value(report,2,0xc4));
            } else {
                CoreProtocolTest.Descriptor d=new CoreProtocolTest.Descriptor(p.getDescriptor());
                assertEquals(0,xboxValue(d,report,1,0x30));assertEquals(20480,xboxValue(d,report,1,0x31));
                assertEquals(0,xboxValue(d,report,1,0x33));assertEquals(20480,xboxValue(d,report,1,0x34));
                assertEquals(1023,xboxValue(d,report,1,0x32));assertEquals(0,xboxValue(d,report,1,0x35));
            }
            assertArrayEquals(p.neutralReport(),mapper.disconnect().getReport());
        }
    }

    @Test public void xboxPreservesSourcePrecisionWithoutAnIntermediateEightBitNativeFrame() {
        ControllerProfile p=profile(ControllerProfile.Kind.XBOX,Map.of(),Map.of(),ControllerProfile.AxisConfig.DEFAULT);
        Map<Integer,AxisRange> ranges=axes();ranges.put(0,new AxisRange(-1000,1000,0));
        G8InputMapper mapper=p.createMapper(ranges,buttons());mapper.accept(abs(0,1));
        assertEquals(32801,xboxValue(new CoreProtocolTest.Descriptor(p.getDescriptor()),sync(mapper).getReport(),1,0x30));
    }

    @Test public void deadzoneCenterAndFailClosedStateUseEachOutputNeutralFormat() {
        ControllerProfile.AxisConfig config=new ControllerProfile.AxisConfig(false,false,false,false,false,false,40);
        Map<Integer,AxisRange> ranges=axes();ranges.put(0,new AxisRange(-100,100,0));
        for(ControllerProfile.Kind kind:new ControllerProfile.Kind[]{ControllerProfile.Kind.NATIVE,ControllerProfile.Kind.XBOX}) {
            ControllerProfile p=profile(kind,Map.of(),Map.of(),config);G8InputMapper mapper=p.createMapper(ranges,buttons());
            assertNull(sync(mapper).getReport());mapper.accept(abs(0,40));assertNull(sync(mapper).getReport());
            mapper.accept(abs(0,100));assertNotNull(sync(mapper).getReport());
            MappingUpdate failure=mapper.accept(new EvdevEvent(0,3,0));assertTrue(failure.isFailed());
            assertArrayEquals(p.neutralReport(),failure.getReport());
            assertArrayEquals(p.neutralReport(),mapper.accept(key(304,1)).getReport());
        }
    }

    @Test public void keyboardFactoryPreservesNonstandardPrefsDisabledButtonsAndHysteresis() {
        ControllerProfile p=profile(ControllerProfile.Kind.KEYBOARD,Map.of(),Map.of(700,0x0d,304,0,702,0),ControllerProfile.AxisConfig.DEFAULT);
        assertThrows(IllegalArgumentException.class,()->p.createMapper(axes(),buttons())); // Active missing key remains invalid.
        Set<Integer> source=buttons();source.add(700);Map<Integer,AxisRange> ranges=axes();ranges.put(0,new AxisRange(-100,100,0));
        G8InputMapper mapper=p.createMapper(ranges,source);mapper.accept(key(700,1));
        assertArrayEquals(new byte[]{0,0,0x0d,0,0,0,0,0},sync(mapper).getReport());
        mapper.accept(key(304,1));assertNull(sync(mapper).getReport());mapper.accept(key(700,0));sync(mapper);
        mapper.accept(abs(0,34));assertNull(sync(mapper).getReport());mapper.accept(abs(0,35));
        assertArrayEquals(new byte[]{0,0,7,0,0,0,0,0},sync(mapper).getReport());
        mapper.accept(abs(0,26));assertNull(sync(mapper).getReport());mapper.accept(abs(0,25));assertArrayEquals(new byte[8],sync(mapper).getReport());
    }

    @Test public void malformedIdentityTextAndResourceLimitsAreRejectedWithoutNormalization() {
        assertThrows(IllegalArgumentException.class,()->new HidIdentity(Character.toString((char)0xd800),1,1,3));
        assertThrows(IllegalArgumentException.class,()->new HidIdentity("a\0b",1,1,3));
        assertThrows(IllegalArgumentException.class,()->new ControllerProfile("x".repeat(129),"label",ControllerProfile.Kind.DIRECT,"pad",0,0,3,Map.of(),Map.of(),ControllerProfile.AxisConfig.DEFAULT));
        assertThrows(IllegalArgumentException.class,()->new ControllerProfile("id","label",ControllerProfile.Kind.DIRECT,"pad",0,0,2,Map.of(),Map.of(),ControllerProfile.AxisConfig.DEFAULT));
    }

    private static ControllerProfile profile(ControllerProfile.Kind kind,Map<Integer,Integer> buttons,Map<Integer,Integer> keyboard,ControllerProfile.AxisConfig axes) {
        return new ControllerProfile("profile-id","Profile",kind,"Selected metadata",0x1234,0x5678,3,buttons,keyboard,axes);
    }
    private static Map<Integer,AxisRange> axes(){Map<Integer,AxisRange> map=new HashMap<>();for(int code:new int[]{0,1,2,5})map.put(code,new AxisRange(-127,127,15));for(int code:new int[]{9,10})map.put(code,new AxisRange(0,255,15));for(int code:new int[]{16,17})map.put(code,new AxisRange(-1,1,0));return map;}
    private static Set<Integer> buttons(){Set<Integer> result=new HashSet<>();for(int i=304;i<=318;i++)result.add(i);return result;}
    private static EvdevEvent key(int code,int value){return new EvdevEvent(1,code,value);}
    private static EvdevEvent abs(int code,int value){return new EvdevEvent(3,code,value);}
    private static MappingUpdate sync(G8InputMapper mapper){return mapper.accept(new EvdevEvent(0,0,0));}
    private static int xboxValue(CoreProtocolTest.Descriptor d,byte[] report,int page,int usage){CoreProtocolTest.Field f=d.field(1,page,usage);int value=0;for(int bit=0;bit<f.bits;bit++){int index=f.offset+bit;value|=((report[1+index/8]>>>(index%8))&1)<<bit;}return value;}
}
