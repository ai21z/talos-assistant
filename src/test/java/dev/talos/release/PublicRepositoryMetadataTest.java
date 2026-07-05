package dev.talos.release;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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

    private static String read(String relative) throws IOException {
        return Files.readString(ROOT.resolve(relative), StandardCharsets.UTF_8);
    }
}
