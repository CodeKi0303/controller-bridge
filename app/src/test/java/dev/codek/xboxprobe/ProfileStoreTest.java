package dev.codek.xboxprobe;

import android.content.SharedPreferences;
import dev.codek.xboxprobe.core.ControllerProfile;
import dev.codek.xboxprobe.core.SourceBindings;
import dev.codek.xboxprobe.core.GamepadSourceLayout;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

public final class ProfileStoreTest {
    @Test public void freshCatalogContainsOnlyFourIdentityPresetsWithKishiDefault() {
        ProfileStore store=fresh();
        assertEquals(Arrays.asList("PS4","PS5","Xbox","Kishi Ultra"),labels(store.listProfiles()));
        assertEquals(ProfileStore.KISHI_ID,store.getDefaultProfileId());
        assertEquals("Razer Razer Kishi Ultra",store.getDefaultProfile().getDeviceName());
        ControllerProfile ps4=store.getProfile(ProfileStore.PS4_ID),ps5=store.getProfile(ProfileStore.PS5_ID);
        assertEquals("Wireless Controller",ps4.getDeviceName());assertEquals("Wireless Controller",ps5.getDeviceName());
        assertEquals(0x054c,ps4.getVendorId());assertEquals(0x09cc,ps4.getProductId());assertEquals(0x0ce6,ps5.getProductId());
        assertEquals(6,ps4.getBus());assertEquals(6,ps5.getBus());
        assertEquals(ControllerProfile.Kind.NATIVE,ps4.getKind());assertEquals(ControllerProfile.Kind.NATIVE,ps5.getKind());
        // Sony output semantics are verified through independent Android key
        // layouts in SonyAndroidOutputTest, not by comparing descriptor bytes.
        assertFalse(ProfileStore.isBuiltIn(ProfileStore.KEYBOARD_ID));assertNull(store.getProfile(ProfileStore.KEYBOARD_ID));
        assertThrows(IllegalArgumentException.class,()->ProfileStore.builtinDefault(ProfileStore.G8_DIRECT_ID));
    }

    @Test public void migrationArchivesExactDocumentRetainsEditedSurvivorsAndReassignsEveryRemovedProfile() {
        MemoryPreferences data=new MemoryPreferences();List<ControllerProfile> previous=oldBuiltins(true);
        ControllerProfile originalKishi=previous.get(5),originalXbox=previous.get(2);
        SourceBindings source=new SourceBindings(false,Map.of(GamepadSourceLayout.Control.LEFT_X,32),Map.of(SourceBindings.DigitalControl.DPAD_UP,700));
        ControllerProfile kishi=new ControllerProfile(originalKishi.getId(),"내 Kishi",originalKishi.getKind(),"Edited Kishi",0x1234,0xabcd,6,
                Map.of(305,304),Map.of(),new ControllerProfile.AxisConfig(true,false,true,false,false,false,12),source,true);
        ControllerProfile xbox=new ControllerProfile(originalXbox.getId(),originalXbox.getLabel(),originalXbox.getKind(),"Edited Xbox",7,8,5,
                Map.of(304,305),Map.of(),ControllerProfile.AxisConfig.DEFAULT);
        previous.set(5,kishi);previous.set(2,xbox);
        ControllerProfile custom=new ControllerProfile("my-custom","Custom",ControllerProfile.Kind.KEYBOARD,"My Keys",1,2,3,Map.of(),Map.of(304,0x0e),ControllerProfile.AxisConfig.DEFAULT);
        previous.add(custom);Map<String,String> games=new LinkedHashMap<>();
        for(int i=0;i<previous.size();i++)games.put("example.game"+i,previous.get(i).getId());
        String raw=" \n"+ProfileCodec.encodeStore(new ProfileCodec.StoreDocument(previous,games,custom.getId(),false,Set.of("example.game0"),true))+"\n";
        data.values.put(ProfileStore.DOCUMENT_KEY,raw);ProfileStore store=new ProfileStore(data,new MemoryPreferences());
        ProfileCodec.StoreDocument migrated=store.snapshot();
        assertEquals(4,migrated.profiles.size());assertEquals(kishi,store.getProfile(kishi.getId()));
        ControllerProfile savedXbox=store.getProfile(xbox.getId());assertEquals("Xbox",savedXbox.getLabel());
        assertEquals(xbox.getIdentity(),savedXbox.getIdentity());assertEquals(xbox.getButtonMappings(),savedXbox.getButtonMappings());
        for(Map.Entry<String,String> game:games.entrySet())assertEquals(ProfileStore.XBOX_ID.equals(game.getValue())?ProfileStore.XBOX_ID:ProfileStore.KISHI_ID,store.getAssignedProfileId(game.getKey()));
        assertEquals(ProfileStore.KISHI_ID,migrated.defaultProfileId);assertFalse(migrated.autoAttachEnabled);assertEquals(Set.of("example.game0"),migrated.pinnedGames);
        assertEquals(raw,store.getCatalogBackupJson());assertEquals(raw,store.getBackupJson());assertEquals(1,data.commits);
        String current=data.getString(ProfileStore.DOCUMENT_KEY,null);new ProfileStore(data,new MemoryPreferences()).snapshot();
        assertEquals(1,data.commits);assertEquals(current,data.getString(ProfileStore.DOCUMENT_KEY,null));
        store.setDefaultProfileId(ProfileStore.PS5_ID);assertEquals(current,store.getBackupJson());assertEquals(raw,store.getCatalogBackupJson());
        assertEquals(4,new ProfileStore(data,new MemoryPreferences()).listProfiles().size());
    }

    @Test public void olderCatalogGetsCurrentKishiAndOldEditsRemainInArchive() {
        MemoryPreferences data=new MemoryPreferences();List<ControllerProfile> previous=oldBuiltins(false);
        ControllerProfile old=previous.get(1);previous.set(1,new ControllerProfile(old.getId(),"Old custom Kishi",old.getKind(),"Old Device",3,4,5,Map.of(304,305),Map.of(),old.getAxes()));
        String raw=ProfileCodec.encodeStore(new ProfileCodec.StoreDocument(previous,Map.of("example.game",ProfileStore.LEGACY_KISHI_ID),ProfileStore.XBOX_ID,true,Set.of(),true));
        data.values.put(ProfileStore.DOCUMENT_KEY,raw);ProfileStore store=new ProfileStore(data,new MemoryPreferences());
        assertEquals("Razer Razer Kishi Ultra",store.resolveProfileForGame("example.game").getDeviceName());
        assertEquals(ProfileStore.XBOX_ID,store.getDefaultProfileId());assertEquals(raw,store.getCatalogBackupJson());
        assertNull(store.getProfile(ProfileStore.LEGACY_KISHI_ID));
    }

    @Test public void pendingLegacyKeyboardSettingsAreValidatedArchivedAndNeverImportedAgain() {
        MemoryPreferences data=new MemoryPreferences(),legacy=new MemoryPreferences();
        legacy.values.put("keyMapping_304",0x0e);legacy.values.put("keyMapping_305",0);legacy.values.put("keyMapping_700",0x1a);legacy.values.put("referenceDevice","unrelated retained value");
        Map<String,Object> original=new LinkedHashMap<>(legacy.values);
        String raw=ProfileCodec.encodeStore(new ProfileCodec.StoreDocument(oldBuiltins(false),Map.of(),ProfileStore.KEYBOARD_ID,true,Set.of(),false));
        data.values.put(ProfileStore.DOCUMENT_KEY,raw);ProfileStore store=new ProfileStore(data,legacy);
        assertEquals(4,store.listProfiles().size());assertTrue(store.snapshot().legacyKeyboardMigrated);
        assertEquals(original,legacy.values);assertEquals(0,legacy.commits);assertEquals(raw,store.getCatalogBackupJson());
        String backup=data.getString(ProfileStore.LEGACY_BACKUP_KEY,"");assertTrue(backup.contains("\"keyMapping_305\":0"));assertTrue(backup.contains("\"keyMapping_700\":26"));
        legacy.values.put("keyMapping_304","later invalid edit");new ProfileStore(data,legacy).snapshot();
        assertEquals(1,data.commits);assertEquals(backup,data.getString(ProfileStore.LEGACY_BACKUP_KEY,null));
    }

    @Test public void malformedStorageAndLegacyValuesNeverSilentlyResetOrPartiallyMigrate() {
        for(int scenario=0;scenario<4;scenario++){
            MemoryPreferences data=new MemoryPreferences(),legacy=new MemoryPreferences();List<ControllerProfile> profiles=oldBuiltins(false);
            if(scenario==0)profiles.remove(2);
            if(scenario==1)profiles.add(new ControllerProfile(ProfileStore.KISHI_ID,"Wrong kind",ControllerProfile.Kind.KEYBOARD,"Keys",0,0,3,Map.of(),Map.of(),ControllerProfile.AxisConfig.DEFAULT));
            if(scenario==2)legacy.values.put("keyMapping_304","invalid");
            String raw=scenario==3?"{broken":ProfileCodec.encodeStore(new ProfileCodec.StoreDocument(profiles,Map.of(),ProfileStore.KEYBOARD_ID,true,Set.of(),scenario!=2));
            data.values.put(ProfileStore.DOCUMENT_KEY,raw);
            assertThrows(RuntimeException.class,()->new ProfileStore(data,legacy).snapshot());
            assertEquals(raw,data.getString(ProfileStore.DOCUMENT_KEY,null));assertFalse(data.contains(ProfileStore.CATALOG_VERSION_KEY));
            assertFalse(data.contains(ProfileStore.CATALOG_BACKUP_KEY));assertFalse(data.contains(ProfileStore.BACKUP_KEY));assertEquals(0,data.commits);
        }
        MemoryPreferences data=new MemoryPreferences(),legacy=new MemoryPreferences();legacy.values.put("keyMapping_304",99999);
        assertThrows(IllegalArgumentException.class,()->new ProfileStore(data,legacy).snapshot());assertEquals(0,data.commits);
    }

    @Test public void currentCatalogCorruptionAndUnknownVersionAreNotRepaired() {
        MemoryPreferences data=new MemoryPreferences();ProfileStore store=new ProfileStore(data,new MemoryPreferences());ProfileCodec.StoreDocument current=store.snapshot();
        List<ControllerProfile> missing=new ArrayList<>(current.profiles);missing.remove(0);
        String broken=ProfileCodec.encodeStore(new ProfileCodec.StoreDocument(missing,current.gameAssignments,current.defaultProfileId,true,Set.of(),true));
        data.values.put(ProfileStore.DOCUMENT_KEY,broken);int commits=data.commits;
        assertThrows(IllegalStateException.class,store::snapshot);assertEquals(commits,data.commits);
        data.values.put(ProfileStore.CATALOG_VERSION_KEY,2);assertThrows(IllegalStateException.class,store::snapshot);assertEquals(commits,data.commits);
    }

    @Test public void migrationCommitFailureRetainsPermanentAndRollingOriginalBackups() {
        MemoryPreferences data=new MemoryPreferences();String raw=ProfileCodec.encodeStore(new ProfileCodec.StoreDocument(oldBuiltins(true),Map.of(),ProfileStore.KEYBOARD_ID,true,Set.of(),true));
        data.values.put(ProfileStore.DOCUMENT_KEY,raw);data.failCommit=true;
        assertThrows(IllegalStateException.class,()->new ProfileStore(data,new MemoryPreferences()).snapshot());
        assertEquals(raw,data.getString(ProfileStore.CATALOG_BACKUP_KEY,null));assertEquals(raw,data.getString(ProfileStore.BACKUP_KEY,null));
    }

    @Test public void onlyExistingBuiltinIdentityAndMappingsCanBeEditedAndReset() {
        ProfileStore store=fresh();ControllerProfile original=store.getProfile(ProfileStore.KISHI_ID);
        SourceBindings source=new SourceBindings(false,Map.of(GamepadSourceLayout.Control.LEFT_X,32),Map.of(SourceBindings.DigitalControl.DPAD_UP,700));
        ControllerProfile edited=new ControllerProfile(original.getId(),"내 설정",original.getKind(),"Custom Pad",0x1234,0xabcd,6,Map.of(704,304),Map.of(),
                new ControllerProfile.AxisConfig(false,true,false,false,false,false,10),source,true);
        store.saveProfile(edited);assertEquals(edited,store.getProfile(original.getId()));
        ControllerProfile wrongKind=new ControllerProfile(original.getId(),"Wrong kind",ControllerProfile.Kind.KEYBOARD,"Keys",1,2,3,Map.of(),Map.of(),ControllerProfile.AxisConfig.DEFAULT);
        ControllerProfile extra=new ControllerProfile("custom","Extra",original.getKind(),original.getDeviceName(),1,2,3,Map.of(),Map.of(),original.getAxes());
        assertThrows(IllegalArgumentException.class,()->store.saveProfile(wrongKind));assertThrows(IllegalArgumentException.class,()->store.saveProfile(extra));
        assertEquals(4,store.listProfiles().size());assertEquals(original,store.resetBuiltin(original.getId()));assertTrue(store.getBackupJson().contains("Custom Pad"));
    }

    @Test public void assignmentsPinsAndAutoAttachPreserveExplicitOmissionAndDefaultChoice() {
        ProfileStore store=fresh();assertTrue(store.isAutoAttachEnabled());assertNull(store.getAssignedProfileId("example.game"));
        assertEquals(ProfileStore.KISHI_ID,store.resolveProfileForGame("example.game").getId());
        store.assignGame("example.game",ProfileStore.XBOX_ID);store.setGamePinned("example.game",true);
        assertEquals(ProfileStore.XBOX_ID,store.resolveProfileForGame("example.game").getId());
        store.setDefaultProfileId(ProfileStore.PS5_ID);store.clearGameAssignment("example.game");
        assertNull(store.getAssignedProfileId("example.game"));assertEquals(ProfileStore.PS5_ID,store.resolveProfileForGame("example.game").getId());
        assertTrue(store.getPinnedGames().contains("example.game"));store.setAutoAttachEnabled(false);assertFalse(store.isAutoAttachEnabled());
        assertThrows(IllegalArgumentException.class,()->store.assignGame("invalid/package",ProfileStore.XBOX_ID));
        assertThrows(IllegalArgumentException.class,()->store.assignGame("example.game",ProfileStore.G8_DIRECT_ID));
    }

    @Test public void mutationsBackUpExactPreviousDocumentAndNoOpWritesAreIdempotent() {
        MemoryPreferences data=new MemoryPreferences();ProfileStore store=new ProfileStore(data,new MemoryPreferences());
        store.listProfiles();String first=data.getString(ProfileStore.DOCUMENT_KEY,null);
        store.setAutoAttachEnabled(false);assertEquals(first,store.getBackupJson());
        int commits=data.commits;String current=data.getString(ProfileStore.DOCUMENT_KEY,null);
        store.setAutoAttachEnabled(false);assertEquals(commits,data.commits);assertEquals(first,store.getBackupJson());
        store.setDefaultProfileId(ProfileStore.XBOX_ID);assertEquals(current,store.getBackupJson());
    }

    @Test public void separateInstancesReadFreshStateAndFailedCommitKeepsPreviousSnapshot() {
        MemoryPreferences data=new MemoryPreferences(),legacy=new MemoryPreferences();ProfileStore one=new ProfileStore(data,legacy),two=new ProfileStore(data,legacy);
        one.listProfiles();two.listProfiles();one.assignGame("example.game",ProfileStore.KISHI_ID);two.setAutoAttachEnabled(false);
        assertEquals(ProfileStore.KISHI_ID,one.getAssignedProfileId("example.game"));assertFalse(one.isAutoAttachEnabled());
        String previous=data.getString(ProfileStore.DOCUMENT_KEY,null);data.failCommit=true;
        assertThrows(IllegalStateException.class,()->one.setAutoAttachEnabled(true));assertEquals(previous,data.getString(ProfileStore.BACKUP_KEY,null));
    }

    private static ProfileStore fresh(){return new ProfileStore(new MemoryPreferences(),new MemoryPreferences());}
    private static List<String> labels(List<ControllerProfile> profiles){List<String> values=new ArrayList<>();for(ControllerProfile p:profiles)values.add(p.getLabel());return values;}
    private static List<ControllerProfile> oldBuiltins(boolean currentKishi){
        List<ControllerProfile> profiles=new ArrayList<>();
        for(String id:new String[]{ProfileStore.KEYBOARD_ID,ProfileStore.LEGACY_KISHI_ID,ProfileStore.XBOX_ID,ProfileStore.G8_VIRTUAL_ID,ProfileStore.G8_DIRECT_ID})profiles.add(ProfileStore.legacyBuiltinDefault(id));
        if(currentKishi)profiles.add(ProfileStore.legacyBuiltinDefault(ProfileStore.KISHI_ID));return profiles;
    }

    /** Android's existing storage interface, including commit failure after in-memory publication. */
    private static final class MemoryPreferences implements SharedPreferences {
        final Map<String,Object> values=new LinkedHashMap<>();int commits;boolean failCommit;
        public Map<String,?> getAll(){return new LinkedHashMap<>(values);}
        public String getString(String key,String fallback){Object value=values.get(key);return value==null?fallback:(String)value;}
        @SuppressWarnings("unchecked") public Set<String> getStringSet(String key,Set<String> fallback){Object value=values.get(key);return value==null?fallback:new LinkedHashSet<>((Set<String>)value);}
        public int getInt(String key,int fallback){Object value=values.get(key);return value==null?fallback:(Integer)value;}
        public long getLong(String key,long fallback){Object value=values.get(key);return value==null?fallback:(Long)value;}
        public float getFloat(String key,float fallback){Object value=values.get(key);return value==null?fallback:(Float)value;}
        public boolean getBoolean(String key,boolean fallback){Object value=values.get(key);return value==null?fallback:(Boolean)value;}
        public boolean contains(String key){return values.containsKey(key);}
        public Editor edit(){return new MemoryEditor();}
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){}
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){}
        private final class MemoryEditor implements Editor {
            final Map<String,Object> changes=new LinkedHashMap<>();final Set<String> removals=new LinkedHashSet<>();boolean clear;
            public Editor putString(String key,String value){if(value==null)return remove(key);changes.put(key,value);return this;}
            public Editor putStringSet(String key,Set<String> value){if(value==null)return remove(key);changes.put(key,new LinkedHashSet<>(value));return this;}
            public Editor putInt(String key,int value){changes.put(key,value);return this;}
            public Editor putLong(String key,long value){changes.put(key,value);return this;}
            public Editor putFloat(String key,float value){changes.put(key,value);return this;}
            public Editor putBoolean(String key,boolean value){changes.put(key,value);return this;}
            public Editor remove(String key){removals.add(key);changes.remove(key);return this;}
            public Editor clear(){clear=true;return this;}
            public boolean commit(){if(clear)values.clear();for(String key:removals)values.remove(key);values.putAll(changes);commits++;return !failCommit;}
            public void apply(){commit();}
        }
    }
}
