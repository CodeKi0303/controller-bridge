package dev.codek.xboxprobe.core;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** User-selected source roles, independent of output report layout and device name. */
public final class SourceBindings {
    public enum DigitalControl { LEFT_TRIGGER, RIGHT_TRIGGER, DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT }
    public static final SourceBindings AUTO = new SourceBindings(true, Collections.emptyMap(), Collections.emptyMap());
    private final boolean automatic;
    private final Map<GamepadSourceLayout.Control,Integer> axisBindings;
    private final Map<DigitalControl,Integer> keyBindings;

    public SourceBindings(boolean automatic, Map<GamepadSourceLayout.Control,Integer> axisBindings,
                          Map<DigitalControl,Integer> keyBindings) {
        Objects.requireNonNull(axisBindings,"axisBindings"); Objects.requireNonNull(keyBindings,"keyBindings");
        if (automatic && (!axisBindings.isEmpty() || !keyBindings.isEmpty()))
            throw new IllegalArgumentException("Automatic source mapping cannot contain explicit bindings");
        EnumMap<GamepadSourceLayout.Control,Integer> axes = new EnumMap<>(GamepadSourceLayout.Control.class);
        Set<Integer> assigned = new HashSet<>();
        for (Map.Entry<GamepadSourceLayout.Control,Integer> entry : axisBindings.entrySet()) {
            GamepadSourceLayout.Control role = Objects.requireNonNull(entry.getKey(),"axis role");
            int code = Objects.requireNonNull(entry.getValue(),"source axis");
            if (code < 0 || code > 0x3f) throw new IllegalArgumentException("Source axis must be 0..63");
            if (!assigned.add(code)) throw new IllegalArgumentException("A source axis cannot have two roles");
            axes.put(role,code);
        }
        EnumMap<DigitalControl,Integer> keys = new EnumMap<>(DigitalControl.class);
        assigned.clear();
        for (Map.Entry<DigitalControl,Integer> entry : keyBindings.entrySet()) {
            DigitalControl role = Objects.requireNonNull(entry.getKey(),"digital role");
            int code = Objects.requireNonNull(entry.getValue(),"source key");
            if (code < 1 || code > 0x2ff) throw new IllegalArgumentException("Source key must be 1..767");
            if (!assigned.add(code)) throw new IllegalArgumentException("A digital source key cannot have two roles");
            keys.put(role,code);
        }
        rejectOverlap(axes,keys,GamepadSourceLayout.Control.LEFT_TRIGGER,DigitalControl.LEFT_TRIGGER);
        rejectOverlap(axes,keys,GamepadSourceLayout.Control.RIGHT_TRIGGER,DigitalControl.RIGHT_TRIGGER);
        rejectOverlap(axes,keys,GamepadSourceLayout.Control.HAT_X,DigitalControl.DPAD_LEFT,DigitalControl.DPAD_RIGHT);
        rejectOverlap(axes,keys,GamepadSourceLayout.Control.HAT_Y,DigitalControl.DPAD_UP,DigitalControl.DPAD_DOWN);
        this.automatic = automatic;
        this.axisBindings = Collections.unmodifiableMap(axes);
        this.keyBindings = Collections.unmodifiableMap(keys);
    }
    private static void rejectOverlap(Map<GamepadSourceLayout.Control,Integer> axes,Map<DigitalControl,Integer> keys,
                                      GamepadSourceLayout.Control axis,DigitalControl... digital) {
        if (axes.containsKey(axis)) for (DigitalControl key : digital) if (keys.containsKey(key))
            throw new IllegalArgumentException("Choose an axis or digital keys for " + axis + ", not both");
    }
    public boolean isAutomatic() { return automatic; }
    public Map<GamepadSourceLayout.Control,Integer> getAxisBindings() { return axisBindings; }
    public Map<DigitalControl,Integer> getKeyBindings() { return keyBindings; }
    @Override public boolean equals(Object other) {
        if (!(other instanceof SourceBindings)) return false;
        SourceBindings b = (SourceBindings)other;
        return automatic == b.automatic && axisBindings.equals(b.axisBindings) && keyBindings.equals(b.keyBindings);
    }
    @Override public int hashCode() { return Objects.hash(automatic,axisBindings,keyBindings); }
}
