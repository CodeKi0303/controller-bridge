package dev.codek.xboxprobe.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Current evdev key/absolute-axis values, obtained from the selected open fd. */
public final class InputStateSnapshot {
    private final Map<Integer, Integer> keys;
    private final Map<Integer, Integer> axes;

    public InputStateSnapshot(Map<Integer, Integer> keys, Map<Integer, Integer> axes) {
        Objects.requireNonNull(keys, "keys");
        Objects.requireNonNull(axes, "axes");
        LinkedHashMap<Integer, Integer> keyCopy = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> entry : keys.entrySet()) {
            int code = Objects.requireNonNull(entry.getKey(), "key code");
            int state = Objects.requireNonNull(entry.getValue(), "key state");
            if (code < 0 || code > 0x2ff || (state != 0 && state != 1)) {
                throw new IllegalArgumentException("Invalid current evdev key state");
            }
            keyCopy.put(code, state);
        }
        LinkedHashMap<Integer, Integer> axisCopy = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> entry : axes.entrySet()) {
            int code = Objects.requireNonNull(entry.getKey(), "axis code");
            int value = Objects.requireNonNull(entry.getValue(), "axis value");
            if (code < 0 || code > 0x3f) throw new IllegalArgumentException("Invalid evdev axis code");
            axisCopy.put(code, value);
        }
        this.keys = Collections.unmodifiableMap(keyCopy);
        this.axes = Collections.unmodifiableMap(axisCopy);
    }

    MappingUpdate restore(G8InputMapper mapper) {
        for (Map.Entry<Integer, Integer> key : keys.entrySet()) {
            MappingUpdate update = mapper.accept(new EvdevEvent(EvdevEvent.EV_KEY, key.getKey(), key.getValue()));
            if (update.isFailed()) return update;
        }
        for (Map.Entry<Integer, Integer> axis : axes.entrySet()) {
            MappingUpdate update = mapper.accept(new EvdevEvent(EvdevEvent.EV_ABS, axis.getKey(), axis.getValue()));
            if (update.isFailed()) return update;
        }
        MappingUpdate committed = mapper.accept(new EvdevEvent(EvdevEvent.EV_SYN, EvdevEvent.SYN_REPORT, 0));
        return committed.isFailed() || committed.getReport() != null
                ? committed : MappingUpdate.report(mapper.neutralReport());
    }
}
