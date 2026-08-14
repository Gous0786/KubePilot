package io.kubepilot.common;

/**
 * Identifies the Kubernetes object (and optionally the container) a finding is about.
 *
 * <p>{@code owner} is the top-level controller this object belongs to, resolved by walking
 * {@code ownerReferences} — a Deployment, StatefulSet, DaemonSet, Job and so on. It is {@code null}
 * for objects nobody owns, such as a pod created directly with {@code kubectl run}.
 *
 * <p>It matters because pod names are ephemeral: a Deployment's pod is called something like
 * {@code bad-7d9f8c4-x2k4l} and gets a new name on every rollout, while the Deployment's name is
 * stable. The owner is therefore what lets sibling pods be recognised as one problem, and what a
 * stable identity should ultimately be keyed on.
 */
public record ResourceRef(String kind, String namespace, String name, String container, ResourceRef owner) {

    public static ResourceRef of(String kind, String namespace, String name) {
        return new ResourceRef(kind, namespace, name, null, null);
    }

    public ResourceRef withContainer(String container) {
        return new ResourceRef(kind, namespace, name, container, owner);
    }

    public ResourceRef withOwner(ResourceRef owner) {
        return new ResourceRef(kind, namespace, name, container, owner);
    }

    @Override
    public String toString() {
        String base = kind + "/" + namespace + "/" + name;
        return container == null ? base : base + "[" + container + "]";
    }
}
