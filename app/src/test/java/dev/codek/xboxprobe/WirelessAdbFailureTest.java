package dev.codek.xboxprobe;

import static org.junit.Assert.*;
import java.io.IOException;
import java.net.SocketTimeoutException;
import org.junit.Test;

public final class WirelessAdbFailureTest {
    @Test public void everyStageHasBoundedAdviceWithoutRawMessageOrCause() {
        String secret="123456 bootstrap_nonce=private-credential";
        IOException original=new IOException(secret,new IllegalStateException(secret));
        for (WirelessAdbFailure.Stage stage:WirelessAdbFailure.Stage.values()) {
            WirelessAdbFailure failure=WirelessAdbFailure.at(stage,original);
            String message=WirelessAdbFailure.userMessage(failure);
            assertSame(stage,failure.stage);
            assertEquals("IOException",failure.exceptionType);
            assertTrue(message.contains(stage.label));
            assertTrue(message.contains("(IOException)"));
            assertTrue(message.length()<400);
            assertFalse(message.contains(secret));
            assertFalse(failure.toString().contains(secret));
            assertNull(failure.getCause());
        }
    }

    @Test public void outerBoundariesPreserveOriginalFailureStage() {
        WirelessAdbFailure inner=WirelessAdbFailure.at(WirelessAdbFailure.Stage.START_BOOTSTRAP,
                new SocketTimeoutException("sensitive socket detail"));
        assertSame(inner,WirelessAdbFailure.at(WirelessAdbFailure.Stage.START_SHELL_OPEN,inner));
        assertTrue(WirelessAdbFailure.userMessage(inner).contains("SocketTimeoutException"));
    }

    @Test public void unknownPreparationFailureNeverUsesItsMessage() {
        Exception original=new Exception("private pairing code");
        String message=WirelessAdbFailure.userMessage(original);
        assertTrue(message.contains(WirelessAdbFailure.Stage.PREPARATION.label));
        assertFalse(message.contains("private pairing code"));
        assertTrue(WirelessAdbFailure.userMessage(null).contains("(Exception)"));
        assertTrue(WirelessAdbFailure.userMessage(new Exception("private"){}).contains("(Exception)"));
    }
}
