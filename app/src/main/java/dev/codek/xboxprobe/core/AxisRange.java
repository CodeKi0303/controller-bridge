package dev.codek.xboxprobe.core;

/** Actual evdev ABS capability values supplied by the selected device's owner. */
public final class AxisRange {
    public final int min;
    public final int max;
    /** Preserved capability fact; this class does not impose a dead zone. */
    public final int flat;

    public AxisRange(int min, int max, int flat) {
        if (min >= max || flat < 0 || flat > (long) max - min) {
            throw new IllegalArgumentException("Invalid evdev axis range or flat value");
        }
        this.min = min;
        this.max = max;
        this.flat = flat;
    }

    public boolean contains(int value) { return value >= min && value <= max; }

    /** Midpoint and both extrema are derived using doubles to avoid int overflow. */
    public double normalize(int value) {
        if (!contains(value)) throw new IllegalArgumentException("Axis value is outside advertised range");
        double center = ((double) min + max) / 2;
        return (value - center) / (value < center ? center - min : max - center);
    }

    /** Maps actual advertised endpoints to 0..1 for unipolar controls such as triggers. */
    public double normalizeUnit(int value) {
        if (!contains(value)) throw new IllegalArgumentException("Axis value is outside advertised range");
        return ((double) value - min) / ((double) max - min);
    }
}
