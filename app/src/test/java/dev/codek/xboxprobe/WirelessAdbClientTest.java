package dev.codek.xboxprobe;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class WirelessAdbClientTest {
    private static final byte[] EMPTY=new byte[0];
    private static final byte[] COMMAND="shell,v2,raw:bootstrap-secret\0".getBytes(StandardCharsets.UTF_8);

    @Test public void rejectedOpenFailsBeforeBinderHandoffAndPreservesStage() throws Exception {
        ByteArrayOutputStream replies=new ByteArrayOutputStream();
        AdbWire.write(replies,AdbWire.CLSE,0,1,EMPTY);
        ByteArrayOutputStream sent=new ByteArrayOutputStream();
        try (WirelessAdbClient client=new WirelessAdbClient(null)) {
            WirelessAdbFailure failure=assertThrows(WirelessAdbFailure.class,
                    ()->client.runBootstrap(input(replies),sent,COMMAND));
            assertSame(WirelessAdbFailure.Stage.START_SHELL_OPEN,failure.stage);
            assertSame(failure.stage,client.currentStage());
            assertFalse(failure.getMessage().contains("bootstrap-secret"));
            ByteArrayOutputStream expected=new ByteArrayOutputStream();
            AdbWire.write(expected,AdbWire.OPEN,1,0,COMMAND);
            assertArrayEquals(expected.toByteArray(),sent.toByteArray());
        }
    }

    @Test public void acceptedShellAcknowledgesOutputAndCloseWithoutSurfacingOutput() throws Exception {
        ByteArrayOutputStream replies=new ByteArrayOutputStream();
        AdbWire.write(replies,AdbWire.OKAY,71,1,EMPTY);
        byte[] response=AdbShellV2Test.concat(
                AdbShellV2Test.frame(1,"sensitive-output".getBytes(StandardCharsets.UTF_8)),
                AdbShellV2Test.frame(3,new byte[]{0}));
        AdbWire.write(replies,AdbWire.WRTE,71,1,java.util.Arrays.copyOfRange(response,0,3));
        AdbWire.write(replies,AdbWire.WRTE,71,1,java.util.Arrays.copyOfRange(response,3,response.length));
        AdbWire.write(replies,AdbWire.CLSE,71,1,EMPTY);
        ByteArrayOutputStream sent=new ByteArrayOutputStream();
        try (WirelessAdbClient client=new WirelessAdbClient(null)) {
            client.runBootstrap(input(replies),sent,COMMAND);
            assertSame(WirelessAdbFailure.Stage.START_BOOTSTRAP,client.currentStage());
            ByteArrayOutputStream expected=new ByteArrayOutputStream();
            AdbWire.write(expected,AdbWire.OPEN,1,0,COMMAND);
            AdbWire.write(expected,AdbWire.OKAY,1,71,EMPTY);
            AdbWire.write(expected,AdbWire.OKAY,1,71,EMPTY);
            AdbWire.write(expected,AdbWire.CLSE,1,71,EMPTY);
            assertArrayEquals(expected.toByteArray(),sent.toByteArray());
        }
    }

    @Test public void closeWithoutShellExitDoesNotReportBootstrapSuccess() throws Exception {
        ByteArrayOutputStream replies=new ByteArrayOutputStream();
        AdbWire.write(replies,AdbWire.OKAY,71,1,EMPTY);
        AdbWire.write(replies,AdbWire.CLSE,71,1,EMPTY);
        try (WirelessAdbClient client=new WirelessAdbClient(null)) {
            WirelessAdbFailure failure=assertThrows(WirelessAdbFailure.class,
                    ()->client.runBootstrap(input(replies),new ByteArrayOutputStream(),COMMAND));
            assertSame(WirelessAdbFailure.Stage.START_BOOTSTRAP,failure.stage);
        }
    }

    @Test public void eofAfterAcceptedOpenIsExecutionFailure() throws Exception {
        ByteArrayOutputStream replies=new ByteArrayOutputStream();
        AdbWire.write(replies,AdbWire.OKAY,71,1,EMPTY);
        try (WirelessAdbClient client=new WirelessAdbClient(null)) {
            WirelessAdbFailure failure=assertThrows(WirelessAdbFailure.class,
                    ()->client.runBootstrap(input(replies),new ByteArrayOutputStream(),COMMAND));
            assertSame(WirelessAdbFailure.Stage.START_BOOTSTRAP,failure.stage);
            assertEquals("EOFException",failure.exceptionType);
        }
    }

    @Test public void invalidPairingPortIsConnectionFailureAndCodeIsErased() throws Exception {
        byte[] code="123456".getBytes(StandardCharsets.US_ASCII);
        try (WirelessAdbClient client=new WirelessAdbClient(null)) {
            assertSame(WirelessAdbFailure.Stage.PREPARATION,client.currentStage());
            WirelessAdbFailure failure=assertThrows(WirelessAdbFailure.class,()->client.pair(0,code));
            assertSame(WirelessAdbFailure.Stage.PAIRING_CONNECT,failure.stage);
            assertArrayEquals(new byte[6],code);
        }
    }

    private static ByteArrayInputStream input(ByteArrayOutputStream bytes) {
        return new ByteArrayInputStream(bytes.toByteArray());
    }
}
