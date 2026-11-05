package dev.codek.xboxprobe.core;

import java.util.Map;
import java.util.Set;

/** Native source semantics encoded using the retained legacy Xbox HID report, not Bluetooth pairing. */
public final class G8XboxMapper extends AnalogGamepadMapper {
    public G8XboxMapper(ControllerProfile profile,Map<Integer,AxisRange> axes,Set<Integer> sourceButtons) {
        this(profile.xboxProfile(),axes,sourceButtons,profile.getButtonMappings(),profile.getAxes(),profile.getSourceBindings());
    }
    public G8XboxMapper(HidProfile profile,Map<Integer,AxisRange> axes,Set<Integer> sourceButtons) {
        this(profile,axes,sourceButtons,ControllerProfile.defaultButtons(ControllerProfile.Kind.XBOX),ControllerProfile.AxisConfig.DEFAULT,SourceBindings.AUTO);
    }
    private G8XboxMapper(HidProfile output,Map<Integer,AxisRange> axes,Set<Integer> sourceButtons,
                         Map<Integer,Integer> mappings,ControllerProfile.AxisConfig config,SourceBindings bindings) {
        super(axes,sourceButtons,mappings,config,bindings,output.encode(GamepadState.NEUTRAL),frame->output.encode(frame.xboxState()));
    }
}
