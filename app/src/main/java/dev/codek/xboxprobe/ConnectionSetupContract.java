package dev.codek.xboxprobe;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import rikka.shizuku.Shizuku;

/** Explicit result contract; opening or completing setup never resumes a caller by itself. */
public final class ConnectionSetupContract {
    private static final String REQUEST_ID = "connection_setup_request_id";
    private static final String LABEL = "connection_setup_continue_label";

    private ConnectionSetupContract() {}

    public enum State {
        READY, STANDALONE_START_REQUIRED, SHIZUKU_START_REQUIRED, SHIZUKU_UPDATE_REQUIRED,
        SHIZUKU_ADB_REQUIRED, SHIZUKU_PERMISSION_REQUIRED, CHECK_FAILED
    }

    public static final class Readiness {
        public final ConnectionMode mode;
        public final State state;
        private Readiness(ConnectionMode mode, State state) { this.mode = mode; this.state = state; }
        public boolean isReady() { return state == State.READY; }
    }

    /** The service requires shell UID 2000; a running root Shizuku server is not ready. */
    public static Readiness readiness(Context context) {
        ConnectionMode mode = ConnectionMode.get(context);
        State state;
        try {
            if (mode == ConnectionMode.STANDALONE)
                state = StandaloneBackend.isAvailable() ? State.READY : State.STANDALONE_START_REQUIRED;
            else if (!Shizuku.pingBinder()) state = State.SHIZUKU_START_REQUIRED;
            else if (Shizuku.isPreV11()) state = State.SHIZUKU_UPDATE_REQUIRED;
            else if (Shizuku.getUid() != 2000) state = State.SHIZUKU_ADB_REQUIRED;
            else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)
                state = State.SHIZUKU_PERMISSION_REQUIRED;
            else state = State.READY;
        } catch (RuntimeException failure) { state = State.CHECK_FAILED; }
        return new Readiness(mode, state);
    }

    public static boolean isReady(Context context) { return readiness(context).isReady(); }

    /** A null/empty label opens management only. Labels are complete button text. */
    public static Intent intent(Activity caller, String continuationLabel, long requestId) {
        if (requestId < 0 || (continuationLabel != null && !continuationLabel.trim().isEmpty()
                && requestId == 0))
            throw new IllegalArgumentException("A continuation needs a positive request ID");
        return new Intent(caller, WirelessDebuggingActivity.class)
                .putExtra(REQUEST_ID, requestId).putExtra(LABEL, continuationLabel);
    }

    public static long requestId(Intent intent) {
        return intent == null ? 0 : Math.max(0, intent.getLongExtra(REQUEST_ID, 0));
    }

    static String continuationLabel(Intent intent) {
        String label = intent == null ? null : intent.getStringExtra(LABEL);
        return label == null ? "" : label.trim();
    }

    public static boolean accepted(int resultCode, Intent result, long expectedId) {
        return resultCode == Activity.RESULT_OK && expectedId > 0
                && requestId(result) == expectedId;
    }

    /** Back carries ownership too, so a stale cancellation cannot revoke a newer request. */
    public static Intent cancelledResult(long requestId) {
        return new Intent().putExtra(REQUEST_ID, Math.max(0, requestId));
    }

    public static void complete(Activity activity, long requestId) {
        if (requestId <= 0) throw new IllegalArgumentException("Request ID is missing");
        activity.setResult(Activity.RESULT_OK, new Intent().putExtra(REQUEST_ID, requestId));
        activity.finish();
    }
}
