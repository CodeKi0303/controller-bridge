package dev.codek.xboxprobe.core;

import java.util.Map;
import java.util.Set;

/** Analog evdev forwarding with no keyboard thresholds or screen mapping. */
public final class G8GamepadMapper extends AnalogGamepadMapper {
    /** Compatibility overload: retains the original complete analog passthrough behavior. */
    public G8GamepadMapper(NativeGamepadProfile profile, Map<Integer,AxisRange> axes,
                           Set<Integer> sourceButtons) {
        this(profile,axes,sourceButtons,ControllerProfile.defaultButtons(ControllerProfile.Kind.NATIVE),ControllerProfile.AxisConfig.DEFAULT,SourceBindings.AUTO);
    }
    public G8GamepadMapper(ControllerProfile profile,Map<Integer,AxisRange> axes,Set<Integer> sourceButtons) {
        this(profile.nativeProfile(),axes,sourceButtons,profile.getButtonMappings(),profile.getAxes(),profile.getSourceBindings());
    }
    private G8GamepadMapper(NativeGamepadProfile output,Map<Integer,AxisRange> axes,Set<Integer> sourceButtons,
                            Map<Integer,Integer> mappings,ControllerProfile.AxisConfig config,SourceBindings bindings) {
        super(axes,sourceButtons,mappings,config,bindings,output.neutralReport(),frame->output.encode(frame.nativeState()));
    }
}
