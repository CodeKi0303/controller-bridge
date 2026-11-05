package dev.codek.xboxprobe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Owns parsing of the bounded, numeric /system/bin/getevent -i inventory. */
public final class UhidSourceCapabilities {
    private static final Pattern DEVICE = Pattern.compile("add device [0-9]+: (/dev/input/event[0-9]+)");
    private static final Pattern ID = Pattern.compile("(bus:|vendor|product|version)\\s+([0-9a-fA-F]{4})");
    private static final Pattern TYPE = Pattern.compile("[A-Z_]+\\s+\\(([0-9a-fA-F]{4})\\):\\s*(.*)");
    private static final Pattern KEYS = Pattern.compile("[0-9a-fA-F]{4}(?:\\s+[0-9a-fA-F]{4})*");
    private static final Pattern AXIS = Pattern.compile("([0-9a-fA-F]{4})\\s*:\\s*value (-?[0-9]+), min (-?[0-9]+), max (-?[0-9]+), fuzz ([0-9]+), flat ([0-9]+)(?:, resolution ([0-9]+))?");

    private UhidSourceCapabilities() {}

    public static final class Axis {
        public final int code, value, min, max, fuzz, flat;
        public final Integer resolution;
        Axis(int code, int value, int min, int max, int fuzz, int flat, Integer resolution) {
            this.code = code; this.value = value; this.min = min; this.max = max;
            this.fuzz = fuzz; this.flat = flat; this.resolution = resolution;
        }
        String signature() { return code + ":" + min + ":" + max + ":" + fuzz + ":" + flat + ":" + resolution; }
    }

    public static final class Device {
        public final String path, name, location, uniqueId, problem;
        public final Integer bus, vendorId, productId, version;
        public final Set<Integer> keyCodes;
        public final Map<Integer, Axis> axes;
        Device(Builder b) {
            path = b.path; name = b.name; location = b.location; uniqueId = b.uniqueId; problem = b.problem;
            bus = b.bus; vendorId = b.vendorId; productId = b.productId; version = b.version;
            keyCodes = Collections.unmodifiableSet(new LinkedHashSet<>(b.keys));
            axes = Collections.unmodifiableMap(new LinkedHashMap<>(b.axes));
        }

        /** Parsed USB/Bluetooth gamepad capability fact; source-selection policy is separate. */
        public boolean isGamepadCandidate() {
            if (!problem.isEmpty() || name == null || name.isEmpty()) return false;
            if (bus == null || vendorId == null || productId == null || version == null) return false;
            if (bus != 0x03 && bus != 0x05) return false; // Linux BUS_USB / BUS_BLUETOOTH; source selection remains explicit.
            if (!hasGamepadButton() || hasKeyboardKeys()) return false;
            for (Axis axis : axes.values()) if (!usable(axis)) return false;
            return true; // Digital-only pads and absent sticks are valid sources.
        }

        /** Reserved output-namespace hint, never proof permitting device deletion. */
        public boolean isBridgeOutputLocation() {
            return location != null && location.startsWith("g8-input-bridge:");
        }

        /** Selection policy is separate from the parsed gamepad capability fact. */
        public boolean isSelectableSource() { return sourceSelectionProblem() == null; }

        public String sourceSelectionProblem() {
            if (hasGamepadButton() && hasKeyboardKeys()) return "Mixed keyboard/gamepad input node is unsupported; select a dedicated gamepad node";
            if (!isGamepadCandidate()) return "Device does not have the required USB or Bluetooth gamepad capabilities";
            // getevent distinguishes an omitted location from an observed empty
            // physical-location field. Our outputs always carry the reserved
            // prefix, so an explicit empty value still excludes our outputs.
            if (location == null) return "Input location is unknown; source selection cannot exclude a generated output";
            if (isBridgeOutputLocation()) return "Input location uses the application's generated-output namespace";
            return null;
        }

        /**
         * Uncertainty that must prevent automatic selection from a partial inventory.
         * Known non-gamepads stay quiet; a malformed gamepad-shaped record is not treated as absent.
         * Generated-output location is only an exclusion hint, never deletion authority.
         */
        public String sourceInventoryProblem() {
            if (isBridgeOutputLocation() || !hasGamepadButton()) return null;
            if (!problem.isEmpty()) return "Gamepad capability record is malformed: " + problem;
            if (name == null || name.isEmpty()) return "Gamepad name is unknown or empty";
            if (bus == null || vendorId == null || productId == null || version == null)
                return "Gamepad identity is incomplete; transport/vendor/product/version must be known";
            if (bus != 0x03 && bus != 0x05) return null; // known unsupported transport, not an unknown candidate
            if (hasKeyboardKeys()) return sourceSelectionProblem();
            for (Axis axis : axes.values()) if (!usable(axis)) return "Gamepad axis " + axis.code + " has an unusable captured range";
            return sourceSelectionProblem();
        }

        private boolean hasGamepadButton() {
            for (int key : keyCodes) if ((key >= 0x100 && key <= 0x10f) || (key >= 0x120 && key <= 0x13f)
                    || (key >= 0x220 && key <= 0x223) || (key >= 0x2c0 && key <= 0x2ff)) return true;
            return false;
        }
        private boolean hasKeyboardKeys() {
            for (int key : keyCodes) if ((key >= 1 && key <= 0x58) || (key >= 0x60 && key <= 0x6f)) return true;
            return false;
        }

        private static boolean usable(Axis axis) {
            return axis != null && axis.min < axis.max && axis.value >= axis.min && axis.value <= axis.max
                    && axis.flat >= 0 && axis.flat <= (long)axis.max-axis.min;
        }

        /** Values are live state; compare only identity and capability facts. */
        public String capabilitySignature() {
            StringBuilder s = new StringBuilder();
            s.append(path).append('\0').append(name).append('\0')
                    .append(location != null).append(':').append(location).append('\0')
                    .append(uniqueId != null).append(':').append(uniqueId).append('\0').append(bus).append(':')
                    .append(vendorId).append(':').append(productId).append(':').append(version).append('\n');
            for (int key : keyCodes) s.append(key).append(',');
            s.append('\n');
            for (Axis axis : axes.values()) s.append(axis.signature()).append(';');
            return s.toString();
        }
    }

    private static final class Builder {
        final String path;
        String name, location, uniqueId, problem = "";
        Integer bus, vendorId, productId, version;
        final Set<Integer> keys = new LinkedHashSet<>();
        final Map<Integer, Axis> axes = new LinkedHashMap<>();
        boolean inEvents;
        int type = -1;
        Builder(String path) { this.path = path; }
        void fail(String reason) { if (problem.isEmpty()) problem = reason; }
    }

    public static List<Device> parse(String text) {
        if (text == null || text.length() > 262144) throw new IllegalArgumentException("Inventory exceeds 256 KiB text budget");
        List<Device> result = new ArrayList<>();
        Set<String> paths = new LinkedHashSet<>();
        Builder b = null;
        for (String sourceLine : text.split("\\r?\\n")) {
            String line = sourceLine.trim();
            Matcher header = DEVICE.matcher(line);
            if (header.matches()) {
                if (!paths.add(header.group(1))) throw new IllegalArgumentException("Repeated input-device path in inventory");
                if (b != null) result.add(new Device(b));
                if (result.size() >= 128) throw new IllegalArgumentException("Too many input devices");
                b = new Builder(header.group(1));
                continue;
            }
            if (line.startsWith("add device ")) {
                if (b != null) { result.add(new Device(b)); b = null; }
                continue; // A non-event node is never a selectable source.
            }
            if (b == null || line.isEmpty()) continue;
            if (line.startsWith("name:")) {
                String raw = line.substring(5).trim();
                if (b.name != null || raw.length() < 2 || raw.charAt(0) != '"' || raw.charAt(raw.length()-1) != '"') b.fail("Malformed or repeated name");
                else b.name = raw.substring(1, raw.length()-1);
                continue;
            }
            if (line.startsWith("location:") || line.startsWith("id:")) {
                boolean location = line.startsWith("location:");
                String raw = line.substring(location ? 9 : 3).trim();
                if ((location ? b.location : b.uniqueId) != null || raw.length() < 2 || raw.charAt(0) != '"' || raw.charAt(raw.length()-1) != '"') {
                    b.fail("Malformed or repeated location/id");
                } else if (location) b.location = raw.substring(1, raw.length()-1);
                else b.uniqueId = raw.substring(1, raw.length()-1);
                continue;
            }
            Matcher id = ID.matcher(line);
            if (id.matches()) {
                int value = Integer.parseInt(id.group(2), 16);
                switch (id.group(1)) {
                    case "bus:": if (b.bus != null) b.fail("Repeated bus"); b.bus = value; break;
                    case "vendor": if (b.vendorId != null) b.fail("Repeated vendor"); b.vendorId = value; break;
                    case "product": if (b.productId != null) b.fail("Repeated product"); b.productId = value; break;
                    case "version": if (b.version != null) b.fail("Repeated device version"); b.version = value; break;
                    default: throw new AssertionError();
                }
                continue;
            }
            if (line.startsWith("bus:") || line.matches("(?:vendor|product|version)(?:\\s.*)?")) {
                b.fail("Malformed device identity field");
                continue;
            }
            if (line.equals("events:")) { b.inEvents = true; b.type = -1; continue; }
            if (line.startsWith("input props:")) { b.inEvents = false; b.type = -1; continue; }
            if (!b.inEvents) continue;
            Matcher type = TYPE.matcher(line);
            String payload = line;
            if (type.matches()) {
                b.type = Integer.parseInt(type.group(1), 16);
                payload = type.group(2).trim();
            }
            if (payload.isEmpty()) continue;
            try {
                if (b.type == 1) {
                    if (!KEYS.matcher(payload).matches()) b.fail("Unparsed KEY capability line");
                    for (String code : payload.split("\\s+")) {
                        // Keep independently valid partial facts, while problem still prevents selection.
                        if (!code.matches("[0-9a-fA-F]{4}")) continue;
                        int key = Integer.parseInt(code, 16);
                        if (key > 0x2ff || !b.keys.add(key)) b.fail("Invalid or repeated KEY capability");
                    }
                } else if (b.type == 3) {
                    Matcher axis = AXIS.matcher(payload);
                    if (!axis.matches()) { b.fail("Unparsed ABS capability line"); continue; }
                    int code = Integer.parseInt(axis.group(1), 16);
                    int value = Integer.parseInt(axis.group(2)), min = Integer.parseInt(axis.group(3)), max = Integer.parseInt(axis.group(4));
                    int fuzz = Integer.parseInt(axis.group(5)), flat = Integer.parseInt(axis.group(6));
                    Integer resolution = axis.group(7) == null ? null : Integer.parseInt(axis.group(7));
                    Axis parsed = new Axis(code, value, min, max, fuzz, flat, resolution);
                    if (code > 0x3f || min > max || value < min || value > max || b.axes.put(code, parsed) != null) b.fail("Invalid or repeated ABS capability");
                }
            } catch (NumberFormatException e) {
                b.fail("Capability number is out of range");
            }
        }
        if (b != null) result.add(new Device(b));
        return Collections.unmodifiableList(result);
    }
}
