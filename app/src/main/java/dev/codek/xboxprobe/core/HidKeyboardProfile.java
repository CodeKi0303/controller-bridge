package dev.codek.xboxprobe.core;

import java.util.Objects;

/** Standard keyboard report layout with independently selected, validated identity. */
public final class HidKeyboardProfile {
    public static final HidKeyboardProfile INSTANCE = new HidKeyboardProfile("G8 Keyboard Probe",0,0,3);

    /**
     * USB HID 1.11 Appendix B.1 boot-compatible keyboard report layout:
     * https://www.usb.org/sites/default/files/documents/hid1_11.pdf
     * 8 modifier bits, one reserved byte, six 8-bit key usages; no report ID.
     * The one-byte output report contains LED flags and padding.
     * UHID supplies a report descriptor, not a real USB boot interface.
     */
    private static final byte[] DESCRIPTOR = bytes(
            "05 01 09 06 a1 01 05 07 19 e0 29 e7 15 00 25 01 75 01 95 08 " +
            "81 02 95 01 75 08 81 01 95 05 75 01 05 08 19 01 29 05 91 02 " +
            "95 01 75 03 91 01 95 06 75 08 15 00 25 65 05 07 19 00 29 65 " +
            "81 00 c0");

    private final HidIdentity identity;
    public HidKeyboardProfile(String name,int vendorId,int productId,int bus){this(new HidIdentity(name,vendorId,productId,bus));}
    public HidKeyboardProfile(HidIdentity identity){this.identity=Objects.requireNonNull(identity,"identity");}
    public HidKeyboardProfile withIdentity(String name,int vendorId,int productId,int bus){return new HidKeyboardProfile(name,vendorId,productId,bus);}
    public String getName() { return identity.getName(); }
    public int getVendorId() { return identity.getVendorId(); }
    public int getProductId() { return identity.getProductId(); }
    public int getBus() { return identity.getBus(); }
    public byte[] getDescriptor(){return keyboardDescriptor();}
    public static byte[] keyboardDescriptor() { return DESCRIPTOR.clone(); }

    private static byte[] bytes(String hex) {
        String[] items = hex.split(" ");
        byte[] result = new byte[items.length];
        for (int i = 0; i < items.length; i++) result[i] = (byte) Integer.parseInt(items[i], 16);
        return result;
    }
}
