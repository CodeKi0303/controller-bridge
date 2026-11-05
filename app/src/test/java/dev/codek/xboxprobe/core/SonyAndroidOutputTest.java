package dev.codek.xboxprobe.core;

import dev.codek.xboxprobe.ProfileStore;
import org.junit.Test;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.*;

/** Production source mapper -> independently parsed HID -> generic Linux input
 * codes -> pinned AOSP key layout. No production layout lookup or bit-offset
 * table is used to determine the resulting Android control names. */
public final class SonyAndroidOutputTest {
    private static final String[] PRESETS={ProfileStore.PS4_ID,ProfileStore.PS5_ID};
    private static final int[] CANONICAL_KEYS={304,305,307,308,310,311,312,313,314,315,316,317,318};
    private static final String[] ANDROID_KEYS={"BUTTON_A","BUTTON_B","BUTTON_X","BUTTON_Y",
            "BUTTON_L1","BUTTON_R1","BUTTON_L2","BUTTON_R2","BUTTON_SELECT","BUTTON_START",
            "BUTTON_MODE","BUTTON_THUMBL","BUTTON_THUMBR"};

    @Test public void allStandardButtonsReachTheirAndroidMeaningAndReleaseIndependently() throws Exception {
        for(String preset:PRESETS){
            ControllerProfile profile=ProfileStore.builtinDefault(preset);AndroidDecoder decoder=decoder(profile);
            G8InputMapper mapper=profile.createMapper(sourceAxes(),sourceKeys());
            for(int i=0;i<CANONICAL_KEYS.length;i++){
                mapper.accept(new EvdevEvent(1,CANONICAL_KEYS[i],1));
                AndroidFrame pressed=decoder.read(sync(mapper));
                assertEquals(preset+" key "+CANONICAL_KEYS[i],Set.of(ANDROID_KEYS[i]),pressed.buttons);
                assertNeutralAxes(pressed);
                mapper.accept(new EvdevEvent(1,CANONICAL_KEYS[i],0));
                AndroidFrame released=decoder.read(sync(mapper));assertTrue(released.buttons.isEmpty());assertNeutralAxes(released);
            }
        }
    }

    @Test public void rightStickAndTriggersAreSeparateAndroidAxes() throws Exception {
        for(String preset:PRESETS){
            ControllerProfile profile=ProfileStore.builtinDefault(preset);AndroidDecoder decoder=decoder(profile);
            G8InputMapper mapper=profile.createMapper(sourceAxes(),sourceKeys());
            mapper.accept(new EvdevEvent(3,2,127));mapper.accept(new EvdevEvent(3,5,-127));
            AndroidFrame sticks=decoder.read(sync(mapper));
            axis(sticks,"Z",1);axis(sticks,"RZ",-1);axis(sticks,"LTRIGGER",0);axis(sticks,"RTRIGGER",0);axis(sticks,"X",0);axis(sticks,"Y",0);
            mapper.accept(new EvdevEvent(3,2,0));mapper.accept(new EvdevEvent(3,5,0));
            mapper.accept(new EvdevEvent(3,10,255));mapper.accept(new EvdevEvent(3,9,64));
            AndroidFrame triggers=decoder.read(sync(mapper));
            axis(triggers,"Z",0);axis(triggers,"RZ",0);axis(triggers,"LTRIGGER",1);axis(triggers,"RTRIGGER",64d/255);
            assertTrue(triggers.buttons.isEmpty());
            mapper.accept(new EvdevEvent(3,10,0));mapper.accept(new EvdevEvent(3,9,0));
            assertNeutralAxes(decoder.read(sync(mapper)));
        }
    }

    @Test public void chordsKeepUnreleasedButtonsSticksTriggersAndHat() throws Exception {
        for(String preset:PRESETS){
            ControllerProfile profile=ProfileStore.builtinDefault(preset);AndroidDecoder decoder=decoder(profile);
            G8InputMapper mapper=profile.createMapper(sourceAxes(),sourceKeys());
            for(int code:new int[]{304,305,308})mapper.accept(new EvdevEvent(1,code,1));
            mapper.accept(new EvdevEvent(3,0,127));mapper.accept(new EvdevEvent(3,1,-127));
            mapper.accept(new EvdevEvent(3,2,-127));mapper.accept(new EvdevEvent(3,5,127));
            mapper.accept(new EvdevEvent(3,10,255));mapper.accept(new EvdevEvent(3,9,255));
            mapper.accept(new EvdevEvent(3,16,1));mapper.accept(new EvdevEvent(3,17,-1));
            AndroidFrame chord=decoder.read(sync(mapper));
            assertEquals(Set.of("BUTTON_A","BUTTON_B","BUTTON_Y"),chord.buttons);
            axis(chord,"X",1);axis(chord,"Y",-1);axis(chord,"Z",-1);axis(chord,"RZ",1);
            axis(chord,"LTRIGGER",1);axis(chord,"RTRIGGER",1);axis(chord,"HAT_X",1);axis(chord,"HAT_Y",-1);
            mapper.accept(new EvdevEvent(1,305,0));mapper.accept(new EvdevEvent(3,10,0));mapper.accept(new EvdevEvent(3,16,0));
            AndroidFrame partial=decoder.read(sync(mapper));
            assertEquals(Set.of("BUTTON_A","BUTTON_Y"),partial.buttons);
            axis(partial,"X",1);axis(partial,"Y",-1);axis(partial,"Z",-1);axis(partial,"RZ",1);
            axis(partial,"LTRIGGER",0);axis(partial,"RTRIGGER",1);axis(partial,"HAT_X",0);axis(partial,"HAT_Y",-1);
            AndroidFrame disconnected=decoder.read(mapper.disconnect().getReport());
            assertTrue(disconnected.buttons.isEmpty());assertNeutralAxes(disconnected);
        }
    }

    @Test public void customSourceMappingsRemainCanonicalBeforeSonyOutputTranslation() throws Exception {
        for(String preset:PRESETS){
            ControllerProfile base=ProfileStore.builtinDefault(preset);
            // The unrelated profile ID also proves that Sony selection follows
            // the advertised identity rather than a built-in catalog string.
            ControllerProfile profile=new ControllerProfile("user-edited-identity","Custom",ControllerProfile.Kind.NATIVE,
                    base.getDeviceName(),base.getVendorId(),base.getProductId(),base.getBus(),
                    Map.of(304,308,308,304,700,311),Map.of(),ControllerProfile.AxisConfig.DEFAULT);
            Set<Integer> source=sourceKeys();source.add(700);G8InputMapper mapper=profile.createMapper(sourceAxes(),source);
            AndroidDecoder decoder=decoder(profile);
            mapper.accept(new EvdevEvent(1,304,1));mapper.accept(new EvdevEvent(1,700,1));
            assertEquals(Set.of("BUTTON_Y","BUTTON_R1"),decoder.read(sync(mapper)).buttons);
            mapper.accept(new EvdevEvent(1,308,1));mapper.accept(new EvdevEvent(1,304,0));
            assertEquals(Set.of("BUTTON_A","BUTTON_R1"),decoder.read(sync(mapper)).buttons);
            assertEquals(Integer.valueOf(308),profile.getButtonMappings().get(304));
            assertEquals(Integer.valueOf(304),profile.getButtonMappings().get(308));
            assertEquals(Integer.valueOf(311),profile.getButtonMappings().get(700));
        }
    }

    @Test public void unrepresentableSonyCAndZFailInsteadOfBecomingAnotherButton() {
        for(String preset:PRESETS)for(int code:new int[]{306,309}){
            ControllerProfile profile=ProfileStore.builtinDefault(preset);Set<Integer> source=sourceKeys();source.add(code);
            G8InputMapper mapper=profile.createMapper(sourceAxes(),source);mapper.accept(new EvdevEvent(1,code,1));
            assertThrows(IllegalArgumentException.class,()->sync(mapper));
        }
    }

    @Test public void fixtureIdentityIsPinnedAndCurrentGenericOutputIsNotMistakenForSony() throws Exception {
        assertEquals("5f4882ab69f17282f82546962b3a3d030ad681fb5f775e5723b08ca458435d58",sha256(resource("Vendor_054c_Product_09cc.kl")));
        assertEquals("7d706d5af7d55c0294712f86391c9b0c3ce79ab96dff7cc6f6ce27b6ecbbe9dc",sha256(resource("Vendor_054c_Product_0ce6.kl")));
        // Regression sensitivity: the formerly shared generic descriptor/report
        // must reproduce the observed Y -> LB / X bug through the real fixtures.
        NativeGamepadProfile generic=NativeGamepadProfile.KISHI_ULTRA_IDENTITY;
        byte[] y=generic.encode(NativeGamepadState.fromGamepadState(new GamepadState(0,0,0,0,0,0,-1,GamepadState.Y)));
        assertEquals(Set.of("BUTTON_L1"),new AndroidDecoder(generic.getDescriptor(),"Vendor_054c_Product_09cc.kl",false).read(y).buttons);
        assertEquals(Set.of("BUTTON_X"),new AndroidDecoder(generic.getDescriptor(),"Vendor_054c_Product_0ce6.kl",false).read(y).buttons);
    }

    private static AndroidDecoder decoder(ControllerProfile profile) throws Exception {
        return new AndroidDecoder(profile.getDescriptor(),profile.getProductId()==0x0ce6?"Vendor_054c_Product_0ce6.kl":"Vendor_054c_Product_09cc.kl",true);
    }
    private static byte[] sync(G8InputMapper mapper){MappingUpdate update=mapper.accept(new EvdevEvent(0,0,0));assertFalse(update.getError(),update.isFailed());assertNotNull("Expected a changed complete input frame",update.getReport());return update.getReport();}
    private static Map<Integer,AxisRange> sourceAxes(){Map<Integer,AxisRange> axes=new HashMap<>();for(int code:new int[]{0,1,2,5})axes.put(code,new AxisRange(-127,127,0));for(int code:new int[]{9,10})axes.put(code,new AxisRange(0,255,0));for(int code:new int[]{16,17})axes.put(code,new AxisRange(-1,1,0));return axes;}
    private static Set<Integer> sourceKeys(){Set<Integer> keys=new LinkedHashSet<>();for(int key:CANONICAL_KEYS)keys.add(key);return keys;}
    private static void axis(AndroidFrame frame,String name,double expected){assertTrue("Missing Android axis "+name,frame.axes.containsKey(name));assertEquals(name,expected,frame.axes.get(name),.0001);}
    private static void assertNeutralAxes(AndroidFrame frame){for(String name:new String[]{"X","Y","Z","RZ","LTRIGGER","RTRIGGER","HAT_X","HAT_Y"})axis(frame,name,0);}
    private static byte[] resource(String name) throws Exception {try(InputStream in=SonyAndroidOutputTest.class.getResourceAsStream("/android-keylayouts/"+name)){assertNotNull("Missing pinned fixture "+name,in);return in.readAllBytes();}}
    private static String sha256(byte[] bytes) throws Exception {StringBuilder out=new StringBuilder();for(byte value:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return out.toString();}

    private static final class AndroidFrame {
        final Set<String> buttons=new LinkedHashSet<>();
        final Map<String,Double> axes=new LinkedHashMap<>();
    }
    private static final class AndroidDecoder {
        final NativeGamepadCoreTest.Descriptor hid;
        final Map<Integer,String> keys=new HashMap<>(),axes=new HashMap<>();
        final boolean requireAllAxes;
        AndroidDecoder(byte[] descriptor,String fixture,boolean requireAllAxes) throws Exception {
            hid=new NativeGamepadCoreTest.Descriptor(descriptor);this.requireAllAxes=requireAllAxes;
            assertTrue("Linux BTN_GAMEPAD interpretation requires a Gamepad application collection",hid.gamepadApplication);
            for(String line:new String(resource(fixture),StandardCharsets.UTF_8).split("\\R")){
                String[] words=line.replaceFirst("#.*$","").trim().split("\\s+");
                if(words.length<3)continue;
                if(words[0].equals("key"))assertNull(keys.put(Integer.decode(words[1]),words[2]));
                if(words[0].equals("axis"))assertNull(axes.put(Integer.decode(words[1]),words[2]));
            }
            assertFalse("Fixture must contain real Android keys",keys.isEmpty());assertFalse(axes.isEmpty());
        }
        AndroidFrame read(byte[] report){
            assertEquals("Complete HID input report",hid.inputBits,report.length*8);
            AndroidFrame result=new AndroidFrame();
            for(NativeGamepadCoreTest.Field field:hid.fields){
                if(field.constant)continue;int value=hid.value(report,field.page,field.usage);
                if(field.page==9){
                    // Linux generic HID gamepad Button usage 1 starts at BTN_GAMEPAD.
                    if(value!=0){String android=keys.get(0x130+field.usage-1);assertNotNull("Active HID button has no Android key: "+field.usage,android);result.buttons.add(android);}
                }else if(field.page==1&&field.usage==0x39){
                    int[] hx={0,1,1,1,0,-1,-1,-1},hy={-1,-1,0,1,1,1,0,-1};
                    result.axes.put(axes.get(0x10),value>=0&&value<8?(double)hx[value]:0d);
                    result.axes.put(axes.get(0x11),value>=0&&value<8?(double)hy[value]:0d);
                }else{
                    // Standard HID Generic Desktop axes X..Rz map to ABS_X..RZ;
                    // Simulation Controls Gas/Brake map to ABS_GAS/ABS_BRAKE.
                    int linux=field.page==1&&field.usage>=0x30&&field.usage<=0x35?field.usage-0x30:
                            field.page==2&&field.usage==0xc4?9:field.page==2&&field.usage==0xc5?10:-1;
                    assertTrue("Unsupported descriptor axis in independent test decoder",linux>=0);
                    String android=axes.get(linux);
                    if(android==null){if(requireAllAxes)fail("HID axis has no Android role: Linux ABS "+linux);continue;}
                    double normalized=android.endsWith("TRIGGER")?(value-field.min)/(double)(field.max-field.min):
                            (value-(field.min+field.max)/2d)/((field.max-field.min)/2d);
                    assertNull("Two HID axes collapse into Android "+android,result.axes.put(android,normalized));
                }
            }
            return result;
        }
    }
}
