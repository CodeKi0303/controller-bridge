package dev.codek.xboxprobe.core;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Validated, immutable UHID identity. Caller text is preserved without trimming/truncation. */
public final class HidIdentity {
    private final String name;
    private final int vendorId, productId, bus;

    public HidIdentity(String name,int vendorId,int productId,int bus) {
        this.name=checkedText(name,127,"device name");
        unsigned16(vendorId,"vendorId");unsigned16(productId,"productId");unsigned16(bus,"bus");
        this.vendorId=vendorId;this.productId=productId;this.bus=bus;
    }
    public String getName(){return name;}
    public int getVendorId(){return vendorId;}
    public int getProductId(){return productId;}
    public int getBus(){return bus;}
    static String checkedText(String text,int maxBytes,String label) {
        Objects.requireNonNull(text,label);
        if(text.trim().isEmpty()||text.indexOf('\0')>=0)throw new IllegalArgumentException(label+" must be nonempty and NUL-free");
        try {
            ByteBuffer bytes=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
            if(bytes.remaining()>maxBytes)throw new IllegalArgumentException(label+" exceeds "+maxBytes+" UTF-8 bytes");
        } catch(CharacterCodingException e){throw new IllegalArgumentException(label+" contains malformed Unicode",e);}
        return text;
    }
    private static void unsigned16(int value,String label){if(value<0||value>65535)throw new IllegalArgumentException(label+" must fit unsigned 16 bits");}
    @Override public boolean equals(Object other){if(!(other instanceof HidIdentity))return false;HidIdentity i=(HidIdentity)other;return name.equals(i.name)&&vendorId==i.vendorId&&productId==i.productId&&bus==i.bus;}
    @Override public int hashCode(){return Objects.hash(name,vendorId,productId,bus);}
}
