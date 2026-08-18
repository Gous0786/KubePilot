package io.kubepilot.common;

import java.util.Map;

public interface Redactor {

    RedactionLevel level();

    Map<String, String> redactEvidence(Map<String, String> evidence);

    String redactText(String text);
}
