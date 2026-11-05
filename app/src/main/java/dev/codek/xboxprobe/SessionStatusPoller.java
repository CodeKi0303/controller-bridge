package dev.codek.xboxprobe;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** One in-flight status read, whose accepted request also renews its session lease. */
final class SessionStatusPoller {
    interface Remote {
        void keepAlive() throws Exception;
        String status() throws Exception;
    }

    private final AtomicBoolean inFlight = new AtomicBoolean();

    void request(Executor executor, BooleanSupplier current, Remote remote,
                 Consumer<String> status, Consumer<Exception> failure) {
        if (!current.getAsBoolean() || !inFlight.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try {
                    if (!current.getAsBoolean()) return;
                    // A UI refresh may coalesce the scheduled heartbeat. Every
                    // admitted read therefore carries the same lease renewal.
                    remote.keepAlive();
                    if (!current.getAsBoolean()) return;
                    String value = remote.status();
                    if (current.getAsBoolean()) status.accept(value);
                } catch (Exception error) {
                    if (current.getAsBoolean()) failure.accept(error);
                } finally {
                    inFlight.set(false);
                }
            });
        } catch (RuntimeException error) {
            // Rejected submission has no runnable to release the admission.
            inFlight.set(false);
            if (current.getAsBoolean()) failure.accept(error);
        }
    }
}
