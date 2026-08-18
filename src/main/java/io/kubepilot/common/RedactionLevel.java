package io.kubepilot.common;

import java.util.Locale;

public enum RedactionLevel {
    STRICT,
    STANDARD,
    OFF;

    public static RedactionLevel from(String value) {
        if (value == null || value.isBlank()) {
            return STANDARD;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return STANDARD;
        }
    }
}
