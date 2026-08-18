package io.kubepilot.analysis;

import io.kubepilot.common.Finding;
import io.kubepilot.common.ResourceRef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        ResourceRef owner = first.ref().owner();
        ResourceRef ref = owner == null
                ? first.ref()
                : owner.withContainer(first.ref().container());

        List<String> affected = sameProblem.stream()
                .map(f -> f.ref().name())
                .sorted()
                .toList();


        return new Finding(first.ruleId(), first.severity(), ref, first.summary(),
                first.evidence(), sameProblem.size(), affected);
    }
}
