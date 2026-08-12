package io.kubepilot.common;

import java.util.Map;
import java.util.Objects;

public record Finding(
        String ruleId,
        Severity severity,
        ResourceRef ref,
        String summary,
        Map<String, String> evidence) {

    public Finding {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(ref, "ref");
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
    }

    public String fingerprint() {
        return String.join("|",
                ruleId,
                ref.kind(),
                nullToEmpty(ref.namespace()),
                nullToEmpty(ref.name()),
                nullToEmpty(ref.container()));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
