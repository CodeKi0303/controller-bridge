package dev.codek.xboxprobe.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Selected-device evdev state to keyboard state, committed at SYN_REPORT.
 * Missing axis ranges mean absent/unsupported axes; no default device ranges
 * are invented. Buttons and each movement axis own their held state separately.
 * This permits the same usage to remain held by another source after a release.
 *
 * Lost-event protocol:
 * https://github.com/torvalds/linux/blob/v6.12/Documentation/input/event-codes.rst
 * SYN_DROPPED requires state resynchronization. This bridge instead releases all
 * keys and stops permanently; the owner must reselect the source/new mapper.
 */
public final class G8KeyboardMapper implements G8InputMapper {
    public static final double PRESS_THRESHOLD = 0.35;
    public static final double RELEASE_THRESHOLD = 0.25;
    public static final int MAX_SOURCE_KEY_CODE = 0x2ff; // Linux input-event-codes.h KEY_MAX
    private static final int[] AXIS_CODES = {0, 1, 16, 17}; // ABS_X/Y/HAT0X/HAT0Y
    private static final int[] NEGATIVE_USAGES = {0x04, 0x1a, 0x04, 0x1a}; // A/W/A/W
    private static final int[] POSITIVE_USAGES = {0x07, 0x16, 0x07, 0x16}; // D/S/D/S
    private final AxisRange[] ranges;
    private final int[] axisCodes = AXIS_CODES.clone();
    private final Map<Integer,Integer> movementBindings = new HashMap<>();
    private final int[] directions = new int[4];
    private final Map<Integer,Integer> bindings;
    private final Set<Integer> heldButtons = new HashSet<>();
    private byte[] lastReport = HidKeyboardReportEncoder.neutral();
    private MappingUpdate failure;

    public G8KeyboardMapper(AxisRange x, AxisRange y, AxisRange hatX, AxisRange hatY,
                            Map<Integer,Integer> keyBindings) {
        Objects.requireNonNull(keyBindings, "keyBindings");
        Map<Integer,Integer> copy = new HashMap<>();
        for (Map.Entry<Integer,Integer> entry : keyBindings.entrySet()) {
            Integer source = Objects.requireNonNull(entry.getKey(), "source key code");
            Integer usage = Objects.requireNonNull(entry.getValue(), "keyboard usage");
            // The catalog owner verifies that this key is an actual capability
            // of the selected gamepad. This layer validates only the evdev ABI
            // range and interprets exactly the caller's configured bindings.
            if (source < 1 || source > MAX_SOURCE_KEY_CODE) {
                throw new IllegalArgumentException("Source button is outside Linux KEY_MAX: " + source);
            }
            HidKeyboardReportEncoder.reportForUsage(usage); // one owner validates target usage
            copy.put(source, usage);
        }
        bindings = Collections.unmodifiableMap(copy);
        ranges = new AxisRange[] {x, y, hatX, hatY};
    }

    /** Uses the same resolved source roles as gamepad output; absent controls stay neutral. */
    public G8KeyboardMapper(GamepadSourceLayout layout,Map<Integer,Integer> keyBindings) {
        this(layout.range(GamepadSourceLayout.Control.LEFT_X),layout.range(GamepadSourceLayout.Control.LEFT_Y),
                layout.range(GamepadSourceLayout.Control.HAT_X),layout.range(GamepadSourceLayout.Control.HAT_Y),keyBindings);
        GamepadSourceLayout.Control[] roles = {GamepadSourceLayout.Control.LEFT_X,GamepadSourceLayout.Control.LEFT_Y,
                GamepadSourceLayout.Control.HAT_X,GamepadSourceLayout.Control.HAT_Y};
        for(int i=0;i<roles.length;i++)axisCodes[i]=layout.hasAxis(roles[i])?layout.sourceCode(roles[i]):-1;
        for(Map.Entry<SourceBindings.DigitalControl,Integer> entry:layout.digitalKeys().entrySet()) {
            switch(entry.getKey()) {
                case DPAD_UP: movementBindings.put(entry.getValue(),0x1a); break;
                case DPAD_DOWN: movementBindings.put(entry.getValue(),0x16); break;
                case DPAD_LEFT: movementBindings.put(entry.getValue(),0x04); break;
                case DPAD_RIGHT: movementBindings.put(entry.getValue(),0x07); break;
                default: break;
            }
        }
    }

    @Override public MappingUpdate accept(EvdevEvent event) {
        Objects.requireNonNull(event, "event");
        if (failure != null) return failure;
        if (event.type == EvdevEvent.EV_SYN) {
            if (event.code == EvdevEvent.SYN_DROPPED) {
                return fail(MappingUpdate.Failure.SYN_DROPPED,
                        "Source event stream overflowed; keys released. Reselect the source.");
            }
            return event.code == EvdevEvent.SYN_REPORT ? commitFrame() : MappingUpdate.none();
        }
        if (event.type == EvdevEvent.EV_KEY && (bindings.containsKey(event.code)||movementBindings.containsKey(event.code))) {
            if (event.value == 0) heldButtons.remove(event.code);
            else if (event.value == 1) heldButtons.add(event.code);
            else if (event.value != 2) {
                return fail(MappingUpdate.Failure.INVALID_KEY_VALUE,
                        "Invalid key state " + event.value + " for source button " + event.code);
            } // repeat (2) never synthesizes an unseen initial press
        } else if (event.type == EvdevEvent.EV_ABS) {
            for (int i = 0; i < axisCodes.length; i++) {
                if (event.code != axisCodes[i] || ranges[i] == null) continue;
                if (!ranges[i].contains(event.value)) {
                    return fail(MappingUpdate.Failure.AXIS_OUT_OF_RANGE,
                            "Axis " + event.code + " is outside its captured capability range: " + event.value);
                }
                directions[i] = direction(ranges[i].normalize(event.value), directions[i]);
                break;
            }
        }
        return MappingUpdate.none();
    }

    @Override public MappingUpdate disconnect() {
        if (failure != null) return failure;
        return fail(MappingUpdate.Failure.SOURCE_LOST, "Source disconnected; keys released. Reselect the source.");
    }

    private MappingUpdate commitFrame() {
        TreeSet<Integer> usages = new TreeSet<>();
        for (int button : heldButtons) {
            Integer bound=bindings.get(button),movement=movementBindings.get(button);
            if(bound!=null)usages.add(bound);
            if(movement!=null)usages.add(movement);
        }
        for (int i = 0; i < directions.length; i++) {
            if (directions[i] < 0) usages.add(NEGATIVE_USAGES[i]);
            if (directions[i] > 0) usages.add(POSITIVE_USAGES[i]);
        }
        if (usages.size() > HidKeyboardReportEncoder.MAX_KEYS) {
            return fail(MappingUpdate.Failure.CAPACITY_EXCEEDED,
                    "More than six distinct keys would be held; keys released. Reselect the source.");
        }
        int[] keys = new int[usages.size()];
        int i = 0;
        for (int usage : usages) keys[i++] = usage;
        byte[] report = HidKeyboardReportEncoder.reportForUsages(keys);
        if (Arrays.equals(lastReport, report)) return MappingUpdate.none();
        lastReport = report;
        return MappingUpdate.report(report);
    }

    @Override public byte[] neutralReport() { return HidKeyboardReportEncoder.neutral(); }

    private MappingUpdate fail(MappingUpdate.Failure reason, String error) {
        heldButtons.clear();
        Arrays.fill(directions, 0);
        lastReport = neutralReport();
        failure = MappingUpdate.failed(reason, error, lastReport);
        return failure;
    }

    private static int direction(double normalized, int previous) {
        if (normalized >= PRESS_THRESHOLD) return 1;
        if (normalized <= -PRESS_THRESHOLD) return -1;
        if (previous > 0 && normalized > RELEASE_THRESHOLD) return 1;
        if (previous < 0 && normalized < -RELEASE_THRESHOLD) return -1;
        return 0;
    }
}
