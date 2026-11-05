package dev.codek.xboxprobe.core;

import java.util.Objects;

/**
 * Owns the conversion from canonical controls to the Linux HID usages expected by
 * an output identity's Android key layout. Source mappings remain canonical.
 *
 * These are authored nine-byte HID reports, not Sony controller protocols. Sony
 * identities require the matching Android key layout to be verified by the caller
 * before starting input; a vendor key layout can differ between Android builds.
 * In particular, the DualSense layout requires CONFIG_HID_PLAYSTATION in AOSP.
 *
 * Mapping provenance: AOSP frameworks/base/data/keyboards, main blobs
 * Vendor_054c_Product_05c4.kl / Vendor_054c_Product_09cc.kl
 * c8b4fc363f9f71a2859cb5355351f9be4ca6d8f0 and
 * Vendor_054c_Product_0ce6.kl 411dd9521921df0609cf485697ba8c68124e4f4c.
 */
public enum NativeGamepadLayout {
    GENERIC(
            "09 30 09 31 09 32 09 35", "05 02 09 c5 09 c4",
            new int[]{304,305,306,307,308,309,310,311,312,313,314,315,316,317,318}),
    SONY_PS4_LEGACY(
            "09 30 09 31 09 32 09 35", "05 01 09 33 09 34",
            new int[]{305,306,-1,304,307,-1,308,309,310,311,312,313,316,314,315}),
    SONY_PS5(
            "09 30 09 31 09 33 09 34", "05 01 09 32 09 35",
            new int[]{304,305,-1,308,307,-1,310,311,312,313,314,315,316,317,318});

    private final byte[] descriptor;
    private final int[] outputButtonCodes;
    private final int supportedButtons;

    NativeGamepadLayout(String stickUsages,String triggerUsages,int[] outputButtonCodes) {
        this.outputButtonCodes=outputButtonCodes;
        int supported=0;
        for(int i=0;i<outputButtonCodes.length;i++)if(outputButtonCodes[i]>=0)supported|=1<<i;
        supportedButtons=supported;
        descriptor=bytes(
                // Four signed stick bytes retain their canonical LX/LY/RX/RY order.
                "05 01 09 05 a1 01 "+stickUsages+" 15 81 25 7f 75 08 95 04 81 02 "+
                // Two unsigned trigger bytes retain their canonical LT/RT order.
                triggerUsages+" 15 00 26 ff 00 75 08 95 02 81 02 "+
                // Hat 0..7, with out-of-range 8 as the null/centered state.
                "05 01 09 39 15 00 25 07 35 00 46 3b 01 65 14 75 08 95 01 81 42 "+
                // Generic Linux HID maps these button usages to scan codes 304..318.
                "65 00 35 00 45 00 05 09 19 01 29 0f 15 00 25 01 75 01 95 0f 81 02 "+
                "75 01 95 01 81 03 c0");
    }

    /** Select by the actual output VID/PID, never by a preset ID or display name. */
    public static NativeGamepadLayout forIdentity(HidIdentity identity) {
        Objects.requireNonNull(identity,"identity");
        if(identity.getVendorId()!=0x054c)return GENERIC;
        switch(identity.getProductId()) {
            case 0x05c4:
            case 0x09cc:return SONY_PS4_LEGACY;
            case 0x0ce6:return SONY_PS5;
            default:return GENERIC;
        }
    }

    public byte[] getDescriptor(){return descriptor.clone();}
    public int getSupportedButtons(){return supportedButtons;}

    /** Whether a canonical Linux-style semantic button code can retain its meaning. */
    public boolean supportsButton(int canonicalCode) {
        return canonicalCode>=304&&canonicalCode<=318&&outputButtonCodes[canonicalCode-304]>=0;
    }

    /** Output evdev scan code, or -1 when the selected Android layout has no such key. */
    public int getOutputButtonCode(int canonicalCode) {
        if(canonicalCode<304||canonicalCode>318)throw new IllegalArgumentException("Canonical button must be 304..318");
        return outputButtonCodes[canonicalCode-304];
    }

    /** Permute a complete frame; releasing one source cannot release another held key. */
    public int encodeButtons(int canonicalMask) {
        if((canonicalMask&~NativeGamepadState.SUPPORTED_BUTTONS)!=0)
            throw new IllegalArgumentException("Unsupported native button bits");
        if((canonicalMask&~supportedButtons)!=0)
            throw new IllegalArgumentException(name()+" does not support C/Z mapping targets; remap to A/B/X/Y or disable those controls");
        int result=0;
        for(int bit=0;bit<outputButtonCodes.length;bit++)
            if((canonicalMask&(1<<bit))!=0)result|=1<<(outputButtonCodes[bit]-304);
        return result;
    }

    private static byte[] bytes(String hex) {
        String[] values=hex.split(" ");byte[] result=new byte[values.length];
        for(int i=0;i<values.length;i++)result[i]=(byte)Integer.parseInt(values[i],16);
        return result;
    }
}
