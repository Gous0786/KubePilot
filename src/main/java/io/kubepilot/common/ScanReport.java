package io.kubepilot.common;

import java.util.List;

public record ScanReport(
        List<Finding> findings,
        int analyzersSucceeded,
        List<String> analyzersFailed) {

    public ScanReport {
        findings = findings == null ? List.of() : List.copyOf(findings);
        analyzersFailed = analyzersFailed == null ? List.of() : List.copyOf(analyzersFailed);
    }
}