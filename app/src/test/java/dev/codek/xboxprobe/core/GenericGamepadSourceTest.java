package dev.codek.xboxprobe.core;

import static org.junit.Assert.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public final class GenericGamepadSourceTest {
    @Test public void digitalOnlyPadUsesDpadKeysAndDigitalTriggersForNativeAndXbox() {
        Set<Integer> keys=Set.of(304,312,313,544,545,546,547);
        for(ControllerProfile.Kind kind:gamepadKinds()) {
            // Disable digital trigger button bits so this test isolates their
            // analog output role; existing native button maps remain unchanged.
            ControllerProfile p=profile(kind,Map.of(312,0,313,0),SourceBindings.AUTO);
            G8InputMapper mapper=p.createMapper(Map.of(),keys);
            assertNull(sync(mapper).getReport());
            mapper.accept(key(304,1));mapper.accept(key(312,1));mapper.accept(key(544,1));mapper.accept(key(547,1));
            assertArrayEquals(p.encodeProbe(new GamepadState(0,0,0,0,1,0,1,GamepadState.A)),sync(mapper).getReport());
            // Opposing directions cancel instead of depending on event order.
            mapper.accept(key(546,1));mapper.accept(key(545,1));mapper.accept(key(313,1));
            assertArrayEquals(p.encodeProbe(new GamepadState(0,0,0,0,1,1,-1,GamepadState.A)),sync(mapper).getReport());
            for(int code:keys)mapper.accept(key(code,0));
            assertArrayEquals(p.neutralReport(),sync(mapper).getReport());
        }
    }

    @Test public void oneStickWithoutHatOrAnalogTriggersKeepsAbsentControlsNeutral() {
        Map<Integer,AxisRange> axes=Map.of(0,new AxisRange(0,255,8),1,new AxisRange(0,255,8));
        for(ControllerProfile.Kind kind:gamepadKinds()) {
            ControllerProfile p=profile(kind,Map.of(312,0),SourceBindings.AUTO);
            G8InputMapper mapper=p.createMapper(axes,Set.of(304,312));
            mapper.accept(abs(0,255));mapper.accept(abs(1,0));mapper.accept(key(312,1));
            assertArrayEquals(p.encodeProbe(new GamepadState(1,-1,0,0,1,0,-1,0)),sync(mapper).getReport());
        }
    }

    @Test public void explicitRolesResolveAmbiguousAndNonstandardAxesWithArbitraryButtons() {
        Map<Integer,AxisRange> axes=Map.of(2,new AxisRange(-100,100,0),5,new AxisRange(-100,100,0),
                32,new AxisRange(0,1023,0));
        assertThrows(IllegalArgumentException.class,()->GamepadSourceLayout.resolve(axes));
        SourceBindings bindings=new SourceBindings(false,Map.of(GamepadSourceLayout.Control.RIGHT_X,2,
                GamepadSourceLayout.Control.RIGHT_Y,5,GamepadSourceLayout.Control.LEFT_TRIGGER,32),
                Map.of(SourceBindings.DigitalControl.DPAD_LEFT,700));
        for(ControllerProfile.Kind kind:gamepadKinds()) {
            ControllerProfile p=profile(kind,Map.of(288,304,704,304),bindings);
            G8InputMapper mapper=p.createMapper(axes,Set.of(288,700,704));
            mapper.accept(abs(2,100));mapper.accept(abs(5,-100));mapper.accept(abs(32,1023));
            mapper.accept(key(288,1));mapper.accept(key(704,1));mapper.accept(key(700,1));
            assertArrayEquals(p.encodeProbe(new GamepadState(0,0,1,-1,1,0,6,GamepadState.A)),sync(mapper).getReport());
            mapper.accept(key(288,0));assertNull(sync(mapper).getReport());
            mapper.accept(key(704,0));
            assertArrayEquals(p.encodeProbe(new GamepadState(0,0,1,-1,1,0,6,0)),sync(mapper).getReport());
            assertEquals(MappingUpdate.Failure.AXIS_OUT_OF_RANGE,mapper.accept(abs(32,1024)).getFailure());
        }
    }

    @Test public void sourceBindingValidationRejectsUnknownMissingOrConflictingRoles() {
        assertThrows(IllegalArgumentException.class,()->new SourceBindings(true,Map.of(GamepadSourceLayout.Control.LEFT_X,0),Map.of()));
        assertThrows(IllegalArgumentException.class,()->new SourceBindings(false,Map.of(GamepadSourceLayout.Control.LEFT_X,64),Map.of()));
        assertThrows(IllegalArgumentException.class,()->new SourceBindings(false,Map.of(GamepadSourceLayout.Control.LEFT_X,0,GamepadSourceLayout.Control.RIGHT_X,0),Map.of()));
        assertThrows(IllegalArgumentException.class,()->new SourceBindings(false,Map.of(),Map.of(SourceBindings.DigitalControl.LEFT_TRIGGER,768)));
        assertThrows(IllegalArgumentException.class,()->new SourceBindings(false,Map.of(GamepadSourceLayout.Control.LEFT_TRIGGER,2),Map.of(SourceBindings.DigitalControl.LEFT_TRIGGER,312)));
        SourceBindings absent=new SourceBindings(false,Map.of(GamepadSourceLayout.Control.LEFT_X,32),Map.of());
        assertThrows(IllegalArgumentException.class,()->GamepadSourceLayout.resolve(Map.of(),Set.of(304),absent));
        SourceBindings missingKey=new SourceBindings(false,Map.of(),Map.of(SourceBindings.DigitalControl.DPAD_UP,544));
        assertThrows(IllegalArgumentException.class,()->GamepadSourceLayout.resolve(Map.of(),Set.of(304),missingKey));
        Map<Integer,AxisRange> unknown=new HashMap<>();unknown.put(32,new AxisRange(-1,1,0));
        assertThrows(IllegalArgumentException.class,()->GamepadSourceLayout.resolve(unknown));
        unknown.put(32,null);assertThrows(IllegalArgumentException.class,()->GamepadSourceLayout.resolve(unknown));
        assertThrows(IllegalArgumentException.class,()->new AxisRange(1,1,0));
        assertThrows(IllegalArgumentException.class,()->new AxisRange(-1,1,3));
        G8InputMapper mapper=profile(ControllerProfile.Kind.NATIVE,Map.of(),SourceBindings.AUTO).createMapper(Map.of(),Set.of(304));
        assertEquals(MappingUpdate.Failure.SOURCE_CAPABILITY_CHANGED,mapper.accept(key(705,1)).getFailure());
    }

    @Test public void digitalDpadCanDriveKeyboardAndExplicitMovementPreservesHeldOwnership() {
        ControllerProfile keyboard=new ControllerProfile("keyboard","Keyboard",ControllerProfile.Kind.KEYBOARD,"Keyboard",1,2,3,
                Map.of(),ControllerProfile.defaultKeyboardBindings(),ControllerProfile.AxisConfig.DEFAULT);
        G8InputMapper digital=keyboard.createMapper(Map.of(),Set.of(304,544,545,546,547));
        digital.accept(key(547,1));assertArrayEquals(new byte[]{0,0,7,0,0,0,0,0},sync(digital).getReport());
        digital.accept(key(547,0));assertArrayEquals(new byte[8],sync(digital).getReport());
        SourceBindings source=new SourceBindings(false,Map.of(GamepadSourceLayout.Control.LEFT_X,32),
                Map.of(SourceBindings.DigitalControl.DPAD_RIGHT,700));
        ControllerProfile explicit=new ControllerProfile("keyboard","Keyboard",ControllerProfile.Kind.KEYBOARD,"Keyboard",1,2,3,
                Map.of(),Map.of(),ControllerProfile.AxisConfig.DEFAULT,source,false);
        G8InputMapper mapper=explicit.createMapper(Map.of(32,new AxisRange(-100,100,0)),Set.of(700));
        mapper.accept(abs(32,100));mapper.accept(key(700,1));assertArrayEquals(new byte[]{0,0,7,0,0,0,0,0},sync(mapper).getReport());
        mapper.accept(key(700,0));assertNull(sync(mapper).getReport());
        mapper.accept(abs(32,0));assertArrayEquals(new byte[8],sync(mapper).getReport());
    }

    private static ControllerProfile.Kind[] gamepadKinds(){return new ControllerProfile.Kind[]{ControllerProfile.Kind.NATIVE,ControllerProfile.Kind.XBOX};}
    private static ControllerProfile profile(ControllerProfile.Kind kind,Map<Integer,Integer> buttons,SourceBindings source) {
        return new ControllerProfile("generic","Generic",kind,"Output",1,2,3,buttons,Map.of(),ControllerProfile.AxisConfig.DEFAULT,source,false);
    }
    private static EvdevEvent key(int code,int value){return new EvdevEvent(1,code,value);}
    private static EvdevEvent abs(int code,int value){return new EvdevEvent(3,code,value);}
    private static MappingUpdate sync(G8InputMapper mapper){return mapper.accept(new EvdevEvent(0,0,0));}
}
