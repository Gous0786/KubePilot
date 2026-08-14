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

/**
 * Walks {@code ownerReferences} up to the top-level controller that owns a resource, so a finding
 * on {@code Pod/bad-7d9f8c4-x2k4l} can be attributed to {@code Deployment/bad}.
 *
 * <p>Resolution happens entirely against the {@link ClusterSnapshot}: no API calls. k8sgpt's
 * equivalent issues a GET per level, which for a pod means two extra round trips per finding.
 *
 * <p>Best-effort by design. When the intermediate object is not in the snapshot, the walk stops and
 * returns the highest owner it could confirm rather than failing — one level of attribution is
 * still better than none.
 */
public final class OwnerResolver {

    /** Guards against a malformed or cyclic ownerReference chain. */
    private static final int MAX_DEPTH = 6;

    private OwnerResolver() {
    }

    /** The top-level controller owning this resource, or {@code null} if nothing owns it. */
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

        // Climb another level if we can actually find this owner. A ReplicaSet is almost never the
        // answer the user wants -- the Deployment above it is.
        HasMetadata owner = lookup(ref, namespace, snapshot);
        if (owner != null && owner.getMetadata() != null) {
            ResourceRef higher = walk(owner.getMetadata().getOwnerReferences(), namespace, snapshot, depth + 1);
            if (higher != null) {
                return higher;
            }
        }
        return here;
    }

    /** A resource can have several owners but only one controller; prefer it. */
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
                // Job, StatefulSet, DaemonSet and friends are not in the snapshot yet, so the
                // ownerReference itself is as far as we can go.
            }
        }
        return null;
    }

    /**
     * Name <b>and</b> namespace must match. An all-namespaces scan can easily hold two ReplicaSets
     * with the same name in different namespaces, and ownerReferences carry no namespace of their
     * own — they are always same-namespace.
     */
    private static boolean matches(HasMetadata resource, OwnerReference ref, String namespace) {
        ObjectMeta meta = resource.getMetadata();
        return meta != null
                && ref.getName().equals(meta.getName())
                && Objects.equals(namespace, meta.getNamespace());
    }
}
