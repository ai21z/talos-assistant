package dev.talos.spi.types;

import java.util.Locale;
import java.util.Objects;

/** Typed provider message layout selected by verified runtime identity. */
public enum RequestMessageLayout {
    PRESERVE_AS_WRITTEN,
    SINGLE_LEADING_SYSTEM;

    public static RequestMessageLayout parse(String raw) {
        String value = Objects.toString(raw, "").strip();
        if (value.isBlank()) return PRESERVE_AS_WRITTEN;
        return valueOf(value.toUpperCase(Locale.ROOT).replace('-', '_'));
    }
}
