package dev.codek.xboxprobe;

/** Owns one explicit connection-recovery request independently of service readiness. */
public final class RecoveryRequest {
    public static final class Snapshot {
        public final long sequence;
        public final long requestId;
        public final long ownerGeneration;

        private Snapshot(long sequence, long requestId, long ownerGeneration) {
            this.sequence = sequence;
            this.requestId = requestId;
            this.ownerGeneration = ownerGeneration;
        }
    }

    private Snapshot current = new Snapshot(0, 0, -1);

    /** Replaces any prior request; the caller owns the meaning of ownerGeneration. */
    public synchronized long begin(long ownerGeneration) {
        if (ownerGeneration < 0) throw new IllegalArgumentException("Owner generation is missing");
        if (current.sequence == Long.MAX_VALUE)
            throw new IllegalStateException("Recovery request sequence exhausted");
        long id = current.sequence + 1;
        current = new Snapshot(id, id, ownerGeneration);
        return id;
    }

    public synchronized boolean isPending() { return current.requestId > 0; }
    public synchronized long requestId() { return current.requestId; }
    public synchronized long ownerGeneration() { return current.ownerGeneration; }
    public synchronized Snapshot snapshot() { return current; }

    /** Consumes only this request. The caller must also verify its owner generation. */
    public synchronized boolean accept(long returnedId) {
        if (returnedId <= 0 || returnedId != current.requestId) return false;
        cancel();
        return true;
    }

    public synchronized void cancel() {
        current = new Snapshot(current.sequence, 0, -1);
    }

    /** Restores the sequence even when nothing is pending, so cancelled IDs are not reused. */
    public synchronized void restore(long sequence, long requestId, long ownerGeneration) {
        if (sequence < 0 || requestId < 0
                || (requestId == 0 ? ownerGeneration != -1
                        : requestId != sequence || ownerGeneration < 0))
            throw new IllegalArgumentException("Invalid saved recovery request");
        if (sequence < current.sequence)
            throw new IllegalArgumentException("Saved request would reuse an older ID");
        if (sequence == current.sequence && (requestId != current.requestId
                || ownerGeneration != current.ownerGeneration))
            throw new IllegalArgumentException("Request ID already has different ownership");
        current = new Snapshot(sequence, requestId, ownerGeneration);
    }
}
