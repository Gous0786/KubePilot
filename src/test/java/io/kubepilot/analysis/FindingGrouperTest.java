package io.kubepilot.analysis;

import io.kubepilot.common.Finding;
import io.kubepilot.common.ResourceRef;
import io.kubepilot.common.Severity;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FindingGrouperTest {

    private static final String RULE = "pod-image-pull-failed";

    @Test
    void siblingsOfOneControllerCollapseIntoOneFinding() {
        List<Finding> grouped = FindingGrouper.group(List.of(
                ownedPodFinding("bad-546d584665-c5lps"),
                ownedPodFinding("bad-546d584665-jtm9l"),
                ownedPodFinding("bad-546d584665-vr87d")));

        assertEquals(1, grouped.size(), () -> "expected one finding, got " + grouped);

        Finding f = grouped.getFirst();
        assertEquals(3, f.affectedCount());
        assertEquals("Deployment", f.ref().kind());
        assertEquals("bad", f.ref().name());
        assertEquals("nginx", f.ref().container());
        assertEquals(
                List.of("bad-546d584665-c5lps", "bad-546d584665-jtm9l", "bad-546d584665-vr87d"),
                f.affected());
    }

    @Test
    void collapsedFindingKeepsTheFingerprintOfItsMembers() {
        Finding member = ownedPodFinding("bad-546d584665-c5lps");

        List<Finding> grouped = FindingGrouper.group(List.of(member, ownedPodFinding("bad-546d584665-jtm9l")));

        assertEquals(member.fingerprint(), grouped.getFirst().fingerprint());
    }

    @Test
    void unrelatedPodsAreNotCollapsed() {
        List<Finding> grouped = FindingGrouper.group(List.of(
                barePodFinding("broken"),
                barePodFinding("also-broken")));

        assertEquals(2, grouped.size(), () -> "expected two findings, got " + grouped);
        assertTrue(grouped.stream().allMatch(f -> f.affectedCount() == 1));
        assertTrue(grouped.stream().allMatch(f -> f.affected().isEmpty()));
    }

    @Test
    void differentContainersOfOneControllerStaySeparate() {
        Finding nginx = new Finding(RULE, Severity.ERROR, podRefOwnedBy("bad-1", "nginx"), "s", Map.of());
        Finding sidecar = new Finding(RULE, Severity.ERROR, podRefOwnedBy("bad-2", "sidecar"), "s", Map.of());

        assertEquals(2, FindingGrouper.group(List.of(nginx, sidecar)).size());
    }

    @Test
    void differentRulesStaySeparate() {
        Finding pull = new Finding(RULE, Severity.ERROR, podRefOwnedBy("bad-1", "nginx"), "s", Map.of());
        Finding crash = new Finding("pod-crash-loop", Severity.ERROR, podRefOwnedBy("bad-2", "nginx"), "s", Map.of());

        assertEquals(2, FindingGrouper.group(List.of(pull, crash)).size());
    }

    @Test
    void emptyInputProducesEmptyOutput() {
        assertEquals(List.of(), FindingGrouper.group(List.of()));
    }

    private static Finding ownedPodFinding(String podName) {
        return new Finding(RULE, Severity.ERROR, podRefOwnedBy(podName, "nginx"),
                "Container cannot pull image nginx:nosuchtag", Map.of("image", "nginx:nosuchtag"));
    }

    private static Finding barePodFinding(String podName) {
        return new Finding(RULE, Severity.ERROR,
                ResourceRef.of("Pod", "default", podName).withContainer(podName),
                "Container cannot pull image nginx:nosuchtag", Map.of());
    }

    private static ResourceRef podRefOwnedBy(String podName, String container) {
        return ResourceRef.of("Pod", "default", podName)
                .withContainer(container)
                .withOwner(ResourceRef.of("Deployment", "default", "bad"));
    }
}
