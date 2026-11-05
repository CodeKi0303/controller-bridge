package dev.codek.xboxprobe;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public final class WirelessSetupGateTest {
    @Test public void queuedCancellationAllowsNewWorkAndOldRunnableCannotEnter() {
        WirelessSetupGate gate = new WirelessSetupGate();
        WirelessSetupGate.Lease old = gate.tryAcquire();
        assertNotNull(old);
        assertTrue(gate.isBusy());
        assertNull(gate.tryAcquire());
        old.cancel();
        assertFalse(gate.isBusy());

        WirelessSetupGate.Lease current = gate.tryAcquire();
        assertNotNull(current);
        assertFalse(old.enter());
        assertTrue(current.enter());
        current.finish();
        assertFalse(gate.isBusy());
    }

    @Test public void runningCancellationRetainsOwnershipUntilWorkerFinishes() {
        WirelessSetupGate gate = new WirelessSetupGate();
        WirelessSetupGate.Lease running = gate.tryAcquire();
        assertTrue(running.enter());
        assertFalse(running.enter());
        running.cancel();
        running.cancel();
        assertTrue(gate.isBusy());
        assertNull(gate.tryAcquire());
        assertFalse(running.enter());

        running.finish();
        assertFalse(gate.isBusy());
        WirelessSetupGate.Lease next = gate.tryAcquire();
        assertNotNull(next);
        next.finish();
    }

    @Test public void staleWorkerCleanupAndCancellationCannotReleaseANewerOwner() {
        WirelessSetupGate gate = new WirelessSetupGate();
        WirelessSetupGate.Lease old = gate.tryAcquire();
        old.cancel();
        WirelessSetupGate.Lease current = gate.tryAcquire();
        assertTrue(current.enter());

        old.finish();
        old.cancel();
        old.finish();
        assertTrue(gate.isBusy());
        assertNull(gate.tryAcquire());
        assertFalse(old.enter());
        current.finish();
        assertFalse(gate.isBusy());
    }

    @Test public void rejectedSubmissionCanFinishItsQueuedReservation() {
        WirelessSetupGate gate = new WirelessSetupGate();
        WirelessSetupGate.Lease rejected = gate.tryAcquire();
        rejected.finish();
        rejected.finish();
        assertFalse(rejected.enter());
        assertFalse(gate.isBusy());
        WirelessSetupGate.Lease retry = gate.tryAcquire();
        assertNotNull(retry);
        retry.finish();
    }

    @Test public void cancellationRacingWorkerEntryEitherRevokesOrRetainsExactOwner() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int attempt = 0; attempt < 100; attempt++) {
                WirelessSetupGate gate = new WirelessSetupGate();
                WirelessSetupGate.Lease lease = gate.tryAcquire();
                CountDownLatch start = new CountDownLatch(1);
                Future<Boolean> entering = pool.submit(() -> {
                    assertTrue(start.await(3, TimeUnit.SECONDS));
                    return lease.enter();
                });
                Future<?> cancelling = pool.submit(() -> {
                    assertTrue(start.await(3, TimeUnit.SECONDS));
                    lease.cancel();
                    return null;
                });
                start.countDown();
                boolean entered = entering.get(3, TimeUnit.SECONDS);
                cancelling.get(3, TimeUnit.SECONDS);
                assertEquals(entered, gate.isBusy());
                assertFalse(lease.enter());
                if (entered) assertNull(gate.tryAcquire());
                lease.finish();
                WirelessSetupGate.Lease next = gate.tryAcquire();
                assertNotNull(next);
                next.finish();
            }
        } finally { pool.shutdownNow(); }
    }

    @Test public void concurrentOwnersCannotBothAcquireTheSharedResource() throws Exception {
        WirelessSetupGate gate = new WirelessSetupGate();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<WirelessSetupGate.Lease>> attempts = new ArrayList<>();
            for (int i = 0; i < 8; i++) attempts.add(pool.submit(() -> {
                assertTrue(start.await(3, TimeUnit.SECONDS));
                return gate.tryAcquire();
            }));
            start.countDown();
            int admitted = 0;
            WirelessSetupGate.Lease owner = null;
            for (Future<WirelessSetupGate.Lease> attempt : attempts) {
                WirelessSetupGate.Lease value = attempt.get(3, TimeUnit.SECONDS);
                if (value != null) { admitted++; owner = value; }
            }
            assertEquals(1, admitted);
            assertNotNull(owner);
            owner.finish();
            assertFalse(gate.isBusy());
        } finally { start.countDown(); pool.shutdownNow(); }
    }
}
