package dev.codek.xboxprobe;

import org.junit.Test;
import static org.junit.Assert.*;

public final class PairingStartOwnershipTest {
    @Test public void closingScreenBeforeServiceDeliveryRejectsItsQueuedStart() {
        PairingStartOwnership owner = new PairingStartOwnership();
        PairingStartOwnership.Request request = owner.begin();
        assertTrue(owner.end(request));
        assertTrue(request.isClosed());
        assertNull(owner.claim(request.id));
        assertFalse(owner.hasRequest());
    }

    @Test public void staleStartAndStaleCancellationCannotTakeOrCloseNewRequest() {
        PairingStartOwnership owner = new PairingStartOwnership();
        PairingStartOwnership.Request first = owner.begin();
        owner.end(first);
        PairingStartOwnership.Request replacement = owner.begin();
        assertNull(owner.claim(first.id));
        assertFalse(owner.end(first));
        assertSame(replacement, owner.current());
        assertSame(replacement, owner.claim(replacement.id));
        assertFalse(replacement.isClosed());
        assertFalse(owner.end(first));
        assertSame(replacement, owner.find(replacement.id));
    }

    @Test public void cancellationAfterClaimClosesTheSameRequestBeforeSessionCreation() {
        PairingStartOwnership owner = new PairingStartOwnership();
        PairingStartOwnership.Request request = owner.begin();
        assertSame(request, owner.claim(request.id));
        assertTrue(owner.end(request));
        assertTrue(request.isClosed());
        assertNull(owner.find(request.id));
        assertNull(owner.claim(request.id));
        assertFalse(owner.end(request));
    }

    @Test public void queuedAndClaimedRequestsEachExcludeDuplicateStarts() {
        PairingStartOwnership owner = new PairingStartOwnership();
        PairingStartOwnership.Request request = owner.begin();
        assertThrows(IllegalStateException.class, owner::begin);
        assertSame(request, owner.claim(request.id));
        assertNull(owner.claim(request.id));
        assertThrows(IllegalStateException.class, owner::begin);
        owner.end(request);
        PairingStartOwnership.Request next = owner.begin();
        assertNotEquals(request.id, next.id);
        assertSame(next, owner.find(next.id));
    }

    @Test public void configurationRestoreFindsOnlyExactLiveOwnership() {
        PairingStartOwnership owner = new PairingStartOwnership();
        PairingStartOwnership.Request request = owner.begin();
        assertSame(request, owner.find(request.id));
        assertNull(owner.find(null));
        assertNull(owner.find("another-request"));
        owner.claim(request.id);
        assertSame(request, owner.find(request.id));
        owner.end(request);
        assertNull(owner.find(request.id));
    }

    @Test public void foreignRegistryCannotCloseOrClaimAnotherOwnersRequest() {
        PairingStartOwnership firstOwner = new PairingStartOwnership();
        PairingStartOwnership secondOwner = new PairingStartOwnership();
        PairingStartOwnership.Request first = firstOwner.begin();
        PairingStartOwnership.Request second = secondOwner.begin();
        assertFalse(firstOwner.end(second));
        assertNull(firstOwner.claim(second.id));
        assertFalse(second.isClosed());
        assertSame(first, firstOwner.current());
        assertSame(second, secondOwner.current());
    }
}
