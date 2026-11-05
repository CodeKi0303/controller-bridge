package dev.codek.xboxprobe;

import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import org.json.JSONObject;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Supplier;

import dev.codek.xboxprobe.core.EvdevDecoder;
import dev.codek.xboxprobe.core.EvdevEvent;
import dev.codek.xboxprobe.core.G8InputMapper;
import dev.codek.xboxprobe.core.MappingUpdate;
import dev.codek.xboxprobe.core.EvdevInputStream;

/** Owns one already verified read-only evdev fd until its reader closes it. */
final class UhidSourceBridge {
    interface Listener {
        void onReport(UhidSourceBridge source, byte[] report) throws IOException;
        void onFailure(UhidSourceBridge source, String reason);
    }

    private final UhidSourceCatalog.OpenedSource source;
    private final Listener listener;
    private final EvdevInputStream input;
    private final String outputProfile;
    private final EvdevDecoder decoder = new EvdevDecoder(Process.is64Bit() ? 24 : 16);
    private final Thread thread;
    private final boolean exclusiveRequested;
    private volatile boolean exclusiveAcquired;
    private volatile boolean inputReady;
    private volatile boolean stopRequested;
    private volatile boolean closed;
    private volatile boolean finished;
    private volatile String error = "";
    private volatile String closeError = "";
    private volatile String releaseWarning = "";
    private volatile long eventCount;
    private volatile long reportCount;
    private volatile EvdevEvent lastEvent;
    private volatile byte[] currentReport;

    UhidSourceBridge(UhidSourceCatalog.OpenedSource source, Supplier<G8InputMapper> mapperFactory,
                     String outputProfile, Listener listener) {
        this(source, mapperFactory, outputProfile, listener, false);
    }

    UhidSourceBridge(UhidSourceCatalog.OpenedSource source, Supplier<G8InputMapper> mapperFactory,
                     String outputProfile, Listener listener, boolean exclusiveRequested) {
        this.source = source;
        this.listener = listener;
        this.exclusiveRequested = exclusiveRequested;
        Objects.requireNonNull(mapperFactory, "mapperFactory");
        int[] keys = source.capabilities.keyCodes.stream().mapToInt(Integer::intValue).toArray();
        int[] axes = source.capabilities.axes.keySet().stream().mapToInt(Integer::intValue).toArray();
        input = new EvdevInputStream(source.fd, mapperFactory, ownedFd -> {
            if (stopRequested) throw new IOException("Source recovery was cancelled");
            dev.codek.xboxprobe.core.InputStateSnapshot state = NativeEvdevState.readSnapshot(ownedFd, keys, axes);
            if (stopRequested) throw new IOException("Source recovery was cancelled");
            return state;
        }, ownedFd -> {
            if (stopRequested) throw new IOException("Source recovery was cancelled");
            StructPollfd pending = new StructPollfd();
            pending.fd = ownedFd;
            pending.events = (short) OsConstants.POLLIN;
            try {
                Os.poll(new StructPollfd[] {pending}, 0);
            } catch (ErrnoException e) {
                if (stopRequested) throw new IOException("Source recovery was cancelled", e);
                // An interrupted check cannot establish an empty queue. Defer
                // to the next bounded reader pass instead of spinning here.
                if (e.errno == OsConstants.EINTR) return EvdevInputStream.QueueState.PENDING;
                throw new IOException("Cannot check source queue after recovery snapshot", e);
            }
            if (stopRequested) throw new IOException("Source recovery was cancelled");
            if ((pending.revents & (OsConstants.POLLERR | OsConstants.POLLHUP | OsConstants.POLLNVAL)) != 0) {
                throw new IOException("Selected input node disconnected during recovery: " + pending.revents);
            }
            return pending.revents == 0 ? EvdevInputStream.QueueState.EMPTY : EvdevInputStream.QueueState.PENDING;
        });
        this.outputProfile = Objects.requireNonNull(outputProfile, "outputProfile");
        currentReport = input.neutralReport();
        thread = new Thread(this::run, "controller-selected-input");
        thread.setDaemon(true);
    }

    void start() { thread.start(); }
    void requestStop() { stopRequested = true; }
    boolean isClosed() { return closed && finished; }
    boolean isRunning() { return inputReady && !stopRequested && !finished; }
    byte[] currentReport() { return currentReport.clone(); }
    String outputProfile() { return outputProfile; }

    private void run() {
        try {
            if (stopRequested) return;
            if (exclusiveRequested) {
                // Opt-in for this exact selected gamepad. Failure must not silently
                // fall back to duplicate input. Closing this fd also releases grab.
                NativeEvdevState.setExclusive(source.fd, true);
                exclusiveAcquired = true;
            }
            if (stopRequested) return;
            inputReady = true;
            // getevent's ABS snapshot and this fd's queued events are not atomic.
            // Seeding that newer snapshot before older queued events could invent
            // a transient movement. Start released and use only this fd's events;
            // held controls at attachment remain explicitly unknown until observed.
            StructPollfd poll = new StructPollfd();
            poll.fd = source.fd;
            poll.events = (short) OsConstants.POLLIN;
            StructPollfd[] polls = {poll};
            byte[] bytes = new byte[4096];
            while (!stopRequested) {
                poll.revents = 0;
                try {
                    if (Os.poll(polls, 100) == 0 && !input.isDiscarding()) continue;
                } catch (ErrnoException e) {
                    if (e.errno == OsConstants.EINTR) continue;
                    throw e;
                }
                if (stopRequested) break;
                if ((poll.revents & (OsConstants.POLLERR | OsConstants.POLLHUP | OsConstants.POLLNVAL)) != 0) {
                    throw new IOException("Selected input node disconnected or poll failed: " + poll.revents);
                }
                if ((poll.revents & OsConstants.POLLIN) == 0 && !input.isDiscarding()) continue;
                // Drain bursts promptly. On overflow, this also discards the
                // already-read remainder and queued frames before taking a newer
                // state snapshot. Bound each pass so cancellation stays prompt.
                for (int batch = 0; batch < 32 && !stopRequested; batch++) {
                    int count;
                    try {
                        count = Os.read(source.fd, bytes, 0, bytes.length);
                    } catch (ErrnoException e) {
                        if (e.errno == OsConstants.EINTR) continue;
                        if (e.errno == OsConstants.EAGAIN) {
                            if (decoder.getPendingByteCount() == 0 && !stopRequested) {
                                publish(input.onQueueDrained().getUpdate());
                            }
                            break;
                        }
                        throw e;
                    }
                    if (count == 0) {
                        decoder.finish();
                        throw new IOException("Selected input node reached EOF");
                    }
                    decoder.accept(bytes, 0, count, this::apply);
                }
            }
        } catch (Exception e) {
            if (!stopRequested) {
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
                stopRequested = true;
                currentReport = input.disconnect().getReport();
                listener.onFailure(this, error);
            }
        } finally {
            stopRequested = true;
            inputReady = false;
            currentReport = input.neutralReport();
            if (exclusiveAcquired) {
                try { NativeEvdevState.setExclusive(source.fd, false); exclusiveAcquired = false; }
                catch (IOException e) { releaseWarning = e.getMessage(); }
            }
            try {
                // This thread alone closes the owned fd. Callers never close it
                // while poll/read is in flight and never reopen on disconnect.
                Os.close(source.fd);
                closed = true;
                exclusiveAcquired = false; // Kernel releases the fd-scoped grab on close.
            } catch (Exception e) {
                closeError = e.getClass().getSimpleName() + ": " + e.getMessage();
                listener.onFailure(this, "Source fd cleanup unresolved: " + closeError);
            } finally {
                finished = true;
            }
        }
    }

    private void apply(EvdevEvent event) throws IOException {
        if (stopRequested) return;
        eventCount++;
        if (event.type == EvdevEvent.EV_KEY || event.type == EvdevEvent.EV_ABS) lastEvent = event;
        if (!input.isDiscarding() && event.type == EvdevEvent.EV_KEY && !source.capabilities.keyCodes.contains(event.code)) {
            throw new IOException("Input key is absent from the verified capability snapshot: " + event.code);
        }
        if (!input.isDiscarding() && event.type == EvdevEvent.EV_ABS && !source.capabilities.axes.containsKey(event.code)) {
            throw new IOException("Input axis is absent from the verified capability snapshot: " + event.code);
        }
        publish(input.accept(event));
    }

    private void publish(MappingUpdate update) throws IOException {
        if (stopRequested) return;
        if (update.isFailed()) throw new IOException(update.getError());
        byte[] report = update.getReport();
        if (report != null) {
            currentReport = report;
            listener.onReport(this, report);
            reportCount++;
        }
    }

    JSONObject status() throws Exception {
        String state = !closeError.isEmpty() ? "cleanup_failed" : !error.isEmpty() ? "failed"
                : isClosed() ? "stopped" : stopRequested ? "stopping" : inputReady ? "running" : "starting";
        JSONObject result = new JSONObject().put("state", state)
                .put("outputProfile", outputProfile)
                .put("sourceName", source.capabilities.name).put("sourcePath", source.capabilities.path)
                .put("identity", source.identity.json()).put("eventCount", eventCount).put("reportCount", reportCount)
                .put("keyStateAtStart", "unknown_release_controls_before_start")
                .put("axisStateAtStart", "unknown_until_source_event")
                .put("sourceExclusiveRequested", exclusiveRequested).put("sourceExclusive", exclusiveAcquired)
                .put("sourceReleaseWarning", releaseWarning).put("sourceClosed", closed).put("readerFinished", finished)
                .put("inputResynchronizing", input.isDiscarding())
                .put("inputDroppedCount", input.getDroppedCount()).put("inputRecoveryCount", input.getRecoveryCount())
                .put("error", error).put("cleanupError", closeError).put("eventSize", decoder.getEventSize());
        EvdevEvent last = lastEvent;
        if (last != null) result.put("lastEvent", new JSONObject().put("type", last.type).put("code", last.code).put("value", last.value));
        return result;
    }
}
