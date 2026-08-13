package io.kubepilot.kubernetes;

import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.kubepilot.common.ClusterSnapshot;

@ApplicationScoped
public class ClusterReader {
    private final KubernetesClient client;
    
    @Inject
    public ClusterReader(KubernetesClient client) {
        this.client = client;
    }
    public ClusterSnapshot readCluster() {
        return new ClusterSnapshot(client.pods().inAnyNamespace().list().getItems());
    }

}
