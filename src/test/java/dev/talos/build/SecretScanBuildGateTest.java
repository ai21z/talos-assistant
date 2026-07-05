package dev.talos.build;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Secret scan build gate")
class SecretScanBuildGateTest {

    private static final Path ROOT = Path.of("").toAbsolutePath().normalize();
    private static final Pattern GITLEAKS_FINGERPRINT = Pattern.compile(
            "^[0-9a-f]{40}:.+:(?:private-key|generic-api-key|jwt|stripe-access-token):\\d+$");

    @Test
    @DisplayName("gitleaksLocal uses a pinned redacted history scan with exact false-positive ignores")
    void gitleaksLocalUsesPinnedRedactedHistoryScan() throws Exception {
        String build = read("build.gradle.kts");

        assertTrue(build.contains("ghcr.io/gitleaks/gitleaks:v8.30.1"),
                "Gitleaks image must be pinned for repeatable release hygiene");
        assertFalse(build.contains("ghcr.io/gitleaks/gitleaks:latest"),
                "Gitleaks release gate must not float on latest");
        assertTrue(build.contains("\"--redact=100\""),
                "Gitleaks console/report output must redact matched secrets");
        assertTrue(build.contains("\"--gitleaks-ignore-path\""),
                "Historical false positives must be explicit, not broad path exclusions");
        assertTrue(build.contains("\"/repo/.gitleaksignore\""),
                "Dockerized scan must use the tracked ignore file");
        assertTrue(build.contains("\"--report-format\""));
        assertTrue(build.contains("\"json\""));
        assertTrue(build.contains("\"--report-path\""));
        assertTrue(build.contains("\"/repo/build/reports/talos/gitleaks-history.json\""),
                "Gitleaks output must land under local build reports");
    }

    @Test
    @DisplayName("gitleaks ignore list contains only exact historical fixture fingerprints")
    void gitleaksIgnoreListContainsOnlyExactHistoricalFingerprints() throws Exception {
        Path ignore = ROOT.resolve(".gitleaksignore");
        assertTrue(Files.isRegularFile(ignore), ".gitleaksignore must be tracked for historical fixture ignores");

        List<String> entries = Files.readAllLines(ignore, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();

        assertTrue(entries.size() >= 7, "expected exact historical fingerprints for known fake fixture findings");
        for (String entry : entries) {
            assertTrue(GITLEAKS_FINGERPRINT.matcher(entry).matches(),
                    "Gitleaks ignore entries must be exact fingerprints, not wildcard/path allowlists: " + entry);
            assertFalse(entry.contains("*"), "Gitleaks ignores must not use wildcard allowlists: " + entry);
        }
    }

    @Test
    @DisplayName("current tracked fake fixtures avoid raw scanner-triggering secret literals")
    void currentTrackedFixturesAvoidRawScannerTriggeringSecretLiterals() throws Exception {
        String approvalDiff = read("src/test/java/dev/talos/runtime/ApprovalDiffPreviewTest.java");
        String redactor = read("src/test/java/dev/talos/core/security/RedactorTest.java");
        String commandRunner = read("src/test/java/dev/talos/runtime/command/ProcessCommandRunnerTest.java");
        String sanitizer = read("src/test/java/dev/talos/safety/ProtectedContentSanitizerTest.java");
        String capabilityAudit = read("scripts/run-capability-live-audit.ps1");

        assertFalse(approvalDiff.contains(fakeDashToken()));
        assertFalse(redactor.contains(fakeUnderscoreToken()));
        assertFalse(redactor.contains(fakeGenericToken()));
        assertFalse(redactor.contains(fakeJwtHeaderPrefix()));
        assertFalse(commandRunner.contains(privateKeyBeginHeader()));
        assertFalse(sanitizer.contains(privateKeyBeginHeader()));
        assertFalse(capabilityAudit.contains(privateRetrieveKeyLine()));
    }

    private static String read(String relative) throws Exception {
        return Files.readString(ROOT.resolve(relative), StandardCharsets.UTF_8);
    }

    private static String fakeDashToken() {
        return "sk" + "-live" + "-12345";
    }

    private static String fakeUnderscoreToken() {
        return "sk" + "_live_" + "aBcDeFgHiJkLmNoP";
    }

    private static String fakeGenericToken() {
        return "ABCDEFGH" + "abcdefgh" + "12345678";
    }

    private static String fakeJwtHeaderPrefix() {
        return "ey" + "JhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.";
    }

    private static String privateKeyBeginHeader() {
        return "-----BEGIN " + "PRIVATE KEY-----";
    }

    private static String privateRetrieveKeyLine() {
        return "Key = \"" + "19-private" + "-retrieve-disabled\"";
    }
}
