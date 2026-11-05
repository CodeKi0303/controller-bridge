package dev.codek.xboxprobe;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** One bounded shell-v2 response, independent of ADB WRTE packet boundaries.
 * AOSP shell_protocol.h uses an ID byte and uint32 little-endian payload length.
 * daemon/shell_service.cpp sends exit status as ONE unsigned byte. Output is
 * discarded immediately because it can contain the bootstrap credential. */
final class AdbShellV2 {
    static final int MAX_RESPONSE_BYTES=65536;
    private static final int STDOUT=1, STDERR=2, EXIT=3;
    private final byte[] header=new byte[5];
    private int headerBytes, remaining, frameId, receivedBytes;
    private int exitCode=-1;

    static byte[] command(byte[] serverBanner, String command) throws IOException {
        String banner=new String(serverBanner,StandardCharsets.UTF_8);
        if (banner.endsWith("\0")) banner=banner.substring(0,banner.length()-1);
        if (banner.indexOf('\0')>=0) throw new IOException("Invalid ADB connection banner");
        String[] parts=banner.split(":",-1);
        boolean featuresSeen=false,shellV2=false;
        if (parts.length>=3) {
            for (String property:parts[2].split(";",-1)) {
                int equal=property.indexOf('=');
                if (equal<0 || !property.substring(0,equal).equals("features")) continue;
                if (featuresSeen || property.indexOf('=',equal+1)>=0)
                    throw new IOException("Invalid ADB feature declaration");
                featuresSeen=true;
                for (String feature:property.substring(equal+1).split(",",-1))
                    if (feature.equals("shell_v2")) shellV2=true;
            }
        }
        if (!shellV2) throw new IOException("ADB shell_v2 is required for raw bootstrap execution");
        return ("shell,v2,raw:"+command+"\0").getBytes(StandardCharsets.UTF_8);
    }

    void accept(byte[] bytes) throws IOException {
        if (bytes.length>MAX_RESPONSE_BYTES-receivedBytes)
            throw new IOException("ADB shell response exceeded its output limit");
        receivedBytes+=bytes.length;
        int offset=0;
        while (offset<bytes.length) {
            if (exitCode>=0) throw new IOException("Unexpected data after ADB shell exit");
            if (headerBytes<header.length) {
                int count=Math.min(header.length-headerBytes,bytes.length-offset);
                System.arraycopy(bytes,offset,header,headerBytes,count);
                headerBytes+=count;offset+=count;
                if (headerBytes<header.length) continue;
                frameId=header[0]&255;
                long length=(header[1]&255L)|((header[2]&255L)<<8)|((header[3]&255L)<<16)|((header[4]&255L)<<24);
                if (frameId!=STDOUT && frameId!=STDERR && frameId!=EXIT)
                    throw new IOException("Unexpected ADB shell response channel");
                if (length>MAX_RESPONSE_BYTES-header.length || (frameId==EXIT && length!=1))
                    throw new IOException("Invalid or oversized ADB shell frame");
                remaining=(int)length;
                if (remaining==0) { headerBytes=0;continue; }
            }
            int count=Math.min(remaining,bytes.length-offset);
            if (count>0 && frameId==EXIT) exitCode=bytes[offset]&255;
            // Stdout/stderr bytes are deliberately neither decoded nor retained.
            offset+=count;remaining-=count;
            if (remaining==0) headerBytes=0;
        }
    }

    void requireSuccess() throws IOException {
        if (headerBytes!=0 || remaining!=0 || exitCode<0)
            throw new IOException("Incomplete ADB shell response or missing exit status");
        if (exitCode!=0) throw new IOException("ADB bootstrap shell exited unsuccessfully");
    }
}
