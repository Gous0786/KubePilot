package io.kubepilot.ai;

import io.kubepilot.common.RedactionLevel;
import io.kubepilot.common.Redactor;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class EvidenceRedactor implements Redactor {

    private static final Set<String> SAFE_KEYS = Set.of(
            "reason",
            "terminationReason",
            "exitCode",
            "restartCount",
            "desiredReplicas",
            "availableReplicas",
            "initContainer",
            "finishedAt",
            "image");

    private static final Set<String> FREE_TEXT_KEYS = Set.of("message");

    private static final String EDGE_CHARS = "\"',;.()[]{}";

    private static final Pattern PEM_BLOCK = Pattern.compile(
            "-----BEGIN[^-]{0,40}-----[\\s\\S]*?-----END[^-]{0,40}-----");

    private static final Pattern JWT = Pattern.compile(
            "eyJ[A-Za-z0-9_\\-]{5,}\\.[A-Za-z0-9_\\-]{5,}\\.[A-Za-z0-9_\\-]*");

    private static final Pattern KNOWN_PREFIX = Pattern.compile(
            "(?:AKIA|ASIA)[A-Z0-9]{16}"
                    + "|gh[pousr]_[A-Za-z0-9]{20,}"
                    + "|sk-[A-Za-z0-9_\\-]{16,}"
                    + "|AIza[A-Za-z0-9_\\-]{30,}"
                    + "|xox[baprs]-[A-Za-z0-9\\-]{10,}");

    private static final Pattern URL_USERINFO = Pattern.compile(
            "([a-zA-Z][a-zA-Z0-9+.\\-]*://)[^\\s/@]+@");

    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)(password|passwd|pwd|secret|token|api[-_]?key|apikey|credential|authorization)"
                    + "(\\s*[=:]\\s*)\"?([^\\s\",;}]+)\"?");

    private static final Pattern DIGEST_OR_ID = Pattern.compile(
            "sha\\d{3}:[0-9a-f]+"
                    + "|^[0-9a-f]{32,}$"
                    + "|^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private static final Pattern KUBERNETES_NAME = Pattern.compile("[a-z0-9]([-a-z0-9]*[a-z0-9])?");

    private static final Pattern NON_SPACE = Pattern.compile("\\S+");

    private static final Pattern BASE64_ISH = Pattern.compile("[A-Za-z0-9+/=_\\-]+");

    private static final int ENTROPY_MIN_LENGTH = 24;
    private static final double ENTROPY_THRESHOLD = 4.2;

    @ConfigProperty(name = "kubepilot.ai.redaction", defaultValue = "standard")
    String configuredLevel;

    @Override
    public RedactionLevel level() {
        return RedactionLevel.from(configuredLevel);
    }

    @Override
    public Map<String, String> redactEvidence(Map<String, String> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return Map.of();
        }
        RedactionLevel level = level();
        if (level == RedactionLevel.OFF) {
            return evidence;
        }

        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : evidence.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            if (SAFE_KEYS.contains(key)) {
                out.put(key, value);
            } else if (level == RedactionLevel.STRICT) {
                continue;
            } else if (FREE_TEXT_KEYS.contains(key)) {
                out.put(key, redactText(value));
            } else {
                out.put(key, describe(value));
            }
        }
        return out;
    }

    @Override
    public String redactText(String text) {
        if (text == null || text.isEmpty() || level() == RedactionLevel.OFF) {
            return text;
        }
        String out = PEM_BLOCK.matcher(text).replaceAll(Matcher.quoteReplacement("<redacted:PEM block>"));
        out = JWT.matcher(out).replaceAll(m -> Matcher.quoteReplacement(describe(m.group())));
        out = KNOWN_PREFIX.matcher(out).replaceAll(m -> Matcher.quoteReplacement(describe(m.group())));
        out = URL_USERINFO.matcher(out).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + "<redacted>@"));
        out = ASSIGNMENT.matcher(out)
                .replaceAll(m -> Matcher.quoteReplacement(m.group(1) + m.group(2) + describe(m.group(3))));
        return maskHighEntropyTokens(out);
    }

    private static String maskHighEntropyTokens(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        Matcher m = NON_SPACE.matcher(text);
        int last = 0;
        while (m.find()) {
            sb.append(text, last, m.start());
            String token = m.group();
            String core = stripPunctuation(token);
            sb.append(looksLikeSecret(core) ? describe(core) : token);
            last = m.end();
        }
        return sb.append(text.substring(last)).toString();
    }

    private static boolean looksLikeSecret(String token) {
        if (token.length() < ENTROPY_MIN_LENGTH) {
            return false;
        }
        if (token.indexOf('/') >= 0 || token.indexOf('\\') >= 0) {
            return false;
        }
        if (KUBERNETES_NAME.matcher(token).matches()) {
            return false;
        }
        if (DIGEST_OR_ID.matcher(token).find()) {
            return false;
        }
        if (token.chars().filter(c -> c == '.').count() >= 2) {
            return false;
        }
        return shannonEntropy(token) >= ENTROPY_THRESHOLD;
    }

    private static String stripPunctuation(String token) {
        int start = 0;
        int end = token.length();
        while (start < end && EDGE_CHARS.indexOf(token.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && EDGE_CHARS.indexOf(token.charAt(end - 1)) >= 0) {
            end--;
        }
        return token.substring(start, end);
    }

    private static double shannonEntropy(String value) {
        int[] counts = new int[128];
        int total = 0;
        for (char c : value.toCharArray()) {
            if (c < 128) {
                counts[c]++;
                total++;
            }
        }
        if (total == 0) {
            return 0;
        }
        double entropy = 0;
        for (int count : counts) {
            if (count > 0) {
                double p = (double) count / total;
                entropy -= p * (Math.log(p) / Math.log(2));
            }
        }
        return entropy;
    }

    static String describe(String value) {
        if (value == null) {
            return "<redacted>";
        }
        String v = value.trim();
        if (v.isEmpty()) {
            return "<empty>";
        }
        if (v.startsWith("-----BEGIN")) {
            return "<redacted:PEM block>";
        }
        if (JWT.matcher(v).matches()) {
            return "<redacted:jwt" + jwtExpiry(v) + ">";
        }
        if (v.length() >= 16 && BASE64_ISH.matcher(v).matches()) {
            return "<redacted:base64," + v.length() + " chars>";
        }
        return "<redacted:" + v.length() + " chars>";
    }

    private static String jwtExpiry(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) {
                return "";
            }
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("\"exp\"\\s*:\\s*(\\d+)").matcher(payload);
            if (m.find()) {
                return ",expires=" + Instant.ofEpochSecond(Long.parseLong(m.group(1)));
            }
        } catch (RuntimeException ignored) {
            return "";
        }
        return "";
    }
}
