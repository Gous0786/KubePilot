package io.kubepilot.analyzer;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.utils.Serialization;

import io.kubepilot.common.ClusterSnapshot;
import io.kubepilot.common.Finding;
import io.kubepilot.common.Severity;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PodAnalyzerTest {
    private final PodAnalyzer analyzer = new PodAnalyzer();

    @Test
    void detectsImagePullFailure(){
        Pod pod = load("pod-imagepull.yaml");

        List<Finding> findings = analyzer.analyze(new ClusterSnapshot(List.of(pod)));

        assertEquals(1, findings.size(), ()->"expected one finding , got "+findings);

        Finding f=findings.getFirst();
        assertEquals(PodAnalyzer.RULE_IMAGE_PULL, f.ruleId());
        assertEquals(Severity.ERROR, f.severity());
        assertEquals("Pod", f.ref().kind());
        assertEquals("broken",f.ref().name());
        assertEquals("broken",f.ref().container());
        assertTrue(f.evidence().get("image").contains("nginx:nosuchtag"));

    }

    @Test
    void detectsCrashLoopWhileTerminated() {
        Pod pod = load("pod-crashloop-terminated.yaml");

        List<Finding> findings = analyzer.analyze(new ClusterSnapshot(List.of(pod)));

        assertEquals(1, findings.size(), () -> "expected one finding, got " + findings);

        Finding f = findings.getFirst();
        assertEquals(PodAnalyzer.RULE_CRASH_LOOP, f.ruleId());
        assertEquals(Severity.ERROR, f.severity());
        assertEquals("crasher", f.ref().name());
        assertEquals("crasher", f.ref().container());
        assertEquals("1", f.evidence().get("exitCode"));
        assertTrue(f.summary().contains("exiting with code 1"), () -> "summary was: " + f.summary());
        assertTrue(Integer.parseInt(f.evidence().get("restartCount")) > 0);
    }
    @Test
    void detectsCrashLoopWhileWaiting() {
        Pod pod = load("pod-crashloop-waiting.yaml");

        List<Finding> findings = analyzer.analyze(new ClusterSnapshot(List.of(pod)));

        assertEquals(1, findings.size(), () -> "expected one finding, got " + findings);

        Finding f = findings.getFirst();
        assertEquals(PodAnalyzer.RULE_CRASH_LOOP, f.ruleId());
        assertEquals("CrashLoopBackOff", f.evidence().get("reason"));

        // Pulled from the PREVIOUS termination, since the container is not dead right now.
        assertEquals("Error", f.evidence().get("terminationReason"));
        assertEquals("1", f.evidence().get("exitCode"));
    }
    @Test
    void crashLoopHasSameFingerprintInBothPhases() {
        Finding terminated = analyzer
                .analyze(new ClusterSnapshot(List.of(load("pod-crashloop-terminated.yaml"))))
                .getFirst();
        Finding waiting = analyzer
                .analyze(new ClusterSnapshot(List.of(load("pod-crashloop-waiting.yaml"))))
                .getFirst();

        assertEquals(terminated.fingerprint(), waiting.fingerprint(),
                "same crash loop must keep one fingerprint across container state changes");
    }
    @Test
    void detectsUnschedulablePod() {
        Pod pod = load("pod-unschedulable.yaml");

        List<Finding> findings = analyzer.analyze(new ClusterSnapshot(List.of(pod)));

        assertEquals(1, findings.size(), () -> "expected one finding, got " + findings);

        Finding f = findings.getFirst();
        assertEquals(PodAnalyzer.RULE_UNSCHEDULABLE, f.ruleId());
        assertEquals(Severity.WARNING, f.severity());
        assertEquals("hungry", f.ref().name());
        assertNull(f.ref().container(), "unschedulable is a pod-level problem, not a container one");
        assertEquals("Unschedulable", f.evidence().get("reason"));
    }
    @Test
    void emptySnapshotProducesNoFindings() {
        assertEquals(List.of(), analyzer.analyze(ClusterSnapshot.empty()));
    }

    private Pod load(String fixture){
        try(InputStream in=getClass().getResourceAsStream("/fixtures/"+fixture)) {
            assertNotNull(in, "fixture not found: "+fixture);
            return Serialization.unmarshal(in, Pod.class);
        }
        catch(Exception e){
            throw new IllegalStateException("failed to load fixture: "+fixture, e);
        }

    }
}
