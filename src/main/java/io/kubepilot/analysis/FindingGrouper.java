package io.kubepilot.analysis;

import io.kubepilot.common.Finding;
import io.kubepilot.common.ResourceRef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collapses findings that describe the same problem into one.
 *
 * <p>Three replicas of a Deployment with a bad image produce three identical findings differing
 * only in a random pod suffix. They share a {@link Finding#fingerprint()}, because that is keyed on
 * the owner, so grouping is just a group-by on that key.
 *
 * <p>This lives here rather than in an analyzer on purpose. {@code PodAnalyzer} looks at one pod at
 * a time and has no business knowing that sibling pods exist; recognising duplicates is a
 * whole-scan concern and only makes sense once every analyzer has finished.
 */
final class FindingGrouper {

    private FindingGrouper() {
    }

    static List<Finding> group(List<Finding> findings) {
        Map<String, List<Finding>> byFingerprint = new LinkedHashMap<>();
        for (Finding f : findings) {
            byFingerprint.computeIfAbsent(f.fingerprint(), k -> new ArrayList<>()).add(f);
        }

        List<Finding> grouped = new ArrayList<>(byFingerprint.size());
        for (List<Finding> sameProblem : byFingerprint.values()) {
            grouped.add(sameProblem.size() == 1 ? sameProblem.getFirst() : collapse(sameProblem));
        }
        return grouped;
    }

    private static Finding collapse(List<Finding> sameProblem) {
        Finding first = sameProblem.getFirst();

        // The collapsed finding is about the controller, not any one of its pods. Keep the
        // container name on it so the fingerprint still recomputes to the same value.
        ResourceRef owner = first.ref().owner();
        ResourceRef ref = owner == null
                ? first.ref()
                : owner.withContainer(first.ref().container());

        List<String> affected = sameProblem.stream()
                .map(f -> f.ref().name())
                .sorted()
                .toList();

        // Evidence is taken from the first occurrence: siblings of one controller are failing for
        // the same reason, so repeating it once per pod adds bytes and no information.
        return new Finding(first.ruleId(), first.severity(), ref, first.summary(),
                first.evidence(), sameProblem.size(), affected);
    }
}
