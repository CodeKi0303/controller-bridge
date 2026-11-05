package dev.codek.xboxprobe;

import dev.codek.xboxprobe.core.ControllerProfile;
import dev.codek.xboxprobe.core.GamepadSourceLayout;
import dev.codek.xboxprobe.core.SourceBindings;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import static org.junit.Assert.*;

public final class ProfileCodecTest {
    @Test public void everyKindRoundTripsWithoutInferringKindFromIdentity() {
        for(String id:new String[]{ProfileStore.KEYBOARD_ID,ProfileStore.LEGACY_KISHI_ID,ProfileStore.XBOX_ID,
                ProfileStore.G8_VIRTUAL_ID,ProfileStore.G8_DIRECT_ID}) {
            ControllerProfile profile=ProfileStore.legacyBuiltinDefault(id);
            assertEquals(profile,ProfileCodec.decode(ProfileCodec.encode(profile)));
        }
        ControllerProfile xbox=new ControllerProfile("custom","이름 \"테스트\" 😀",ControllerProfile.Kind.XBOX,
                "GAMESIR GameSir-G8",0x3537,0x0108,3,Collections.emptyMap(),Collections.emptyMap(),
                new ControllerProfile.AxisConfig(true,true,false,false,true,true,20));
        ControllerProfile decoded=ProfileCodec.decode(ProfileCodec.encode(xbox));
        assertEquals(xbox,decoded);
        assertEquals(ControllerProfile.Kind.XBOX,decoded.getKind());
        assertEquals(0x3537,decoded.getVendorId());
    }

    @Test public void explicitDisabledBindingsAndLegacyKeyRangeArePreserved() {
        Map<Integer,Integer> keys=new LinkedHashMap<>();keys.put(304,0);keys.put(700,0x65);
        ControllerProfile profile=new ControllerProfile("keys","키",ControllerProfile.Kind.KEYBOARD,
                "G8 Keyboard Probe",0,0,3,Collections.emptyMap(),keys,ControllerProfile.AxisConfig.DEFAULT);
        ControllerProfile decoded=ProfileCodec.decode(ProfileCodec.encode(profile));
        assertEquals(Integer.valueOf(0),decoded.getKeyboardBindings().get(304));
        assertEquals(Integer.valueOf(0x65),decoded.getKeyboardBindings().get(700));
        assertEquals(2,decoded.getKeyboardBindings().size());
    }

    @Test public void duplicateUnknownMissingAndTrailingFieldsAreRejected() {
        String source=ProfileCodec.encode(ProfileStore.legacyBuiltinDefault(ProfileStore.KEYBOARD_ID));
        rejected(source.replace("\"schemaVersion\":2","\"schemaVersion\":2,\"schemaVersion\":2"));
        rejected(source.replace("\"schemaVersion\":2","\"schemaVersion\":3"));
        rejected(source.replace("\"schemaVersion\":2","\"schemaVersion\":2,\"future\":true"));
        rejected(source.replace("\"vendorId\":0,",""));
        rejected(source+" true");
        rejected(source.substring(0,source.length()-1)+",}");
        rejected(source.replace("\"keyboard\"","'keyboard'"));
        rejected(source.replace("\"keyboard\"","keyboard"));
    }

    @Test public void integerSemanticsDoNotTruncateStringsFractionsOrOverflow() {
        String source=ProfileCodec.encode(ProfileStore.legacyBuiltinDefault(ProfileStore.KEYBOARD_ID));
        rejected(source.replace("\"vendorId\":0","\"vendorId\":\"0\""));
        rejected(source.replace("\"vendorId\":0","\"vendorId\":0.1"));
        rejected(source.replace("\"vendorId\":0","\"vendorId\":2147483648"));
        rejected(source.replace("\"vendorId\":0","\"vendorId\":01"));
        rejected(source.replace("\"vendorId\":0","\"vendorId\":NaN"));
        assertEquals(ProfileCodec.decode(source),ProfileCodec.decode(source.replace("\"schemaVersion\":2","\"schemaVersion\":2e0")));
    }

    @Test public void utf8BudgetNestingAndUnicodeEscapesAreValidated() {
        rejected(" ".repeat(ProfileCodec.MAX_PROFILE_BYTES+1));
        String source=ProfileCodec.encode(ProfileStore.legacyBuiltinDefault(ProfileStore.KEYBOARD_ID));
        String large=source.replace("키보드 변환","가".repeat(25000));
        IllegalArgumentException oversized=assertThrows(IllegalArgumentException.class,()->ProfileCodec.decode(large));
        assertTrue(oversized.getMessage().contains("byte budget"));
        rejected(source.replace("키보드 변환","\\uD800"));
        rejected(source.replace("키보드 변환","\\uＦ000"));
        rejected(source.replace("키보드 변환","\\q"));
        rejected("{\"schemaVersion\":1,\"profile\":"+"[".repeat(20)+"null"+"]".repeat(20)+"}");
        assertEquals("😀",ProfileCodec.decode(source.replace("키보드 변환","\\uD83D\\uDE00")).getLabel());
    }

    @Test public void storeRoundTripPreservesReferencesAndDefensiveCopies() {
        ControllerProfile keyboard=ProfileStore.legacyBuiltinDefault(ProfileStore.KEYBOARD_ID);
        ControllerProfile kishi=ProfileStore.legacyBuiltinDefault(ProfileStore.LEGACY_KISHI_ID);
        Map<String,String> assignments=new LinkedHashMap<>();assignments.put("example.game",kishi.getId());
        LinkedHashSet<String> pins=new LinkedHashSet<>(Arrays.asList("example.game","example.other"));
        ProfileCodec.StoreDocument d=new ProfileCodec.StoreDocument(Arrays.asList(keyboard,kishi),assignments,
                keyboard.getId(),true,pins,true);
        assignments.clear();pins.clear();
        ProfileCodec.StoreDocument copy=ProfileCodec.decodeStore(ProfileCodec.encodeStore(d));
        assertEquals(d.profiles,copy.profiles);assertEquals(d.gameAssignments,copy.gameAssignments);
        assertEquals(d.pinnedGames,copy.pinnedGames);assertTrue(copy.autoAttachEnabled);
        assertTrue(copy.legacyKeyboardMigrated);assertEquals(keyboard.getId(),copy.defaultProfileId);
        assertThrows(UnsupportedOperationException.class,()->copy.gameAssignments.clear());
    }

    @Test public void brokenStoreReferencesAndDuplicateProfilesFailClosed() {
        ControllerProfile p=ProfileStore.legacyBuiltinDefault(ProfileStore.KEYBOARD_ID);
        assertThrows(IllegalArgumentException.class,()->new ProfileCodec.StoreDocument(Arrays.asList(p,p),
                Collections.emptyMap(),p.getId(),true,Collections.emptySet(),true));
        assertThrows(IllegalArgumentException.class,()->new ProfileCodec.StoreDocument(Collections.singletonList(p),
                Collections.singletonMap("example.game","absent"),p.getId(),true,Collections.emptySet(),true));
        assertThrows(IllegalArgumentException.class,()->new ProfileCodec.StoreDocument(Collections.singletonList(p),
                Collections.emptyMap(),"absent",true,Collections.emptySet(),true));
        assertThrows(IllegalArgumentException.class,()->new ProfileCodec.StoreDocument(new ArrayList<>(),
                Collections.emptyMap(),p.getId(),true,Collections.emptySet(),true));
    }

    @Test public void versionOneProfilesAndStoresMigrateWithoutChangingExistingSettings() {
        String profile = legacyProfile();
        ControllerProfile migrated = ProfileCodec.decode("{\"schemaVersion\":1,\"profile\":" + profile + "}");
        ControllerProfile expected = new ControllerProfile("legacy", "기존 사용자 설정", ControllerProfile.Kind.NATIVE,
                "Retained identity", 4660, 22136, 5, Map.of(304,305,308,0), Map.of(),
                new ControllerProfile.AxisConfig(true,false,true,false,false,true,12));
        assertEquals(expected, migrated);
        assertEquals(SourceBindings.AUTO, migrated.getSourceBindings());
        assertFalse(migrated.isSourceExclusive());
        String store = "{\"schemaVersion\":1,\"profiles\":[" + profile + "],"
                + "\"gameAssignments\":{\"example.game\":\"legacy\"},\"defaultProfileId\":\"legacy\","
                + "\"autoAttachEnabled\":true,\"pinnedGames\":[\"example.game\"],\"legacyKeyboardMigrated\":true}";
        ProfileCodec.StoreDocument migratedStore = ProfileCodec.decodeStore(store);
        assertEquals(Collections.singletonList(expected), migratedStore.profiles);
        assertEquals(Map.of("example.game", "legacy"), migratedStore.gameAssignments);
        assertEquals(Collections.singleton("example.game"), migratedStore.pinnedGames);
        assertTrue(migratedStore.autoAttachEnabled);
        assertTrue(migratedStore.legacyKeyboardMigrated);
        assertEquals("legacy", migratedStore.defaultProfileId);
        String upgraded = ProfileCodec.encodeStore(migratedStore);
        assertTrue(upgraded.startsWith("{\"schemaVersion\":2,"));
        assertEquals(migratedStore.profiles, ProfileCodec.decodeStore(upgraded).profiles);
        rejected("{\"schemaVersion\":2,\"profile\":" + profile + "}");
        rejected(ProfileCodec.encode(expected).replace("\"schemaVersion\":2", "\"schemaVersion\":1"));
    }

    @Test public void customSourceAxesDigitalControlsButtonsAndExclusiveChoiceRoundTrip() {
        SourceBindings bindings = new SourceBindings(false,
                Map.of(GamepadSourceLayout.Control.LEFT_X,42,GamepadSourceLayout.Control.LEFT_Y,43),
                Map.of(SourceBindings.DigitalControl.LEFT_TRIGGER,600,
                        SourceBindings.DigitalControl.RIGHT_TRIGGER,601,
                        SourceBindings.DigitalControl.DPAD_UP,544,
                        SourceBindings.DigitalControl.DPAD_DOWN,545,
                        SourceBindings.DigitalControl.DPAD_LEFT,546,
                        SourceBindings.DigitalControl.DPAD_RIGHT,547));
        ControllerProfile custom = new ControllerProfile("custom", "별도 패드", ControllerProfile.Kind.XBOX,
                "Virtual output", 1, 2, 3, Map.of(700,304,701,0), Map.of(), ControllerProfile.AxisConfig.DEFAULT,
                bindings, true);
        ControllerProfile copy = ProfileCodec.decode(ProfileCodec.encode(custom));
        assertEquals(custom, copy);
        assertEquals(bindings, copy.getSourceBindings());
        assertTrue(copy.isSourceExclusive());
        assertEquals(Integer.valueOf(304), copy.getButtonMappings().get(700));
        assertEquals(Integer.valueOf(0), copy.getButtonMappings().get(701));
        ProfileCodec.StoreDocument store = new ProfileCodec.StoreDocument(Collections.singletonList(custom),
                Map.of("example.game","custom"),"custom",false,Collections.emptySet(),true);
        assertEquals(store.profiles, ProfileCodec.decodeStore(ProfileCodec.encodeStore(store)).profiles);
    }

    @Test public void sourceBindingsRequireKnownTypedFieldsAndExplicitExclusiveBoolean() {
        String encoded = ProfileCodec.encode(ProfileStore.legacyBuiltinDefault(ProfileStore.LEGACY_KISHI_ID));
        rejected(encoded.replace(",\"sourceExclusive\":false", ""));
        rejected(encoded.replace("\"sourceExclusive\":false", "\"sourceExclusive\":\"false\""));
        rejected(encoded.replace("\"sourceExclusive\":false", "\"sourceExclusive\":0"));
        rejected(encoded.replace("\"automatic\":true", "\"automatic\":true,\"future\":0"));
        rejected(encoded.replace("\"keys\":{}", "\"keys\":{\"UNKNOWN\":544}"));
        rejected(encoded.replace("\"axes\":{}", "\"axes\":{\"LEFT_X\":0}")); // auto cannot hide manual bindings
        String explicit = encoded.replace("\"automatic\":true", "\"automatic\":false");
        rejected(explicit.replace("\"axes\":{}", "\"axes\":{\"LEFT_X\":64}"));
        rejected(explicit.replace("\"axes\":{}", "\"axes\":{\"LEFT_X\":\"0\"}"));
        rejected(explicit.replace("\"keys\":{}", "\"keys\":{\"LEFT_TRIGGER\":768}"));
        rejected(explicit.replace("\"keys\":{}", "\"keys\":{\"LEFT_TRIGGER\":0}"));
        rejected(explicit.replace("\"keys\":{}", "\"keys\":{\"LEFT_TRIGGER\":544,\"LEFT_TRIGGER\":545}"));
    }

    private static String legacyProfile() {
        return "{\"id\":\"legacy\",\"label\":\"기존 사용자 설정\",\"kind\":\"native\","
                + "\"deviceName\":\"Retained identity\",\"vendorId\":4660,\"productId\":22136,\"bus\":5,"
                + "\"buttonMappings\":{\"304\":305,\"308\":0},\"keyboardBindings\":{},"
                + "\"axes\":{\"swapSticks\":true,\"invertLeftX\":false,\"invertLeftY\":true,"
                + "\"invertRightX\":false,\"invertRightY\":false,\"swapTriggers\":true,\"deadzonePercent\":12}}";
    }

    private static void rejected(String value){assertThrows(IllegalArgumentException.class,()->ProfileCodec.decode(value));}
}
