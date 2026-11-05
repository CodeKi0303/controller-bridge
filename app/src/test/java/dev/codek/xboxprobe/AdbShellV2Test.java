package dev.codek.xboxprobe;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;

public final class AdbShellV2Test {
    @Test public void rawShellRequiresExactNegotiatedFeature() throws Exception {
        for (String suffix:new String[]{"",";","\0",";\0"}) {
            byte[] command=AdbShellV2.command(utf8("device::ro.product.model=S25;features=cmd,shell_v2,stat_v2"+suffix),"secret-command");
            assertEquals("shell,v2,raw:secret-command\0",new String(command,StandardCharsets.UTF_8));
        }
        for (String banner:new String[]{"device::features=cmd","device::features=shell_v20",
                "device::ro.product.name=shell_v2","device::features=shell_v2;features=cmd",
                "device::features=shell_v2=invalid","device::features=shell_v2\0;features=cmd"})
            assertThrows(IOException.class,()->AdbShellV2.command(utf8(banner),"secret-command"));
    }

    @Test public void frameHeadersAndPayloadsCanSplitAtEveryByteBoundary() throws Exception {
        byte[] response=concat(frame(1,utf8("stdout-secret")),frame(2,utf8("stderr-secret")),frame(3,new byte[]{0}));
        for (int split=0;split<=response.length;split++) {
            AdbShellV2 parser=new AdbShellV2();
            parser.accept(Arrays.copyOfRange(response,0,split));
            parser.accept(Arrays.copyOfRange(response,split,response.length));
            parser.requireSuccess();
        }
        AdbShellV2 fragmented=new AdbShellV2();
        for (byte value:response) fragmented.accept(new byte[]{value});
        fragmented.requireSuccess();
    }

    @Test public void missingPartialAndNonzeroExitAreRejectedWithoutOutputInError() throws Exception {
        byte[] response=concat(frame(2,utf8("bootstrap-secret")),frame(3,new byte[]{0}));
        for (int length=0;length<response.length;length++) {
            AdbShellV2 parser=new AdbShellV2();
            parser.accept(Arrays.copyOf(response,length));
            IOException error=assertThrows(IOException.class,parser::requireSuccess);
            assertFalse(error.getMessage().contains("bootstrap-secret"));
        }
        for (int exit:new int[]{1,126,127,255}) {
            AdbShellV2 parser=new AdbShellV2();
            parser.accept(frame(3,new byte[]{(byte)exit}));
            assertThrows(IOException.class,parser::requireSuccess);
        }
    }

    @Test public void unknownChannelsInvalidLengthsAndTrailingDataFailClosed() throws Exception {
        for (int channel:new int[]{0,4,5,255}) {
            AdbShellV2 parser=new AdbShellV2();
            assertThrows(IOException.class,()->parser.accept(frame(channel,new byte[0])));
        }
        for (int length:new int[]{0,2,4,-1,Integer.MAX_VALUE}) {
            AdbShellV2 parser=new AdbShellV2();
            assertThrows(IOException.class,()->parser.accept(header(3,length)));
        }
        AdbShellV2 oversized=new AdbShellV2();
        assertThrows(IOException.class,()->oversized.accept(header(1,-1)));
        for (byte[] trailing:new byte[][]{frame(3,new byte[]{0}),frame(1,utf8("secret")),new byte[]{1}}) {
            AdbShellV2 parser=new AdbShellV2();
            parser.accept(frame(3,new byte[]{0}));
            assertThrows(IOException.class,()->parser.accept(trailing));
        }
    }

    @Test public void responseBudgetBoundsAllFramesTogether() throws Exception {
        byte[] stdout=frame(1,new byte[AdbShellV2.MAX_RESPONSE_BYTES-11]);
        AdbShellV2 exact=new AdbShellV2();
        exact.accept(stdout);exact.accept(frame(3,new byte[]{0}));exact.requireSuccess();
        AdbShellV2 over=new AdbShellV2();
        over.accept(stdout);over.accept(frame(1,new byte[0]));
        assertThrows(IOException.class,()->over.accept(frame(3,new byte[]{0})));
    }

    static byte[] frame(int channel,byte[] payload) {
        return concat(header(channel,payload.length),payload);
    }
    static byte[] concat(byte[]... pieces) {
        int length=0;for(byte[] piece:pieces)length+=piece.length;
        byte[] bytes=new byte[length];int offset=0;
        for(byte[] piece:pieces){System.arraycopy(piece,0,bytes,offset,piece.length);offset+=piece.length;}
        return bytes;
    }
    private static byte[] header(int channel,int length) {
        return ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).put((byte)channel).putInt(length).array();
    }
    private static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }
}
