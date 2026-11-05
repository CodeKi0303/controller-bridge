package dev.codek.xboxprobe.core;

import java.util.Objects;

/** An explicit frame result; failures include the selected output's neutral report. */
public final class MappingUpdate {
    public enum Failure {
        SYN_DROPPED, SOURCE_LOST, INVALID_KEY_VALUE, AXIS_OUT_OF_RANGE, CAPACITY_EXCEEDED,
        SOURCE_CAPABILITY_CHANGED
    }
    private static final MappingUpdate NONE = new MappingUpdate(null, null, "");
    private final byte[] report;
    private final Failure failure;
    private final String error;

    private MappingUpdate(byte[] report, Failure failure, String error) {
        this.report = report == null ? null : report.clone();
        this.failure = failure;
        this.error = error;
    }

    static MappingUpdate none() { return NONE; }
    static MappingUpdate report(byte[] report) { return new MappingUpdate(report, null, ""); }
    static MappingUpdate failed(Failure failure, String error, byte[] neutralReport) {
        Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(error, "error");
        Objects.requireNonNull(neutralReport, "neutralReport");
        if (neutralReport.length == 0) throw new IllegalArgumentException("Neutral report must not be empty");
        return new MappingUpdate(neutralReport, failure, error);
    }
    public byte[] getReport() { return report == null ? null : report.clone(); }
    public boolean isFailed() { return failure != null; }
    public Failure getFailure() { return failure; }
    public String getError() { return error; }
}
