package io.kubepilot.ai;

import io.kubepilot.common.RedactionLevel;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EvidenceRedactorTest {

    private static final String SECRET = "hunter2SuperSecretValue!9xQ";

    private static EvidenceRedactor redactor(RedactionLevel level) {
        EvidenceRedactor r = new EvidenceRedactor();
        r.configuredLevel = level.name().toLowerCase();
        return r;
    }

    private static Map<String, String> evidence(String key, String value) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }

    @Test
    void secretNeverSurvivesInAnEvidenceValue() {
        Map<String, String> out = redactor(RedactionLevel.STANDARD)
                .redactEvidence(evidence("dbPassword", SECRET));

        assertFalse(out.toString().contains(SECRET), () -> "leaked: " + out);
        assertTrue(out.get("dbPassword").startsWith("<redacted:"));
    }

    @Test
    void secretNeverSurvivesInsideFreeText() {
        String message = "auth failed: password=" + SECRET + " for user app";

        String out = redactor(RedactionLevel.STANDARD).redactText(message);

        assertFalse(out.contains(SECRET), () -> "leaked: " + out);
        assertTrue(out.contains("password="), () -> "lost the key name: " + out);
    }

    @Test
    void secretNeverSurvivesInUrlCredentials() {
        String message = "cannot connect to postgres://app:" + SECRET + "@db.internal:5432/app";

        String out = redactor(RedactionLevel.STANDARD).redactText(message);

        assertFalse(out.contains(SECRET), () -> "leaked: " + out);
        assertTrue(out.contains("db.internal:5432"), () -> "lost the host: " + out);
    }

    @Test
    void knownTokenPrefixesAreCaught() {
        String out = redactor(RedactionLevel.STANDARD)
                .redactText("used AKIAIOSFODNN7EXAMPLE and ghp_16CharsMinimumAbcdefghijklmnop here");

        assertFalse(out.contains("AKIAIOSFODNN7EXAMPLE"), () -> out);
        assertFalse(out.contains("ghp_16CharsMinimumAbcdefghijklmnop"), () -> out);
    }

    @Test
    void identifiersAndDiagnosticFactsArePreserved() {
        Map<String, String> in = new LinkedHashMap<>();
        in.put("reason", "CrashLoopBackOff");
        in.put("exitCode", "137");
        in.put("terminationReason", "OOMKilled");
        in.put("restartCount", "638");
        in.put("image", "nginx:nosuchtag");

        Map<String, String> out = redactor(RedactionLevel.STANDARD).redactEvidence(in);

        assertEquals(in, out, "diagnostic evidence must survive redaction untouched");
    }

    @Test
    void kubernetesMessagesAboutSecretsKeepNamesAndKeys() {
        String message = "couldn't find key DB_PASSWORD in Secret default/db-creds";

        String out = redactor(RedactionLevel.STANDARD).redactText(message);

        assertTrue(out.contains("DB_PASSWORD"), () -> "lost the key name: " + out);
        assertTrue(out.contains("db-creds"), () -> "lost the secret name: " + out);
    }

    @Test
    void podNamesAndImageDigestsAreNotMistakenForSecrets() {
        String message = "pod local-path-provisioner-855c7b7774-kpwmn failed pulling "
                + "docker.io/library/nginx@sha256:73aaf090f3d85aa34ee199857f03fa3a95c8ede2ffd4cc2cdb5b94e566b11662";

        String out = redactor(RedactionLevel.STANDARD).redactText(message);

        assertTrue(out.contains("local-path-provisioner-855c7b7774-kpwmn"), () -> out);
        assertTrue(out.contains("sha256:73aaf090f3d85aa34ee199857f03fa3a95c8ede2ffd4cc2cdb5b94e566b11662"), () -> out);
    }

    @Test
    void unknownKeysAreMaskedByDefault() {
        Map<String, String> out = redactor(RedactionLevel.STANDARD)
                .redactEvidence(evidence("somethingNewAnAnalyzerAdded", SECRET));

        assertFalse(out.toString().contains(SECRET), () -> "leaked: " + out);
    }

    @Test
    void emptyValuesAreReportedAsEmptyBecauseThatIsTheBug() {
        Map<String, String> out = redactor(RedactionLevel.STANDARD).redactEvidence(evidence("apiKey", ""));

        assertEquals("<empty>", out.get("apiKey"));
    }

    @Test
    void strictDropsFreeTextAndUnknownKeys() {
        Map<String, String> in = new LinkedHashMap<>();
        in.put("reason", "CrashLoopBackOff");
        in.put("message", "anything at all");
        in.put("unclassified", "value");

        Map<String, String> out = redactor(RedactionLevel.STRICT).redactEvidence(in);

        assertEquals(Map.of("reason", "CrashLoopBackOff"), out);
    }

    @Test
    void offPassesEverythingThrough() {
        Map<String, String> in = evidence("dbPassword", SECRET);

        assertEquals(in, redactor(RedactionLevel.OFF).redactEvidence(in));
        assertEquals(SECRET, redactor(RedactionLevel.OFF).redactText(SECRET));
    }

    @Test
    void redactionIsDeterministic() {
        String message = "password=" + SECRET;
        EvidenceRedactor r = redactor(RedactionLevel.STANDARD);

        assertEquals(r.redactText(message), r.redactText(message));
    }

    @Test
    void unknownLevelFallsBackToStandard() {
        EvidenceRedactor r = new EvidenceRedactor();
        r.configuredLevel = "nonsense";

        assertEquals(RedactionLevel.STANDARD, r.level());
    }
}
