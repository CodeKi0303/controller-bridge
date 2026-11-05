package dev.codek.xboxprobe;

import android.content.Context;

/** Selects who starts the shell service; input profiles are independent of this choice. */
public enum ConnectionMode {
    STANDALONE, SHIZUKU;

    public static ConnectionMode get(Context context) {
        String value = context.getSharedPreferences("bridge_connection", Context.MODE_PRIVATE)
                .getString("mode", STANDALONE.name());
        return SHIZUKU.name().equals(value) ? SHIZUKU : STANDALONE;
    }

    public static void set(Context context, ConnectionMode mode) {
        if (mode == null) throw new IllegalArgumentException("Connection mode is required");
        context.getSharedPreferences("bridge_connection", Context.MODE_PRIVATE)
                .edit().putString("mode", mode.name()).apply();
    }
}
