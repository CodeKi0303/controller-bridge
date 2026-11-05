package dev.codek.xboxprobe.core;

import java.util.Objects;

/**
 * Standard HID gamepad identity and report encoder.
 * NativeGamepadLayout selects the button and axis usages for each output identity.
 * Reports include two sticks, two triggers, a directional hat and gamepad buttons.
 * HID item semantics follow USB HID 1.11 and the Generic Desktop, Simulation
 * Controls and Button usage pages.
 * https://www.usb.org/sites/default/files/documents/hid1_11.pdf
 */
public final class NativeGamepadProfile {
    public static final int REPORT_SIZE = 9;

    public static final NativeGamepadProfile KISHI_IDENTITY = new NativeGamepadProfile(
            "kishi-identity", "Razer Kishi Ultra", 0x1532, 0x071a);
    public static final NativeGamepadProfile KISHI_ULTRA_IDENTITY = new NativeGamepadProfile(
            "kishi-ultra-name", "Razer Razer Kishi Ultra", 0x1532, 0x071a);
    public static final NativeGamepadProfile G8_IDENTITY = new NativeGamepadProfile(
            "g8-identity", "GAMESIR GameSir-G8", 0x3537, 0x0108);
    public static final int G8_PROBE_ID = 0;
    public static final int KISHI_CANDIDATE_PROBE_ID = 1;
    public static final int KISHI_ULTRA_PROBE_ID = 2;
    private final String id;
    private final HidIdentity identity;
    private final NativeGamepadLayout layout;

    private NativeGamepadProfile(String id,String name,int vendorId,int productId) {
        this(id,new HidIdentity(name,vendorId,productId,3));
    }
    public NativeGamepadProfile(String id,String name,int vendorId,int productId,int bus){this(id,new HidIdentity(name,vendorId,productId,bus));}
    public NativeGamepadProfile(String id,HidIdentity identity){this.id=Objects.requireNonNull(id,"id");this.identity=Objects.requireNonNull(identity,"identity");this.layout=NativeGamepadLayout.forIdentity(identity);}
    public NativeGamepadProfile withIdentity(String name,int vendorId,int productId,int bus){return new NativeGamepadProfile(id,name,vendorId,productId,bus);}
    public String getId() { return id; }
    public String getName() { return identity.getName(); }
    public int getVendorId() { return identity.getVendorId(); }
    public int getProductId() { return identity.getProductId(); }
    public int getBus() { return identity.getBus(); }
    public NativeGamepadLayout getLayout() { return layout; }
    public byte[] getDescriptor() { return layout.getDescriptor(); }
    public byte[] neutralReport() { return encode(NativeGamepadState.NEUTRAL); }

    /** Stable diagnostic IDs; custom profiles are selected through their own JSON API. */
    public static NativeGamepadProfile forProbeId(int profileId) {
        switch (profileId) {
            case G8_PROBE_ID: return G8_IDENTITY;
            case KISHI_CANDIDATE_PROBE_ID: return KISHI_IDENTITY;
            case KISHI_ULTRA_PROBE_ID: return KISHI_ULTRA_IDENTITY;
            default: throw new IllegalArgumentException("Unknown gamepad profile: use 0=G8, 1=previous Kishi name, or 2=Kishi Ultra name.");
        }
    }

    public int getProbeId() {
        if (this == G8_IDENTITY) return G8_PROBE_ID;
        if (this == KISHI_IDENTITY) return KISHI_CANDIDATE_PROBE_ID;
        if (this == KISHI_ULTRA_IDENTITY) return KISHI_ULTRA_PROBE_ID;
        throw new IllegalStateException("Custom identities do not have a diagnostic profile ID");
    }

    /** Unnumbered report: no report-ID byte is prepended. */
    public byte[] encode(NativeGamepadState state) {
        Objects.requireNonNull(state,"state");
        int outputButtons=layout.encodeButtons(state.buttons);
        return new byte[] {(byte)state.leftX,(byte)state.leftY,(byte)state.rightX,(byte)state.rightY,
                (byte)state.leftTrigger,(byte)state.rightTrigger,(byte)(state.hat<0?8:state.hat),
                (byte)outputButtons,(byte)(outputButtons>>>8)};
    }
}
