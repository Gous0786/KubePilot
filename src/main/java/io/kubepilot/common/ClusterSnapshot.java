package io.kubepilot.common;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;

import java.util.List;


public record ClusterSnapshot(
        List<Pod> pods,
        List<ReplicaSet> replicaSets,
        List<Deployment> deployments) {

    public ClusterSnapshot {
        pods = pods == null ? List.of() : List.copyOf(pods);
        replicaSets = replicaSets == null ? List.of() : List.copyOf(replicaSets);
        deployments = deployments == null ? List.of() : List.copyOf(deployments);
    }

    public ClusterSnapshot(List<Pod> pods) {
        this(pods, List.of(), List.of());
    }

    public static ClusterSnapshot empty() {
        return new ClusterSnapshot(List.of(), List.of(), List.of());
    }
}
