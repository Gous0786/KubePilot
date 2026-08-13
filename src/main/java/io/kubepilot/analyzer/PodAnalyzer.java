package io.kubepilot.analyzer;

import io.fabric8.kubernetes.api.model.ContainerState;
import io.fabric8.kubernetes.api.model.ContainerStateTerminated;
import io.fabric8.kubernetes.api.model.ContainerStateWaiting;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodCondition;
import io.kubepilot.common.ClusterSnapshot;
import io.kubepilot.common.Finding;
import io.kubepilot.common.ResourceRef;
import io.kubepilot.common.Severity;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class PodAnalyzer implements Analyzer {

    static final String RULE_IMAGE_PULL = "pod-image-pull-failed";
    static final String RULE_CRASH_LOOP = "pod-crash-loop";
    static final String RULE_UNSCHEDULABLE = "pod-unschedulable";

    /** One problem, several strings: kubelet flips between these as it retries. */
    private static final Set<String> IMAGE_PULL_REASONS = Set.of(
            "ErrImagePull",
            "ImagePullBackOff",
            "InvalidImageName",
            "ImageInspectError",
            "RegistryUnavailable");

    @Override
    public String id() {
        return "pod";
    }

    @Override
    public List<Finding> analyze(ClusterSnapshot snapshot) {
        List<Finding> findings = new ArrayList<>();
        for (Pod pod : snapshot.pods()) {
            findings.addAll(analyzePod(pod));
        }
        return findings;
    }

    private List<Finding> analyzePod(Pod pod) {
        List<Finding> findings = new ArrayList<>();
        ResourceRef podRef = ResourceRef.of("Pod", namespaceOf(pod), nameOf(pod));

        // Pod-level check.
        unschedulable(pod, podRef).ifPresent(findings::add);

        // Per-container checks: two containers can be broken in two different ways.
        for (ContainerStatus cs : containerStatuses(pod)) {
            ContainerState state = cs.getState();
            if (state == null) {
                continue;
            }
            ContainerStateWaiting waiting = state.getWaiting();
            if (waiting == null || waiting.getReason() == null) {
                continue; // running or terminated normally
            }

            ResourceRef ref = podRef.withContainer(cs.getName());
            String reason = waiting.getReason();

            if (IMAGE_PULL_REASONS.contains(reason)) {
                findings.add(imagePull(cs, ref, waiting));
            } else if ("CrashLoopBackOff".equals(reason)) {
                findings.add(crashLoop(cs, ref, waiting));
            }
        }
        return findings;
    }

    private Finding imagePull(ContainerStatus cs, ResourceRef ref, ContainerStateWaiting waiting) {
        Map<String, String> evidence = new LinkedHashMap<>();
        put(evidence, "reason", waiting.getReason());
        put(evidence, "image", cs.getImage());
        put(evidence, "message", waiting.getMessage());

        return new Finding(RULE_IMAGE_PULL, Severity.ERROR, ref,
                "Cannot pull image " + cs.getImage(), evidence);
    }

    private Finding crashLoop(ContainerStatus cs, ResourceRef ref, ContainerStateWaiting waiting) {
        Map<String, String> evidence = new LinkedHashMap<>();
        put(evidence, "reason", waiting.getReason());
        put(evidence, "restartCount", String.valueOf(orZero(cs.getRestartCount())));

        // "CrashLoopBackOff" alone says nothing useful. WHY it crashed is in the
        // PREVIOUS termination, not the current waiting state.
        String summary = "Container is restarting repeatedly";
        ContainerStateTerminated last =
                cs.getLastState() == null ? null : cs.getLastState().getTerminated();

        if (last != null) {
            put(evidence, "lastTerminationReason", last.getReason());
            if (last.getExitCode() != null) {
                put(evidence, "lastExitCode", String.valueOf(last.getExitCode()));
            }
            if ("OOMKilled".equals(last.getReason())) {
                summary = "Container was killed for exceeding its memory limit";
            } else if (last.getExitCode() != null && last.getExitCode() != 0) {
                summary = "Container keeps exiting with code " + last.getExitCode();
            }
        }
        return new Finding(RULE_CRASH_LOOP, Severity.ERROR, ref, summary, evidence);
    }

    private Optional<Finding> unschedulable(Pod pod, ResourceRef ref) {
        if (pod.getStatus() == null || !"Pending".equals(pod.getStatus().getPhase())) {
            return Optional.empty();
        }
        List<PodCondition> conditions = pod.getStatus().getConditions();
        if (conditions == null) {
            return Optional.empty();
        }
        for (PodCondition c : conditions) {
            if ("PodScheduled".equals(c.getType()) && "False".equals(c.getStatus())) {
                Map<String, String> evidence = new LinkedHashMap<>();
                put(evidence, "reason", c.getReason());
                put(evidence, "message", c.getMessage());
                return Optional.of(new Finding(RULE_UNSCHEDULABLE, Severity.WARNING, ref,
                        "Pod cannot be scheduled onto any node", evidence));
            }
        }
        return Optional.empty();
    }

    // --- null-safe helpers: almost every field in a Pod status is optional ---

    private static List<ContainerStatus> containerStatuses(Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null) {
            return List.of();
        }
        return pod.getStatus().getContainerStatuses();
    }

    private static String namespaceOf(Pod pod) {
        return pod.getMetadata() == null ? "" : pod.getMetadata().getNamespace();
    }

    private static String nameOf(Pod pod) {
        return pod.getMetadata() == null ? "" : pod.getMetadata().getName();
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }
}
