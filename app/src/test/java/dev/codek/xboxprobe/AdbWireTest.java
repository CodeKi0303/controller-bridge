package dev.codek.xboxprobe;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;

public final class AdbWireTest {
    @Test public void fragmentedPacketsPreserveStreamIdentityAndPayload()throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        byte[] payload={0,1,2,(byte)255,4};AdbWire.write(out,AdbWire.WRTE,71,1,payload);
        InputStream split=new FilterInputStream(new ByteArrayInputStream(out.toByteArray())){
            @Override public int read(byte[] b,int offset,int count)throws IOException{return super.read(b,offset,Math.min(count,3));}
        };
        AdbWire.Packet packet=AdbWire.read(split);
        assertEquals(AdbWire.WRTE,packet.command);assertEquals(71,packet.arg0);assertEquals(1,packet.arg1);assertArrayEquals(payload,packet.payload);
    }
    @Test public void malformedHeaderFailsBeforeAllocatingOrReadingPayload()throws Exception{
        for(int length:new int[]{-1,AdbWire.MAX_PAYLOAD+1,Integer.MAX_VALUE}){
            byte[] header=header(AdbWire.WRTE,length,AdbWire.WRTE^-1);
            assertThrows(IOException.class,()->AdbWire.read(new ByteArrayInputStream(header)));
        }
        assertThrows(IOException.class,()->AdbWire.read(new ByteArrayInputStream(header(AdbWire.WRTE,0,0))));
        assertThrows(IOException.class,()->AdbWire.read(new ByteArrayInputStream(header(0x12345678,0,0x12345678^-1))));
    }
    @Test public void truncatedAndCorruptedPayloadAreNotAccepted()throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();AdbWire.write(out,AdbWire.WRTE,1,2,new byte[]{3,4,5});byte[] bytes=out.toByteArray();
        assertThrows(EOFException.class,()->AdbWire.read(new ByteArrayInputStream(java.util.Arrays.copyOf(bytes,bytes.length-1))));
        bytes[25]^=1;assertThrows(IOException.class,()->AdbWire.read(new ByteArrayInputStream(bytes)));
    }
    @Test public void oldAdbAndPlaintextConnectedRepliesCannotBypassTls()throws Exception{
        AdbWire.requireTlsUpgrade(new AdbWire.Packet(AdbWire.STLS,AdbWire.TLS_VERSION,0,new byte[0]));
        for(int command:new int[]{AdbWire.AUTH,AdbWire.CNXN,AdbWire.OKAY})
            assertThrows(IOException.class,()->AdbWire.requireTlsUpgrade(new AdbWire.Packet(command,AdbWire.TLS_VERSION,0,new byte[0])));
        assertThrows(IOException.class,()->AdbWire.requireTlsUpgrade(new AdbWire.Packet(AdbWire.STLS,0,0,new byte[0])));
    }
    private static byte[] header(int command,int length,int magic){return ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(command).putInt(1).putInt(2).putInt(length).putInt(0).putInt(magic).array();}
}
