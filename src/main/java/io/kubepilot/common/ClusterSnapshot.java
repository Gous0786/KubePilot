package io.kubepilot.common;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;

import java.util.List;

/**
 * An immutable point-in-time read of the cluster. Grows a list per resource kind.
 *
 * <p>ReplicaSets and Deployments are present so that owner references can be resolved locally.
 * Walking {@code pod -> ReplicaSet -> Deployment} against the API instead would cost two extra
 * calls per finding.
 */
public record ClusterSnapshot(
        List<Pod> pods,
        List<ReplicaSet> replicaSets,
        List<Deployment> deployments) {

    public ClusterSnapshot {
        pods = pods == null ? List.of() : List.copyOf(pods);
        replicaSets = replicaSets == null ? List.of() : List.copyOf(replicaSets);
        deployments = deployments == null ? List.of() : List.copyOf(deployments);
    }

    /** Pods only — convenient for tests that do not care about ownership. */
    public ClusterSnapshot(List<Pod> pods) {
        this(pods, List.of(), List.of());
    }

    public static ClusterSnapshot empty() {
        return new ClusterSnapshot(List.of(), List.of(), List.of());
    }
}
