package dev.codek.xboxprobe;

import java.util.UUID;

/** Owns a pairing request before and after its asynchronous service-start delivery. */
final class PairingStartOwnership {
    static final class Request {
        final String id = UUID.randomUUID().toString();
        private volatile boolean closed;
        private Request() {}
        boolean isClosed() { return closed; }
    }

    private Request pending;
    private Request claimed;

    synchronized Request begin() {
        if (hasRequest()) throw new IllegalStateException("Pairing request is already owned");
        pending = new Request();
        return pending;
    }

    /** Only the exact queued delivery can become the active service request, once. */
    synchronized Request claim(String id) {
        if (pending == null || !pending.id.equals(id)) return null;
        claimed = pending;
        pending = null;
        return claimed;
    }

    /** Cancellation and completion both relinquish ownership; stale handles do neither. */
    synchronized boolean end(Request request) {
        if (request == null || (request != pending && request != claimed)) return false;
        if (pending == request) pending = null;
        if (claimed == request) claimed = null;
        request.closed = true;
        return true;
    }

    synchronized boolean hasRequest() { return pending != null || claimed != null; }
    synchronized Request current() { return pending != null ? pending : claimed; }

    synchronized Request find(String id) {
        Request request = current();
        return request != null && request.id.equals(id) ? request : null;
    }
}
