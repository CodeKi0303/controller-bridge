package dev.codek.xboxprobe;

import android.content.Context;
import android.content.SharedPreferences;

import dev.codek.xboxprobe.core.ControllerProfile;
import dev.codek.xboxprobe.core.NativeGamepadProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Single app-process settings owner. Every mutation keeps the preceding document. */
public final class ProfileStore {
    public static final String PS4_ID = "ps4-default";
    public static final String PS5_ID = "ps5-default";
    // Retired IDs remain solely to decode and archive existing installations.
    public static final String KEYBOARD_ID = "keyboard-default";
    public static final String LEGACY_KISHI_ID = "kishi-default";
    // Stable storage key retained for upgrades from earlier app versions.
    public static final String KISHI_ID = "honkai-kishi-default";
    public static final String XBOX_ID = "xbox-default";
    public static final String G8_VIRTUAL_ID = "g8-virtual-default";
    public static final String G8_DIRECT_ID = "g8-direct-default";
    public static final String PREFERENCES_NAME = "g8_profiles";
    static final String DOCUMENT_KEY = "document_v1";
    static final String BACKUP_KEY = "document_backup_v1";
    static final String LEGACY_BACKUP_KEY = "legacy_keyboard_backup_v1";
    static final String CATALOG_BACKUP_KEY = "four_profile_catalog_backup_v1";
    static final String CATALOG_VERSION_KEY = "profile_catalog_version";
    private static final int CATALOG_VERSION = 1;
    private static final String[] BUILTIN_IDS = {
            PS4_ID,PS5_ID,XBOX_ID,KISHI_ID
    };
    private static final String[] LEGACY_IDS = {KEYBOARD_ID,LEGACY_KISHI_ID,XBOX_ID,G8_VIRTUAL_ID,G8_DIRECT_ID};
    private static final Object STORE_LOCK = new Object();
    private final SharedPreferences preferences;
    private final SharedPreferences legacy;

    public ProfileStore(Context context) {
        this(preferences(context, PREFERENCES_NAME), preferences(context, "MainActivity"));
    }

    /** Uses the platform storage boundary directly so migration can be tested without a device. */
    ProfileStore(SharedPreferences preferences, SharedPreferences legacy) {
        this.preferences = Objects.requireNonNull(preferences, "preferences");
        this.legacy = Objects.requireNonNull(legacy, "legacy");
    }

    private static SharedPreferences preferences(Context context, String name) {
        Context application = Objects.requireNonNull(context, "context").getApplicationContext();
        return (application == null ? context : application).getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    public ProfileCodec.StoreDocument snapshot() { synchronized (STORE_LOCK) { return load(); } }
    public List<ControllerProfile> listProfiles() { return snapshot().profiles; }
    public ControllerProfile getProfile(String id) { return find(snapshot(), id); }
    public String getDefaultProfileId() { return snapshot().defaultProfileId; }
    public ControllerProfile getDefaultProfile() { ProfileCodec.StoreDocument d=snapshot();return find(d,d.defaultProfileId); }
    public boolean isAutoAttachEnabled() { return snapshot().autoAttachEnabled; }
    public Map<String, String> getGameAssignments() { return snapshot().gameAssignments; }
    public Set<String> getPinnedGames() { return snapshot().pinnedGames; }
    public String getAssignedProfileId(String packageName) {
        ProfileCodec.validatePackageName(packageName);return snapshot().gameAssignments.get(packageName);
    }
    public ControllerProfile resolveProfileForGame(String packageName) {
        ProfileCodec.StoreDocument d=snapshot();
        if(packageName!=null) ProfileCodec.validatePackageName(packageName);
        String id=packageName==null?null:d.gameAssignments.get(packageName);
        return find(d,id==null?d.defaultProfileId:id);
    }
    public String getBackupJson() { synchronized(STORE_LOCK){return preferences.getString(BACKUP_KEY,null);} }
    public String getCatalogBackupJson() { synchronized(STORE_LOCK){return preferences.getString(CATALOG_BACKUP_KEY,null);} }

    public static boolean isBuiltIn(String id) {
        for(String builtinId:BUILTIN_IDS)if(builtinId.equals(id))return true;
        return false;
    }

    public static ControllerProfile builtinDefault(String id) {
        switch(id){
            // Identity presets use our native report layout, not Sony's device protocol.
            // Sony VID/PIDs: upstream Linux drivers/hid/hid-ids.h; PS4 revision 2 and PS5.
            // Virtual bus avoids binding Sony's USB/Bluetooth hardware driver,
            // which expects Sony-specific feature reports from real controllers.
            case PS4_ID:return new ControllerProfile(id,"PS4",ControllerProfile.Kind.NATIVE,"Wireless Controller",0x054c,0x09cc,6,
                    Collections.emptyMap(),Collections.emptyMap(),ControllerProfile.AxisConfig.DEFAULT);
            case PS5_ID:return new ControllerProfile(id,"PS5",ControllerProfile.Kind.NATIVE,"Wireless Controller",0x054c,0x0ce6,6,
                    Collections.emptyMap(),Collections.emptyMap(),ControllerProfile.AxisConfig.DEFAULT);
            case XBOX_ID:return copyProfile(legacyBuiltinDefault(id),id,"Xbox",Collections.emptyMap());
            case KISHI_ID:return copyProfile(legacyBuiltinDefault(id),id,"Kishi Ultra",Collections.emptyMap());
            default:throw new IllegalArgumentException("Not an active built-in profile: "+id);
        }
    }

    /** Retired settings defaults used only for migration and compatibility parsing tests. */
    static ControllerProfile legacyBuiltinDefault(String id) {
        ControllerProfile.Kind kind;String label,name;int vendor,product,bus;
        switch(id) {
            case KEYBOARD_ID:kind=ControllerProfile.Kind.KEYBOARD;label="키보드 변환";name="G8 Keyboard Probe";vendor=0;product=0;bus=3;break;
            case LEGACY_KISHI_ID:kind=ControllerProfile.Kind.NATIVE;label="Kishi 호환 시험";name="Razer Kishi Ultra";vendor=0x1532;product=0x071a;bus=3;break;
            case KISHI_ID:
                NativeGamepadProfile profile=NativeGamepadProfile.KISHI_ULTRA_IDENTITY;
                kind=ControllerProfile.Kind.NATIVE;label="Kishi Ultra";
                name=profile.getName();vendor=profile.getVendorId();product=profile.getProductId();bus=profile.getBus();break;
            case XBOX_ID:kind=ControllerProfile.Kind.XBOX;label="Xbox 호환 시험";name="Xbox Wireless Controller";vendor=0x045e;product=0x02e0;bus=5;break;
            case G8_VIRTUAL_ID:kind=ControllerProfile.Kind.NATIVE;label="G8 가상 비교";name="GAMESIR GameSir-G8";vendor=0x3537;product=0x0108;bus=3;break;
            case G8_DIRECT_ID:kind=ControllerProfile.Kind.DIRECT;label="원본 직접 입력";name="GAMESIR GameSir-G8";vendor=0x3537;product=0x0108;bus=3;break;
            default:throw new IllegalArgumentException("Not a built-in profile: "+id);
        }
        return new ControllerProfile(id,label,kind,name,vendor,product,bus,Collections.emptyMap(),
                kind==ControllerProfile.Kind.KEYBOARD?ControllerProfile.defaultKeyboardBindings():Collections.emptyMap(),
                ControllerProfile.AxisConfig.DEFAULT);
    }

    public void saveProfile(ControllerProfile profile) {
        Objects.requireNonNull(profile,"profile");
        synchronized(STORE_LOCK) {
            ProfileCodec.StoreDocument d=load();
            if(!isBuiltIn(profile.getId())||profile.getKind()!=builtinDefault(profile.getId()).getKind()) {
                throw new IllegalArgumentException("Only the four built-in identities can be edited; their output kinds cannot change");
            }
            List<ControllerProfile> profiles=new ArrayList<>(d.profiles);int index=indexOf(profiles,profile.getId());
            profiles.set(index,profile);
            persist(copy(d,profiles,d.gameAssignments,d.defaultProfileId,d.autoAttachEnabled,d.pinnedGames),null,false);
        }
    }

    public ControllerProfile resetBuiltin(String id) {
        ControllerProfile profile=builtinDefault(id);saveProfile(profile);return profile;
    }

    public void setDefaultProfileId(String id) {
        synchronized(STORE_LOCK){ProfileCodec.StoreDocument d=load();require(d,id);persist(copy(d,d.profiles,d.gameAssignments,id,d.autoAttachEnabled,d.pinnedGames),null,false);}
    }
    public void setAutoAttachEnabled(boolean enabled) {
        synchronized(STORE_LOCK){ProfileCodec.StoreDocument d=load();persist(copy(d,d.profiles,d.gameAssignments,d.defaultProfileId,enabled,d.pinnedGames),null,false);}
    }
    public void assignGame(String packageName,String profileId) {
        ProfileCodec.validatePackageName(packageName);
        synchronized(STORE_LOCK){ProfileCodec.StoreDocument d=load();require(d,profileId);Map<String,String> games=new LinkedHashMap<>(d.gameAssignments);games.put(packageName,profileId);persist(copy(d,d.profiles,games,d.defaultProfileId,d.autoAttachEnabled,d.pinnedGames),null,false);}
    }
    public void clearGameAssignment(String packageName) {
        ProfileCodec.validatePackageName(packageName);
        synchronized(STORE_LOCK){ProfileCodec.StoreDocument d=load();Map<String,String> games=new LinkedHashMap<>(d.gameAssignments);games.remove(packageName);persist(copy(d,d.profiles,games,d.defaultProfileId,d.autoAttachEnabled,d.pinnedGames),null,false);}
    }
    public void setGamePinned(String packageName,boolean pinned) {
        ProfileCodec.validatePackageName(packageName);
        synchronized(STORE_LOCK){ProfileCodec.StoreDocument d=load();Set<String> pins=new LinkedHashSet<>(d.pinnedGames);if(pinned)pins.add(packageName);else pins.remove(packageName);persist(copy(d,d.profiles,d.gameAssignments,d.defaultProfileId,d.autoAttachEnabled,pins),null,false);}
    }

    private ProfileCodec.StoreDocument load() {
        String raw=preferences.getString(DOCUMENT_KEY,null);
        int catalogVersion=preferences.getInt(CATALOG_VERSION_KEY,0);
        if(catalogVersion!=0&&catalogVersion!=CATALOG_VERSION)throw new IllegalStateException("Unsupported profile catalog version: "+catalogVersion);
        if(catalogVersion==CATALOG_VERSION){
            if(raw==null)throw new IllegalStateException("The active profile catalog document is missing");
            ProfileCodec.StoreDocument current=ProfileCodec.decodeStore(raw);validateActiveCatalog(current);return current;
        }
        ProfileCodec.StoreDocument document;
        String legacyBackup=null;
        if(raw==null) {
            List<ControllerProfile> defaults=new ArrayList<>();
            for(String id:LEGACY_IDS)defaults.add(legacyBuiltinDefault(id));
            defaults.add(legacyBuiltinDefault(KISHI_ID));
            document=new ProfileCodec.StoreDocument(defaults,Collections.emptyMap(),KEYBOARD_ID,true,Collections.emptySet(),false);
        } else {
            // Malformed/unsupported storage is not replaced by defaults.
            document=ProfileCodec.decodeStore(raw);
            for(String id:LEGACY_IDS) {
                ControllerProfile profile=find(document,id);
                if(profile==null||profile.getKind()!=legacyBuiltinDefault(id).getKind())throw new IllegalStateException("Missing or invalid legacy built-in profile: "+id);
            }
            ControllerProfile kishi=find(document,KISHI_ID);
            if(kishi!=null&&kishi.getKind()!=ControllerProfile.Kind.NATIVE)throw new IllegalStateException("Invalid retained Kishi profile kind");
        }
        if(!document.legacyKeyboardMigrated) {
            Map<String,Integer> originals=new LinkedHashMap<>();Map<Integer,Integer> imported=new LinkedHashMap<>();
            for(Map.Entry<String,?> entry:legacy.getAll().entrySet()) {
                if(!entry.getKey().startsWith("keyMapping_"))continue;
                String code=entry.getKey().substring("keyMapping_".length());
                if(!code.matches("[1-9][0-9]{0,2}")||!(entry.getValue() instanceof Integer))throw new IllegalStateException("Legacy keyboard setting cannot be imported: "+entry.getKey());
                originals.put(entry.getKey(),(Integer)entry.getValue());imported.put(Integer.parseInt(code),(Integer)entry.getValue());
            }
            ControllerProfile keyboard=require(document,KEYBOARD_ID);
            Map<Integer,Integer> bindings=new LinkedHashMap<>(keyboard.getKeyboardBindings());bindings.putAll(imported);
            // Validate the retired bindings before archiving, even though the
            // keyboard preset is no longer in the active catalog.
            copyProfile(keyboard,keyboard.getId(),keyboard.getLabel(),bindings);
            legacyBackup=ProfileCodec.encodeLegacySnapshot(originals);
        }
        List<ControllerProfile> profiles=new ArrayList<>();
        for(String id:BUILTIN_IDS){
            ControllerProfile retained=(XBOX_ID.equals(id)||KISHI_ID.equals(id))?find(document,id):null;
            if(retained==null)profiles.add(builtinDefault(id));
            else{
                String label=retained.getLabel().equals(legacyBuiltinDefault(id).getLabel())?builtinDefault(id).getLabel():retained.getLabel();
                profiles.add(copyProfile(retained,id,label,retained.getKeyboardBindings()));
            }
        }
        Map<String,String> games=new LinkedHashMap<>();
        for(Map.Entry<String,String> assignment:document.gameAssignments.entrySet())games.put(assignment.getKey(),retainedId(assignment.getValue()));
        ProfileCodec.StoreDocument migrated=new ProfileCodec.StoreDocument(profiles,games,retainedId(document.defaultProfileId),
                document.autoAttachEnabled,document.pinnedGames,true);
        validateActiveCatalog(migrated);
        // Validate everything before one atomic preferences write. The complete
        // preceding document is archived once, independently of rolling backups.
        persist(migrated,legacyBackup,true);
        return migrated;
    }

    private static String retainedId(String id){return XBOX_ID.equals(id)?XBOX_ID:KISHI_ID;}
    private static void validateActiveCatalog(ProfileCodec.StoreDocument document){
        if(document.profiles.size()!=BUILTIN_IDS.length||!document.legacyKeyboardMigrated)throw new IllegalStateException("Invalid active profile catalog");
        for(String id:BUILTIN_IDS){ControllerProfile profile=find(document,id);
            if(profile==null||profile.getKind()!=builtinDefault(id).getKind())throw new IllegalStateException("Missing or invalid active profile: "+id);}
    }
    private void persist(ProfileCodec.StoreDocument document,String legacyBackup,boolean migrateCatalog) {
        String encoded=ProfileCodec.encodeStore(document);
        String previous=preferences.getString(DOCUMENT_KEY,null);
        if(encoded.equals(previous)&&legacyBackup==null&&!migrateCatalog)return;
        SharedPreferences.Editor edit=preferences.edit();
        if(previous!=null&&!previous.equals(encoded))edit.putString(BACKUP_KEY,previous);
        if(migrateCatalog){
            if(previous!=null&&!preferences.contains(CATALOG_BACKUP_KEY))edit.putString(CATALOG_BACKUP_KEY,previous);
            edit.putInt(CATALOG_VERSION_KEY,CATALOG_VERSION);
        }
        if(legacyBackup!=null&&!preferences.contains(LEGACY_BACKUP_KEY))edit.putString(LEGACY_BACKUP_KEY,legacyBackup);
        edit.putString(DOCUMENT_KEY,encoded);
        if(!edit.commit())throw new IllegalStateException("Profile settings persistence is unresolved; reopen settings to verify");
    }

    private static ProfileCodec.StoreDocument copy(ProfileCodec.StoreDocument old,List<ControllerProfile> profiles,
                                                   Map<String,String> games,String defaultId,boolean auto,Set<String> pins) {
        return new ProfileCodec.StoreDocument(profiles,games,defaultId,auto,pins,old.legacyKeyboardMigrated);
    }
    private static ControllerProfile copyProfile(ControllerProfile p,String id,String label,Map<Integer,Integer> keyboard) {
        return new ControllerProfile(id,label,p.getKind(),p.getDeviceName(),p.getVendorId(),p.getProductId(),p.getBus(),p.getButtonMappings(),keyboard,p.getAxes(),p.getSourceBindings(),p.isSourceExclusive());
    }
    private static ControllerProfile find(ProfileCodec.StoreDocument d,String id) {
        for(ControllerProfile p:d.profiles)if(p.getId().equals(id))return p;return null;
    }
    private static ControllerProfile require(ProfileCodec.StoreDocument d,String id) {
        ControllerProfile p=find(d,id);if(p==null)throw new IllegalArgumentException("Profile does not exist: "+id);return p;
    }
    private static int indexOf(List<ControllerProfile> profiles,String id) {
        for(int i=0;i<profiles.size();i++)if(profiles.get(i).getId().equals(id))return i;return -1;
    }
}
