package io.kubepilot.ai;

import io.kubepilot.common.Diagnosis;
import io.quarkus.cache.CacheKey;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class DiagnosisCache {

    private final DiagnosisAiService aiService;

    public DiagnosisCache(DiagnosisAiService aiService) {
        this.aiService = aiService;
    }

    @CacheResult(cacheName = "diagnosis")
    public Diagnosis get(@CacheKey String key,
                         String namespace,
                         String kind,
                         String name,
                         int affectedCount,
                         String findings) {
        return aiService.diagnose(namespace, kind, name, affectedCount, findings);
    }
}
