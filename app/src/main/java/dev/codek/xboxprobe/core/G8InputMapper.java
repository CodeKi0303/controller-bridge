package dev.codek.xboxprobe.core;

/** Selected-source mapping; each output owns its report format and neutral state. */
public interface G8InputMapper {
    MappingUpdate accept(EvdevEvent event);
    MappingUpdate disconnect();
    byte[] neutralReport();
}
