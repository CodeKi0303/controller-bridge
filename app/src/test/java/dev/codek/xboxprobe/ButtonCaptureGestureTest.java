package dev.codek.xboxprobe;

import org.junit.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;

public final class ButtonCaptureGestureTest {
    @Test public void completedHardwareGestureKeepsRawCodeRatherThanAndroidMeaning() {
        ButtonCaptureGesture capture=new ButtonCaptureGesture();capture.arm(100);
        assertEquals(ButtonCaptureGesture.Kind.PRESSED,capture.event(true,308,99,0,110,false).kind);
        ButtonCaptureGesture.Result done=capture.event(false,308,99,0,110,false);
        assertEquals(ButtonCaptureGesture.Kind.CAPTURED,done.kind);
        assertEquals(308,done.scanCode);assertEquals(99,done.keyCode);
        assertFalse(capture.isArmed());
        assertEquals(ButtonCaptureGesture.Kind.NONE,capture.event(false,308,99,0,110,false).kind);
    }
    @Test public void initiatingHeldButtonAndAutorepeatCannotBind() {
        ButtonCaptureGesture capture=new ButtonCaptureGesture();capture.arm(100);
        assertEquals(ButtonCaptureGesture.Kind.NONE,capture.event(false,304,96,0,90,false).kind);
        assertEquals(ButtonCaptureGesture.Kind.NONE,capture.event(true,304,96,2,90,false).kind);
        assertEquals(ButtonCaptureGesture.Kind.NONE,capture.event(true,304,96,1,110,false).kind);
        assertEquals(ButtonCaptureGesture.Kind.NONE,capture.event(false,304,96,0,110,false).kind);
        assertTrue(capture.isArmed());
    }
    @Test public void chordsMustReleaseCompletelyAndThenPressAgain() {
        ButtonCaptureGesture capture=new ButtonCaptureGesture();capture.arm(100);
        capture.event(true,304,96,0,110,false);
        assertEquals(ButtonCaptureGesture.Kind.MULTIPLE,capture.event(true,305,97,0,120,false).kind);
        assertEquals(ButtonCaptureGesture.Kind.NONE,capture.event(false,304,96,0,110,false).kind);
        assertEquals(ButtonCaptureGesture.Kind.RETRY,capture.event(false,305,97,0,120,false).kind);
        assertEquals(ButtonCaptureGesture.Kind.PRESSED,capture.event(true,308,99,0,140,false).kind);
        assertEquals(ButtonCaptureGesture.Kind.CAPTURED,capture.event(false,308,99,0,140,false).kind);
    }
    @Test public void cancellationAndRearmingRevokeThePreviousDown() {
        ButtonCaptureGesture capture=new ButtonCaptureGesture();capture.arm(100);
        capture.event(true,304,96,0,110,false);capture.cancel();
        assertEquals(ButtonCaptureGesture.Kind.NONE,capture.event(false,304,96,0,110,false).kind);
        capture.arm(200);
        assertEquals(ButtonCaptureGesture.Kind.NONE,capture.event(false,304,96,0,110,false).kind);
        capture.event(true,305,97,0,220,false);
        assertEquals(ButtonCaptureGesture.Kind.RETRY,capture.event(false,305,97,0,220,true).kind);
    }
    @Test public void missingRawCodeAndMismatchedReleaseNeverCommit() {
        ButtonCaptureGesture capture=new ButtonCaptureGesture();capture.arm(100);
        assertEquals(ButtonCaptureGesture.Kind.UNAVAILABLE,capture.event(true,0,96,0,110,false).kind);
        assertFalse(capture.isArmed());capture.arm(100);
        assertEquals(ButtonCaptureGesture.Kind.UNAVAILABLE,capture.event(true,768,96,0,110,false).kind);
        assertEquals(ButtonCaptureGesture.Kind.NONE,capture.event(false,304,96,0,110,false).kind);
        capture.arm(100);
        capture.event(true,304,96,0,120,false);
        assertEquals(ButtonCaptureGesture.Kind.RETRY,capture.event(false,304,96,0,121,false).kind);
        capture.event(true,304,96,0,130,false);
        assertEquals(ButtonCaptureGesture.Kind.RETRY,capture.event(false,304,97,0,130,false).kind);
    }
    @Test public void invalidSecondButtonRevokesTheValidFirstCandidate() {
        ButtonCaptureGesture capture=new ButtonCaptureGesture();capture.arm(100);
        capture.event(true,304,96,0,110,false);
        assertEquals(ButtonCaptureGesture.Kind.UNAVAILABLE,capture.event(true,0,97,0,120,false).kind);
        assertEquals(ButtonCaptureGesture.Kind.NONE,capture.event(false,304,96,0,110,false).kind);
        capture.arm(200);
        assertEquals(ButtonCaptureGesture.Kind.RETRY,capture.event(true,305,97,0,210,true).kind);
        assertEquals(ButtonCaptureGesture.Kind.NONE,capture.event(false,305,97,0,210,false).kind);
    }
    @Test public void excludedSourceCancelsTheWholeGestureAndFilterIsOwned() {
        Set<Integer> excluded=new HashSet<>(Set.of(312,544,700));
        ButtonCaptureGesture capture=new ButtonCaptureGesture(excluded);
        excluded.clear();excluded.add(304);
        capture.arm(100);
        assertEquals(ButtonCaptureGesture.Kind.PRESSED,capture.event(true,304,96,0,110,false).kind);
        assertEquals(ButtonCaptureGesture.Kind.EXCLUDED,capture.event(true,700,97,0,120,false).kind);
        assertFalse(capture.isArmed());
        assertEquals(ButtonCaptureGesture.Kind.NONE,capture.event(false,304,96,0,110,false).kind);
        capture.arm(200);
        assertEquals(ButtonCaptureGesture.Kind.EXCLUDED,capture.event(true,312,104,0,210,false).kind);
        assertThrows(IllegalArgumentException.class,()->new ButtonCaptureGesture(Set.of(0)));
        assertThrows(IllegalArgumentException.class,()->new ButtonCaptureGesture(Set.of(768)));
    }
}
