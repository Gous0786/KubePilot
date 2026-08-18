package io.kubepilot.common;

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

    public ResourceRef workload() {
        return owner != null ? owner : new ResourceRef(kind, namespace, name, null, null);
    }

    @Override
    public String toString() {
        String base = kind + "/" + namespace + "/" + name;
        return container == null ? base : base + "[" + container + "]";
    }
}
