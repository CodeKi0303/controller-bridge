package dev.codek.xboxprobe;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public final class SessionStatusPollerTest {
    private static final class QueuedExecutor implements Executor {
        final ArrayDeque<Runnable> queue=new ArrayDeque<>();
        @Override public void execute(Runnable task){queue.add(task);}
        void run(){queue.remove().run();}
    }
    private static final class Remote implements SessionStatusPoller.Remote {
        int renewals,reads;
        Runnable afterRenew=()->{},afterRead=()->{};
        boolean failRead;
        @Override public void keepAlive(){renewals++;afterRenew.run();}
        @Override public String status()throws IOException {
            reads++;afterRead.run();if(failRead)throw new IOException("read failed");return "ready";
        }
    }

    @Test public void recurringUiRefreshAndHeartbeatOverlapStillRenewsEveryAcceptedRead() {
        SessionStatusPoller poller=new SessionStatusPoller();QueuedExecutor worker=new QueuedExecutor();
        Remote remote=new Remote();List<String> statuses=new ArrayList<>();
        List<Exception> errors=new ArrayList<>();
        for(int heartbeat=0;heartbeat<12;heartbeat++) {
            poller.request(worker,()->true,remote,statuses::add,errors::add); // UI arrives first
            for(int burst=0;burst<100;burst++)
                poller.request(worker,()->true,remote,statuses::add,errors::add); // coalesced heartbeat/refresh
            assertEquals(1,worker.queue.size());
            worker.run();
            assertEquals(heartbeat+1,remote.renewals);
            assertEquals(heartbeat+1,remote.reads);
        }
        assertEquals(12,statuses.size());assertTrue(errors.isEmpty());
    }

    @Test public void cancelledQueuedSessionCannotRenewAndDoesNotBlockNewSession() {
        SessionStatusPoller poller=new SessionStatusPoller();QueuedExecutor worker=new QueuedExecutor();
        AtomicBoolean current=new AtomicBoolean(true);Remote old=new Remote(),fresh=new Remote();
        List<String> statuses=new ArrayList<>();List<Exception> errors=new ArrayList<>();
        poller.request(worker,current::get,old,statuses::add,errors::add);
        current.set(false);worker.run();
        assertEquals(0,old.renewals);assertEquals(0,old.reads);assertTrue(statuses.isEmpty());
        poller.request(worker,()->true,fresh,statuses::add,errors::add);worker.run();
        assertEquals(1,fresh.renewals);assertEquals(List.of("ready"),statuses);assertTrue(errors.isEmpty());
    }

    @Test public void sessionClosingDuringRenewalOrReadCannotPublishOldStatus() {
        SessionStatusPoller poller=new SessionStatusPoller();QueuedExecutor worker=new QueuedExecutor();
        AtomicBoolean current=new AtomicBoolean(true);Remote remote=new Remote();
        List<String> statuses=new ArrayList<>();List<Exception> errors=new ArrayList<>();
        remote.afterRenew=()->current.set(false);
        poller.request(worker,current::get,remote,statuses::add,errors::add);worker.run();
        assertEquals(1,remote.renewals);assertEquals(0,remote.reads);assertTrue(statuses.isEmpty());
        current.set(true);remote.afterRenew=()->{};remote.afterRead=()->current.set(false);
        poller.request(worker,current::get,remote,statuses::add,errors::add);worker.run();
        assertEquals(2,remote.renewals);assertEquals(1,remote.reads);assertTrue(statuses.isEmpty());
        assertTrue(errors.isEmpty());
    }

    @Test public void failedReadReleasesAdmissionForNextHeartbeat() {
        SessionStatusPoller poller=new SessionStatusPoller();QueuedExecutor worker=new QueuedExecutor();
        Remote remote=new Remote();remote.failRead=true;
        List<String> statuses=new ArrayList<>();List<Exception> errors=new ArrayList<>();
        poller.request(worker,()->true,remote,statuses::add,errors::add);worker.run();
        assertEquals(1,errors.size());assertTrue(statuses.isEmpty());
        remote.failRead=false;
        poller.request(worker,()->true,remote,statuses::add,errors::add);worker.run();
        assertEquals(2,remote.renewals);assertEquals(List.of("ready"),statuses);
    }

    @Test public void rejectedSubmissionDoesNotLeavePollingPermanentlyBusy() {
        SessionStatusPoller poller=new SessionStatusPoller();QueuedExecutor worker=new QueuedExecutor();
        Remote remote=new Remote();List<String> statuses=new ArrayList<>();List<Exception> errors=new ArrayList<>();
        poller.request(task->{throw new RejectedExecutionException("stopped");},()->true,remote,statuses::add,errors::add);
        assertEquals(1,errors.size());assertEquals(0,remote.renewals);
        poller.request(worker,()->true,remote,statuses::add,errors::add);worker.run();
        assertEquals(1,remote.renewals);assertEquals(List.of("ready"),statuses);
    }
}
