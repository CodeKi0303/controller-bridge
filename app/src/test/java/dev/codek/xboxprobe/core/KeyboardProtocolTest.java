package dev.codek.xboxprobe.core;

import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class KeyboardProtocolTest {
    @Test public void descriptorDeclaresUnnumberedBootCompatibleKeyboardReports() {
        byte[] descriptor = HidKeyboardProfile.keyboardDescriptor();
        assertEquals(63, descriptor.length);
        List<Item> inputs = new ArrayList<>(), outputs = new ArrayList<>();
        int page=0,size=0,count=0,min=0,max=0,usageMin=0,usageMax=0,collections=0;
        boolean keyboardApplication=false;
        int usage=0;
        for (int pos=0; pos<descriptor.length;) {
            int prefix=descriptor[pos++]&255;
            assertNotEquals(0xfe,prefix);
            int length=(prefix&3)==3?4:prefix&3;
            assertTrue(pos+length<=descriptor.length);
            int value=0;
            for(int i=0;i<length;i++) value|=(descriptor[pos++]&255)<<(8*i);
            int type=(prefix>>>2)&3,tag=prefix>>>4;
            if(type==1) {
                switch(tag) {
                    case 0: page=value; break;
                    case 1: min=value; break;
                    case 2: max=value; break;
                    case 7: size=value; break;
                    case 8: fail("Keyboard report must not have a report-ID byte"); break;
                    case 9: count=value; break;
                    default: fail("Unexpected global item");
                }
            } else if(type==2) {
                if(tag==0) usage=value;
                else if(tag==1) usageMin=value;
                else if(tag==2) usageMax=value;
                else fail("Unexpected local item");
            } else if(type==0) {
                if(tag==10) { collections++; if(value==1 && page==1 && usage==6) keyboardApplication=true; }
                else if(tag==12) { collections--; assertTrue(collections>=0); }
                else if(tag==8 || tag==9) {
                    Item item=new Item(page,size,count,min,max,usageMin,usageMax,value);
                    (tag==8?inputs:outputs).add(item);
                } else fail("Unexpected main item");
                usage=usageMin=usageMax=0;
            } else fail("Reserved HID item");
        }
        assertTrue(keyboardApplication);
        assertEquals(0,collections);
        assertEquals(3,inputs.size());
        assertEquals(2,outputs.size());
        assertArrayEquals(new int[]{7,1,8,0,1,0xe0,0xe7,2},inputs.get(0).values); // modifiers
        assertArrayEquals(new int[]{7,8,1,0,1,0,0,1},inputs.get(1).values); // reserved
        assertArrayEquals(new int[]{7,8,6,0,0x65,0,0x65,0},inputs.get(2).values); // six-key array
        assertEquals(64,bits(inputs));
        assertEquals(8,bits(outputs));
        assertEquals(8,outputs.get(0).values[0]); // LED usage page
        assertEquals(5,outputs.get(0).values[2]);
    }

    @Test public void observedWorkingKeysUseHidUsagesNotAsciiOrAndroidKeyCodes() {
        int[] usages = {HidKeyboardReportEncoder.USAGE_W,HidKeyboardReportEncoder.USAGE_A,
                HidKeyboardReportEncoder.USAGE_S,HidKeyboardReportEncoder.USAGE_D,
                HidKeyboardReportEncoder.USAGE_K,HidKeyboardReportEncoder.USAGE_J,
                HidKeyboardReportEncoder.USAGE_U,HidKeyboardReportEncoder.USAGE_SPACE,
                HidKeyboardReportEncoder.USAGE_ESCAPE,HidKeyboardReportEncoder.USAGE_ENTER};
        assertArrayEquals(new int[]{0x1a,0x04,0x16,0x07,0x0e,0x0d,0x18,0x2c,0x29,0x28},usages);
        for(int usage:usages) {
            assertArrayEquals(new byte[]{0,0,(byte)usage,0,0,0,0,0},
                    HidKeyboardReportEncoder.reportForUsage(usage));
        }
        assertArrayEquals(new byte[8],HidKeyboardReportEncoder.neutral());
        assertArrayEquals(new byte[8],HidKeyboardReportEncoder.reportForUsages());
    }

    @Test public void sixKeyCapacityRejectsRolloverDuplicatesAndOutOfRangeUsages() {
        assertArrayEquals(new byte[]{0,0,4,7,13,14,22,26},
                HidKeyboardReportEncoder.reportForUsages(4,7,13,14,22,26));
        for(int usage=4;usage<=0x65;usage++) {
            assertEquals(usage,HidKeyboardReportEncoder.reportForUsage(usage)[2]&255);
        }
        assertThrows(IllegalArgumentException.class,()->HidKeyboardReportEncoder.reportForUsages(4,5,6,7,8,9,10));
        assertThrows(IllegalArgumentException.class,()->HidKeyboardReportEncoder.reportForUsages(4,4));
        for(int invalid:new int[]{-1,0,1,2,3,0x66,0xe0,0xff,Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class,()->HidKeyboardReportEncoder.reportForUsage(invalid));
        }
        assertThrows(NullPointerException.class,()->HidKeyboardReportEncoder.reportForUsages((int[])null));
    }

    @Test public void uhidCreateAndInputContainNamedZeroIdentityAndExactRawReport() {
        byte[] event=UhidEventBuilder.createKeyboard(HidKeyboardProfile.INSTANCE);
        ByteBuffer b=ByteBuffer.wrap(event).order(ByteOrder.LITTLE_ENDIAN);
        byte[] name="G8 Keyboard Probe".getBytes(StandardCharsets.UTF_8);
        assertEquals(4376,event.length);
        assertEquals(11,b.getInt(0));
        assertArrayEquals(name,Arrays.copyOfRange(event,4,4+name.length));
        assertEquals(0,event[4+name.length]);
        assertEquals(63,b.getShort(260)&65535);
        assertEquals(3,b.getShort(262)&65535);
        assertEquals(0,b.getInt(264));
        assertEquals(0,b.getInt(268));
        assertArrayEquals(HidKeyboardProfile.keyboardDescriptor(),Arrays.copyOfRange(event,280,343));
        byte[] input=UhidEventBuilder.input2(HidKeyboardReportEncoder.reportForUsage(0x1a));
        assertEquals(8,ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN).getShort(4));
        assertArrayEquals(new byte[]{0,0,0x1a,0,0,0,0,0},Arrays.copyOfRange(input,6,14));
    }

    @Test public void descriptorAndNeutralReturnIndependentStorage() {
        byte[] descriptor=HidKeyboardProfile.keyboardDescriptor();
        descriptor[0]=0;
        assertEquals(5,HidKeyboardProfile.keyboardDescriptor()[0]);
        byte[] neutral=HidKeyboardReportEncoder.neutral();
        neutral[2]=0x1a;
        assertArrayEquals(new byte[8],HidKeyboardReportEncoder.neutral());
    }

    private static int bits(List<Item> items) {
        int result=0;
        for(Item item:items) result+=item.values[1]*item.values[2];
        return result;
    }

    private static final class Item {
        final int[] values;
        Item(int... values) { this.values=values; }
    }
}
