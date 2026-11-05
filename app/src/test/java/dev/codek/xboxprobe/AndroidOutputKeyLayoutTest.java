package dev.codek.xboxprobe;

import dev.codek.xboxprobe.core.HidIdentity;
import dev.codek.xboxprobe.core.NativeGamepadLayout;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class AndroidOutputKeyLayoutTest {
    private static final String LOCATION="g8-input-bridge:4b778aa6-158e-4c3d-b8d8-a1ea40b20d26";
    private static final String PS5_PATH="/system/usr/keylayout/Vendor_054c_Product_0ce6.kl";
    private static final HidIdentity PS5=new HidIdentity("Wireless Controller",0x054c,0x0ce6,6);

    @Test public void selectsExactOwnedEventHubRecordAndIgnoresReaderCopy() {
        String text=dump(record(154,"other-location",PS5_PATH)
                +record(156,LOCATION,PS5_PATH))+record(156,LOCATION,"/not/a/real/file.kl");
        assertEquals(PS5_PATH,AndroidOutputKeyLayout.selectedPath(text,LOCATION,PS5));
    }

    @Test public void recordWithCorrectNameAndIdsButWrongLocationCannotSubstitute() {
        rejected(()->AndroidOutputKeyLayout.selectedPath(
                dump(record(156,"somebody-else",PS5_PATH))+record(157,LOCATION,PS5_PATH),LOCATION,PS5));
        rejected(()->AndroidOutputKeyLayout.selectedPath(
                dump(record(156,LOCATION,PS5_PATH)).replace("Wireless Controller","Other Controller"),LOCATION,PS5));
    }

    @Test public void duplicateLocationAndFieldsAreAmbiguousEvenWhenValuesAgree() {
        rejected(()->AndroidOutputKeyLayout.selectedPath(dump(record(155,LOCATION,PS5_PATH)
                +record(156,LOCATION,PS5_PATH)),LOCATION,PS5));
        rejected(()->AndroidOutputKeyLayout.selectedPath(dump(record(156,LOCATION,PS5_PATH)
                .replace("      Identifier:","      Location: "+LOCATION+"\n      Identifier:")),LOCATION,PS5));
        rejected(()->AndroidOutputKeyLayout.selectedPath(dump(record(156,LOCATION,PS5_PATH)
                .replace("      KeyLayoutFile:","      KeyLayoutFile: "+PS5_PATH+"\n      KeyLayoutFile:")),LOCATION,PS5));
    }

    @Test public void incompleteOrWrongOwnedIdentityFailsClosed() {
        String text=dump(record(156,LOCATION,PS5_PATH));
        rejected(()->AndroidOutputKeyLayout.selectedPath(text.replace("bus=0x0006","bus=0x0003"),LOCATION,PS5));
        rejected(()->AndroidOutputKeyLayout.selectedPath(text.replace("vendor=0x054c","vendor=0x1532"),LOCATION,PS5));
        rejected(()->AndroidOutputKeyLayout.selectedPath(text.replace("product=0x0ce6","product=0x09cc"),LOCATION,PS5));
        rejected(()->AndroidOutputKeyLayout.selectedPath(text.replace("vendor=0x054c","vendor=???"),LOCATION,PS5));
        rejected(()->AndroidOutputKeyLayout.selectedPath(text.substring(0,text.indexOf("Input Reader State")),LOCATION,PS5));
        rejected(()->AndroidOutputKeyLayout.selectedPath(text.replace("      KeyLayoutFile: "+PS5_PATH+"\n",""),LOCATION,PS5));
    }

    @Test public void permitsOnlyAbsoluteAndroidKeyLayoutDirectoriesWithoutTraversal() {
        for(String root:new String[]{"/odm/usr/keylayout/","/vendor/usr/keylayout/","/system/usr/keylayout/",
                "/product/usr/keylayout/","/system_ext/usr/keylayout/","/data/system/devices/keylayout/"}) {
            String path=root+"Vendor_054c_Product_0ce6.kl";
            assertEquals(path,AndroidOutputKeyLayout.selectedPath(dump(record(156,LOCATION,path)),LOCATION,PS5));
        }
        for(String path:new String[]{"system/usr/keylayout/x.kl","/tmp/x.kl","/system/usr/keylayout2/x.kl",
                "/system/usr/keylayout/../x.kl","/system/usr/keylayout/./x.kl","/system/usr/keylayout//x.kl",
                "/system/usr/keylayout/x.txt","/system/usr/keylayout/x.kl/","/system/usr/keylayout/x\\y.kl"})
            rejected(()->AndroidOutputKeyLayout.selectedPath(dump(record(156,LOCATION,path)),LOCATION,PS5));
    }

    @Test public void acceptsPinnedAospLayoutsIncludingKernelConditionAndSensors() throws Exception {
        AndroidOutputKeyLayout.verifyContents(fixture("09cc"),NativeGamepadLayout.SONY_PS4_LEGACY);
        AndroidOutputKeyLayout.verifyContents(fixture("0ce6"),NativeGamepadLayout.SONY_PS5);
        AndroidOutputKeyLayout.verifyContents(fixture("0ce6")+"\nkey 700 BUTTON_16\nkey usage 0x00070004 A\n",
                NativeGamepadLayout.SONY_PS5);
    }

    @Test public void legacyAndModernSonyLayoutsAreNotInterchangeable() throws Exception {
        String ps4=fixture("09cc"),ps5=fixture("0ce6");
        rejected(()->AndroidOutputKeyLayout.verifyContents(ps4,NativeGamepadLayout.SONY_PS5));
        rejected(()->AndroidOutputKeyLayout.verifyContents(ps5,NativeGamepadLayout.SONY_PS4_LEGACY));
        rejected(()->AndroidOutputKeyLayout.verifyContents(replaceStatement(ps5,"key 0x133 BUTTON_Y","key 0x133 BUTTON_X"),NativeGamepadLayout.SONY_PS5));
        rejected(()->AndroidOutputKeyLayout.verifyContents(replaceStatement(ps5,"axis 0x03 Z","axis 0x03 RX"),NativeGamepadLayout.SONY_PS5));
    }

    @Test public void duplicatesAndConflictingSemanticsAreRejected() throws Exception {
        String text=fixture("0ce6");
        for(String suffix:new String[]{"key 304 BUTTON_A","axis 0x03 Z","key 700 BUTTON_A","axis 0x06 Z",
                "key usage 0x00070004 A\nkey usage 0x00070004 A"})
            rejected(()->AndroidOutputKeyLayout.verifyContents(text+"\n"+suffix,NativeGamepadLayout.SONY_PS5));
    }

    @Test public void forbidsOurButtonUsageOverridesRegardlessOfTheirIntendedMeaning() throws Exception {
        String text=fixture("0ce6");
        for(String suffix:new String[]{"key usage 0x00090001 BUTTON_A","key usage 0x0009000f BUTTON_THUMBR"})
            rejected(()->AndroidOutputKeyLayout.verifyContents(text+"\n"+suffix,NativeGamepadLayout.SONY_PS5));
    }

    @Test public void rejectsUnknownAxisQualifiersSplitAndInversion() throws Exception {
        String text=fixture("0ce6");
        for(String replacement:new String[]{"axis 0x03 invert Z","axis 0x03 split 127 Z RZ",
                "axis 0x03 Z flat 10","axis 0x03 strange Z"})
            rejected(()->AndroidOutputKeyLayout.verifyContents(replaceStatement(text,"axis 0x03 Z",replacement),NativeGamepadLayout.SONY_PS5));
    }

    @Test public void enforcesUtf8BoundsBeforeParsingAndRejectsMalformedUnicode() {
        rejected(()->AndroidOutputKeyLayout.selectedPath(repeated('x',AndroidOutputKeyLayout.MAX_DUMP_BYTES+1),LOCATION,PS5));
        rejected(()->AndroidOutputKeyLayout.selectedPath(repeated('\uac00',AndroidOutputKeyLayout.MAX_DUMP_BYTES/3+1),LOCATION,PS5));
        rejected(()->AndroidOutputKeyLayout.verifyContents(repeated('x',AndroidOutputKeyLayout.MAX_LAYOUT_BYTES+1),NativeGamepadLayout.SONY_PS5));
        rejected(()->AndroidOutputKeyLayout.verifyContents(repeated('\uac00',AndroidOutputKeyLayout.MAX_LAYOUT_BYTES/3+1),NativeGamepadLayout.SONY_PS5));
        rejected(()->AndroidOutputKeyLayout.verifyContents("# \ud800",NativeGamepadLayout.SONY_PS5));
        rejected(()->AndroidOutputKeyLayout.verifyContents("# \u0000",NativeGamepadLayout.SONY_PS5));
    }

    private static String dump(String records) {
        return "Event Hub State:\n  BuiltInKeyboardId: -2\n  Devices:\n"+records
                +"Input Reader State (Nums of device: 11):\n";
    }

    private static String record(int id,String location,String path) {
        return "    "+id+": Wireless Controller\n      Classes: KEYBOARD | GAMEPAD | JOYSTICK\n"
                +"      Path: /dev/input/event15\n      Enabled: true\n      Descriptor: opaque\n"
                +"      Location: "+location+"\n      ControllerNumber: 2\n      UniqueId: \n"
                +"      Identifier: bus=0x0006, vendor=0x054c, product=0x0ce6, version=0x0000, bluetoothAddress=<not set>\n"
                +"      KeyLayoutFile: "+path+"\n";
    }

    private static String fixture(String product) throws Exception {
        try(InputStream input=AndroidOutputKeyLayoutTest.class.getResourceAsStream(
                "/android-keylayouts/Vendor_054c_Product_"+product+".kl")) {
            if(input==null)throw new AssertionError("Pinned Android fixture is missing");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;
            while((count=input.read(buffer))!=-1)bytes.write(buffer,0,count);
            return new String(bytes.toByteArray(),StandardCharsets.UTF_8);
        }
    }

    private static String repeated(char value,int length){char[] text=new char[length];Arrays.fill(text,value);return new String(text);}
    private static String replaceStatement(String text,String from,String to) {
        String changed=text.replaceFirst("(?m)^"+from.replace(" ","[ \\t]+")+"$",to);
        assertTrue("Fixture statement must exist before mutating it",!changed.equals(text));return changed;
    }
    private static void rejected(Runnable action) {
        try {action.run();fail("Expected key layout verification to fail");}
        catch(IllegalArgumentException expected) {assertTrue(expected.getMessage()!=null&&!expected.getMessage().isEmpty());}
    }
}
