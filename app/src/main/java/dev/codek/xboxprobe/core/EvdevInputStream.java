package dev.codek.xboxprobe.core;

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Owns loss/recovery for one open evdev stream. A dropped interval is never
 * replayed into a mapper. The fd owner drains buffered input before recovering.
 * https://docs.kernel.org/input/event-codes.html#ev-syn
 */
public final class EvdevInputStream {
    @FunctionalInterface public interface SnapshotReader {
        InputStateSnapshot read(FileDescriptor ownedSource) throws IOException;
    }
    public enum QueueState { EMPTY, PENDING }
    @FunctionalInterface public interface QueueStateReader {
        QueueState read(FileDescriptor ownedSource) throws IOException;
    }
    public static final class RecoveryAttempt {
        public enum Outcome { NOT_NEEDED, DEFERRED, RESTORED, FAILED }
        private static final RecoveryAttempt NOT_NEEDED = new RecoveryAttempt(Outcome.NOT_NEEDED, MappingUpdate.none());
        private static final RecoveryAttempt DEFERRED = new RecoveryAttempt(Outcome.DEFERRED, MappingUpdate.none());
        private final Outcome outcome;
        private final MappingUpdate update;

        private RecoveryAttempt(Outcome outcome, MappingUpdate update) {
            this.outcome = outcome;
            this.update = update;
        }

        public Outcome getOutcome() { return outcome; }
        public MappingUpdate getUpdate() { return update; }
    }
    private enum State { RUNNING, WAITING_REPORT, WAITING_SNAPSHOT, DISCONNECTED }
    private final FileDescriptor source;
    private final Supplier<G8InputMapper> factory;
    private final SnapshotReader reader;
    private final QueueStateReader queueStateReader;
    private G8InputMapper mapper;
    private volatile State state = State.RUNNING;
    private volatile long droppedCount;
    private volatile long recoveryCount;

    public EvdevInputStream(FileDescriptor source, Supplier<G8InputMapper> factory, SnapshotReader reader,
                            QueueStateReader queueStateReader) {
        this.source = Objects.requireNonNull(source, "source");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.queueStateReader = Objects.requireNonNull(queueStateReader, "queueStateReader");
        mapper = Objects.requireNonNull(factory.get(), "mapper");
    }

    public MappingUpdate accept(EvdevEvent event) {
        Objects.requireNonNull(event, "event");
        if (state == State.DISCONNECTED) return mapper.disconnect();
        if (event.type == EvdevEvent.EV_SYN && event.code == EvdevEvent.SYN_DROPPED) {
            boolean wasRunning = state == State.RUNNING;
            state = State.WAITING_REPORT;
            droppedCount++;
            return wasRunning ? MappingUpdate.report(mapper.neutralReport()) : MappingUpdate.none();
        }
        if (state != State.RUNNING) {
            if (event.type == EvdevEvent.EV_SYN && event.code == EvdevEvent.SYN_REPORT) {
                state = State.WAITING_SNAPSHOT;
            }
            return MappingUpdate.none();
        }
        return mapper.accept(event);
    }

    /** Called only after the reader reaches EAGAIN with no partial event. */
    public RecoveryAttempt onQueueDrained() throws IOException {
        if (state != State.WAITING_SNAPSHOT) return RecoveryAttempt.NOT_NEEDED;
        InputStateSnapshot snapshot = Objects.requireNonNull(reader.read(source), "snapshot");
        // EVIOCGKEY and EVIOCGABS are separate reads. Events queued during them
        // may predate some captured values, so publishing now would replay stale
        // presses or motion. Discard that queue and retry a fresh snapshot.
        if (Objects.requireNonNull(queueStateReader.read(source), "queue state") == QueueState.PENDING) {
            return RecoveryAttempt.DEFERRED;
        }
        G8InputMapper replacement = Objects.requireNonNull(factory.get(), "replacement mapper");
        MappingUpdate restored = snapshot.restore(replacement);
        if (restored.isFailed()) return new RecoveryAttempt(RecoveryAttempt.Outcome.FAILED, restored);
        mapper = replacement;
        recoveryCount++;
        state = State.RUNNING;
        return new RecoveryAttempt(RecoveryAttempt.Outcome.RESTORED, restored);
    }

    public MappingUpdate disconnect() {
        state = State.DISCONNECTED;
        return mapper.disconnect();
    }

    public byte[] neutralReport() { return mapper.neutralReport(); }
    public boolean isDiscarding() { return state == State.WAITING_REPORT || state == State.WAITING_SNAPSHOT; }
    public long getDroppedCount() { return droppedCount; }
    public long getRecoveryCount() { return recoveryCount; }
}
