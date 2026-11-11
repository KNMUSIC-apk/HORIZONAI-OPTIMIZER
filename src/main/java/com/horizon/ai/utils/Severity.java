package com.horizon.ai.utils;

import java.util.Locale;

/** Mức độ nghiêm trọng dùng chung cho rule-based, AI và cảnh báo. */
public enum Severity {
    NORMAL, LOW, MEDIUM, HIGH, CRITICAL;

    public static Severity parse(String raw, Severity fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    public boolean atLeast(Severity other) {
        return this.ordinal() >= other.ordinal();
    }
}
