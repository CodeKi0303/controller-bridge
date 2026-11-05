package dev.codek.xboxprobe;

/** Monotonic proof of whether this helper could have opened input resources or
 * started an inventory subprocess. Cleanup closes admission before using the
 * idle proof; an earlier failed/unfinished call still counts as admitted work. */
final class SessionResourceAdmission {
    private boolean closed, used;

    synchronized boolean admit() {
        if (closed) return false;
        used = true;
        return true;
    }

    synchronized void close() { closed = true; }

    synchronized boolean closedUnused() { return closed && !used; }
}
