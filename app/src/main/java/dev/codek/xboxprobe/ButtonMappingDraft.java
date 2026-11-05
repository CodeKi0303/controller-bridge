package dev.codek.xboxprobe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/** Editable output-first view of the persisted source-to-target button map. */
public final class ButtonMappingDraft {
    private final Set<Integer> allowedTargets;
    private final TreeMap<Integer,Integer> mappings = new TreeMap<>();

    public ButtonMappingDraft(Map<Integer,Integer> current, Set<Integer> allowedTargets) {
        Objects.requireNonNull(current, "current mappings");
        Objects.requireNonNull(allowedTargets, "allowed targets");
        Set<Integer> allowed = new HashSet<>();
        for (Integer target : allowedTargets) {
            if (target == null || target <= 0)
                throw new IllegalArgumentException("Allowed targets must be nonzero button codes");
            allowed.add(target);
        }
        this.allowedTargets = Collections.unmodifiableSet(allowed);
        for (Map.Entry<Integer,Integer> entry : current.entrySet()) {
            Integer source = entry.getKey(), target = entry.getValue();
            if (source == null) throw new IllegalArgumentException("Source button is missing");
            requireSource(source);
            if (target == null || (target != 0 && !allowed.contains(target)))
                throw new IllegalArgumentException("Unsupported target button: " + target);
            mappings.put(source,target);
        }
    }

    /** A stable save candidate; subsequent edits cannot change an earlier snapshot. */
    public SortedMap<Integer,Integer> snapshot() {
        return Collections.unmodifiableSortedMap(new TreeMap<>(mappings));
    }

    public List<Integer> sourcesFor(int target) {
        requireTarget(target);
        List<Integer> sources = new ArrayList<>();
        for (Map.Entry<Integer,Integer> entry : mappings.entrySet())
            if (entry.getValue() == target) sources.add(entry.getKey());
        return Collections.unmodifiableList(sources);
    }

    /** Replace this output's assignments and move the captured input from its old output. */
    public void replaceTarget(int target, int source) {
        requireTarget(target);
        requireSource(source);
        disableTarget(target);
        mappings.put(source,target);
    }

    public void clearTarget(int target) {
        requireTarget(target);
        disableTarget(target);
    }

    /** Disable only the captured input, including inputs absent from the stored map. */
    public void disableSource(int source) {
        requireSource(source);
        mappings.put(source,0);
    }

    private void disableTarget(int target) {
        // ControllerProfile fills omitted bindings with defaults. Keep explicit
        // zeroes so clearing or replacing a binding survives save/reload.
        for (Map.Entry<Integer,Integer> entry : mappings.entrySet())
            if (entry.getValue() == target) entry.setValue(0);
    }

    private void requireTarget(int target) {
        if (!allowedTargets.contains(target))
            throw new IllegalArgumentException("Unsupported target button: " + target);
    }

    private static void requireSource(int source) {
        if (source < 1 || source > 0x2ff)
            throw new IllegalArgumentException("Source button must fit Linux KEY_MAX (1..767)");
    }
}
