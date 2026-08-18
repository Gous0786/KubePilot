package io.kubepilot.ai;

import io.kubepilot.common.Diagnosis;
import io.kubepilot.common.DiagnosisEngine;
import io.kubepilot.common.Finding;
import io.kubepilot.common.Redactor;
import io.kubepilot.common.ResourceRef;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Collectors;

@ApplicationScoped
public class LlmDiagnosisEngine implements DiagnosisEngine {

    private final DiagnosisCache cache;
    private final Redactor redactor;

    @ConfigProperty(name = "quarkus.langchain4j.openai.chat-model.model-name", defaultValue = "unknown")
    String modelName;

    public LlmDiagnosisEngine(DiagnosisCache cache, Redactor redactor) {
        this.cache = cache;
        this.redactor = redactor;
    }

    @Override
    @Timeout(value = 90, unit = ChronoUnit.SECONDS)
    @Retry(maxRetries = 2, delay = 2, delayUnit = ChronoUnit.SECONDS)
    @CircuitBreaker(requestVolumeThreshold = 4, failureRatio = 0.5, delay = 60, delayUnit = ChronoUnit.SECONDS)
    @Fallback(fallbackMethod = "modelUnavailable")
    public Diagnosis diagnose(ResourceRef workload, List<Finding> findings) {
        return cache.get(
                cacheKey(workload, findings),
                workload.namespace(),
                workload.kind(),
                workload.name(),
                affectedInstances(findings),
                format(findings));
    }

    @SuppressWarnings("unused")
    private Diagnosis modelUnavailable(ResourceRef workload, List<Finding> findings) {
        Log.warnf("No diagnosis for %s: model unavailable", workload);
        return Diagnosis.unavailable("No diagnosis available: the language model could not be reached.");
    }

    private String cacheKey(ResourceRef workload, List<Finding> findings) {
        String problems = findings.stream()
                .map(Finding::fingerprint)
                .sorted()
                .collect(Collectors.joining(","));

        return String.join("::",
                modelName,
                workload.kind() + "/" + workload.namespace() + "/" + workload.name(),
                problems);
    }

    private static int affectedInstances(List<Finding> findings) {
        return findings.stream().mapToInt(Finding::affectedCount).max().orElse(1);
    }

    private String format(List<Finding> findings) {
        StringJoiner out = new StringJoiner("\n\n");
        for (Finding f : findings) {
            StringJoiner block = new StringJoiner("\n");
            block.add("- rule: " + f.ruleId());
            block.add("  severity: " + f.severity());
            block.add("  kind: " + f.ref().kind());
            block.add("  name: " + f.ref().name());
            if (f.ref().container() != null) {
                block.add("  container: " + f.ref().container());
            }
            block.add("  summary: " + f.summary());
            for (Map.Entry<String, String> e : redactor.redactEvidence(f.evidence()).entrySet()) {
                block.add("  " + e.getKey() + ": " + e.getValue());
            }
            if (!f.affected().isEmpty()) {
                block.add("  affected: " + String.join(", ", f.affected()));
            }
            out.add(block.toString());
        }
        return redactor.redactText(out.toString());
    }
}
