package dev.codek.xboxprobe;

import dev.codek.xboxprobe.core.ControllerProfile;
import dev.codek.xboxprobe.core.EvdevEvent;
import dev.codek.xboxprobe.core.G8InputMapper;
import dev.codek.xboxprobe.core.GamepadState;
import dev.codek.xboxprobe.core.MappingUpdate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ButtonMappingDraftTest {
    @Test public void sonyFaceButtonsCanBeLearnedInEitherOrderWithoutLeavingOldBindings() {
        for (boolean squareFirst : new boolean[]{true,false}) {
            ControllerProfile original = profile(ControllerProfile.Kind.XBOX,Map.of());
            ButtonMappingDraft draft = draft(original);
            if (squareFirst) {
                draft.replaceTarget(307,308); // Square -> X.
                draft.replaceTarget(308,307); // Triangle -> Y.
            } else {
                draft.replaceTarget(308,307);
                draft.replaceTarget(307,308);
            }
            ControllerProfile restored = roundTrip(ControllerProfile.Kind.XBOX,draft);
            assertEquals(Integer.valueOf(307),restored.getButtonMappings().get(308));
            assertEquals(Integer.valueOf(308),restored.getButtonMappings().get(307));
            assertEquals(List.of(308),draft.sourcesFor(307));
            assertEquals(List.of(307),draft.sourcesFor(308));
            assertEquals(Integer.valueOf(307),original.getButtonMappings().get(307));
        }
    }

    @Test public void clearingAndReplacingDefaultSourcesSurviveProfileCodecRoundTrip() {
        for (ControllerProfile.Kind kind : List.of(ControllerProfile.Kind.NATIVE,ControllerProfile.Kind.XBOX)) {
            ButtonMappingDraft draft = draft(profile(kind,Map.of()));
            draft.clearTarget(304);
            draft.replaceTarget(305,700);
            ControllerProfile restored = roundTrip(kind,draft);
            assertEquals(Integer.valueOf(0),restored.getButtonMappings().get(304));
            assertEquals(Integer.valueOf(0),restored.getButtonMappings().get(305));
            assertEquals(Integer.valueOf(305),restored.getButtonMappings().get(700));
            ButtonMappingDraft reopened = draft(restored);
            assertTrue(reopened.sourcesFor(304).isEmpty());
            assertEquals(List.of(700),reopened.sourcesFor(305));
        }
    }

    @Test public void disablingCapturedSourcesPersistsWithoutChangingOtherBindings() {
        ControllerProfile original = profile(ControllerProfile.Kind.NATIVE,Map.of(700,304,701,304));
        ButtonMappingDraft draft = draft(original);
        draft.disableSource(306);
        draft.disableSource(309);
        draft.disableSource(700);
        draft.disableSource(767); // Previously unassigned input still gets an explicit disable.
        ControllerProfile restored = roundTrip(ControllerProfile.Kind.NATIVE,draft);
        for (int source : new int[]{306,309,700,767})
            assertEquals(Integer.valueOf(0),restored.getButtonMappings().get(source));
        assertEquals(List.of(304,701),draft(restored).sourcesFor(304));
        for (Map.Entry<Integer,Integer> entry : original.getButtonMappings().entrySet())
            if (!Set.of(306,309,700).contains(entry.getKey()))
                assertEquals(entry.getValue(),restored.getButtonMappings().get(entry.getKey()));
        Map<Integer,Integer> before = draft.snapshot();
        for (int source : new int[]{-1,0,768,Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class,()->draft.disableSource(source));
        assertEquals(before,draft.snapshot());
        assertEquals(Integer.valueOf(306),original.getButtonMappings().get(306));
    }

    @Test public void learnedSonyFaceButtonsProduceIntendedXboxBitsAfterSaveAndReload() {
        G8InputMapper mapper = learnedSonyProfile().createMapper(Map.of(),Set.of(304,305,307,308));
        int[][] sourceAndOutput = {{304,GamepadState.A},{305,GamepadState.B},
                {307,GamepadState.Y},{308,GamepadState.X}};
        for (int[] button : sourceAndOutput) {
            assertNull(mapper.accept(key(button[0],1)).getReport());
            assertXboxButtons(mapper,button[1]);
            mapper.accept(key(button[0],0));
            assertXboxButtons(mapper,0);
        }
    }

    @Test public void learnedSonyButtonsRemainIndependentDuringSimultaneousPressAndRelease() {
        for (int releasedFirst : new int[]{307,308}) {
            G8InputMapper mapper = learnedSonyProfile().createMapper(Map.of(),Set.of(307,308));
            mapper.accept(key(307,1));mapper.accept(key(308,1));
            assertXboxButtons(mapper,GamepadState.X|GamepadState.Y);
            mapper.accept(key(releasedFirst,0));
            assertXboxButtons(mapper,releasedFirst==307?GamepadState.X:GamepadState.Y);
            mapper.accept(key(releasedFirst==307?308:307,0));
            assertXboxButtons(mapper,0);
        }
    }

    @Test public void unrelatedEditsPreserveManyToOneBindingsDisabledValuesAndUnusualSources() {
        ControllerProfile original = profile(ControllerProfile.Kind.NATIVE,
                Map.of(700,304,701,304,702,0,703,305));
        ButtonMappingDraft draft = draft(original);
        assertEquals(List.of(304,700,701),draft.sourcesFor(304));
        draft.replaceTarget(307,767);
        assertEquals(List.of(304,700,701),draft.sourcesFor(304));
        assertEquals(List.of(305,703),draft.sourcesFor(305));
        assertEquals(Integer.valueOf(0),draft.snapshot().get(702));
        ControllerProfile restored = roundTrip(ControllerProfile.Kind.NATIVE,draft);
        assertEquals(draft.snapshot(),restored.getButtonMappings());
        draft.replaceTarget(304,703);
        assertEquals(List.of(703),draft.sourcesFor(304));
        assertEquals(List.of(305),draft.sourcesFor(305));
        for (int disabled : new int[]{304,700,701})
            assertEquals(Integer.valueOf(0),draft.snapshot().get(disabled));
    }

    @Test public void constructorAndReturnedSnapshotsDoNotShareMutableState() {
        Map<Integer,Integer> current = new LinkedHashMap<>();
        current.put(700,304);current.put(304,304);current.put(305,0);
        Set<Integer> allowed = new HashSet<>(Arrays.asList(304,305));
        ButtonMappingDraft draft = new ButtonMappingDraft(current,allowed);
        SortedMap<Integer,Integer> before = draft.snapshot();
        List<Integer> sources = draft.sourcesFor(304);
        current.clear();allowed.clear();
        draft.replaceTarget(304,305);
        assertEquals(Map.of(304,304,305,0,700,304),before);
        assertEquals(List.of(304,305,700),new ArrayList<>(before.keySet()));
        assertEquals(List.of(304,700),sources);
        assertThrows(UnsupportedOperationException.class,()->before.put(1,304));
        assertThrows(UnsupportedOperationException.class,()->before.entrySet().iterator().next().setValue(0));
        assertThrows(UnsupportedOperationException.class,()->sources.add(1));
        assertEquals(List.of(305),draft.sourcesFor(304));
    }

    @Test public void invalidCaptureOrTargetDoesNotPartiallyDisableCurrentAssignments() {
        ButtonMappingDraft draft = new ButtonMappingDraft(Map.of(304,304,700,304),Set.of(304,305));
        Map<Integer,Integer> before = draft.snapshot();
        for (int source : new int[]{-1,0,768,Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class,()->draft.replaceTarget(304,source));
        for (int target : new int[]{-1,0,306,Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class,()->draft.replaceTarget(target,1));
            assertThrows(IllegalArgumentException.class,()->draft.clearTarget(target));
            assertThrows(IllegalArgumentException.class,()->draft.sourcesFor(target));
        }
        assertEquals(before,draft.snapshot());
    }

    @Test public void invalidStoredMappingsAndAllowedTargetsAreRejected() {
        Set<Integer> allowed = Set.of(304,305);
        for (Map<Integer,Integer> current : List.of(Map.of(0,304),Map.of(768,304),Map.of(1,306),Map.of(1,-1)))
            assertThrows(IllegalArgumentException.class,()->new ButtonMappingDraft(current,allowed));
        Map<Integer,Integer> missingSource = new HashMap<>();missingSource.put(null,304);
        Map<Integer,Integer> missingTarget = new HashMap<>();missingTarget.put(304,null);
        assertThrows(IllegalArgumentException.class,()->new ButtonMappingDraft(missingSource,allowed));
        assertThrows(IllegalArgumentException.class,()->new ButtonMappingDraft(missingTarget,allowed));
        for (Set<Integer> invalid : List.of(Set.of(0),Set.of(-1),new HashSet<>(Arrays.asList(304,null))))
            assertThrows(IllegalArgumentException.class,()->new ButtonMappingDraft(Map.of(),invalid));
        assertTrue(new ButtonMappingDraft(Map.of(1,0,767,0),Set.of()).snapshot().values().stream().allMatch(v->v==0));
    }

    private static ButtonMappingDraft draft(ControllerProfile profile) {
        return new ButtonMappingDraft(profile.getButtonMappings(),ControllerProfile.supportedTargetButtons(profile.getKind()));
    }

    private static ControllerProfile roundTrip(ControllerProfile.Kind kind,ButtonMappingDraft draft) {
        return ProfileCodec.decode(ProfileCodec.encode(profile(kind,draft.snapshot())));
    }

    private static ControllerProfile learnedSonyProfile() {
        ButtonMappingDraft draft = draft(profile(ControllerProfile.Kind.XBOX,Map.of()));
        draft.replaceTarget(307,308);
        draft.replaceTarget(308,307);
        return roundTrip(ControllerProfile.Kind.XBOX,draft);
    }

    private static EvdevEvent key(int code,int value) {
        return new EvdevEvent(EvdevEvent.EV_KEY,code,value);
    }

    private static void assertXboxButtons(G8InputMapper mapper,int expectedBits) {
        MappingUpdate update = mapper.accept(new EvdevEvent(EvdevEvent.EV_SYN,EvdevEvent.SYN_REPORT,0));
        assertFalse(update.getError(),update.isFailed());
        byte[] report = update.getReport();
        assertNotNull(report);
        assertEquals(16,report.length);
        assertEquals(1,report[0]);
        // Xbox report ID 1 ends with its little-endian logical button bitmap.
        // Decode the wire bytes rather than comparing against the encoder itself.
        assertEquals(expectedBits,(report[14]&0xff)|((report[15]&0xff)<<8));
    }

    private static ControllerProfile profile(ControllerProfile.Kind kind,Map<Integer,Integer> buttons) {
        return new ControllerProfile("test","Test",kind,"Virtual gamepad",0x1234,0x5678,6,
                buttons,Map.of(),ControllerProfile.AxisConfig.DEFAULT);
    }
}
