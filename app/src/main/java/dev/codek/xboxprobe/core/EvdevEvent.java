package dev.codek.xboxprobe.core;

/** One decoded Linux input_event. Timestamps are retained, not used for key timing. */
public final class EvdevEvent {
    public static final int EV_SYN = 0;
    public static final int EV_KEY = 1;
    public static final int EV_ABS = 3;
    public static final int SYN_REPORT = 0;
    public static final int SYN_DROPPED = 3;

    public final long seconds;
    public final long micros;
    public final int type;
    public final int code;
    public final int value;

    public EvdevEvent(int type, int code, int value) { this(0, 0, type, code, value); }

    public EvdevEvent(long seconds, long micros, int type, int code, int value) {
        if (type < 0 || type > 0xffff || code < 0 || code > 0xffff) {
            throw new IllegalArgumentException("Event type/code must fit unsigned 16 bits");
        }
        this.seconds = seconds;
        this.micros = micros;
        this.type = type;
        this.code = code;
        this.value = value;
    }
}
