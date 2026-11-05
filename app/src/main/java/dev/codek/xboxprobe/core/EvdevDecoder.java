package dev.codek.xboxprobe.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Streaming Android little-endian evdev ABI decoder. The owner must select 24
 * bytes for a 64-bit process or 16 bytes for a 32-bit process; data never selects
 * its own ABI. Only one incomplete event (at most 23 bytes) is retained.
 * https://github.com/torvalds/linux/blob/v6.12/include/uapi/linux/input.h
 */
public final class EvdevDecoder {
    @FunctionalInterface
    public interface EventConsumer { void accept(EvdevEvent event) throws IOException; }

    private final int eventSize;
    private final byte[] pending;
    private int pendingBytes;

    public EvdevDecoder(int eventSize) {
        if (eventSize != 16 && eventSize != 24) {
            throw new IllegalArgumentException("Linux input_event size must be 16 or 24 bytes");
        }
        this.eventSize = eventSize;
        this.pending = new byte[eventSize];
    }

    public int getEventSize() { return eventSize; }
    public int getPendingByteCount() { return pendingBytes; }

    public void accept(byte[] bytes, int offset, int length, EventConsumer consumer) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(consumer, "consumer");
        if (offset < 0 || length < 0 || offset > bytes.length - length) {
            throw new IndexOutOfBoundsException("Invalid evdev byte range");
        }
        int end = offset + length;
        if (pendingBytes != 0) {
            int copied = Math.min(eventSize - pendingBytes, length);
            System.arraycopy(bytes, offset, pending, pendingBytes, copied);
            offset += copied;
            pendingBytes += copied;
            if (pendingBytes == eventSize) {
                pendingBytes = 0;
                consumer.accept(decode(pending, 0));
            }
        }
        while (end - offset >= eventSize) {
            consumer.accept(decode(bytes, offset));
            offset += eventSize;
        }
        if (offset < end) {
            pendingBytes = end - offset;
            System.arraycopy(bytes, offset, pending, 0, pendingBytes);
        }
    }

    /** EOF must not turn a partial kernel event into a silently missing release. */
    public void finish() throws IOException {
        if (pendingBytes != 0) {
            throw new IOException("Truncated evdev event at EOF: " + pendingBytes + "/" + eventSize + " bytes");
        }
    }

    private EvdevEvent decode(byte[] bytes, int offset) {
        ByteBuffer b = ByteBuffer.wrap(bytes, offset, eventSize).order(ByteOrder.LITTLE_ENDIAN);
        long seconds = eventSize == 24 ? b.getLong() : b.getInt();
        long micros = eventSize == 24 ? b.getLong() : b.getInt();
        return new EvdevEvent(seconds, micros, b.getShort() & 0xffff, b.getShort() & 0xffff, b.getInt());
    }
}
