package io.kubepilot.common;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record Finding(
        String ruleId,
        Severity severity,
        ResourceRef ref,
        String summary,
        Map<String, String> evidence,
        int affectedCount,
        List<String> affected) {

    public Finding {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(ref, "ref");
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
        affected = affected == null ? List.of() : List.copyOf(affected);
        affectedCount = Math.max(affectedCount, 1);
    }

    public Finding(String ruleId, Severity severity, ResourceRef ref, String summary,
                   Map<String, String> evidence) {
        this(ruleId, severity, ref, summary, evidence, 1, List.of());
    }

    public String fingerprint() {
        ResourceRef target = ref.owner() != null ? ref.owner() : ref;
        return String.join("|",
                ruleId,
                target.kind(),
                nullToEmpty(target.namespace()),
                nullToEmpty(target.name()),
                nullToEmpty(ref.container()));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
