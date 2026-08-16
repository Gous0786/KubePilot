package io.kubepilot.analyzer;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.kubepilot.common.ClusterSnapshot;
import io.kubepilot.common.Finding;
import io.kubepilot.common.Severity;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DeploymentAnalyzerTest {

    private final DeploymentAnalyzer analyzer = new DeploymentAnalyzer();

    @Test
    void detectsUnavailableReplicasAndStalledRollout() {
        Deployment deployment = load("deployment-unavailable.yaml");

        List<Finding> findings = analyze(deployment);

        assertEquals(2, findings.size(), () -> "expected two findings, got " + findings);

        Finding unavailable = ruleOf(findings, DeploymentAnalyzer.RULE_REPLICAS_UNAVAILABLE);
        assertEquals(Severity.ERROR, unavailable.severity());
        assertEquals("Deployment", unavailable.ref().kind());
        assertEquals("bad", unavailable.ref().name());
        assertEquals("3", unavailable.evidence().get("desiredReplicas"));
        assertEquals("0", unavailable.evidence().get("availableReplicas"));

        Finding stalled = ruleOf(findings, DeploymentAnalyzer.RULE_PROGRESS_DEADLINE_EXCEEDED);
        assertEquals(Severity.ERROR, stalled.severity());
        assertEquals("ProgressDeadlineExceeded", stalled.evidence().get("reason"));
    }

    @Test
    void partialAvailabilityIsAWarningNotAnError() {
        Deployment deployment = new DeploymentBuilder()
                .withNewMetadata().withName("partial").withNamespace("default").endMetadata()
                .withNewSpec().withReplicas(3).endSpec()
                .withNewStatus().withAvailableReplicas(2).endStatus()
                .build();

        List<Finding> findings = analyze(deployment);

        assertEquals(1, findings.size(), () -> "expected one finding, got " + findings);
        Finding f = findings.getFirst();
        assertEquals(DeploymentAnalyzer.RULE_REPLICAS_UNAVAILABLE, f.ruleId());
        assertEquals(Severity.WARNING, f.severity());
        assertTrue(f.summary().contains("2 of 3"), () -> "summary was: " + f.summary());
    }

    @Test
    void detectsReplicaFailure() {
        Deployment deployment = new DeploymentBuilder()
                .withNewMetadata().withName("blocked").withNamespace("default").endMetadata()
                .withNewSpec().withReplicas(2).endSpec()
                .withNewStatus()
                    .withAvailableReplicas(2)
                    .addNewCondition()
                        .withType("ReplicaFailure")
                        .withStatus("True")
                        .withReason("FailedCreate")
                        .withMessage("exceeded quota")
                    .endCondition()
                .endStatus()
                .build();

        List<Finding> findings = analyze(deployment);

        assertEquals(1, findings.size(), () -> "expected one finding, got " + findings);
        Finding f = findings.getFirst();
        assertEquals(DeploymentAnalyzer.RULE_REPLICA_FAILURE, f.ruleId());
        assertEquals(Severity.ERROR, f.severity());
        assertEquals("exceeded quota", f.evidence().get("message"));
    }

    @Test
    void healthyDeploymentProducesNothing() {
        Deployment deployment = new DeploymentBuilder()
                .withNewMetadata().withName("fine").withNamespace("default").endMetadata()
                .withNewSpec().withReplicas(2).endSpec()
                .withNewStatus().withAvailableReplicas(2).endStatus()
                .build();

        assertEquals(List.of(), analyze(deployment));
    }

    @Test
    void deliberatelyScaledToZeroIsNotAFault() {
        Deployment deployment = new DeploymentBuilder()
                .withNewMetadata().withName("paused").withNamespace("default").endMetadata()
                .withNewSpec().withReplicas(0).endSpec()
                .withNewStatus().endStatus()
                .build();

        assertEquals(List.of(), analyze(deployment));
    }

    private List<Finding> analyze(Deployment deployment) {
        return analyzer.analyze(new ClusterSnapshot(List.of(), List.of(), List.of(deployment)));
    }

    private Finding ruleOf(List<Finding> findings, String ruleId) {
        Optional<Finding> match = findings.stream().filter(f -> ruleId.equals(f.ruleId())).findFirst();
        assertTrue(match.isPresent(), () -> "no finding with ruleId " + ruleId + " in " + findings);
        return match.get();
    }

    private Deployment load(String fixture) {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/" + fixture)) {
            assertNotNull(in, "fixture not found: " + fixture);
            return Serialization.unmarshal(in, Deployment.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to load fixture: " + fixture, e);
        }
    }
}
