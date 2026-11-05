package dev.codek.xboxprobe;

import org.junit.Test;
import static org.junit.Assert.*;

public final class RecoveryRequestTest {
    @Test public void replacingRequestRejectsLateSuccessWithoutCancellingReplacement() {
        RecoveryRequest request = new RecoveryRequest();
        long old = request.begin(12);
        long latest = request.begin(13);
        assertFalse(request.accept(old));
        assertEquals(latest, request.requestId());
        assertEquals(13, request.ownerGeneration());
        assertTrue(request.accept(latest));
        assertFalse(request.accept(latest));
        assertFalse(request.isPending());
        assertEquals(-1, request.ownerGeneration());
    }

    @Test public void cancelledSetupCannotBeAcceptedAfterARegularResume() {
        RecoveryRequest request = new RecoveryRequest();
        long id = request.begin(0);
        request.cancel();
        assertFalse(request.accept(id));
        assertFalse(request.accept(0));
        assertFalse(request.accept(-1));
        assertFalse(request.isPending());
        assertEquals(0, request.requestId());
    }

    @Test public void restoredPendingRequestStillNeedsExactExplicitResult() {
        RecoveryRequest before = new RecoveryRequest();
        long id = before.begin(51);
        RecoveryRequest.Snapshot saved = before.snapshot();
        RecoveryRequest after = new RecoveryRequest();
        after.restore(saved.sequence, saved.requestId, saved.ownerGeneration);
        assertTrue(after.isPending());
        assertEquals(51, after.ownerGeneration());
        assertFalse(after.accept(id + 1));
        assertTrue(after.accept(id));
        assertFalse(after.accept(id));
        assertEquals(id, saved.requestId); // immutable snapshot survives consumption
    }

    @Test public void restoringCancelledSequenceDoesNotReuseLateResultId() {
        RecoveryRequest before = new RecoveryRequest();
        long cancelled = before.begin(9);
        before.cancel();
        RecoveryRequest.Snapshot saved = before.snapshot();
        RecoveryRequest after = new RecoveryRequest();
        after.restore(saved.sequence, saved.requestId, saved.ownerGeneration);
        long next = after.begin(10);
        assertTrue(next > cancelled);
        assertFalse(after.accept(cancelled));
        assertEquals(next, after.requestId());
    }

    @Test public void invalidRestoreAndGenerationRollbackLeaveOwnershipIntact() {
        RecoveryRequest request = new RecoveryRequest();
        request.restore(4, 4, 8);
        RecoveryRequest.Snapshot before = request.snapshot();
        assertThrows(IllegalArgumentException.class, () -> request.restore(3, 3, 7));
        assertThrows(IllegalArgumentException.class, () -> request.restore(4, 4, 9));
        assertThrows(IllegalArgumentException.class, () -> request.restore(4, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> request.restore(5, 4, 8));
        assertThrows(IllegalArgumentException.class, () -> request.restore(5, 0, 8));
        assertThrows(IllegalArgumentException.class, () -> request.restore(5, 5, -1));
        assertThrows(IllegalArgumentException.class, () -> request.restore(-1, 0, -1));
        assertSame(before, request.snapshot());
        request.cancel();
        assertThrows(IllegalArgumentException.class, () -> request.restore(4, 4, 8));
    }

    @Test public void invalidBeginAndExhaustedSequenceDoNotChangeOwnership() {
        RecoveryRequest request = new RecoveryRequest();
        request.restore(Long.MAX_VALUE, Long.MAX_VALUE, 2);
        RecoveryRequest.Snapshot before = request.snapshot();
        assertThrows(IllegalArgumentException.class, () -> request.begin(-1));
        assertThrows(IllegalStateException.class, () -> request.begin(3));
        assertSame(before, request.snapshot());
        request.cancel();
        assertThrows(IllegalStateException.class, () -> request.begin(3));
        assertFalse(request.isPending());
    }
}
