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
    static final String RULE_CONTAINER_TERMINATED = "pod-container-terminated";

    private static final Set<String> IMAGE_PULL_REASONS = Set.of(
            "ErrImagePull",
            "ImagePullBackOff",
            "InvalidImageName",
            "ImageInspectError",
            "ErrImageNeverPull",
            "RegistryUnavailable");

    private static final String CRASH_LOOP_BACKOFF = "CrashLoopBackOff";

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

        unschedulable(pod, podRef).ifPresent(findings::add);

        findings.addAll(analyzeContainers(initContainerStatuses(pod), podRef, true));
        findings.addAll(analyzeContainers(containerStatuses(pod), podRef, false));

        return findings;
    }


    private List<Finding> analyzeContainers(List<ContainerStatus> statuses, ResourceRef podRef, boolean init) {
        List<Finding> findings = new ArrayList<>();

        for (ContainerStatus cs : statuses) {
            ContainerState state = cs.getState();
            if (state == null) {
                continue;
            }
            ResourceRef ref = podRef.withContainer(cs.getName());
            ContainerStateWaiting waiting = state.getWaiting();
            ContainerStateTerminated terminated = state.getTerminated();

            if (waiting != null && waiting.getReason() != null) {
                String reason = waiting.getReason();
                if (IMAGE_PULL_REASONS.contains(reason)) {
                    findings.add(imagePull(cs, ref, waiting, init));
                } else if (CRASH_LOOP_BACKOFF.equals(reason)) {
                    findings.add(crashLoop(cs, ref, reason, init));
                }

            } else if (terminated != null
                    && terminated.getExitCode() != null
                    && terminated.getExitCode() != 0) {

                if (orZero(cs.getRestartCount()) > 0) {
                    findings.add(crashLoop(cs, ref, terminated.getReason(), init));
                } else {
                    findings.add(terminatedOnce(cs, ref, terminated, init));
                }
            }
        }
        return findings;
    }

    private Finding imagePull(ContainerStatus cs, ResourceRef ref, ContainerStateWaiting waiting, boolean init) {
        Map<String, String> evidence = new LinkedHashMap<>();
        put(evidence, "reason", waiting.getReason());
        put(evidence, "image", cs.getImage());
        put(evidence, "message", waiting.getMessage());
        markInit(evidence, init);

        return new Finding(RULE_IMAGE_PULL, Severity.ERROR, ref,
                (init ? "Init container" : "Container") + " cannot pull image " + cs.getImage(), evidence);
    }

    private Finding crashLoop(ContainerStatus cs, ResourceRef ref, String reason, boolean init) {
        Map<String, String> evidence = new LinkedHashMap<>();
        put(evidence, "reason", reason);
        put(evidence, "restartCount", String.valueOf(orZero(cs.getRestartCount())));
        markInit(evidence, init);

        ContainerStateTerminated t = currentOrLastTermination(cs);

        String summary = "Container is restarting repeatedly";
        if (t != null) {
            put(evidence, "terminationReason", t.getReason());
            put(evidence, "finishedAt", t.getFinishedAt());
            if (t.getExitCode() != null) {
                put(evidence, "exitCode", String.valueOf(t.getExitCode()));
            }
            if ("OOMKilled".equals(t.getReason())) {
                summary = "Container was killed for exceeding its memory limit";
            } else if (t.getExitCode() != null && t.getExitCode() != 0) {
                summary = "Container keeps exiting with code " + t.getExitCode();
            }
        }
        if (init) {
            summary = "Init " + Character.toLowerCase(summary.charAt(0)) + summary.substring(1);
        }
        return new Finding(RULE_CRASH_LOOP, Severity.ERROR, ref, summary, evidence);
    }


    private Finding terminatedOnce(ContainerStatus cs, ResourceRef ref, ContainerStateTerminated t, boolean init) {
        Map<String, String> evidence = new LinkedHashMap<>();
        put(evidence, "reason", t.getReason() == null ? "Unknown" : t.getReason());
        put(evidence, "exitCode", String.valueOf(t.getExitCode()));
        put(evidence, "finishedAt", t.getFinishedAt());
        put(evidence, "restartCount", String.valueOf(orZero(cs.getRestartCount())));
        markInit(evidence, init);

        return new Finding(RULE_CONTAINER_TERMINATED, Severity.ERROR, ref,
                (init ? "Init container" : "Container") + " exited with code " + t.getExitCode(), evidence);
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

    private static ContainerStateTerminated currentOrLastTermination(ContainerStatus cs) {
        if (cs.getState() != null && cs.getState().getTerminated() != null) {
            return cs.getState().getTerminated();
        }
        if (cs.getLastState() != null && cs.getLastState().getTerminated() != null) {
            return cs.getLastState().getTerminated();
        }
        return null;
    }

    private static List<ContainerStatus> containerStatuses(Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null) {
            return List.of();
        }
        return pod.getStatus().getContainerStatuses();
    }

    private static List<ContainerStatus> initContainerStatuses(Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getInitContainerStatuses() == null) {
            return List.of();
        }
        return pod.getStatus().getInitContainerStatuses();
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

    private static void markInit(Map<String, String> evidence, boolean init) {
        if (init) {
            evidence.put("initContainer", "true");
        }
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }
}
