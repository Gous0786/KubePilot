package io.kubepilot.common;

import java.util.List;

public interface DiagnosisEngine {

    Diagnosis diagnose(ResourceRef workload, List<Finding> findings);
}
