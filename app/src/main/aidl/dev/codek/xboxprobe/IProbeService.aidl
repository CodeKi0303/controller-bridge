package dev.codek.xboxprobe;

/** Bounded, user-triggered HID probe only. No touchscreen injection API. */
interface IProbeService {
    String create(String name, int vendorId, int productId, int bus) = 0;
    String schedulePulse(int kind, int delayMs) = 1;
    String status() = 2;
    void keepAlive() = 3;
    String stop() = 4;
    String createKeyboard() = 5;
    String scheduleKeyboardPulse(int hidUsage, int delayMs) = 6;
    String listSources() = 7;
    String startBridge(String sourceToken, String mappingsJson) = 8;
    String createGamepadProbe(int profileId) = 9;
    String startGamepadBridge(String sourceToken, int profileId) = 10;
    String startConfiguredBridge(String sourceToken, String profileJson) = 11;
    String createConfiguredProbe(String profileJson) = 12;
    String cancelPendingPulse() = 13;
    String verifyConfiguredOutputLayout() = 14;
    void destroy() = 16777114;
}
