package dev.codek.xboxprobe.core;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Resolves source roles once from capabilities or explicit bindings, never product names. */
public final class GamepadSourceLayout {
    public enum Kind { Z_RZ_BRAKE_GAS, RX_RY_Z_RZ, STANDARD_OPTIONAL, EXPLICIT }
    public enum Control { LEFT_X, LEFT_Y, RIGHT_X, RIGHT_Y, LEFT_TRIGGER, RIGHT_TRIGGER, HAT_X, HAT_Y }
    private static final Set<Integer> SUPPORTED_CODES = Set.of(0,1,2,3,4,5,9,10,16,17);
    private final Kind kind;
    private final Map<Control,Integer> codes;
    private final Map<Control,AxisRange> ranges;
    private final Map<Integer,Control> controlsByCode;
    private final Map<SourceBindings.DigitalControl,Integer> digitalKeys;

    private GamepadSourceLayout(Kind kind,Map<Control,Integer> selected,
                                Map<SourceBindings.DigitalControl,Integer> digital,
                                Map<Integer,AxisRange> axes,Set<Integer> keys) {
        this.kind = kind;
        EnumMap<Control,Integer> codeMap = new EnumMap<>(Control.class);
        EnumMap<Control,AxisRange> rangeMap = new EnumMap<>(Control.class);
        Map<Integer,Control> reverse = new LinkedHashMap<>();
        for (Map.Entry<Control,Integer> entry : selected.entrySet()) {
            Control control = entry.getKey(); int code = entry.getValue();
            AxisRange range = axes.get(code);
            if (range == null) throw new IllegalArgumentException("Source axis binding is absent from captured capabilities: " + code);
            if ((control == Control.HAT_X || control == Control.HAT_Y) && (range.min != -1 || range.max != 1))
                throw new IllegalArgumentException("Hat axes require captured -1..1 ranges");
            if (reverse.put(code,control) != null) throw new IllegalArgumentException("Source axis has more than one role");
            codeMap.put(control,code); rangeMap.put(control,range);
        }
        EnumMap<SourceBindings.DigitalControl,Integer> digitalMap = new EnumMap<>(SourceBindings.DigitalControl.class);
        for (Map.Entry<SourceBindings.DigitalControl,Integer> entry : digital.entrySet()) {
            if (!keys.contains(entry.getValue()))
                throw new IllegalArgumentException("Digital source binding is absent from captured capabilities: " + entry.getValue());
            digitalMap.put(entry.getKey(),entry.getValue());
        }
        codes = Collections.unmodifiableMap(codeMap); ranges = Collections.unmodifiableMap(rangeMap);
        controlsByCode = Collections.unmodifiableMap(reverse); digitalKeys = Collections.unmodifiableMap(digitalMap);
    }

    /** Standard auto-layout codes. Explicit mappings may use any captured ABS code. */
    public static Set<Integer> supportedAxisCodes() { return SUPPORTED_CODES; }
    public static GamepadSourceLayout resolve(Map<Integer,AxisRange> axes) { return resolve(axes,Collections.emptySet(),SourceBindings.AUTO); }
    public static GamepadSourceLayout resolve(Map<Integer,AxisRange> axes,Set<Integer> keys,SourceBindings bindings) {
        Objects.requireNonNull(axes,"axes"); Objects.requireNonNull(keys,"keys"); Objects.requireNonNull(bindings,"bindings");
        for (Map.Entry<Integer,AxisRange> entry : axes.entrySet()) {
            if (entry.getKey() == null || entry.getKey() < 0 || entry.getKey() > 0x3f || entry.getValue() == null)
                throw new IllegalArgumentException("Malformed captured source axis");
        }
        if (!bindings.isAutomatic()) return new GamepadSourceLayout(Kind.EXPLICIT,bindings.getAxisBindings(),bindings.getKeyBindings(),axes,keys);
        EnumMap<Control,Integer> selected = new EnumMap<>(Control.class);
        EnumMap<SourceBindings.DigitalControl,Integer> digital = new EnumMap<>(SourceBindings.DigitalControl.class);
        for (int code : axes.keySet()) if (!SUPPORTED_CODES.contains(code))
            throw new IllegalArgumentException("Unknown source axis " + code + "; configure explicit source bindings");
        pair(axes,selected,0,1,Control.LEFT_X,Control.LEFT_Y);
        pair(axes,selected,16,17,Control.HAT_X,Control.HAT_Y);
        boolean rx = paired(axes,3,4), z = paired(axes,2,5), pedals = paired(axes,10,9);
        Kind kind = Kind.STANDARD_OPTIONAL;
        if (rx && z && pedals) throw new IllegalArgumentException("Ambiguous gamepad axes: Z/RZ could be a right stick or triggers; configure explicit source bindings");
        if (rx) {
            assign(selected,3,4,Control.RIGHT_X,Control.RIGHT_Y);
            if (z) { assign(selected,2,5,Control.LEFT_TRIGGER,Control.RIGHT_TRIGGER); kind=Kind.RX_RY_Z_RZ; }
            else if (pedals) assign(selected,10,9,Control.LEFT_TRIGGER,Control.RIGHT_TRIGGER);
        } else if (z && pedals) {
            assign(selected,2,5,Control.RIGHT_X,Control.RIGHT_Y);
            assign(selected,10,9,Control.LEFT_TRIGGER,Control.RIGHT_TRIGGER); kind=Kind.Z_RZ_BRAKE_GAS;
        } else if (z) {
            // Advertised ranges cannot distinguish unsigned centered sticks
            // from triggers; both use Z/RZ in existing Linux drivers.
            throw new IllegalArgumentException("Ambiguous Z/RZ axes without RX/RY or BRAKE/GAS; configure explicit source bindings");
        } else if (pedals) assign(selected,10,9,Control.LEFT_TRIGGER,Control.RIGHT_TRIGGER);
        automaticKey(digital,keys,selected,Control.LEFT_TRIGGER,SourceBindings.DigitalControl.LEFT_TRIGGER,312);
        automaticKey(digital,keys,selected,Control.RIGHT_TRIGGER,SourceBindings.DigitalControl.RIGHT_TRIGGER,313);
        automaticKey(digital,keys,selected,Control.HAT_Y,SourceBindings.DigitalControl.DPAD_UP,544);
        automaticKey(digital,keys,selected,Control.HAT_Y,SourceBindings.DigitalControl.DPAD_DOWN,545);
        automaticKey(digital,keys,selected,Control.HAT_X,SourceBindings.DigitalControl.DPAD_LEFT,546);
        automaticKey(digital,keys,selected,Control.HAT_X,SourceBindings.DigitalControl.DPAD_RIGHT,547);
        return new GamepadSourceLayout(kind,selected,digital,axes,keys);
    }
    private static void automaticKey(Map<SourceBindings.DigitalControl,Integer> digital,Set<Integer> keys,Map<Control,Integer> axes,
                                     Control axis,SourceBindings.DigitalControl role,int code) {
        if (!axes.containsKey(axis) && keys.contains(code)) digital.put(role,code);
    }
    private static boolean paired(Map<Integer,AxisRange> axes,int first,int second) {
        if (axes.containsKey(first) != axes.containsKey(second))
            throw new IllegalArgumentException("Incomplete source axis pair " + first + "/" + second + "; configure explicit source bindings");
        return axes.containsKey(first);
    }
    private static void pair(Map<Integer,AxisRange> axes,Map<Control,Integer> selected,int first,int second,Control a,Control b) {
        if (paired(axes,first,second)) assign(selected,first,second,a,b);
    }
    private static void assign(Map<Control,Integer> selected,int first,int second,Control a,Control b) { selected.put(a,first); selected.put(b,second); }
    public Kind getKind() { return kind; }
    public boolean hasAxis(Control control) { return codes.containsKey(Objects.requireNonNull(control,"control")); }
    public int sourceCode(Control control) { Integer code=codes.get(Objects.requireNonNull(control,"control")); if(code==null)throw new IllegalArgumentException("Source role is absent: "+control); return code; }
    public AxisRange range(Control control) { return ranges.get(Objects.requireNonNull(control,"control")); }
    Control controlForSourceAxis(int code) { return controlsByCode.get(code); }
    public Map<SourceBindings.DigitalControl,Integer> digitalKeys() { return digitalKeys; }
    double digitalValue(SourceBindings.DigitalControl role,Set<Integer> held) { Integer code=digitalKeys.get(role);return code!=null&&held.contains(code)?1:0; }
    double normalize(Control control,int value) {
        AxisRange range = range(control);
        switch (control) {
            case LEFT_X: case LEFT_Y: case RIGHT_X: case RIGHT_Y: return range.normalize(value);
            case LEFT_TRIGGER: case RIGHT_TRIGGER: return range.normalizeUnit(value);
            default: if (!range.contains(value)) throw new IllegalArgumentException("Hat value is outside advertised range"); return value;
        }
    }
}
