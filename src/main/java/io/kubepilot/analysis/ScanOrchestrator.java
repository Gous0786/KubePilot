package io.kubepilot.analysis;

import io.kubepilot.analyzer.Analyzer;
import io.kubepilot.kubernetes.ClusterReader;
import io.kubepilot.common.ClusterSnapshot;
import io.kubepilot.common.Diagnosis;
import io.kubepilot.common.DiagnosisEngine;
import io.kubepilot.common.Finding;
import io.kubepilot.common.ResourceRef;
import io.kubepilot.common.ScanReport;

import io.quarkus.arc.All;
import io.quarkus.logging.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;


@ApplicationScoped
public class ScanOrchestrator {
    private final List<Analyzer> analyzers;
    private final ClusterReader reader;
    private final DiagnosisEngine engine;

    public ScanOrchestrator(@All List<Analyzer> analyzers, ClusterReader reader, DiagnosisEngine engine) {
        this.analyzers = analyzers;
        this.reader = reader;
        this.engine = engine;
    }

    public ScanReport scan() {
        return scan(null, false);
    }

    public ScanReport scan(String namespace) {
        return scan(namespace, false);
    }

    public ScanReport scan(String namespace, boolean explain) {
        ScanReport report = runAnalyzers(snapshotFor(namespace));
        return explain ? report.withDiagnoses(diagnose(report.findings())) : report;
    }

    private ClusterSnapshot snapshotFor(String namespace) {
        return (namespace == null || namespace.isBlank())
                ? reader.readCluster()
                : reader.readCluster(namespace);
    }

    private ScanReport runAnalyzers(ClusterSnapshot snapshot) {
        List<Finding> findings = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int succeeded = 0;

        for (Analyzer analyzer : analyzers) {
            try {
                findings.addAll(analyzer.analyze(snapshot));
                succeeded++;
            } catch (Exception e) {
                Log.warnf(e, "Analyzer %s failed", analyzer.id());
                failed.add(analyzer.id());
            }
        }
        return new ScanReport(FindingGrouper.group(findings), succeeded, failed);
    }

    private Map<String, Diagnosis> diagnose(List<Finding> findings) {
        Map<ResourceRef, List<Finding>> byWorkload = findings.stream()
                .collect(Collectors.groupingBy(f -> f.ref().workload(),
                        LinkedHashMap::new, Collectors.toList()));

        Map<String, Diagnosis> diagnoses = new LinkedHashMap<>();
        byWorkload.forEach((workload, group) -> {
            Log.debugf("Diagnosing %s from %d finding(s)", workload, group.size());
            diagnoses.put(workload.toString(), engine.diagnose(workload, group));
        });
        return diagnoses;
    }
}
