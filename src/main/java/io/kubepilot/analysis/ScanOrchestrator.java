package io.kubepilot.analysis;

import io.kubepilot.analyzer.Analyzer;
import io.kubepilot.kubernetes.ClusterReader;
import io.kubepilot.common.ClusterSnapshot;
import io.kubepilot.common.Finding;
import io.kubepilot.common.ScanReport;

import io.quarkus.arc.All;
import io.quarkus.logging.Log;


import java.util.List;
import java.util.ArrayList;


import jakarta.enterprise.context.ApplicationScoped;


@ApplicationScoped
public class ScanOrchestrator {
    private final List<Analyzer> analyzers;
    private final ClusterReader reader;

    public ScanOrchestrator(@All List<Analyzer> analyzers, ClusterReader reader) {
        this.analyzers = analyzers;
        this.reader = reader;
    }

    public ScanReport scan(){
        ClusterSnapshot snapshot = reader.readCluster();
        List<Finding> findings = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int succeeded = 0;

        for(Analyzer analyzer : analyzers){
            try{
                findings.addAll(analyzer.analyze(snapshot));
                succeeded++;
            } catch (Exception e) {
                // Keep going: one broken analyzer must not discard the others' findings.
                // Record the id so the caller can tell an incomplete scan from a clean one.
                Log.warnf(e, "Analyzer %s failed", analyzer.id());
                failed.add(analyzer.id());
            }
        }
        return new ScanReport(findings, succeeded, failed);
    }
    
}
