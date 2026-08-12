package io.kubepilot.common;

public record ResourceRef(String kind, String namespace, String name, String container) {

    public static ResourceRef of(String kind, String namespace, String name) {
        return new ResourceRef(kind, namespace, name, null);
    }

    public ResourceRef withContainer(String container) {
        return new ResourceRef(kind, namespace, name, container);
    }

    @Override
    public String toString() {
        String base = kind + "/" + namespace + "/" + name;
        return container == null ? base : base + "[" + container + "]";
    }
}
