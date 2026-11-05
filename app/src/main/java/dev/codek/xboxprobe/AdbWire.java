package dev.codek.xboxprobe;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Bounded ADB framing for one authenticated bootstrap shell stream.
 * Wire constants: AOSP packages/modules/adb/docs/dev/protocol.md. */
final class AdbWire {
    static final int CNXN=0x4e584e43, STLS=0x534c5453, AUTH=0x48545541,
            OPEN=0x4e45504f, OKAY=0x59414b4f, WRTE=0x45545257, CLSE=0x45534c43;
    static final int VERSION=0x01000001, TLS_VERSION=0x01000000, MAX_PAYLOAD=65536;
    static final class Packet {
        final int command, arg0, arg1;
        final byte[] payload;
        Packet(int command, int arg0, int arg1, byte[] payload) {
            this.command=command; this.arg0=arg0; this.arg1=arg1; this.payload=payload;
        }
    }
    private AdbWire() {}
    static void requireTlsUpgrade(Packet packet) throws IOException {
        if(packet.command!=STLS||packet.arg0!=TLS_VERSION||packet.payload.length!=0)
            throw new IOException("Wireless debugging TLS upgrade required; legacy ADB is not accepted");
    }
    static Packet read(InputStream in) throws IOException {
        DataInputStream source = new DataInputStream(in);
        byte[] header = new byte[24]; source.readFully(header);
        ByteBuffer bytes = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int command=bytes.getInt(), arg0=bytes.getInt(), arg1=bytes.getInt(), length=bytes.getInt();
        int checksum=bytes.getInt(), magic=bytes.getInt();
        if (magic != (command ^ -1) || length < 0 || length > MAX_PAYLOAD)
            throw new IOException("Invalid or oversized ADB packet");
        if (command!=CNXN && command!=STLS && command!=AUTH && command!=OKAY && command!=WRTE && command!=CLSE)
            throw new IOException("Unexpected ADB packet type");
        byte[] payload = new byte[length]; source.readFully(payload);
        // ADB version 0x01000001 permits a zero checksum. Validate one if sent.
        if (checksum != 0 && checksum != checksum(payload)) throw new IOException("ADB packet checksum mismatch");
        return new Packet(command, arg0, arg1, payload);
    }
    static void write(OutputStream out, int command, int arg0, int arg1, byte[] payload) throws IOException {
        if (payload.length > MAX_PAYLOAD) throw new IOException("ADB output exceeds advertised payload limit");
        byte[] header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(command).putInt(arg0).putInt(arg1).putInt(payload.length)
                .putInt(checksum(payload)).putInt(command ^ -1).array();
        out.write(header); out.write(payload); out.flush();
    }
    private static int checksum(byte[] bytes) { int sum=0; for(byte b:bytes) sum+=b&255; return sum; }
}
