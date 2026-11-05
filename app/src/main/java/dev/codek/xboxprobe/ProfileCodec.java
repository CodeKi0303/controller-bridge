package dev.codek.xboxprobe;

import dev.codek.xboxprobe.core.ControllerProfile;
import dev.codek.xboxprobe.core.GamepadSourceLayout;
import dev.codek.xboxprobe.core.SourceBindings;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** One JSON boundary for Binder requests, exports and private profile storage. */
public final class ProfileCodec {
    public static final int SCHEMA_VERSION = 2;
    public static final int MAX_PROFILE_BYTES = 64 * 1024;
    public static final int MAX_STORE_BYTES = 1024 * 1024;

    private ProfileCodec() {}

    public static ControllerProfile decode(String json) {
        Map<String, Object> root = object(parse(json, MAX_PROFILE_BYTES), "profile document");
        fields(root, "schemaVersion", "profile");
        int version = version(root);
        return readProfile(object(root.get("profile"), "profile"), version);
    }

    public static String encode(ControllerProfile profile) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("profile", profileValue(Objects.requireNonNull(profile, "profile")));
        return render(root, MAX_PROFILE_BYTES);
    }

    /** Immutable storage facts; cross-profile references are checked here once. */
    public static final class StoreDocument {
        public final List<ControllerProfile> profiles;
        public final Map<String, String> gameAssignments;
        public final String defaultProfileId;
        public final boolean autoAttachEnabled;
        public final Set<String> pinnedGames;
        public final boolean legacyKeyboardMigrated;

        public StoreDocument(List<ControllerProfile> profiles, Map<String, String> gameAssignments,
                             String defaultProfileId, boolean autoAttachEnabled,
                             Set<String> pinnedGames, boolean legacyKeyboardMigrated) {
            Objects.requireNonNull(profiles, "profiles");
            Objects.requireNonNull(gameAssignments, "gameAssignments");
            Objects.requireNonNull(pinnedGames, "pinnedGames");
            Set<String> ids = new LinkedHashSet<>();
            List<ControllerProfile> copied = new ArrayList<>();
            for (ControllerProfile profile : profiles) {
                Objects.requireNonNull(profile, "profile");
                if (!ids.add(profile.getId())) throw invalid("Duplicate profile id: " + profile.getId());
                copied.add(profile);
            }
            if (!ids.contains(defaultProfileId)) throw invalid("Default profile does not exist");
            Map<String, String> assignments = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : gameAssignments.entrySet()) {
                validatePackageName(entry.getKey());
                if (!ids.contains(entry.getValue())) throw invalid("Game assignment references an absent profile: " + entry.getKey());
                assignments.put(entry.getKey(), entry.getValue());
            }
            Set<String> pins = new LinkedHashSet<>();
            for (String packageName : pinnedGames) { validatePackageName(packageName); pins.add(packageName); }
            this.profiles = Collections.unmodifiableList(copied);
            this.gameAssignments = Collections.unmodifiableMap(assignments);
            this.defaultProfileId = defaultProfileId;
            this.autoAttachEnabled = autoAttachEnabled;
            this.pinnedGames = Collections.unmodifiableSet(pins);
            this.legacyKeyboardMigrated = legacyKeyboardMigrated;
        }
    }

    public static StoreDocument decodeStore(String json) {
        Map<String, Object> root = object(parse(json, MAX_STORE_BYTES), "store document");
        fields(root, "schemaVersion", "profiles", "gameAssignments", "defaultProfileId",
                "autoAttachEnabled", "pinnedGames", "legacyKeyboardMigrated");
        int version = version(root);
        List<ControllerProfile> profiles = new ArrayList<>();
        for (Object value : array(root.get("profiles"), "profiles")) profiles.add(readProfile(object(value, "profile"), version));
        Map<String, String> assignments = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : object(root.get("gameAssignments"), "gameAssignments").entrySet()) {
            assignments.put(entry.getKey(), string(entry.getValue(), "assigned profile id"));
        }
        Set<String> pins = new LinkedHashSet<>();
        for (Object value : array(root.get("pinnedGames"), "pinnedGames")) {
            String pin = string(value, "pinned package");
            if (!pins.add(pin)) throw invalid("Duplicate pinned package: " + pin);
        }
        return new StoreDocument(profiles, assignments, string(root.get("defaultProfileId"), "defaultProfileId"),
                bool(root.get("autoAttachEnabled"), "autoAttachEnabled"), pins,
                bool(root.get("legacyKeyboardMigrated"), "legacyKeyboardMigrated"));
    }

    public static String encodeStore(StoreDocument document) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", SCHEMA_VERSION);
        List<Object> profiles = new ArrayList<>();
        for (ControllerProfile profile : document.profiles) profiles.add(profileValue(profile));
        root.put("profiles", profiles);
        root.put("gameAssignments", new TreeMap<>(document.gameAssignments));
        root.put("defaultProfileId", document.defaultProfileId);
        root.put("autoAttachEnabled", document.autoAttachEnabled);
        root.put("pinnedGames", new ArrayList<>(new TreeSet<>(document.pinnedGames)));
        root.put("legacyKeyboardMigrated", document.legacyKeyboardMigrated);
        return render(root, MAX_STORE_BYTES);
    }

    static String encodeLegacySnapshot(Map<String, Integer> values) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 1); // The original preference-backup format is independent of profile schemas.
        root.put("sourcePreferences", "MainActivity");
        root.put("values", new TreeMap<>(values));
        return render(root, MAX_PROFILE_BYTES);
    }

    public static void validatePackageName(String value) {
        if (value == null || value.isEmpty() || !value.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)*")) {
            throw invalid("Invalid Android package name");
        }
    }

    private static ControllerProfile readProfile(Map<String, Object> p, int version) {
        if (version == 1) fields(p, "id", "label", "kind", "deviceName", "vendorId", "productId", "bus",
                "buttonMappings", "keyboardBindings", "axes");
        else fields(p, "id", "label", "kind", "deviceName", "vendorId", "productId", "bus",
                "buttonMappings", "keyboardBindings", "axes", "sourceBindings", "sourceExclusive");
        String kindText = string(p.get("kind"), "kind");
        ControllerProfile.Kind kind;
        switch (kindText) {
            case "direct": kind = ControllerProfile.Kind.DIRECT; break;
            case "keyboard": kind = ControllerProfile.Kind.KEYBOARD; break;
            case "native": kind = ControllerProfile.Kind.NATIVE; break;
            case "xbox": kind = ControllerProfile.Kind.XBOX; break;
            default: throw invalid("Unknown profile kind: " + kindText);
        }
        Map<String, Object> a = object(p.get("axes"), "axes");
        fields(a, "swapSticks", "invertLeftX", "invertLeftY", "invertRightX", "invertRightY", "swapTriggers", "deadzonePercent");
        ControllerProfile.AxisConfig axes = new ControllerProfile.AxisConfig(
                bool(a.get("swapSticks"), "swapSticks"), bool(a.get("invertLeftX"), "invertLeftX"),
                bool(a.get("invertLeftY"), "invertLeftY"), bool(a.get("invertRightX"), "invertRightX"),
                bool(a.get("invertRightY"), "invertRightY"), bool(a.get("swapTriggers"), "swapTriggers"),
                integer(a.get("deadzonePercent"), "deadzonePercent"));
        return new ControllerProfile(string(p.get("id"), "id"), string(p.get("label"), "label"), kind,
                string(p.get("deviceName"), "deviceName"), integer(p.get("vendorId"), "vendorId"),
                integer(p.get("productId"), "productId"), integer(p.get("bus"), "bus"),
                intMap(p.get("buttonMappings"), "buttonMappings"), intMap(p.get("keyboardBindings"), "keyboardBindings"), axes,
                version == 1 ? SourceBindings.AUTO : readSourceBindings(p.get("sourceBindings")),
                version != 1 && bool(p.get("sourceExclusive"), "sourceExclusive"));
    }

    private static Map<String, Object> profileValue(ControllerProfile profile) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", profile.getId()); p.put("label", profile.getLabel());
        p.put("kind", profile.getKind().name().toLowerCase(Locale.ROOT));
        p.put("deviceName", profile.getDeviceName()); p.put("vendorId", profile.getVendorId());
        p.put("productId", profile.getProductId()); p.put("bus", profile.getBus());
        p.put("buttonMappings", mapValue(profile.getButtonMappings()));
        p.put("keyboardBindings", mapValue(profile.getKeyboardBindings()));
        ControllerProfile.AxisConfig axes = profile.getAxes();
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("swapSticks", axes.isSwapSticks()); a.put("invertLeftX", axes.isInvertLeftX());
        a.put("invertLeftY", axes.isInvertLeftY()); a.put("invertRightX", axes.isInvertRightX());
        a.put("invertRightY", axes.isInvertRightY()); a.put("swapTriggers", axes.isSwapTriggers());
        a.put("deadzonePercent", axes.getDeadzonePercent()); p.put("axes", a);
        SourceBindings source = profile.getSourceBindings();
        Map<String, Object> bindings = new LinkedHashMap<>();
        bindings.put("automatic", source.isAutomatic());
        bindings.put("axes", enumMapValue(source.getAxisBindings()));
        bindings.put("keys", enumMapValue(source.getKeyBindings()));
        p.put("sourceBindings", bindings);
        p.put("sourceExclusive", profile.isSourceExclusive());
        return p;
    }

    private static SourceBindings readSourceBindings(Object value) {
        Map<String, Object> bindings = object(value, "sourceBindings");
        fields(bindings, "automatic", "axes", "keys");
        return new SourceBindings(bool(bindings.get("automatic"), "sourceBindings.automatic"),
                enumIntMap(bindings.get("axes"), "sourceBindings.axes", GamepadSourceLayout.Control.class),
                enumIntMap(bindings.get("keys"), "sourceBindings.keys", SourceBindings.DigitalControl.class));
    }

    private static <E extends Enum<E>> Map<E, Integer> enumIntMap(Object value, String name, Class<E> type) {
        Map<E, Integer> result = new EnumMap<>(type);
        for (Map.Entry<String, Object> entry : object(value, name).entrySet()) {
            E control;
            try { control = Enum.valueOf(type, entry.getKey()); }
            catch (IllegalArgumentException failure) { throw invalid("Unknown source control in " + name + ": " + entry.getKey()); }
            result.put(control, integer(entry.getValue(), name + " value"));
        }
        return result;
    }

    private static Map<String, Object> enumMapValue(Map<? extends Enum<?>, Integer> values) {
        Map<String, Object> result = new TreeMap<>();
        for (Map.Entry<? extends Enum<?>, Integer> entry : values.entrySet()) result.put(entry.getKey().name(), entry.getValue());
        return result;
    }

    private static Map<String, Object> mapValue(Map<Integer, Integer> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> entry : new TreeMap<>(values).entrySet()) result.put(entry.getKey().toString(), entry.getValue());
        return result;
    }

    private static Map<Integer, Integer> intMap(Object value, String name) {
        Map<Integer, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : object(value, name).entrySet()) {
            if (!entry.getKey().matches("[1-9][0-9]{0,2}")) throw invalid(name + " keys must be canonical decimal Linux key codes");
            result.put(Integer.parseInt(entry.getKey()), integer(entry.getValue(), name + " value"));
        }
        return result;
    }

    private static int version(Map<String, Object> value) {
        int version = integer(value.get("schemaVersion"), "schemaVersion");
        if (version != 1 && version != SCHEMA_VERSION) throw invalid("Unsupported profile schema version");
        return version;
    }
    private static void fields(Map<String, Object> value, String... names) {
        Set<String> allowed = new LinkedHashSet<>(Arrays.asList(names));
        if (!value.keySet().equals(allowed)) throw invalid("Missing or unknown object fields; expected " + allowed);
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value, String name) {
        if (!(value instanceof Map)) throw invalid(name + " must be an object");
        return (Map<String, Object>) value;
    }
    @SuppressWarnings("unchecked") private static List<Object> array(Object value, String name) {
        if (!(value instanceof List)) throw invalid(name + " must be an array");
        return (List<Object>) value;
    }
    private static String string(Object value, String name) {
        if (!(value instanceof String)) throw invalid(name + " must be a string");
        return (String) value;
    }
    private static boolean bool(Object value, String name) {
        if (!(value instanceof Boolean)) throw invalid(name + " must be boolean");
        return (Boolean) value;
    }
    private static int integer(Object value, String name) {
        if (!(value instanceof BigDecimal)) throw invalid(name + " must be an integer");
        try { return ((BigDecimal) value).intValueExact(); }
        catch (ArithmeticException e) { throw invalid(name + " must be an exact 32-bit integer"); }
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }

    private static Object parse(String json, int limit) {
        bounded(json, limit);
        Parser parser = new Parser(json);
        Object result = parser.value(0); parser.space();
        if (parser.at != json.length()) throw invalid("Trailing content after JSON");
        return result;
    }
    private static void bounded(String value, int limit) {
        if (value == null || value.length() > limit) throw invalid("JSON exceeds the " + limit + " byte budget");
        unicode(value);
        if (value.getBytes(StandardCharsets.UTF_8).length > limit) throw invalid("JSON exceeds the " + limit + " byte budget");
    }
    private static void unicode(String value) {
        for (int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i>=value.length() || !Character.isLowSurrogate(value.charAt(i))) throw invalid("Malformed UTF-16 string");
            } else if (Character.isLowSurrogate(c)) throw invalid("Malformed UTF-16 string");
        }
    }

    /** Strict JSON grammar, including duplicate-key checks, shared by Android and JVM tests. */
    private static final class Parser {
        final String text; int at;
        Parser(String text) { this.text=text; }
        void space() { while(at<text.length() && " \t\r\n".indexOf(text.charAt(at))>=0) at++; }
        char next() { if(at>=text.length()) throw invalid("Unexpected end of JSON"); return text.charAt(at++); }
        boolean take(char c) { space(); if(at<text.length() && text.charAt(at)==c){at++;return true;}return false; }
        void expect(char c) { if(!take(c)) throw invalid("Expected '"+c+"' at JSON offset "+at); }
        Object value(int depth) {
            if(depth>16) throw invalid("JSON nesting exceeds 16 levels");
            space(); if(at>=text.length()) throw invalid("Unexpected end of JSON");
            char c=text.charAt(at);
            if(c=='"') return text();
            if(c=='{') {
                at++; Map<String,Object> result=new LinkedHashMap<>(); if(take('}'))return result;
                do { space(); if(at>=text.length()||text.charAt(at)!='"')throw invalid("Object keys must be quoted");
                    String key=text();expect(':'); if(result.containsKey(key))throw invalid("Duplicate JSON key: "+key);
                    result.put(key,value(depth+1)); if(take('}'))return result;expect(',');
                }while(true);
            }
            if(c=='[') {
                at++;List<Object> result=new ArrayList<>();if(take(']'))return result;
                do {result.add(value(depth+1));if(take(']'))return result;expect(',');}while(true);
            }
            for(String literal:new String[]{"true","false","null"}) if(text.startsWith(literal,at)) {
                at+=literal.length();return literal.equals("null")?null:literal.equals("true");
            }
            int start=at;
            if(c=='-')at++;
            if(at>=text.length())throw invalid("Invalid JSON number");
            if(text.charAt(at)=='0')at++;
            else {if(text.charAt(at)<'1'||text.charAt(at)>'9')throw invalid("Invalid JSON value");digits();}
            if(at<text.length()&&text.charAt(at)=='.'){at++;int from=at;digits();if(at==from)throw invalid("Missing fractional digits");}
            if(at<text.length()&&(text.charAt(at)=='e'||text.charAt(at)=='E')){at++;if(at<text.length()&&(text.charAt(at)=='+'||text.charAt(at)=='-'))at++;int from=at;digits();if(at==from)throw invalid("Missing exponent digits");}
            if(at-start>128)throw invalid("JSON numeric token exceeds resource budget");
            try{return new BigDecimal(text.substring(start,at));}catch(NumberFormatException e){throw invalid("Invalid JSON number");}
        }
        void digits(){while(at<text.length()&&text.charAt(at)>='0'&&text.charAt(at)<='9')at++;}
        String text() {
            if(next()!='"')throw invalid("Expected quoted string");StringBuilder value=new StringBuilder();
            while(true){char c=next();if(c=='"'){String s=value.toString();unicode(s);return s;}if(c<0x20)throw invalid("Unescaped control character");
                if(c!='\\'){value.append(c);continue;}
                char escaped=next();switch(escaped){
                    case '"':case '\\':case '/':value.append(escaped);break;
                    case 'b':value.append('\b');break;case 'f':value.append('\f');break;
                    case 'n':value.append('\n');break;case 'r':value.append('\r');break;case 't':value.append('\t');break;
                    case 'u':int code=0;for(int i=0;i<4;i++){char digit=next();int hex=digit>='0'&&digit<='9'?digit-'0':digit>='a'&&digit<='f'?digit-'a'+10:digit>='A'&&digit<='F'?digit-'A'+10:-1;if(hex<0)throw invalid("Invalid Unicode escape");code=(code<<4)|hex;}value.append((char)code);break;
                    default:throw invalid("Invalid JSON escape");
                }
            }
        }
    }

    private static String render(Object value, int limit) {
        StringBuilder out=new StringBuilder();append(out,value);String json=out.toString();bounded(json,limit);return json;
    }
    private static void append(StringBuilder out,Object value) {
        if(value instanceof String){quote(out,(String)value);return;}
        if(value instanceof Integer||value instanceof Boolean){out.append(value);return;}
        if(value instanceof Map){out.append('{');boolean first=true;for(Map.Entry<?,?>e:((Map<?,?>)value).entrySet()){
            if(!first)out.append(',');first=false;quote(out,(String)e.getKey());out.append(':');append(out,e.getValue());}out.append('}');return;}
        if(value instanceof List){out.append('[');boolean first=true;for(Object item:(List<?>)value){if(!first)out.append(',');first=false;append(out,item);}out.append(']');return;}
        throw invalid("Unsupported serialization value");
    }
    private static void quote(StringBuilder out,String value) {
        unicode(value);out.append('"');for(int i=0;i<value.length();i++){char c=value.charAt(i);switch(c){
            case '"':out.append("\\\"");break;case '\\':out.append("\\\\");break;
            case '\b':out.append("\\b");break;case '\f':out.append("\\f");break;case '\n':out.append("\\n");break;
            case '\r':out.append("\\r");break;case '\t':out.append("\\t");break;
            default:if(c<0x20)out.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else out.append(c);
        }}out.append('"');
    }
}
