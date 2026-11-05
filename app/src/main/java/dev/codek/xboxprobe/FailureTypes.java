package dev.codek.xboxprobe;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;

/** The sole boundary for exception names that may reach diagnostics. */
final class FailureTypes {
    private FailureTypes() {}

    static String className(Throwable failure) {
        if (failure == null) return "Exception";
        String type = failure.getClass().getSimpleName();
        return type.matches("[A-Za-z_$][A-Za-z0-9_$]{0,63}") ? type : "Exception";
    }

    /** Unwrap API invocation wrappers only; preserve ordinary operation failures. */
    static Throwable reflectionCause(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; depth < 8; depth++) {
            if (!(cause instanceof InvocationTargetException)
                    && !(cause instanceof UndeclaredThrowableException)
                    && !(cause instanceof ExceptionInInitializerError)) break;
            Throwable next = cause.getCause();
            if (next == null || next == cause) break;
            cause = next;
        }
        return cause;
    }
}
