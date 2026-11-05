package dev.codek.xboxprobe.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Pure profile contract. JSON, persistence, game-package associations and builtin IDs have other owners. */
public final class ControllerProfile {
    public enum Kind { DIRECT, KEYBOARD, NATIVE, XBOX }
    public static final int MAX_ID_BYTES=128,MAX_LABEL_BYTES=256;
    private final String id,label;
    private final Kind kind;
    private final HidIdentity identity;
    private final Map<Integer,Integer> buttonMappings,keyboardBindings;
    private final AxisConfig axes;
    private final SourceBindings sourceBindings;
    private final boolean sourceExclusive;

    public ControllerProfile(String id,String label,Kind kind,String deviceName,int vendorId,int productId,int bus,
                              Map<Integer,Integer> buttonMappings,Map<Integer,Integer> keyboardBindings,AxisConfig axes) {
        this(id,label,kind,deviceName,vendorId,productId,bus,buttonMappings,keyboardBindings,axes,SourceBindings.AUTO,false);
    }
    public ControllerProfile(String id,String label,Kind kind,String deviceName,int vendorId,int productId,int bus,
                              Map<Integer,Integer> buttonMappings,Map<Integer,Integer> keyboardBindings,AxisConfig axes,
                              SourceBindings sourceBindings,boolean sourceExclusive) {
        this.id=HidIdentity.checkedText(id,MAX_ID_BYTES,"profile id");
        this.label=HidIdentity.checkedText(label,MAX_LABEL_BYTES,"profile label");
        this.kind=Objects.requireNonNull(kind,"kind");
        this.identity=new HidIdentity(deviceName,vendorId,productId,bus);
        if(bus!=3&&bus!=5&&bus!=6)throw new IllegalArgumentException("Profile bus must be USB (3), Bluetooth metadata (5), or virtual (6)");
        this.axes=Objects.requireNonNull(axes,"axes");
        this.sourceBindings=Objects.requireNonNull(sourceBindings,"sourceBindings");
        this.sourceExclusive=sourceExclusive;
        if(kind==Kind.DIRECT&&(!sourceBindings.isAutomatic()||sourceExclusive))throw new IllegalArgumentException("Direct mode cannot remap or exclusively capture its source");
        Objects.requireNonNull(buttonMappings,"buttonMappings");Objects.requireNonNull(keyboardBindings,"keyboardBindings");
        if((kind==Kind.DIRECT||kind==Kind.KEYBOARD)&&!axes.equals(AxisConfig.DEFAULT))throw new IllegalArgumentException("Axis settings apply only to native/Xbox output");
        if(kind!=Kind.KEYBOARD&&!keyboardBindings.isEmpty())throw new IllegalArgumentException("Keyboard bindings require keyboard output");
        if((kind==Kind.DIRECT||kind==Kind.KEYBOARD)&&!buttonMappings.isEmpty())throw new IllegalArgumentException("Gamepad bindings require native/Xbox output");
        TreeMap<Integer,Integer> mapped=new TreeMap<>(defaultButtons(kind));
        Set<Integer> allowed=supportedTargetButtons(kind);
        for(Map.Entry<Integer,Integer> e:buttonMappings.entrySet()) {
            int source=Objects.requireNonNull(e.getKey(),"source button"),target=Objects.requireNonNull(e.getValue(),"target button");
            if(source<1||source>0x2ff)throw new IllegalArgumentException("Source button must fit Linux KEY_MAX (1..767)");
            if(target!=0&&!allowed.contains(target))throw new IllegalArgumentException("Target button "+target+" is unsupported for "+kind);
            mapped.put(source,target);
        }
        this.buttonMappings=Collections.unmodifiableMap(mapped);
        TreeMap<Integer,Integer> keyboard=new TreeMap<>();
        for(Map.Entry<Integer,Integer> e:keyboardBindings.entrySet()) {
            int source=Objects.requireNonNull(e.getKey(),"keyboard source"),usage=Objects.requireNonNull(e.getValue(),"keyboard usage");
            if(source<1||source>0x2ff)throw new IllegalArgumentException("Keyboard source must fit Linux KEY_MAX");
            if(usage!=0)HidKeyboardReportEncoder.reportForUsage(usage);
            keyboard.put(source,usage);
        }
        this.keyboardBindings=Collections.unmodifiableMap(keyboard);
    }
    public String getId(){return id;}
    public String getLabel(){return label;}
    public Kind getKind(){return kind;}
    public HidIdentity getIdentity(){return identity;}
    public String getDeviceName(){return identity.getName();}
    public int getVendorId(){return identity.getVendorId();}
    public int getProductId(){return identity.getProductId();}
    public int getBus(){return identity.getBus();}
    public Map<Integer,Integer> getButtonMappings(){return buttonMappings;}
    public Map<Integer,Integer> getKeyboardBindings(){return keyboardBindings;}
    public AxisConfig getAxes(){return axes;}
    public SourceBindings getSourceBindings(){return sourceBindings;}
    public boolean isSourceExclusive(){return sourceExclusive;}
    public static Set<Integer> supportedTargetButtons(Kind kind) {
        Objects.requireNonNull(kind,"kind");
        return kind==Kind.NATIVE?GamepadButtons.NATIVE_TARGETS:kind==Kind.XBOX?GamepadButtons.XBOX_TARGETS:Collections.emptySet();
    }
    /** Missing native mappings mean identity; unsupported Xbox buttons default to explicit disabled (0). */
    public static Map<Integer,Integer> defaultButtons(Kind kind) {
        Set<Integer> allowed=supportedTargetButtons(kind);LinkedHashMap<Integer,Integer> result=new LinkedHashMap<>();
        if(kind==Kind.NATIVE||kind==Kind.XBOX)for(int source=304;source<=318;source++)result.put(source,allowed.contains(source)?source:0);
        return Collections.unmodifiableMap(result);
    }
    /** Matches the working 0.5 UI defaults; an explicitly empty keyboard map has no button bindings. */
    public static Map<Integer,Integer> defaultKeyboardBindings() {
        Map<Integer,Integer> result=new LinkedHashMap<>();
        result.put(304,0x0d);result.put(305,0x0e);result.put(307,0x2c);result.put(308,0x18);
        result.put(310,0x1e);result.put(311,0x1f);result.put(314,0x29);result.put(315,0x29);
        return Collections.unmodifiableMap(result);
    }
    public NativeGamepadProfile nativeProfile(){requireKind(Kind.NATIVE);return new NativeGamepadProfile(id,identity);}
    public HidProfile xboxProfile(){requireKind(Kind.XBOX);return new HidProfile(identity);}
    public HidKeyboardProfile keyboardProfile(){requireKind(Kind.KEYBOARD);return new HidKeyboardProfile(identity);}
    public byte[] getDescriptor() {
        switch(kind){case KEYBOARD:return HidKeyboardProfile.keyboardDescriptor();case NATIVE:return nativeProfile().getDescriptor();case XBOX:return xboxProfile().getDescriptor();default:throw new IllegalStateException("Direct mode does not create virtual HID output");}
    }
    public byte[] neutralReport() {
        switch(kind){case KEYBOARD:return HidKeyboardReportEncoder.neutral();case NATIVE:return nativeProfile().neutralReport();case XBOX:return XboxReportEncoder.encode(GamepadState.NEUTRAL);default:throw new IllegalStateException("Direct mode has no virtual neutral report");}
    }
    /** Output-directed diagnostic, independent of source mappings and axis settings. */
    public byte[] encodeProbe(GamepadState state) {
        Objects.requireNonNull(state,"state");
        if(kind==Kind.XBOX)return XboxReportEncoder.encode(state);
        if(kind==Kind.NATIVE)return nativeProfile().encode(NativeGamepadState.fromGamepadState(state));
        throw new IllegalStateException("Gamepad probe requires native/Xbox output");
    }
    public G8InputMapper createMapper(Map<Integer,AxisRange> sourceAxes,Set<Integer> sourceButtons) {
        Objects.requireNonNull(sourceAxes,"sourceAxes");Objects.requireNonNull(sourceButtons,"sourceButtons");
        switch(kind) {
            case NATIVE:return new G8GamepadMapper(this,sourceAxes,sourceButtons);
            case XBOX:return new G8XboxMapper(this,sourceAxes,sourceButtons);
            case KEYBOARD:
                Map<Integer,Integer> active=new HashMap<>();
                for(Map.Entry<Integer,Integer> e:keyboardBindings.entrySet())if(e.getValue()!=0) {
                    if(sourceButtons.contains(e.getKey()))active.put(e.getKey(),e.getValue());
                    else if(!Objects.equals(defaultKeyboardBindings().get(e.getKey()),e.getValue()))
                        throw new IllegalArgumentException("Keyboard binding is not in captured source capabilities: "+e.getKey());
                }
                return new G8KeyboardMapper(GamepadSourceLayout.resolve(sourceAxes,sourceButtons,sourceBindings),active);
            default:throw new IllegalStateException("Direct mode does not create a mapper");
        }
    }
    private void requireKind(Kind expected){if(kind!=expected)throw new IllegalStateException("Profile kind is "+kind+", expected "+expected);}
    @Override public boolean equals(Object other){if(!(other instanceof ControllerProfile))return false;ControllerProfile p=(ControllerProfile)other;return id.equals(p.id)&&label.equals(p.label)&&kind==p.kind&&identity.equals(p.identity)&&buttonMappings.equals(p.buttonMappings)&&keyboardBindings.equals(p.keyboardBindings)&&axes.equals(p.axes)&&sourceBindings.equals(p.sourceBindings)&&sourceExclusive==p.sourceExclusive;}
    @Override public int hashCode(){return Objects.hash(id,label,kind,identity,buttonMappings,keyboardBindings,axes,sourceBindings,sourceExclusive);}

    public static final class AxisConfig {
        public static final AxisConfig DEFAULT=new AxisConfig(false,false,false,false,false,false,0);
        private final boolean swapSticks,invertLeftX,invertLeftY,invertRightX,invertRightY,swapTriggers;
        private final int deadzonePercent;
        public AxisConfig(boolean swapSticks,boolean invertLeftX,boolean invertLeftY,boolean invertRightX,boolean invertRightY,boolean swapTriggers,int deadzonePercent) {
            if(deadzonePercent<0||deadzonePercent>40)throw new IllegalArgumentException("Deadzone must be 0..40 percent");
            this.swapSticks=swapSticks;this.invertLeftX=invertLeftX;this.invertLeftY=invertLeftY;
            this.invertRightX=invertRightX;this.invertRightY=invertRightY;this.swapTriggers=swapTriggers;this.deadzonePercent=deadzonePercent;
        }
        public boolean isSwapSticks(){return swapSticks;}
        public boolean isInvertLeftX(){return invertLeftX;}
        public boolean isInvertLeftY(){return invertLeftY;}
        public boolean isInvertRightX(){return invertRightX;}
        public boolean isInvertRightY(){return invertRightY;}
        public boolean isSwapTriggers(){return swapTriggers;}
        public int getDeadzonePercent(){return deadzonePercent;}
        @Override public boolean equals(Object other){if(!(other instanceof AxisConfig))return false;AxisConfig a=(AxisConfig)other;return swapSticks==a.swapSticks&&invertLeftX==a.invertLeftX&&invertLeftY==a.invertLeftY&&invertRightX==a.invertRightX&&invertRightY==a.invertRightY&&swapTriggers==a.swapTriggers&&deadzonePercent==a.deadzonePercent;}
        @Override public int hashCode(){return Objects.hash(swapSticks,invertLeftX,invertLeftY,invertRightX,invertRightY,swapTriggers,deadzonePercent);}
    }
}
