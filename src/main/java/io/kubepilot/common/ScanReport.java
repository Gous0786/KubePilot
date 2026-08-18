package io.kubepilot.common;

import java.util.List;
import java.util.Map;

public record ScanReport(
        List<Finding> findings,
        int analyzersSucceeded,
        List<String> analyzersFailed,
        Map<String, Diagnosis> diagnoses) {

    public ScanReport {
        findings = findings == null ? List.of() : List.copyOf(findings);
        analyzersFailed = analyzersFailed == null ? List.of() : List.copyOf(analyzersFailed);
        diagnoses = diagnoses == null ? Map.of() : Map.copyOf(diagnoses);
    }

    public ScanReport(List<Finding> findings, int analyzersSucceeded, List<String> analyzersFailed) {
        this(findings, analyzersSucceeded, analyzersFailed, Map.of());
    }

    public ScanReport withDiagnoses(Map<String, Diagnosis> diagnoses) {
        return new ScanReport(findings, analyzersSucceeded, analyzersFailed, diagnoses);
    }
}
