package io.kubepilot.analyzer;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentCondition;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatus;
import io.kubepilot.common.ClusterSnapshot;
import io.kubepilot.common.Finding;
import io.kubepilot.common.ResourceRef;
import io.kubepilot.common.Severity;
import io.kubepilot.util.OwnerResolver;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class DeploymentAnalyzer implements Analyzer {

    static final String RULE_REPLICAS_UNAVAILABLE = "deployment-replicas-unavailable";
    static final String RULE_PROGRESS_DEADLINE_EXCEEDED = "deployment-progress-deadline-exceeded";
    static final String RULE_REPLICA_FAILURE = "deployment-replica-failure";

    @Override
    public String id() {
        return "deployment";
    }

    @Override
    public List<Finding> analyze(ClusterSnapshot snapshot) {
        List<Finding> findings = new ArrayList<>();
        for (Deployment deployment : snapshot.deployments()) {
            findings.addAll(analyzeDeployment(deployment, snapshot));
        }
        return findings;
    }

    private List<Finding> analyzeDeployment(Deployment deployment, ClusterSnapshot snapshot) {
        List<Finding> findings = new ArrayList<>();

        int desired = desiredReplicas(deployment);
        if (desired == 0) {
            return findings;
        }

        ResourceRef ref = ResourceRef.of("Deployment", namespaceOf(deployment), nameOf(deployment));
        ResourceRef owner = OwnerResolver.resolveOwner(deployment, snapshot);
        if (owner != null) {
            ref = ref.withOwner(owner);
        }

        int available = availableReplicas(deployment);
        if (available < desired) {
            findings.add(replicasUnavailable(ref, desired, available));
        }

        DeploymentCondition replicaFailureCondition = condition(deployment, "ReplicaFailure");
        if (replicaFailureCondition != null && "True".equals(replicaFailureCondition.getStatus())) {
            findings.add(replicaFailure(ref, replicaFailureCondition));
        }

        DeploymentCondition progressingCondition = condition(deployment, "Progressing");
        if (progressingCondition != null
                && "False".equals(progressingCondition.getStatus())
                && "ProgressDeadlineExceeded".equals(progressingCondition.getReason())) {
            findings.add(progressDeadlineExceeded(ref, progressingCondition));
        }

        return findings;
    }

    private Finding replicasUnavailable(ResourceRef ref, int desired, int available) {
        Map<String, String> evidence = new LinkedHashMap<>();
        evidence.put("desiredReplicas", String.valueOf(desired));
        evidence.put("availableReplicas", String.valueOf(available));

        Severity severity = available == 0 ? Severity.ERROR : Severity.WARNING;
        String summary = available == 0
                ? "None of the " + desired + " replicas are available"
                : available + " of " + desired + " replicas are available";

        return new Finding(RULE_REPLICAS_UNAVAILABLE, severity, ref, summary, evidence);
    }

    private Finding replicaFailure(ResourceRef ref, DeploymentCondition condition) {
        Map<String, String> evidence = new LinkedHashMap<>();
        put(evidence, "reason", condition.getReason());
        put(evidence, "message", condition.getMessage());

        return new Finding(RULE_REPLICA_FAILURE, Severity.ERROR, ref,
                "Replicas cannot be created", evidence);
    }

    private Finding progressDeadlineExceeded(ResourceRef ref, DeploymentCondition condition) {
        Map<String, String> evidence = new LinkedHashMap<>();
        put(evidence, "reason", condition.getReason());
        put(evidence, "message", condition.getMessage());

        return new Finding(RULE_PROGRESS_DEADLINE_EXCEEDED, Severity.ERROR, ref,
                "Rollout has not progressed within its deadline", evidence);
    }

    private static DeploymentCondition condition(Deployment deployment, String type) {
        DeploymentStatus status = deployment.getStatus();
        if (status == null || status.getConditions() == null) {
            return null;
        }
        for (DeploymentCondition c : status.getConditions()) {
            if (type.equals(c.getType())) {
                return c;
            }
        }
        return null;
    }

    private static int desiredReplicas(Deployment deployment) {
        if (deployment.getSpec() == null || deployment.getSpec().getReplicas() == null) {
            return 1;
        }
        return deployment.getSpec().getReplicas();
    }

    private static int availableReplicas(Deployment deployment) {
        DeploymentStatus status = deployment.getStatus();
        if (status == null || status.getAvailableReplicas() == null) {
            return 0;
        }
        return status.getAvailableReplicas();
    }

    private static String namespaceOf(Deployment deployment) {
        return deployment.getMetadata() == null ? "" : deployment.getMetadata().getNamespace();
    }

    private static String nameOf(Deployment deployment) {
        return deployment.getMetadata() == null ? "" : deployment.getMetadata().getName();
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }
}
