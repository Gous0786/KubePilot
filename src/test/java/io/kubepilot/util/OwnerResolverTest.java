package io.kubepilot.util;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder;
import io.kubepilot.common.ClusterSnapshot;
import io.kubepilot.common.ResourceRef;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class OwnerResolverTest {

    @Test
    void climbsPastTheReplicaSetToTheDeployment() {
        Pod pod = podOwnedBy("bad-546d584665-c5lps", "default", "ReplicaSet", "bad-546d584665");
        ReplicaSet rs = replicaSetOwnedBy("bad-546d584665", "default", "bad");
        Deployment deployment = deployment("bad", "default");

        ResourceRef owner = OwnerResolver.resolveOwner(pod,
                new ClusterSnapshot(List.of(pod), List.of(rs), List.of(deployment)));

        assertEquals("Deployment", owner.kind());
        assertEquals("bad", owner.name());
        assertEquals("default", owner.namespace());
    }

    @Test
    void bareResourceHasNoOwner() {
        Pod pod = new PodBuilder()
                .withNewMetadata().withName("broken").withNamespace("default").endMetadata()
                .build();

        assertNull(OwnerResolver.resolveOwner(pod, new ClusterSnapshot(List.of(pod))));
    }

    @Test
    void stopsAtTheReplicaSetWhenItIsNotInTheSnapshot() {
        Pod pod = podOwnedBy("bad-546d584665-c5lps", "default", "ReplicaSet", "bad-546d584665");

        ResourceRef owner = OwnerResolver.resolveOwner(pod, new ClusterSnapshot(List.of(pod)));

        assertEquals("ReplicaSet", owner.kind());
        assertEquals("bad-546d584665", owner.name());
    }

    @Test
    void doesNotMatchASameNamedReplicaSetInAnotherNamespace() {
        Pod pod = podOwnedBy("web-abc-1", "team-a", "ReplicaSet", "web-abc");
        ReplicaSet decoy = replicaSetOwnedBy("web-abc", "team-b", "web-in-team-b");
        Deployment decoyDeployment = deployment("web-in-team-b", "team-b");

        ResourceRef owner = OwnerResolver.resolveOwner(pod,
                new ClusterSnapshot(List.of(pod), List.of(decoy), List.of(decoyDeployment)));

        assertEquals("ReplicaSet", owner.kind());
        assertEquals("web-abc", owner.name());
        assertEquals("team-a", owner.namespace());
    }

    @Test
    void reportsAnOwnerKindThatIsNotInTheSnapshot() {
        Pod pod = podOwnedBy("nightly-run-xyz", "default", "Job", "nightly-run");

        ResourceRef owner = OwnerResolver.resolveOwner(pod, new ClusterSnapshot(List.of(pod)));

        assertEquals("Job", owner.kind());
        assertEquals("nightly-run", owner.name());
    }

    @Test
    void nullResourceResolvesToNull() {
        assertNull(OwnerResolver.resolveOwner(null, ClusterSnapshot.empty()));
    }

    private static Pod podOwnedBy(String name, String namespace, String ownerKind, String ownerName) {
        return new PodBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(namespace)
                    .addNewOwnerReference()
                        .withKind(ownerKind)
                        .withName(ownerName)
                        .withController(true)
                    .endOwnerReference()
                .endMetadata()
                .build();
    }

    private static ReplicaSet replicaSetOwnedBy(String name, String namespace, String deploymentName) {
        return new ReplicaSetBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(namespace)
                    .addNewOwnerReference()
                        .withKind("Deployment")
                        .withName(deploymentName)
                        .withController(true)
                    .endOwnerReference()
                .endMetadata()
                .build();
    }

    private static Deployment deployment(String name, String namespace) {
        return new DeploymentBuilder()
                .withNewMetadata().withName(name).withNamespace(namespace).endMetadata()
                .build();
    }
}
