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

    /**
     * A single occurrence. Analyzers emit these; collapsing several into one is a whole-scan
     * concern and happens later, in the analysis package.
     */
    public Finding(String ruleId, Severity severity, ResourceRef ref, String summary,
                   Map<String, String> evidence) {
        this(ruleId, severity, ref, summary, evidence, 1, List.of());
    }

    /**
     * Stable identity for this problem.
     *
     * <p>Keyed on the <b>owner</b> when there is one, because the object's own name is often
     * ephemeral: a Deployment's pod is called {@code bad-546d584665-c5lps} and is renamed on every
     * rollout, while {@code Deployment/bad} is stable. Keying on the pod would mean the same
     * problem got a new identity after each deploy — a cache miss and a duplicate alert every time.
     *
     * <p>It also means sibling pods of one controller share a fingerprint, so deduplicating them
     * needs no separate logic.
     *
     * <p>The container name always comes from the object itself, never the owner: two containers in
     * the same Deployment failing for different reasons are genuinely two problems.
     */
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
