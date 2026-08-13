package io.kubepilot.analyzer;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.utils.Serialization;

import io.kubepilot.common.ClusterSnapshot;
import io.kubepilot.common.Finding;
import io.kubepilot.common.Severity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;



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
