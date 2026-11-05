package dev.codek.xboxprobe.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Owns the one parsed analog source state used by both native and Xbox encoders. */
abstract class AnalogGamepadMapper implements G8InputMapper {
    interface Encoder { byte[] encode(Frame frame); }
    static final class Frame {
        final double leftX,leftY,rightX,rightY,leftTrigger,rightTrigger;
        final int hat,buttons;
        Frame(double lx,double ly,double rx,double ry,double lt,double rt,int hat,int buttons) {
            leftX=lx;leftY=ly;rightX=rx;rightY=ry;leftTrigger=lt;rightTrigger=rt;this.hat=hat;this.buttons=buttons;
        }
        NativeGamepadState nativeState(){return new NativeGamepadState((int)Math.round(leftX*127),(int)Math.round(leftY*127),
                (int)Math.round(rightX*127),(int)Math.round(rightY*127),(int)Math.round(leftTrigger*255),(int)Math.round(rightTrigger*255),hat,buttons);}
        GamepadState xboxState(){return new GamepadState((float)leftX,(float)leftY,(float)rightX,(float)rightY,
                (float)leftTrigger,(float)rightTrigger,hat,GamepadButtons.toXbox(buttons));}
    }
    private final GamepadSourceLayout layout;
    private final double[] controls=new double[8];
    private final Set<Integer> sourceButtons,heldButtons=new HashSet<>();
    private final Set<Integer> sourceAxes;
    private final Map<Integer,Integer> mappings;
    private final ControllerProfile.AxisConfig config;
    private final Encoder encoder;
    private final byte[] neutral;
    private byte[] lastReport;
    private MappingUpdate failure;

    AnalogGamepadMapper(Map<Integer,AxisRange> axes,Set<Integer> sourceButtons,Map<Integer,Integer> mappings,
                        ControllerProfile.AxisConfig config,SourceBindings sourceBindings,byte[] neutral,Encoder encoder) {
        Objects.requireNonNull(axes,"axes");Objects.requireNonNull(sourceButtons,"sourceButtons");
        this.config=Objects.requireNonNull(config,"config");this.encoder=Objects.requireNonNull(encoder,"encoder");
        this.neutral=Objects.requireNonNull(neutral,"neutral").clone();lastReport=this.neutral.clone();
        layout=GamepadSourceLayout.resolve(axes,sourceButtons,sourceBindings);
        sourceAxes=Collections.unmodifiableSet(new HashSet<>(axes.keySet()));
        Set<Integer> copy=new HashSet<>();
        for(Integer code:sourceButtons){Objects.requireNonNull(code,"source key code");if(code<1||code>0x2ff)throw new IllegalArgumentException("Source button is outside Linux KEY_MAX: "+code);copy.add(code);}
        this.sourceButtons=Collections.unmodifiableSet(copy);
        Map<Integer,Integer> buttons=new HashMap<>();
        for(Map.Entry<Integer,Integer> entry:mappings.entrySet()) {
            int code=Objects.requireNonNull(entry.getKey(),"source key");
            if(code<1||code>0x2ff)throw new IllegalArgumentException("Source key is outside Linux KEY_MAX");
            Integer target=Objects.requireNonNull(entry.getValue(),"normalized target for "+code);
            if(target!=0&&(target<304||target>318))throw new IllegalArgumentException("Invalid normalized target button");
            buttons.put(code,target);
        }
        this.mappings=Collections.unmodifiableMap(buttons);
    }
    @Override public final byte[] neutralReport(){return neutral.clone();}
    @Override public final MappingUpdate accept(EvdevEvent event) {
        Objects.requireNonNull(event,"event");if(failure!=null)return failure;
        if(event.type==EvdevEvent.EV_SYN) {
            if(event.code==EvdevEvent.SYN_DROPPED)return fail(MappingUpdate.Failure.SYN_DROPPED,"Source event stream overflowed; gamepad neutralized. Reselect the source.");
            return event.code==EvdevEvent.SYN_REPORT?commitFrame():MappingUpdate.none();
        }
        if(event.type==EvdevEvent.EV_KEY) {
            if(!sourceButtons.contains(event.code))return fail(MappingUpdate.Failure.SOURCE_CAPABILITY_CHANGED,"Gamepad button was not in captured source capabilities: "+event.code);
            if(event.value==0)heldButtons.remove(event.code);
            else if(event.value==1)heldButtons.add(event.code);
            else if(event.value!=2)return fail(MappingUpdate.Failure.INVALID_KEY_VALUE,"Invalid gamepad button state: "+event.value);
        } else if(event.type==EvdevEvent.EV_ABS) {
            if(!sourceAxes.contains(event.code))return fail(MappingUpdate.Failure.SOURCE_CAPABILITY_CHANGED,"Gamepad axis was not in captured source capabilities: "+event.code);
            GamepadSourceLayout.Control control=layout.controlForSourceAxis(event.code);
            if(control!=null) {
                if(!layout.range(control).contains(event.value))return fail(MappingUpdate.Failure.AXIS_OUT_OF_RANGE,"Axis "+event.code+" is outside its captured range: "+event.value);
                controls[control.ordinal()]=layout.normalize(control,event.value);
            }
        }
        return MappingUpdate.none();
    }
    @Override public final MappingUpdate disconnect(){return failure!=null?failure:fail(MappingUpdate.Failure.SOURCE_LOST,"Source disconnected; gamepad neutralized. Reselect the source.");}
    private MappingUpdate commitFrame() {
        int buttons=0;
        for(int source:heldButtons){int target=mappings.getOrDefault(source,0);if(target!=0)buttons|=1<<(target-304);}
        double leftTrigger=controls[4]+layout.digitalValue(SourceBindings.DigitalControl.LEFT_TRIGGER,heldButtons);
        double rightTrigger=controls[5]+layout.digitalValue(SourceBindings.DigitalControl.RIGHT_TRIGGER,heldButtons);
        int hatX=(int)controls[6]+(int)layout.digitalValue(SourceBindings.DigitalControl.DPAD_RIGHT,heldButtons)-(int)layout.digitalValue(SourceBindings.DigitalControl.DPAD_LEFT,heldButtons);
        int hatY=(int)controls[7]+(int)layout.digitalValue(SourceBindings.DigitalControl.DPAD_DOWN,heldButtons)-(int)layout.digitalValue(SourceBindings.DigitalControl.DPAD_UP,heldButtons);
        int left=config.isSwapSticks()?2:0,right=config.isSwapSticks()?0:2;
        Frame frame=new Frame(axis(controls[left],config.isInvertLeftX()),axis(controls[left+1],config.isInvertLeftY()),
                axis(controls[right],config.isInvertRightX()),axis(controls[right+1],config.isInvertRightY()),
                config.isSwapTriggers()?rightTrigger:leftTrigger,config.isSwapTriggers()?leftTrigger:rightTrigger,
                hat(hatX,hatY),buttons);
        byte[] report=encoder.encode(frame);if(Arrays.equals(lastReport,report))return MappingUpdate.none();
        lastReport=report;return MappingUpdate.report(report);
    }
    private double axis(double value,boolean invert) {
        double signed=invert?-value:value,zone=config.getDeadzonePercent()/100.0;
        if(zone==0)return signed;
        double magnitude=Math.abs(signed);return magnitude<=zone?0:Math.copySign((magnitude-zone)/(1-zone),signed);
    }
    private MappingUpdate fail(MappingUpdate.Failure reason,String message){Arrays.fill(controls,0);heldButtons.clear();lastReport=neutralReport();failure=MappingUpdate.failed(reason,message,lastReport);return failure;}
    private static int hat(int x,int y){if(y<0)return x<0?7:x>0?1:0;if(y>0)return x<0?5:x>0?3:4;return x<0?6:x>0?2:-1;}
}
