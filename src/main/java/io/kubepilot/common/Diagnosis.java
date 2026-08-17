package io.kubepilot.common;

import java.util.List;

public record Diagnosis(
        String rootCause,
        String explanation,
        List<String> remediation,
        Confidence confidence) {

    public Diagnosis {
        remediation = remediation == null ? List.of() : List.copyOf(remediation);
        confidence = confidence == null ? Confidence.LOW : confidence;
    }

    public static Diagnosis unavailable(String reason) {
        return new Diagnosis(reason, null, List.of(), Confidence.LOW);
    }
}
