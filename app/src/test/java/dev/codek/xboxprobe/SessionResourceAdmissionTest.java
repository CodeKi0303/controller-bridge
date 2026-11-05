package dev.codek.xboxprobe;

import static org.junit.Assert.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public final class SessionResourceAdmissionTest {
    @Test public void idleProofRequiresClosingAdmissionAndRejectsLaterWork() {
        SessionResourceAdmission resources=new SessionResourceAdmission();
        assertFalse(resources.closedUnused());
        resources.close();
        assertTrue(resources.closedUnused());
        assertFalse(resources.admit());
        resources.close();
        assertTrue(resources.closedUnused());
    }

    @Test public void FailedOrUnfinishedAdmittedWorkNeverBecomesIdle() {
        SessionResourceAdmission resources=new SessionResourceAdmission();
        assertTrue(resources.admit());
        // No successful IPC result is needed: the helper could have allocated
        // before dying or before the caller observed failure/cancellation.
        resources.close();
        assertFalse(resources.closedUnused());
        assertFalse(resources.admit());
        resources.close();
        assertFalse(resources.closedUnused());
    }

    @Test public void closingAndOperationAdmissionCannotBothWin() throws Exception {
        ExecutorService workers=Executors.newFixedThreadPool(2);
        try {
            for(int attempt=0;attempt<100;attempt++) {
                SessionResourceAdmission resources=new SessionResourceAdmission();
                CountDownLatch start=new CountDownLatch(1);
                Future<Boolean> operation=workers.submit(()->{start.await();return resources.admit();});
                Future<Boolean> cleanup=workers.submit(()->{start.await();resources.close();return resources.closedUnused();});
                start.countDown();
                boolean admitted=operation.get(3,TimeUnit.SECONDS);
                boolean unused=cleanup.get(3,TimeUnit.SECONDS);
                assertNotEquals(admitted,unused);
                assertEquals(unused,resources.closedUnused());
                assertFalse(resources.admit());
            }
        } finally {workers.shutdownNow();}
    }
}
