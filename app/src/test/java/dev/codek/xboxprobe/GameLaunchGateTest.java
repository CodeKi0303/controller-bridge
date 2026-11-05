package dev.codek.xboxprobe;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public final class GameLaunchGateTest {
    @Test public void directLaunchNeedsConfirmedCleanupAndCanBeConsumedOnlyOnce() {
        GameLaunchGate gate=new GameLaunchGate();long token=gate.begin("game.one","Direct",true);
        assertFalse(gate.consumeReady(token));assertEquals(GameLaunchGate.Phase.CLEANING,gate.snapshot().phase);
        assertTrue(gate.afterCleanup(token));assertTrue(gate.consumeReady(token));assertFalse(gate.consumeReady(token));
        assertEquals(GameLaunchGate.Phase.HANDED_OFF,gate.snapshot().phase);
    }
    @Test public void inputLaunchRequiresEveryNativeReadinessFact() {
        GameLaunchGate gate=new GameLaunchGate();long token=gate.begin("game.one","Keyboard",false);
        assertFalse(gate.afterConnected(token));assertTrue(gate.afterCleanup(token));assertTrue(gate.afterConnected(token));
        assertTrue(gate.sourceSelected(token));assertTrue(gate.waitingForInput(token));
        assertFalse(gate.inputReady(token,true,true,false,true));assertFalse(gate.consumeReady(token));
        assertTrue(gate.inputReady(token,true,true,true,true));assertTrue(gate.consumeReady(token));
    }
    @Test public void lateResultsCannotPrepareOrLaunchTheSupersededGame() {
        GameLaunchGate gate=new GameLaunchGate();long first=gate.begin("game.one","One",false);
        gate.afterCleanup(first);long second=gate.begin("game.two","Two",true);
        assertFalse(gate.afterConnected(first));assertFalse(gate.fail(first,"late failure"));assertFalse(gate.consumeReady(first));
        assertTrue(gate.afterCleanup(second));assertEquals("game.two",gate.snapshot().packageName);
        assertTrue(gate.consumeReady(second));
    }
    @Test public void ambiguousSourcesRequireCurrentExplicitSelection() {
        GameLaunchGate gate=new GameLaunchGate();long token=gate.begin("game.one","Custom",false);
        gate.afterCleanup(token);gate.afterConnected(token);
        assertNull(gate.sourcesListed(token,"two candidates",Arrays.asList("one","two"),true));
        assertFalse(gate.sourceSelected(token+1));assertFalse(gate.waitingForInput(token));
        assertTrue(gate.sourceSelected(token));assertTrue(gate.waitingForInput(token));
    }
    @Test public void soleSelectableTokenAutoStartsWithoutControllerIdentityPreference() {
        for(String source:new String[]{"opaque-usb-controller","opaque-bluetooth-controller"}) {
            GameLaunchGate gate=new GameLaunchGate();long token=gate.begin("game.one","Pad",false);
            gate.afterCleanup(token);gate.afterConnected(token);
            assertEquals(source,gate.sourcesListed(token,"complete catalog",Collections.singletonList(source),true));
            assertEquals(GameLaunchGate.Phase.STARTING,gate.snapshot().phase);
            assertEquals("complete catalog",gate.snapshot().sourcesJson);
            assertTrue(gate.waitingForInput(token));
        }
    }
    @Test public void multipleOrPartialCatalogsRequireExplicitSelectionAndRetainEvidence() {
        GameLaunchGate gate=new GameLaunchGate();long token=gate.begin("game.one","Pad",false);
        gate.afterCleanup(token);gate.afterConnected(token);
        assertNull(gate.sourcesListed(token,"G8 plus another controller",Arrays.asList("g8-token","other-token"),true));
        assertEquals(GameLaunchGate.Phase.NEEDS_SOURCE,gate.snapshot().phase);
        assertEquals("G8 plus another controller",gate.snapshot().sourcesJson);
        assertFalse(gate.waitingForInput(token));assertTrue(gate.relist(token));
        assertNull(gate.sourcesListed(token,"one observed source; scan error elsewhere",Collections.singletonList("other-token"),false));
        assertEquals(GameLaunchGate.Phase.NEEDS_SOURCE,gate.snapshot().phase);
        assertEquals("one observed source; scan error elsewhere",gate.snapshot().sourcesJson);
        assertTrue(gate.sourceSelected(token));assertTrue(gate.waitingForInput(token));
    }
    @Test public void emptyMalformedAndStaleCatalogsNeverAutoStart() {
        GameLaunchGate gate=new GameLaunchGate();long first=gate.begin("game.one","Pad",false);
        gate.afterCleanup(first);gate.afterConnected(first);
        assertNull(gate.sourcesListed(first,"no source",Collections.emptyList(),true));
        assertEquals(GameLaunchGate.Phase.NEEDS_SOURCE,gate.snapshot().phase);assertTrue(gate.relist(first));
        assertThrows(IllegalArgumentException.class,()->gate.sourcesListed(first,"bad token",Collections.singletonList(""),true));
        assertEquals(GameLaunchGate.Phase.LISTING,gate.snapshot().phase);
        long second=gate.begin("game.two","Other",false);
        assertNull(gate.sourcesListed(first,"late catalog",Collections.singletonList("old-token"),true));
        assertEquals(second,gate.snapshot().token);assertEquals(GameLaunchGate.Phase.CLEANING,gate.snapshot().phase);
        gate.afterCleanup(second);gate.afterConnected(second);gate.cancel(second);
        assertNull(gate.sourcesListed(second,"cancelled catalog",Collections.singletonList("new-token"),true));
        assertEquals(GameLaunchGate.Phase.CANCELLED,gate.snapshot().phase);
    }
    @Test public void cancelRevokesPendingReadyAndStaleCallbacks() {
        GameLaunchGate gate=new GameLaunchGate();long token=gate.begin("game.one","Direct",true);
        gate.afterCleanup(token);assertTrue(gate.cancel(token));assertFalse(gate.current(token));
        assertFalse(gate.consumeReady(token));assertFalse(gate.afterCleanup(token));
    }
    @Test public void uncertainCleanupBlocksEvenDirectUntilOwnershipIsResolved() {
        GameLaunchGate gate=new GameLaunchGate();long first=gate.begin("game.one","Pad",false);
        gate.blockCleanup("owned binder cleanup unknown");long direct=gate.begin("game.two","Direct",true);
        assertEquals(GameLaunchGate.Phase.BLOCKED,gate.snapshot().phase);assertFalse(gate.afterCleanup(direct));
        assertFalse(gate.consumeReady(first));gate.cleanupRecovered();assertFalse(gate.consumeReady(direct));
        long retry=gate.begin("game.two","Direct",true);assertTrue(gate.afterCleanup(retry));assertTrue(gate.consumeReady(retry));
    }
}
