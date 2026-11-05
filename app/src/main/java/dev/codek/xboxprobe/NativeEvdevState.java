package dev.codek.xboxprobe;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import dev.codek.xboxprobe.core.InputStateSnapshot;

/** State queries and optional exclusive input on the source reader's owned fd. */
final class NativeEvdevState {
    private static boolean loaded;
    private static String loadError = "Native input-state library has not been initialized";

    private NativeEvdevState() {}

    static synchronized void initialize(Context context) {
        if (loaded) return;
        try {
            System.loadLibrary("controllerbridge");
            loaded = true;
            loadError = "";
            return;
        } catch (LinkageError | SecurityException error) {
            loadError = error.getClass().getSimpleName() + ": " + error.getMessage();
        }
        if (context == null) return;
        try {
            // Shizuku's app_process class loader may not set a native search path.
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(BuildConfig.APPLICATION_ID, 0);
            if (info.nativeLibraryDir == null) throw new IOException("Application native library directory is missing");
            File library = new File(info.nativeLibraryDir, "libcontrollerbridge.so");
            if (!library.isFile()) throw new IOException("Extracted native input-state library is missing");
            System.load(library.getAbsolutePath());
            loaded = true;
            loadError = "";
        } catch (Exception | LinkageError error) {
            loadError = error.getClass().getSimpleName() + ": " + error.getMessage();
        }
    }

    static InputStateSnapshot readSnapshot(FileDescriptor source, int[] keys, int[] axes) throws IOException {
        requireLoaded();
        final int[] values;
        try {
            values = readState(source, keys, axes);
        } catch (LinkageError error) {
            throw new IOException("Native input-state reader is unavailable", error);
        }
        if (values == null || values.length != keys.length + axes.length) {
            throw new IOException("Native input-state reader returned an incomplete snapshot");
        }
        Map<Integer, Integer> keyValues = new LinkedHashMap<>();
        Map<Integer, Integer> axisValues = new LinkedHashMap<>();
        for (int i = 0; i < keys.length; i++) keyValues.put(keys[i], values[i]);
        for (int i = 0; i < axes.length; i++) axisValues.put(axes[i], values[keys.length + i]);
        return new InputStateSnapshot(keyValues, axisValues);
    }

    static void setExclusive(FileDescriptor source, boolean enabled) throws IOException {
        requireLoaded();
        try { setExclusiveState(source, enabled); }
        catch (LinkageError error) { throw new IOException("Native input exclusivity is unavailable", error); }
    }

    private static void requireLoaded() throws IOException {
        synchronized (NativeEvdevState.class) {
            if (!loaded) initialize(null);
            if (!loaded) throw new IOException("Cannot resynchronize selected input: " + loadError);
        }
    }

    private static native int[] readState(FileDescriptor source, int[] keys, int[] axes) throws IOException;
    private static native void setExclusiveState(FileDescriptor source, boolean enabled) throws IOException;
}
