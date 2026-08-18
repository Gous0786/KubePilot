package io.kubepilot.util;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.kubepilot.common.ClusterSnapshot;
import io.kubepilot.common.ResourceRef;

import java.util.List;
import java.util.Objects;

public final class OwnerResolver {


    private static final int MAX_DEPTH = 6;

    private OwnerResolver() {
    }

    public static ResourceRef resolveOwner(HasMetadata resource, ClusterSnapshot snapshot) {
        if (resource == null || resource.getMetadata() == null) {
            return null;
        }
        return walk(resource.getMetadata().getOwnerReferences(),
                resource.getMetadata().getNamespace(), snapshot, 0);
    }

    private static ResourceRef walk(List<OwnerReference> refs, String namespace,
                                    ClusterSnapshot snapshot, int depth) {
        if (refs == null || refs.isEmpty() || depth >= MAX_DEPTH) {
            return null;
        }
        OwnerReference ref = controllerOf(refs);
        if (ref == null || ref.getKind() == null || ref.getName() == null) {
            return null;
        }
        ResourceRef here = ResourceRef.of(ref.getKind(), namespace, ref.getName());
        HasMetadata owner = lookup(ref, namespace, snapshot);
        if (owner != null && owner.getMetadata() != null) {
            ResourceRef higher = walk(owner.getMetadata().getOwnerReferences(), namespace, snapshot, depth + 1);
            if (higher != null) {
                return higher;
            }
        }
        return here;
    }

    private static OwnerReference controllerOf(List<OwnerReference> refs) {
        for (OwnerReference r : refs) {
            if (Boolean.TRUE.equals(r.getController())) {
                return r;
            }
        }
        return refs.getFirst();
    }

    private static HasMetadata lookup(OwnerReference ref, String namespace, ClusterSnapshot snapshot) {
        switch (ref.getKind()) {
            case "ReplicaSet" -> {
                for (ReplicaSet rs : snapshot.replicaSets()) {
                    if (matches(rs, ref, namespace)) {
                        return rs;
                    }
                }
            }
            case "Deployment" -> {
                for (Deployment d : snapshot.deployments()) {
                    if (matches(d, ref, namespace)) {
                        return d;
                    }
                }
            }
            default -> {
            }
        }
        return null;
    }

    private static boolean matches(HasMetadata resource, OwnerReference ref, String namespace) {
        ObjectMeta meta = resource.getMetadata();
        return meta != null
                && ref.getName().equals(meta.getName())
                && Objects.equals(namespace, meta.getNamespace());
    }
}
