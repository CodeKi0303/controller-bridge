package dev.codek.xboxprobe.core;

import java.util.Objects;

/**
 * One explicitly selected legacy Xbox Bluetooth report layout, with editable identity.
 * Changing VID/PID can select a different Android key layout; it does not translate
 * this descriptor or create a bonded Bluetooth device.
 */
public final class HidProfile {
    public static final int BUS_BLUETOOTH = 0x05;
    public static final int BUS_USB = 0x03;

    /**
     * Xbox One S Windows-mode descriptor from xpadneo, excluding the
     * source data's trailing 00 (not a HID item). Source protocol data:
     * https://github.com/atar-axis/xpadneo/blob/7213c9a4a67c4f41be4179d1f28602c221a01adb/docs/descriptors/xb1s_windows.md
     * The source's textual 65534 annotation is incorrect: 27 ff ff 00 00 is 65535.
     * The source uses PID 02fd; this profile uses PID 02e0 to match
     * Android's legacy 10-button/Rx-Ry/Z-Rz layout, documented in AOSP:
     * https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/data/keyboards/Vendor_045e_Product_02e0.kl
     * This is a compatibility profile, not a claim to reproduce the user's controller.
     * ID 1=input; ID 2=guide; ID 3=rumble output; ID 4=battery. This encoder sends only
     * ID 1 and does not implement rumble, guide, or battery telemetry.
     */
    private static final byte[] XBOX_DESCRIPTOR = parseHex(
            "05 01 09 05 a1 01 85 01 09 01 a1 00 09 30 09 31 15 00 27 ff " +
            "ff 00 00 95 02 75 10 81 02 c0 09 01 a1 00 09 33 09 34 15 00 " +
            "27 ff ff 00 00 95 02 75 10 81 02 c0 05 01 09 32 15 00 26 ff " +
            "03 95 01 75 0a 81 02 15 00 25 00 75 06 95 01 81 03 05 01 09 " +
            "35 15 00 26 ff 03 95 01 75 0a 81 02 15 00 25 00 75 06 95 01 " +
            "81 03 05 01 09 39 15 01 25 08 35 00 46 3b 01 66 14 00 75 04 " +
            "95 01 81 42 75 04 95 01 15 00 25 00 35 00 45 00 65 00 81 03 " +
            "05 09 19 01 29 0a 15 00 25 01 75 01 95 0a 81 02 15 00 25 00 " +
            "75 06 95 01 81 03 05 01 09 80 85 02 a1 00 09 85 15 00 25 01 " +
            "95 01 75 01 81 02 15 00 25 00 75 07 95 01 81 03 c0 05 0f 09 " +
            "21 85 03 a1 02 09 97 15 00 25 01 75 04 95 01 91 02 15 00 25 " +
            "00 75 04 95 01 91 03 09 70 15 00 25 64 75 08 95 04 91 02 09 " +
            "50 66 01 10 55 0e 15 00 26 ff 00 75 08 95 01 91 02 09 a7 15 " +
            "00 26 ff 00 75 08 95 01 91 02 65 00 55 00 09 7c 15 00 26 ff " +
            "00 75 08 95 01 91 02 c0 85 04 05 06 09 20 15 00 26 ff 00 75 " +
            "08 95 01 81 02 c0");

    public static final HidProfile XBOX_ONE_S = new HidProfile(
            "Xbox Wireless Controller", 0x045e, 0x02e0, BUS_BLUETOOTH);

    private final HidIdentity identity;

    public HidProfile(String name, int vendorId, int productId, int bus) {
        this(new HidIdentity(name,vendorId,productId,bus));
    }
    public HidProfile(HidIdentity identity){this.identity=Objects.requireNonNull(identity,"identity");}

    public HidProfile withIdentity(String name, int vendorId, int productId, int bus) {
        return new HidProfile(name, vendorId, productId, bus);
    }

    public String getName() { return identity.getName(); }
    public int getVendorId() { return identity.getVendorId(); }
    public int getProductId() { return identity.getProductId(); }
    public int getBus() { return identity.getBus(); }
    public byte[] getDescriptor() { return XBOX_DESCRIPTOR.clone(); }
    public byte[] descriptor() { return getDescriptor(); }
    public byte[] encode(GamepadState state) { return XboxReportEncoder.encode(state); }

    private static byte[] parseHex(String value) {
        String[] parts = value.split(" ");
        byte[] bytes = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) {
            bytes[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return bytes;
    }
}
