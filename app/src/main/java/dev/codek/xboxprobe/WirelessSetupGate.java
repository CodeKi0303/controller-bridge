package dev.codek.xboxprobe;

/** One application-wide owner for pairing, startup, and identity changes.
 *
 * Acquire before submitting work, and call {@link Lease#enter()} in the worker
 * before touching the shared ADB identity. Queued cancellation releases the
 * reservation immediately. Once entered, cancellation retains ownership until
 * the worker has closed its resources and calls {@link Lease#finish()} in
 * finally. A rejected submission may finish its never-entered reservation.
 *
 * Every transition uses this gate's monitor only; no callbacks or blocking
 * resource operations run while it is held.
 */
final class WirelessSetupGate {
    static final WirelessSetupGate SHARED = new WirelessSetupGate();

    private enum State { QUEUED, RUNNING, CANCELLING, FINISHED }
    private Lease owner;

    synchronized Lease tryAcquire() {
        if (owner != null) return null;
        owner = new Lease(this);
        return owner;
    }

    synchronized boolean isBusy() { return owner != null; }

    static final class Lease {
        private final WirelessSetupGate gate;
        private State state = State.QUEUED;

        private Lease(WirelessSetupGate gate) { this.gate = gate; }

        /** Returns false for cancelled, already-entered, or superseded work. */
        boolean enter() {
            synchronized (gate) {
                if (gate.owner != this || state != State.QUEUED) return false;
                state = State.RUNNING;
                return true;
            }
        }

        /** Does not release a running operation's identity or socket ownership. */
        void cancel() {
            synchronized (gate) {
                if (gate.owner != this) return;
                if (state == State.QUEUED) {
                    state = State.FINISHED;
                    gate.owner = null;
                } else if (state == State.RUNNING) {
                    state = State.CANCELLING;
                }
            }
        }

        /** Release after cleanup; repeated or stale calls cannot release a new owner. */
        void finish() {
            synchronized (gate) {
                state = State.FINISHED;
                if (gate.owner == this) gate.owner = null;
            }
        }
    }
}
