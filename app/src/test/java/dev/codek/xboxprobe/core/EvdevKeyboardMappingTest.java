package dev.codek.xboxprobe.core;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class EvdevKeyboardMappingTest {
    @Test public void arm64FixtureSurvivesEveryPossibleSplitAndRetainsSignedValue() throws Exception {
        byte[] raw = hex("05 04 03 02 01 00 00 00 d0 07 00 00 00 00 00 00 03 00 00 00 2e fb ff ff");
        for (int split=0; split<=raw.length; split++) {
            EvdevDecoder decoder = new EvdevDecoder(24);
            List<EvdevEvent> events = new ArrayList<>();
            decoder.accept(raw,0,split,events::add);
            decoder.accept(raw,split,raw.length-split,events::add);
            decoder.finish();
            assertEquals(0,decoder.getPendingByteCount());
            assertEquals(1,events.size());
            EvdevEvent event=events.get(0);
            assertEquals(0x0102030405L,event.seconds);
            assertEquals(2000,event.micros);
            assertEquals(3,event.type);
            assertEquals(0,event.code);
            assertEquals(-1234,event.value);
        }
    }

    @Test public void arm32FixtureAndNonzeroOffsetsDoNotReadAdjacentBytes() throws Exception {
        byte[] raw=hex("aa bb 04 03 02 01 d0 07 00 00 01 00 30 01 01 00 00 00 cc dd");
        EvdevDecoder decoder=new EvdevDecoder(16);
        List<EvdevEvent> events=new ArrayList<>();
        decoder.accept(raw,2,16,events::add);
        decoder.finish();
        assertEquals(16,decoder.getEventSize());
        assertEquals(1,events.size());
        assertEquals(0x01020304,events.get(0).seconds);
        assertEquals(2000,events.get(0).micros);
        assertEquals(1,events.get(0).type);
        assertEquals(304,events.get(0).code);
        assertEquals(1,events.get(0).value);
    }

    @Test public void decoderBoundsAndTruncatedEofFailInsteadOfLosingAnEvent() throws Exception {
        assertThrows(IllegalArgumentException.class,()->new EvdevDecoder(20));
        EvdevDecoder decoder=new EvdevDecoder(24);
        assertThrows(IndexOutOfBoundsException.class,()->decoder.accept(new byte[24],Integer.MAX_VALUE,2,e->{}));
        assertThrows(IndexOutOfBoundsException.class,()->decoder.accept(new byte[24],0,-1,e->{}));
        decoder.accept(new byte[23],0,23,e->fail("Partial event must not be delivered"));
        assertEquals(23,decoder.getPendingByteCount());
        assertThrows(IOException.class,decoder::finish);
        decoder.accept(new byte[1],0,1,e->assertEquals(0,e.type));
        decoder.finish();
        assertEquals(0,decoder.getPendingByteCount());
    }

    @Test public void fragmentedRawPressRepeatReleaseProducesOnlyCommittedKeyboardTransitions() throws Exception {
        byte[] events=concat(rawEvent(1,304,1),rawEvent(0,0,0),rawEvent(1,304,2),
                rawEvent(0,0,0),rawEvent(1,304,0),rawEvent(0,0,0));
        for(int chunk=1;chunk<=25;chunk++) {
            EvdevDecoder decoder=new EvdevDecoder(24);
            G8KeyboardMapper mapper=mapper(null,null,null,null,bindings());
            List<byte[]> reports=new ArrayList<>();
            for(int pos=0;pos<events.length;) {
                int size=Math.min(chunk,events.length-pos);
                decoder.accept(events,pos,size,event->{
                    MappingUpdate update=mapper.accept(event);
                    assertFalse(update.getError(),update.isFailed());
                    if(update.getReport()!=null)reports.add(update.getReport());
                });
                assertTrue(decoder.getPendingByteCount()<24);
                pos+=size;
            }
            decoder.finish();
            assertEquals(2,reports.size());
            assertArrayEquals(report(0x0d),reports.get(0));
            assertArrayEquals(new byte[8],reports.get(1));
        }
    }

    @Test public void allFourFaceMappingsCommitTogetherAndReleasesAreIndependent() {
        G8KeyboardMapper mapper=mapper(null,null,null,null,bindings());
        for(int code:new int[]{304,305,307,308})assertNull(mapper.accept(key(code,1)).getReport());
        assertArrayEquals(report(0x0d,0x0e,0x18,0x2c),sync(mapper).getReport());
        mapper.accept(key(305,0));
        assertArrayEquals(report(0x0d,0x18,0x2c),sync(mapper).getReport());
        mapper.accept(key(304,0)); mapper.accept(key(307,0)); mapper.accept(key(308,0));
        assertArrayEquals(new byte[8],sync(mapper).getReport());
        assertNull(sync(mapper).getReport());
    }

    @Test public void thresholdsUseCapturedRangeAndHysteresisAtExactBoundaries() {
        G8KeyboardMapper mapper=mapper(new AxisRange(0,2000,16),null,null,null,bindings());
        assertNull(axisFrame(mapper,0,1349).getReport());
        assertArrayEquals(report(0x07),axisFrame(mapper,0,1350).getReport());
        assertNull(axisFrame(mapper,0,1300).getReport());
        assertNull(axisFrame(mapper,0,1251).getReport());
        assertArrayEquals(new byte[8],axisFrame(mapper,0,1250).getReport());
        assertNull(axisFrame(mapper,0,700).getReport());
        assertArrayEquals(report(0x04),axisFrame(mapper,0,650).getReport());
        assertNull(axisFrame(mapper,0,749).getReport());
        assertArrayEquals(new byte[8],axisFrame(mapper,0,750).getReport());
        assertArrayEquals(report(0x04),axisFrame(mapper,0,650).getReport());
        assertArrayEquals(report(0x07),axisFrame(mapper,0,1350).getReport());
    }

    @Test public void duplicateBindingsAndAxisOwnershipNeverReleaseAnotherHeldSource() {
        Map<Integer,Integer> bindings=bindings(); bindings.put(305,0x0d);
        G8KeyboardMapper mapper=mapper(new AxisRange(-100,100,0),null,new AxisRange(-1,1,0),null,bindings);
        mapper.accept(key(304,1)); mapper.accept(key(305,1));
        assertArrayEquals(report(0x0d),sync(mapper).getReport());
        mapper.accept(key(304,0)); assertNull(sync(mapper).getReport());
        mapper.accept(abs(0,100)); mapper.accept(abs(16,1));
        assertArrayEquals(report(0x07,0x0d),sync(mapper).getReport());
        mapper.accept(abs(0,0)); assertNull(sync(mapper).getReport());
        mapper.accept(key(305,0)); assertArrayEquals(report(0x07),sync(mapper).getReport());
        mapper.accept(abs(16,0)); assertArrayEquals(new byte[8],sync(mapper).getReport());
    }

    @Test public void buttonMappedToMovementUsageAlsoRetainsSeparateOwnership() {
        Map<Integer,Integer> bindings=bindings(); bindings.put(304,0x07);
        G8KeyboardMapper mapper=mapper(new AxisRange(-100,100,0),null,null,null,bindings);
        mapper.accept(key(304,1)); mapper.accept(abs(0,100));
        assertArrayEquals(report(0x07),sync(mapper).getReport());
        mapper.accept(key(304,0)); assertNull(sync(mapper).getReport());
        assertArrayEquals(new byte[8],axisFrame(mapper,0,0).getReport());
    }

    @Test public void omittedCapabilitiesAndUnmappedEventsNeverCreateKeys() {
        G8KeyboardMapper mapper=mapper(null,null,null,null,bindings());
        mapper.accept(abs(0,Integer.MAX_VALUE));
        mapper.accept(abs(16,1));
        mapper.accept(key(30,1)); // keyboard A is not a gamepad binding
        mapper.accept(key(304,2)); // repeat without observed press does not create a held key
        mapper.accept(new EvdevEvent(2,0,100)); // relative input is not a supported mapping
        assertNull(sync(mapper).getReport());
        mapper.accept(key(304,1));
        assertArrayEquals(report(0x0d),sync(mapper).getReport());
    }

    @Test public void droppedStreamReleasesImmediatelyAndCannotResumeWithoutReselection() {
        G8KeyboardMapper mapper=mapper(null,null,null,null,bindings());
        mapper.accept(key(304,1)); sync(mapper);
        MappingUpdate dropped=mapper.accept(new EvdevEvent(0,3,0));
        assertFailure(dropped,MappingUpdate.Failure.SYN_DROPPED);
        assertFailure(mapper.accept(key(305,1)),MappingUpdate.Failure.SYN_DROPPED);
        assertFailure(sync(mapper),MappingUpdate.Failure.SYN_DROPPED);
        byte[] mutable=dropped.getReport(); mutable[2]=0x1a;
        assertArrayEquals(new byte[8],dropped.getReport());
    }

    @Test public void disconnectAndInvalidMappedInputsFailWithAllKeysUp() {
        G8KeyboardMapper mapper=mapper(null,null,null,null,bindings());
        mapper.accept(key(304,1)); sync(mapper);
        assertFailure(mapper.disconnect(),MappingUpdate.Failure.SOURCE_LOST);
        assertFailure(sync(mapper),MappingUpdate.Failure.SOURCE_LOST);
        mapper=mapper(null,null,null,null,bindings());
        assertFailure(mapper.accept(key(304,3)),MappingUpdate.Failure.INVALID_KEY_VALUE);
        mapper=mapper(new AxisRange(0,255,0),null,null,null,bindings());
        assertFailure(mapper.accept(abs(0,256)),MappingUpdate.Failure.AXIS_OUT_OF_RANGE);
    }

    @Test public void moreThanSixDistinctKeysFailsClosedButExactlySixWorks() {
        AxisRange analog=new AxisRange(-100,100,0),hat=new AxisRange(-1,1,0);
        G8KeyboardMapper mapper=mapper(analog,analog,hat,hat,bindings());
        mapper.accept(abs(0,100)); mapper.accept(abs(1,100)); // D + S
        mapper.accept(abs(16,-1)); mapper.accept(abs(17,-1)); // A + W
        mapper.accept(key(304,1)); mapper.accept(key(305,1)); // J + K = six
        assertArrayEquals(report(4,7,13,14,22,26),sync(mapper).getReport());
        mapper.accept(key(307,1));
        assertFailure(sync(mapper),MappingUpdate.Failure.CAPACITY_EXCEEDED);
    }

    @Test public void rangesAndBindingsAreValidatedAndCallerMutationCannotChangeMapping() {
        AxisRange wide=new AxisRange(Integer.MIN_VALUE,Integer.MAX_VALUE,0);
        assertEquals(-1,wide.normalize(Integer.MIN_VALUE),0);
        assertEquals(1,wide.normalize(Integer.MAX_VALUE),0);
        assertThrows(IllegalArgumentException.class,()->new AxisRange(1,1,0));
        assertThrows(IllegalArgumentException.class,()->new AxisRange(0,255,-1));
        assertThrows(IllegalArgumentException.class,()->new AxisRange(0,255,256));
        Map<Integer,Integer> bindings=bindings();
        G8KeyboardMapper mapper=mapper(null,null,null,null,bindings);
        bindings.put(304,0x29);
        mapper.accept(key(304,1));
        assertArrayEquals(report(0x0d),sync(mapper).getReport());
        assertThrows(IllegalArgumentException.class,()->mapper(null,null,null,null,Map.of(0x300,0x0d)));
        assertThrows(IllegalArgumentException.class,()->mapper(null,null,null,null,Map.of(0,0x0d)));
        assertThrows(IllegalArgumentException.class,()->mapper(null,null,null,null,Map.of(304,0)));
    }

    @Test public void explicitlyConfiguredAdditionalGamepadKeysNeedNoSecondCapabilityInterpretation() {
        G8KeyboardMapper mapper=mapper(null,null,null,null,Map.of(310,0x18,315,0x29));
        mapper.accept(key(310,1)); mapper.accept(key(315,1));
        assertArrayEquals(report(0x18,0x29),sync(mapper).getReport());
        mapper.accept(key(310,0));
        assertArrayEquals(report(0x29),sync(mapper).getReport());
    }

    private static G8KeyboardMapper mapper(AxisRange x,AxisRange y,AxisRange hx,AxisRange hy,Map<Integer,Integer> bindings) {
        return new G8KeyboardMapper(x,y,hx,hy,bindings);
    }
    private static Map<Integer,Integer> bindings() {
        Map<Integer,Integer> bindings=new HashMap<>();
        bindings.put(304,0x0d); bindings.put(305,0x0e); bindings.put(307,0x18); bindings.put(308,0x2c);
        return bindings;
    }
    private static EvdevEvent key(int code,int value) { return new EvdevEvent(1,code,value); }
    private static EvdevEvent abs(int code,int value) { return new EvdevEvent(3,code,value); }
    private static MappingUpdate sync(G8KeyboardMapper mapper) { return mapper.accept(new EvdevEvent(0,0,0)); }
    private static MappingUpdate axisFrame(G8KeyboardMapper mapper,int code,int value) {
        MappingUpdate update=mapper.accept(abs(code,value));
        return update.isFailed()?update:sync(mapper);
    }
    private static void assertFailure(MappingUpdate update,MappingUpdate.Failure failure) {
        assertTrue(update.isFailed()); assertEquals(failure,update.getFailure());
        assertFalse(update.getError().isEmpty()); assertArrayEquals(new byte[8],update.getReport());
    }
    private static byte[] report(int... keys) {
        byte[] report=new byte[8];
        for(int i=0;i<keys.length;i++)report[2+i]=(byte)keys[i];
        return report;
    }
    private static byte[] rawEvent(int type,int code,int value) {
        return ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(42).putLong(123).putShort((short)type).putShort((short)code).putInt(value).array();
    }
    private static byte[] concat(byte[]... parts) {
        int length=0; for(byte[] part:parts)length+=part.length;
        byte[] result=new byte[length]; int offset=0;
        for(byte[] part:parts){System.arraycopy(part,0,result,offset,part.length);offset+=part.length;}
        return result;
    }
    private static byte[] hex(String value) {
        String[] parts=value.split(" "); byte[] result=new byte[parts.length];
        for(int i=0;i<parts.length;i++)result[i]=(byte)Integer.parseInt(parts[i],16);
        return result;
    }
}
