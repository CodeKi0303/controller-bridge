package dev.codek.xboxprobe;

import static org.junit.Assert.*;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import org.junit.Test;

public final class FailureTypesTest {
    private static final String SECRET = "pairing=123456 nonce=private";

    @Test public void diagnosticsUseOnlyBoundedNamedClassWithoutReadingMessage() {
        assertEquals("IOException", FailureTypes.className(new IOException(SECRET)));
        assertEquals("MessageMustNotBeRead", FailureTypes.className(new MessageMustNotBeRead()));
        assertEquals("Exception", FailureTypes.className(null));
        assertEquals("Exception", FailureTypes.className(new IOException(SECRET) {}));
        assertEquals("Exception", FailureTypes.className(
                new ExceptionNameThatDeliberatelyExceedsTheSixtyFourCharacterDiagnosticLimit()));
    }

    @Test public void reflectionWrappersUnwrapButOrdinaryOperationCauseIsPreserved() {
        IOException operation = new IOException(SECRET, new IllegalArgumentException(SECRET));
        Throwable wrapped = new InvocationTargetException(new UndeclaredThrowableException(
                new ExceptionInInitializerError(operation)));
        assertSame(operation, FailureTypes.reflectionCause(wrapped));
        assertSame(operation, FailureTypes.reflectionCause(operation));
        assertEquals("IOException", FailureTypes.className(FailureTypes.reflectionCause(wrapped)));
        assertNull(FailureTypes.reflectionCause(null));
    }

    @Test(timeout = 1000) public void cyclicOrExcessivelyDeepWrappersHaveBoundedTraversal() {
        CyclicInvocation first = new CyclicInvocation(), second = new CyclicInvocation();
        first.next = second; second.next = first;
        assertSame(first, FailureTypes.reflectionCause(first));
        assertEquals(8, first.reads + second.reads);

        Throwable deep = new IOException(SECRET);
        for (int depth = 0; depth < 10; depth++) deep = new InvocationTargetException(deep);
        assertTrue(FailureTypes.reflectionCause(deep) instanceof InvocationTargetException);
        first.next = first; first.reads = 0;
        assertSame(first, FailureTypes.reflectionCause(first));
        assertEquals(1, first.reads);
    }

    private static final class MessageMustNotBeRead extends Exception {
        @Override public String getMessage() { throw new AssertionError("message access"); }
        @Override public String toString() { throw new AssertionError("exception formatting"); }
    }
    private static final class CyclicInvocation extends InvocationTargetException {
        Throwable next; int reads;
        CyclicInvocation() { super(null); }
        @Override public Throwable getCause() { reads++; return next; }
    }
    private static final class ExceptionNameThatDeliberatelyExceedsTheSixtyFourCharacterDiagnosticLimit extends Exception {}
}
