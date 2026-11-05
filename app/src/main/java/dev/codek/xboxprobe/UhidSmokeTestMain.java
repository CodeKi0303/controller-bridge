package dev.codek.xboxprobe;

import android.os.SystemClock;

import org.json.JSONObject;

import dev.codek.xboxprobe.core.HidProfile;

/**
 * Explicit adb/app_process smoke test of the exact production UHID service.
 * No pressed buttons, stick movement, touchscreen events, or game interaction.
 * The device is always stopped before this dedicated shell process exits.
 *
 * adb shell CLASSPATH=/path/to/base.apk app_process / dev.codek.xboxprobe.UhidSmokeTestMain
 */
public final class UhidSmokeTestMain {
    private UhidSmokeTestMain() {}

    public static void main(String[] args) {
        boolean keyboard = args.length == 1 && "--keyboard".equals(args[0]);
        int gamepadProfile = args.length == 1 && "--gamepad-g8".equals(args[0]) ? 0
                : args.length == 1 && "--gamepad-kishi".equals(args[0]) ? 1 : -1;
        if (args.length != 0 && !keyboard && gamepadProfile < 0) {
            System.err.println("Usage: UhidSmokeTestMain [--keyboard|--gamepad-g8|--gamepad-kishi]. The default is legacy Xbox; all modes send only neutral input.");
            System.exit(2);
            return;
        }
        ProbeUserService service = new ProbeUserService();
        boolean successful = false;
        try {
            if (keyboard) {
                System.out.println(service.createKeyboard());
            } else if (gamepadProfile >= 0) {
                System.out.println(service.createGamepadProbe(gamepadProfile));
            } else {
                HidProfile profile = HidProfile.XBOX_ONE_S;
                System.out.println(service.create(profile.getName(), profile.getVendorId(),
                        profile.getProductId(), profile.getBus()));
            }
            SystemClock.sleep(2000);
            String result = service.status();
            System.out.println(result);
            JSONObject status = new JSONObject(result);
            successful = status.optBoolean("kernelStarted") && status.optBoolean("consumerOpen")
                    && status.optString("lastError").isEmpty();
        } catch (Exception e) {
            System.err.println(e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            try {
                String stopped = service.stop();
                System.out.println(stopped);
                if (new JSONObject(stopped).optBoolean("deviceOpened")) successful = false;
                // Exercise the exact service admission boundary after terminal
                // STOP. A late Binder-style call must never reopen an input fd.
                JSONObject rejected = new JSONObject(gamepadProfile >= 0
                        ? service.createGamepadProbe(gamepadProfile) : service.createKeyboard());
                boolean rejectedAfterStop = !rejected.optBoolean("deviceOpened")
                        && rejected.optBoolean("sessionClosing")
                        && !rejected.optString("lastError").isEmpty();
                System.out.println(new JSONObject().put("check", "reject_create_after_stop")
                        .put("passed", rejectedAfterStop).put("result", rejected).toString());
                if (!rejectedAfterStop) {
                    successful = false;
                    System.out.println(new JSONObject().put("check", "cleanup_after_failed_stop_regression")
                            .put("result", new JSONObject(service.stop())).toString());
                }
            } catch (Exception e) {
                successful = false;
                System.err.println("Cleanup failed: " + e.getMessage());
            }
        }
        // No persistent Binder service is registered by this entry point.
        System.exit(successful ? 0 : 1);
    }
}
