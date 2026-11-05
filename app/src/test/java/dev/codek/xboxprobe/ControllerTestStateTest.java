package dev.codek.xboxprobe;

import static org.junit.Assert.*;
import org.junit.Test;

public final class ControllerTestStateTest {
    @Test public void simultaneousButtonsAndAxesKeepIndependentState(){
        ControllerTestState s=new ControllerTestState();
        s.key(ControllerTestState.Control.A,true);s.key(ControllerTestState.Control.B,true);
        s.motion(.7f,-.6f,-.4f,.5f,.9f,.8f,0,0);
        assertTrue(s.active(ControllerTestState.Control.A));assertTrue(s.active(ControllerTestState.Control.B));
        assertTrue(s.leftMoved());assertTrue(s.rightMoved());
        assertTrue(s.active(ControllerTestState.Control.LT));assertTrue(s.active(ControllerTestState.Control.RT));
        assertEquals(6,s.peak());
        s.key(ControllerTestState.Control.A,false);
        assertFalse(s.active(ControllerTestState.Control.A));assertTrue(s.active(ControllerTestState.Control.B));
        assertTrue(s.rightMoved());assertEquals(6,s.peak());
        s.clear();assertTrue(s.activeLabels().isEmpty());assertEquals(6,s.peakLabels().size());
        assertTrue(s.peakLabels().contains("A"));assertTrue(s.peakLabels().contains("B"));assertTrue(s.peakLabels().contains("왼쪽 스틱"));
    }
    @Test public void keyAndHatDpadDoNotReleaseEachOther(){
        ControllerTestState s=new ControllerTestState();
        s.key(ControllerTestState.Control.LEFT,true);s.motion(0,0,0,0,0,0,1,-1);
        assertTrue(s.active(ControllerTestState.Control.LEFT));assertTrue(s.active(ControllerTestState.Control.RIGHT));assertTrue(s.active(ControllerTestState.Control.UP));
        s.motion(0,0,0,0,0,0,0,0);
        assertTrue(s.active(ControllerTestState.Control.LEFT));assertFalse(s.active(ControllerTestState.Control.RIGHT));
    }
    @Test public void clearingFocusClearsHeldStateWhileResetAlsoClearsHistory(){
        ControllerTestState s=new ControllerTestState();s.key(ControllerTestState.Control.X,true);s.motion(1,1,0,0,1,0,0,0);
        s.clear();assertTrue(s.activeLabels().isEmpty());assertEquals(3,s.peak());
        s.motion(Float.NaN,Float.POSITIVE_INFINITY,2,-2,Float.NaN,3,0,0);
        assertEquals(0,s.leftX(),0);assertEquals(1,s.rightX(),0);assertEquals(-1,s.rightY(),0);assertEquals(1,s.rightTrigger(),0);
        s.reset();assertEquals(0,s.peak());assertEquals(0,s.samples());assertTrue(s.activeLabels().isEmpty());
    }
    @Test public void keyboardChordsKeepActualKeysAndReleaseIndependently(){
        ControllerTestState s=new ControllerTestState();s.keyboardKey("W",true);s.keyboardKey("J",true);s.keyboardKey("SPACE",true);
        s.keyboardKey("W",true);assertEquals(3,s.activeLabels().size());
        s.keyboardKey("J",false);assertEquals(2,s.activeLabels().size());assertTrue(s.activeLabels().contains("W"));
        s.clear();assertTrue(s.activeLabels().isEmpty());assertEquals(3,s.peak());assertTrue(s.peakLabels().contains("J"));
        s.reset();assertTrue(s.peakLabels().isEmpty());
    }
}
