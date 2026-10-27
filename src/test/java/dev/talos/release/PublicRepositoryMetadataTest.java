package dev.talos.release;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Public repository metadata truth")
class PublicRepositoryMetadataTest {
    private static final Path ROOT = Path.of("").toAbsolutePath().normalize();

    @Test
    @DisplayName("notice uses current Talos identity and no template owner")
    void noticeUsesCurrentIdentity() throws Exception {
        String notice = read("NOTICE");

        assertTrue(notice.contains("Talos"), "NOTICE must name the current product");
        assertTrue(notice.contains("Aris Zounarakis"), "NOTICE must name the current owner");
        assertFalse(notice.contains("LOQ-J"), "NOTICE must not publish the old product identity");
        assertFalse(notice.contains("<Your Name or Org>"), "NOTICE must not publish template placeholders");
    }

    @Test
    @DisplayName("contributing guide uses current GitHub and Gradle workflow")
    void contributingUsesCurrentWorkflow() throws Exception {
        String contributing = read("CONTRIBUTING.md");

        assertTrue(contributing.contains("# Contributing to Talos"));
        assertTrue(contributing.contains("GitHub"));
        assertTrue(contributing.toLowerCase().contains("pull request"));
        assertTrue(contributing.contains(".\\gradlew.bat check --no-daemon")
                        || contributing.contains("./gradlew.bat check --no-daemon"));
        assertTrue(contributing.contains(".\\gradlew.bat installDist")
                        || contributing.contains("./gradlew.bat installDist"));
        assertTrue(contributing.contains("build\\install\\talos\\bin\\talos.bat --version")
                        || contributing.contains("build/install/talos/bin/talos --version"));

        assertFalse(contributing.contains("LOQ-J"));
        assertFalse(contributing.contains("Merge Request"));
        assertFalse(contributing.contains("spotlessApply"));
        assertFalse(contributing.contains("build/install/loqj"));
    }

    @Test
    @DisplayName("third-party notices cover major declared dependencies without guessed licenses")
    void thirdPartyNoticesCoverMajorDependencies() throws Exception {
        String notices = read("THIRD-PARTY-NOTICES.md");

        for (String component : new String[] {
                "Apache Lucene",
                "Picocli",
                "JLine",
                "SQLite JDBC",
                "Jackson",
                "SLF4J",
                "Logback",
                "Apache PDFBox",
                "Apache POI",
                "HtmlUnit",
                "java-diff-utils",
                "JUnit",
                "ArchUnit"
        }) {
            assertTrue(notices.contains(component), "missing third-party notice for " + component);
        }
        assertFalse(notices.contains("(e.g."), "third-party notices must not publish guessed license wording");
        assertFalse(notices.contains("non-exhaustive"), "third-party notices should name the major declared deps");
        assertFalse(notices.contains("LOQ-J"));
    }

    @Test
    @DisplayName("user command reference includes doctor surfaces")
    void userCommandsIncludeDoctor() throws Exception {
        String commands = read("docs/user/commands.md");

        assertTrue(commands.contains("talos doctor"), "top-level doctor command must be documented");
        assertTrue(commands.contains("talos doctor --start"),
                "model-starting doctor smoke must be documented");
        assertTrue(commands.contains("/doctor"), "REPL doctor command must be documented");
    }

    @Test
    @DisplayName("public quality tooling is local and community first")
    void qualityToolingIsLocalAndCommunityFirst() throws Exception {
        String qodana = read("qodana.yaml");
        String buildAndTest = read("docs/development/build-and-test.md");
        String developerSetup = read("docs/development/developer-setup.md");
        String qualityReports = read("docs/development/quality-reports.md");

        String normalizedQodana = qodana.replace("\r\n", "\n");
        int excludeStart = normalizedQodana.indexOf("exclude:\n");
        int failureConditionsStart = normalizedQodana.indexOf("failureConditions:\n");
        int includeStart = normalizedQodana.indexOf("include:\n");
        assertTrue(excludeStart >= 0 && failureConditionsStart > excludeStart,
                "public Qodana config must have a bounded exclusion block");
        assertTrue(includeStart > failureConditionsStart,
                "public Qodana config must have an include block after failure conditions");
        String excludeBlock = normalizedQodana.substring(excludeStart, failureConditionsStart);
        String includeBlock = normalizedQodana.substring(includeStart);
        String unstableInspection = "  - name: ExtractMethodRecommender";
        assertTrue(excludeBlock.contains(unstableInspection + "\n\n"),
                "the unstable optional inspection must be excluded globally");
        assertTrue(excludeBlock.indexOf(unstableInspection) == excludeBlock.lastIndexOf(unstableInspection),
                "the unstable optional inspection must have exactly one exclusion");
        assertFalse(includeBlock.contains("ExtractMethodRecommender"),
                "the unstable optional inspection must not be explicitly included");

        assertTrue(qodana.contains("jetbrains/qodana-jvm-community:2026.1"),
                "public Qodana config must use the community linter");
        assertFalse(qodana.contains("linter: jetbrains/qodana-jvm:2026.1"),
                "public Qodana config must not point at the paid/full JVM linter");
        assertFalse(qodana.contains("QODANA_TOKEN"),
                "public Qodana config must not imply a cloud token is required");
        assertFalse(Files.exists(ROOT.resolve("qodana_LOCAL-DISABLED.yaml")),
                "local disabled Qodana config must not be tracked in the public repo");
        assertFalse(Files.exists(ROOT.resolve("reports-disabled/README.md")),
                "tracked reports-disabled docs must not duplicate the maintained quality reports page");

        String publicDocs = buildAndTest + "\n" + developerSetup + "\n" + qualityReports;
        assertTrue(publicDocs.contains("No private token is required for the normal build and test loop."));
        assertTrue(publicDocs.contains("Qodana Community"));
        assertTrue(publicDocs.contains("Generated report outputs stay local"));
        assertTrue(publicDocs.contains("reports/"));
    }

    @Test
    @DisplayName("quality guidance distinguishes exact accepted provenance from absent native metadata")
    void qualityGuidanceMatchesExactProvenanceContract() throws Exception {
        String guidance = read("docs/development/quality-reports.md");
        assertTrue(guidance.contains("accepts only `qodana-results-match-current-candidate`"));
        assertTrue(guidance.contains("`qodana-provenance-incomplete` is not an accepted status"));
        assertTrue(guidance.contains("Explicit native branch/revision conflicts still reject"));
        assertFalse(guidance.contains("does not reject `qodana-provenance-incomplete`"));
    }

    @Test
    @DisplayName("package ownership map covers actual top-level source packages and bounds gate claims")
    void packageMapCoversCurrentSourcePackagesAndNamesGateLimits() throws Exception {
        String map = read("docs/architecture/package-map.md");
        Set<String> actual = new TreeSet<>();
        try (var packages = Files.list(ROOT.resolve("src/main/java/dev/talos"))) {
            packages.filter(Files::isDirectory)
                    .forEach(path -> actual.add("dev.talos." + path.getFileName()));
        }
        Set<String> documented = new TreeSet<>();
        var rows = Pattern.compile("(?m)^\\| `(?<name>dev\\.talos\\.[a-z]+)` \\|").matcher(map);
        while (rows.find()) documented.add(rows.group("name"));
        assertEquals(actual, documented, "ownership rows must cover actual packages without stale entries");
        assertTrue(map.contains("six selected rules"));
        assertTrue(map.contains("plus five selected rules"));
        assertTrue(map.contains("not complete architectural correctness"));
        assertTrue(map.contains("`dev.talos.core.security`"));
    }

    private static String read(String relative) throws IOException {
        return Files.readString(ROOT.resolve(relative), StandardCharsets.UTF_8);
    }
}
