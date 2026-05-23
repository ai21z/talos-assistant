package dev.talos.runtime.verification;

import dev.talos.runtime.capability.ArtifactOperation;
import dev.talos.runtime.capability.CapabilityProfile;
import dev.talos.runtime.capability.TargetSurface;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetScopeStaticVerifierTest {

    @TempDir
    Path workspace;

    @Test
    void expectedAndForbiddenTargetsUseSameTargetScopeMatching() {
        TaskContract contract = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("script.js"),
                Set.of("scripts.js"),
                "Replace .missing-button with #submit in script.js. Do not edit scripts.js.");

        TargetScopeStaticVerifier.Result result = TargetScopeStaticVerifier.verify(
                contract,
                workspace,
                CapabilityProfile.none(),
                Set.of("script.js", "scripts.js"),
                Set.of(),
                Set.of());

        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("scripts.js: forbidden mutation target was changed")),
                result.problems().toString());
        assertFalse(result.facts().stream()
                        .anyMatch(f -> f.contains("Expected mutation target(s) were updated")),
                result.facts().toString());
    }

    @Test
    void onlyTargetRequestFailsWhenAdditionalMutationDoesNotMatchExpectedTarget() {
        TaskContract contract = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("script.js"),
                Set.of(),
                "Only change script.js.");

        TargetScopeStaticVerifier.Result result = TargetScopeStaticVerifier.verify(
                contract,
                workspace,
                CapabilityProfile.none(),
                Set.of("script.js", "scripts.js"),
                Set.of(),
                Set.of());

        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains(
                                "scripts.js: non-requested mutation target was changed under an only-target request")),
                result.problems().toString());
        assertFalse(result.facts().stream()
                        .anyMatch(f -> f.contains("Expected mutation target(s) were updated")),
                result.facts().toString());
    }

    @Test
    void staticWebRepairContextTargetsCanBeSatisfiedWithoutDirectMutation() throws Exception {
        Files.writeString(workspace.resolve("index.html"), "<html><body><script src=\"script.js\"></script></body></html>");
        Files.writeString(workspace.resolve("styles.css"), "body { color: white; }");
        Files.writeString(workspace.resolve("script.js"), "document.querySelector('#run-button');");
        TaskContract contract = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("index.html", "styles.css", "script.js"),
                Set.of(),
                "Fix the static web button fixture. Keep filenames index.html, styles.css, and script.js.");

        TargetScopeStaticVerifier.Result result = TargetScopeStaticVerifier.verify(
                contract,
                workspace,
                CapabilityProfile.staticWeb(ArtifactOperation.REPAIR, TargetSurface.FUNCTIONAL_WEB),
                Set.of("script.js"),
                Set.of(),
                Set.of());

        assertFalse(result.problems().stream()
                        .anyMatch(p -> p.contains("expected target was not successfully mutated")),
                result.problems().toString());
        assertTrue(result.facts().stream()
                        .anyMatch(f -> f.contains(
                                "Expected mutation target(s) and static web context target(s) were satisfied")),
                result.facts().toString());
    }

    @Test
    void expectedTargetMatchingPreservesWindowsCaseInsensitiveOption() {
        assertTrue(TargetScopeStaticVerifier.expectedTargetMatches("Index.html", "index.html", true));
        assertTrue(TargetScopeStaticVerifier.expectedTargetMatches(".\\Index.html", "./index.html", true));
        assertFalse(TargetScopeStaticVerifier.expectedTargetMatches("scripts.js", "script.js", true));
        assertFalse(TargetScopeStaticVerifier.expectedTargetMatches("Index.html", "index.html", false));
    }
}
