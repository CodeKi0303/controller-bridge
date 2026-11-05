package dev.codek.xboxprobe.core;

import static org.junit.Assert.*;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/** Compound evdev frames through the same pure boundary owned by UhidSourceBridge.
 * This verifies report contents/ordering, not Android driver or game support. */
public final class SimultaneousInputStreamTest {
    private static final ControllerProfile.Kind[] GAMEPADS={ControllerProfile.Kind.NATIVE,ControllerProfile.Kind.XBOX};

    @Test public void bothSticksTriggersHatAndAllOutputButtonsCommitTogetherAndReleaseIndependently()throws Exception{
        for(ControllerProfile.Kind kind:GAMEPADS)for(int eventSize:new int[]{16,24}){
            Stream stream=new Stream(gamepad(kind),axes(),allSourceButtons(),eventSize);
            List<EvdevEvent> chord=new ArrayList<>(List.of(abs(0,127),abs(1,-127),abs(2,-127),abs(5,127),
                    abs(10,255),abs(9,128),abs(16,1),abs(17,-1)));
            for(int code=304;code<=318;code++)chord.add(key(code,1));
            stream.feed(chord,7);
            assertEquals("A partial frame must never emit a partial chord",0,stream.reports.size());
            stream.feed(List.of(sync()),5);
            assertEquals(1,stream.reports.size());
            assertGamepad(kind,stream.reports.get(0),127,-127,-127,127,255,128,1,0x7fff,0x3ff);

            // Release A and LT and center only RX. Other held controls must not
            // be erased by the new frame or by the order of individual events.
            stream.feed(List.of(key(304,0),abs(10,0),abs(2,0)),11);
            assertEquals(1,stream.reports.size());
            stream.feed(List.of(sync()),3);
            assertEquals(2,stream.reports.size());
            assertGamepad(kind,stream.reports.get(1),127,-127,0,127,0,128,1,0x7ffe,0x3fe);
            stream.feed(List.of(key(305,2),sync()),4096);
            assertEquals("A repeat must retain the held chord without fabricating another report",2,stream.reports.size());
            assertTrue(stream.failures.isEmpty());
            stream.decoder.finish();
        }
    }

    @Test public void aBurstOfCompoundFramesKeepsEveryButtonEdgeAndOtherHeldControls()throws Exception{
        for(ControllerProfile.Kind kind:GAMEPADS){
            Stream stream=new Stream(gamepad(kind),axes(),allSourceButtons(),24);
            List<EvdevEvent> burst=new ArrayList<>(List.of(key(305,1),abs(1,-127),abs(5,127),abs(10,255),abs(9,128)));
            for(int frame=0;frame<128;frame++){
                burst.add(abs(0,(frame&1)==0?127:-127));
                burst.add(key(304,(frame&1)==0?1:0));
                burst.add(abs(2,(frame&1)==0?-127:127));
                burst.add(sync());
            }
            // 4096-byte reads split 24-byte events, as in the real reader.
            stream.feed(burst,4096);
            assertEquals(128,stream.reports.size());
            for(int frame=0;frame<128;frame++){
                boolean pressed=(frame&1)==0;
                assertGamepad(kind,stream.reports.get(frame),pressed?127:-127,-127,pressed?-127:127,127,
                        255,128,-1,pressed?3:2,pressed?3:2);
            }
            assertTrue(stream.failures.isEmpty());
            assertEquals(0,stream.decoder.getPendingByteCount());
            stream.decoder.finish();
        }
    }

    @Test public void keyboardSixUsageChordCanHaveMoreSourcesAndTransferOwnershipWithinOneFrame()throws Exception{
        Map<Integer,Integer> bindings=Map.of(304,0x0d,305,0x0d,307,0x0e,308,0x18,
                310,0x2c,311,0x29,314,0x07,315,0x07);
        Stream stream=new Stream(keyboard(bindings),axes(),allSourceButtons(),24);
        List<EvdevEvent> chord=new ArrayList<>(List.of(abs(0,127)));
        for(int code:bindings.keySet())chord.add(key(code,1));
        chord.add(sync());stream.feed(chord,13);
        assertEquals(1,stream.reports.size());
        // Eight physical buttons plus a stick own six distinct keys: D/J/K/U/Esc/Space.
        assertArrayEquals(new byte[]{0,0,7,13,14,24,41,44},stream.reports.get(0));
        stream.feed(List.of(key(304,0),key(314,0),abs(0,0),sync()),4096);
        assertEquals("J and D still have another held source",1,stream.reports.size());
        stream.feed(List.of(key(305,0),key(304,1),sync()),4096);
        assertEquals("Within-frame ownership transfer must not create a false key-up",1,stream.reports.size());
        stream.feed(List.of(key(315,0),sync()),7);
        assertEquals(2,stream.reports.size());
        assertArrayEquals(new byte[]{0,0,13,14,24,41,44,0},stream.reports.get(1));
        stream.feed(List.of(key(304,0),key(307,0),key(308,0),key(310,0),key(311,0),sync()),7);
        assertEquals(3,stream.reports.size());
        assertArrayEquals(new byte[8],stream.reports.get(2));
        assertTrue(stream.failures.isEmpty());
        stream.decoder.finish();
    }

    @Test public void seventhDistinctKeyboardUsageFailsTheFrameInsteadOfSilentlyLosingOneKey()throws Exception{
        Stream stream=new Stream(keyboard(ControllerProfile.defaultKeyboardBindings()),axes(),allSourceButtons(),16);
        // W+D movement, J+K+U+Space actions: exactly six simultaneous usages.
        stream.feed(List.of(abs(0,127),abs(1,-127),key(304,1),key(305,1),key(307,1),key(308,1),sync()),5);
        assertEquals(1,stream.reports.size());
        assertArrayEquals(new byte[]{0,0,7,13,14,24,26,44},stream.reports.get(0));
        stream.feed(List.of(key(314,1),sync()),4096); // Esc becomes the seventh usage.
        assertEquals("No truncated seven-key report may be published",1,stream.reports.size());
        assertEquals(1,stream.failures.size());
        MappingUpdate failure=stream.failures.get(0);
        assertEquals(MappingUpdate.Failure.CAPACITY_EXCEEDED,failure.getFailure());
        assertArrayEquals(new byte[8],failure.getReport());
        // The reader treats this as a failed input session; removing a key does
        // not silently resume the old mapper without explicit source restart.
        MappingUpdate after=stream.input.accept(key(314,0));
        assertTrue(after.isFailed());
        assertEquals(MappingUpdate.Failure.CAPACITY_EXCEEDED,after.getFailure());
        stream.decoder.finish();
    }

    private static ControllerProfile gamepad(ControllerProfile.Kind kind){
        return new ControllerProfile("chord-test","Chord",kind,"Test output",1,2,3,
                Map.of(),Map.of(),ControllerProfile.AxisConfig.DEFAULT);
    }
    private static ControllerProfile keyboard(Map<Integer,Integer> bindings){
        return new ControllerProfile("chord-keyboard","Chord keyboard",ControllerProfile.Kind.KEYBOARD,"Test keyboard",1,2,3,
                Map.of(),bindings,ControllerProfile.AxisConfig.DEFAULT);
    }
    private static Map<Integer,AxisRange> axes(){
        AxisRange stick=new AxisRange(-127,127,0),trigger=new AxisRange(0,255,0),hat=new AxisRange(-1,1,0);
        return Map.of(0,stick,1,stick,2,stick,5,stick,10,trigger,9,trigger,16,hat,17,hat);
    }
    private static Set<Integer> allSourceButtons(){Set<Integer> keys=new HashSet<>();for(int code=304;code<=318;code++)keys.add(code);return keys;}
    private static EvdevEvent abs(int code,int value){return new EvdevEvent(3,code,value);}
    private static EvdevEvent key(int code,int value){return new EvdevEvent(1,code,value);}
    private static EvdevEvent sync(){return new EvdevEvent(0,0,0);}

    private static void assertGamepad(ControllerProfile.Kind kind,byte[] report,int lx,int ly,int rx,int ry,
                                      int lt,int rt,int hat,int nativeButtons,int xboxButtons){
        if(kind==ControllerProfile.Kind.NATIVE){
            assertArrayEquals(new byte[]{(byte)lx,(byte)ly,(byte)rx,(byte)ry,(byte)lt,(byte)rt,
                    (byte)(hat<0?8:hat),(byte)nativeButtons,(byte)(nativeButtons>>>8)},report);
        }else{
            assertEquals(16,report.length);assertEquals(1,report[0]);
            assertEquals(xboxAxis(lx),u16(report,1));assertEquals(xboxAxis(ly),u16(report,3));
            assertEquals(xboxAxis(rx),u16(report,5));assertEquals(xboxAxis(ry),u16(report,7));
            assertEquals(lt==255?1023:0,u16(report,9));assertEquals(rt==128?514:0,u16(report,11));
            assertEquals(hat+1,report[13]);assertEquals(xboxButtons,u16(report,14));
        }
    }
    private static int xboxAxis(int value){if(value==127)return 65535;if(value==-127)return 0;if(value==0)return 32768;throw new AssertionError("Fixture axis must be an endpoint or neutral");}
    private static int u16(byte[] report,int at){return (report[at]&255)|((report[at+1]&255)<<8);}

    private static final class Stream {
        final EvdevDecoder decoder;
        final EvdevInputStream input;
        final List<byte[]> reports=new ArrayList<>();
        final List<MappingUpdate> failures=new ArrayList<>();
        Stream(ControllerProfile profile,Map<Integer,AxisRange> axes,Set<Integer> buttons,int eventSize){
            decoder=new EvdevDecoder(eventSize);
            input=new EvdevInputStream(new FileDescriptor(),()->profile.createMapper(axes,buttons),
                    fd->{throw new AssertionError("No overflow snapshot expected in an intact chord");},
                    fd->{throw new AssertionError("No recovery queue inspection expected");});
        }
        void feed(List<EvdevEvent> events,int readSize)throws IOException{
            ByteBuffer bytes=ByteBuffer.allocate(events.size()*decoder.getEventSize()).order(ByteOrder.LITTLE_ENDIAN);
            for(EvdevEvent event:events){
                if(decoder.getEventSize()==24)bytes.putLong(1).putLong(2);else bytes.putInt(1).putInt(2);
                bytes.putShort((short)event.type).putShort((short)event.code).putInt(event.value);
            }
            byte[] data=bytes.array();
            for(int offset=0;offset<data.length;offset+=readSize)
                decoder.accept(data,offset,Math.min(readSize,data.length-offset),event->{
                    MappingUpdate update=input.accept(event);
                    // UhidSourceBridge also checks failure before publishing a report.
                    if(update.isFailed())failures.add(update);
                    else if(update.getReport()!=null)reports.add(update.getReport());
                });
        }
    }
}
