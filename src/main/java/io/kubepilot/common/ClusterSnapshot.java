package io.kubepilot.common;

import io.fabric8.kubernetes.api.model.Pod;

import java.util.List;

public record ClusterSnapshot(List<Pod> pods){
    public ClusterSnapshot {
        pods = pods == null ? List.of() :List.copyOf(pods);
    }

    public static ClusterSnapshot empty() {
        return new ClusterSnapshot(List.of());
    }
}
