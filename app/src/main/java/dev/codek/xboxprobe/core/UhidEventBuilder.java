package dev.codek.xboxprobe.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Packed Linux UHID UAPI, little-endian for the target Android arm64 device.
 * https://github.com/torvalds/linux/blob/v6.12/include/uapi/linux/uhid.h
 * https://github.com/torvalds/linux/blob/v6.12/include/uapi/linux/hid.h
 * create2 = 276 + HID_MAX_DESCRIPTOR_SIZE; event = 4 + create2 = 4376 bytes.
 * No Java object layout, native pointer, or legacy UHID_CREATE is used.
 */
public final class UhidEventBuilder {
    public static final int MAX_DESCRIPTOR_SIZE = 4096;
    public static final int MAX_REPORT_SIZE = 4096;
    public static final int EVENT_SIZE = 4376;
    public static final int UHID_DESTROY = 1;
    public static final int UHID_START = 2;
    public static final int UHID_STOP = 3;
    public static final int UHID_OPEN = 4;
    public static final int UHID_CLOSE = 5;
    public static final int UHID_OUTPUT = 6;
    public static final int UHID_GET_REPORT = 9;
    public static final int UHID_GET_REPORT_REPLY = 10;
    public static final int UHID_CREATE2 = 11;
    public static final int UHID_INPUT2 = 12;
    public static final int UHID_SET_REPORT = 13;
    public static final int UHID_SET_REPORT_REPLY = 14;

    private UhidEventBuilder() {}

    public static byte[] create2(HidProfile profile) {
        Objects.requireNonNull(profile, "profile");
        return create2(profile, profile.getDescriptor());
    }

    public static byte[] create2(HidProfile profile, String ownerLocation) {
        Objects.requireNonNull(profile, "profile");
        return create(profile.getName(), profile.getVendorId(), profile.getProductId(),
                profile.getBus(), profile.getDescriptor(), ownerLocation);
    }

    /** Raw descriptor overload for boundary tests or a separately owned HID profile. */
    public static byte[] create2(HidProfile identity, byte[] descriptor) {
        Objects.requireNonNull(identity, "identity");
        return create(identity.getName(), identity.getVendorId(), identity.getProductId(),
                identity.getBus(), descriptor, "");
    }

    public static byte[] createKeyboard(HidKeyboardProfile profile) {
        return createKeyboard(profile, "");
    }

    public static byte[] createKeyboard(HidKeyboardProfile profile, String ownerLocation) {
        Objects.requireNonNull(profile, "profile");
        return create(profile.getName(), profile.getVendorId(), profile.getProductId(),
                profile.getBus(), HidKeyboardProfile.keyboardDescriptor(), ownerLocation);
    }

    public static byte[] createGamepad(NativeGamepadProfile profile) {
        return createGamepad(profile, "");
    }

    public static byte[] createGamepad(NativeGamepadProfile profile, String ownerLocation) {
        Objects.requireNonNull(profile, "profile");
        return create(profile.getName(), profile.getVendorId(), profile.getProductId(),
                profile.getBus(), profile.getDescriptor(), ownerLocation);
    }

    public static byte[] createConfigured(ControllerProfile profile,String ownerLocation) {
        Objects.requireNonNull(profile,"profile");
        return create(profile.getDeviceName(),profile.getVendorId(),profile.getProductId(),
                profile.getBus(),profile.getDescriptor(),ownerLocation);
    }

    private static byte[] create(String name, int vendor, int product, int bus, byte[] descriptor,
                                  String ownerLocation) {
        checkBytes(descriptor, 1, MAX_DESCRIPTOR_SIZE, "descriptor");
        Objects.requireNonNull(ownerLocation, "ownerLocation");
        byte[] physical = ownerLocation.getBytes(StandardCharsets.UTF_8);
        if (ownerLocation.indexOf('\0') >= 0 || physical.length > 63) {
            throw new IllegalArgumentException("Owner location must be NUL-free and at most 63 UTF-8 bytes");
        }
        ByteBuffer event = event(UHID_CREATE2);
        event.put(name.getBytes(StandardCharsets.UTF_8));
        event.position(132); // after type[4] and name[128]
        event.put(physical); // phys[64]; remaining bytes and uniq[64] stay zero
        event.position(260);
        event.putShort((short) descriptor.length);
        event.putShort((short) bus);
        event.putInt(vendor);
        event.putInt(product);
        event.putInt(0); // version unknown: do not invent the user's firmware version
        event.putInt(0); // country unspecified
        event.put(descriptor);
        return event.array();
    }

    public static byte[] input2(byte[] report) {
        checkBytes(report, 1, MAX_REPORT_SIZE, "report");
        ByteBuffer event = event(UHID_INPUT2);
        event.putShort((short) report.length);
        event.put(report);
        return event.array();
    }

    public static byte[] destroy() {
        return event(UHID_DESTROY).array();
    }

    public static byte[] getReportReply(int requestId, int errno) {
        return getReportReply(requestId, errno, new byte[0]);
    }

    public static byte[] getReportReply(int requestId, int errno, byte[] report) {
        checkErrno(errno);
        checkBytes(report, 0, MAX_REPORT_SIZE, "report");
        if (errno != 0 && report.length != 0) {
            throw new IllegalArgumentException("Failed reply cannot contain report bytes");
        }
        ByteBuffer event = event(UHID_GET_REPORT_REPLY);
        event.putInt(requestId); // opaque unsigned 32-bit id; preserve all bits
        event.putShort((short) errno);
        event.putShort((short) report.length);
        event.put(report);
        return event.array();
    }

    public static byte[] setReportReply(int requestId, int errno) {
        checkErrno(errno);
        ByteBuffer event = event(UHID_SET_REPORT_REPLY);
        event.putInt(requestId);
        event.putShort((short) errno);
        return event.array();
    }

    private static ByteBuffer event(int type) {
        ByteBuffer event = ByteBuffer.allocate(EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        event.putInt(type);
        return event;
    }

    private static void checkBytes(byte[] bytes, int min, int max, String field) {
        Objects.requireNonNull(bytes, field);
        if (bytes.length < min || bytes.length > max) {
            throw new IllegalArgumentException(field + " length must be " + min + ".." + max);
        }
    }

    private static void checkErrno(int errno) {
        if (errno < 0 || errno > 0xffff) {
            throw new IllegalArgumentException("errno must fit unsigned 16 bits");
        }
    }
}
