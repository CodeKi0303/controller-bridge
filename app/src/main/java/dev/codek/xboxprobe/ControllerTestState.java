package dev.codek.xboxprobe;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** One selected input device's current controls. Key and motion updates are
 * independent, so a new axis event never releases an unrelated held button. */
final class ControllerTestState {
    enum Control {
        A("A"),B("B"),X("X"),Y("Y"),LB("LB"),RB("RB"),LT("LT"),RT("RT"),
        L3("L3"),R3("R3"),UP("↑"),DOWN("↓"),LEFT("←"),RIGHT("→"),
        SELECT("View"),START("Menu"),GUIDE("Home");
        final String label;
        Control(String label){this.label=label;}
    }
    private final EnumSet<Control> keys=EnumSet.noneOf(Control.class);
    private final Set<String> keyboardKeys=new LinkedHashSet<>();
    private final List<String> peakLabels=new ArrayList<>();
    private float lx,ly,rx,ry,lt,rt,hatX,hatY;
    private long samples;

    void key(Control control,boolean down){
        if(down)keys.add(control);else keys.remove(control);
        sampled();
    }
    void keyboardKey(String label,boolean down){
        if(down)keyboardKeys.add(label);else keyboardKeys.remove(label);
        sampled();
    }
    void motion(float lx,float ly,float rx,float ry,float lt,float rt,float hatX,float hatY){
        this.lx=axis(lx);this.ly=axis(ly);this.rx=axis(rx);this.ry=axis(ry);
        this.lt=trigger(lt);this.rt=trigger(rt);this.hatX=axis(hatX);this.hatY=axis(hatY);
        sampled();
    }
    private void sampled(){
        samples++;List<String> active=activeLabels();
        if(active.size()>peakLabels.size()){peakLabels.clear();peakLabels.addAll(active);}
    }
    boolean active(Control c){
        if(keys.contains(c))return true;
        switch(c){
            case UP:return hatY<-.5f;case DOWN:return hatY>.5f;
            case LEFT:return hatX<-.5f;case RIGHT:return hatX>.5f;
            case LT:return lt>.08f;case RT:return rt>.08f;default:return false;
        }
    }
    boolean leftMoved(){return Math.abs(lx)>.08f||Math.abs(ly)>.08f;}
    boolean rightMoved(){return Math.abs(rx)>.08f||Math.abs(ry)>.08f;}
    float leftX(){return lx;} float leftY(){return ly;}
    float rightX(){return rx;} float rightY(){return ry;}
    float leftTrigger(){return keys.contains(Control.LT)?1:lt;}
    float rightTrigger(){return keys.contains(Control.RT)?1:rt;}
    List<String> activeLabels(){
        List<String> result=new ArrayList<>();
        for(Control c:Control.values())if(active(c))result.add(c.label);
        if(leftMoved())result.add("왼쪽 스틱");
        if(rightMoved())result.add("오른쪽 스틱");
        result.addAll(keyboardKeys);
        return result;
    }
    int peak(){return peakLabels.size();} long samples(){return samples;}
    List<String> peakLabels(){return new ArrayList<>(peakLabels);}
    void clear(){keys.clear();keyboardKeys.clear();lx=ly=rx=ry=lt=rt=hatX=hatY=0;}
    void reset(){clear();peakLabels.clear();samples=0;}
    private static float axis(float value){return Float.isFinite(value)?Math.max(-1,Math.min(1,value)):0;}
    private static float trigger(float value){return Float.isFinite(value)?Math.max(0,Math.min(1,value)):0;}
}
