package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** A private document named by any spelling of its path may be read, and no other private document may. */
class PrivateDocumentNamedTargetGuardTest {
    private static final String OUTSIDE_TARGET_SET = "outside the current requested private document target set";

    @TempDir Path temporary;
    Path root;
    Context ctx;

    @BeforeEach void useTheResolvedWorkspace() throws Exception {
        root = temporary.toRealPath();
        Files.createDirectories(root.resolve("docs"));
        Files.createDirectories(root.resolve("archive"));
        Files.writeString(root.resolve("docs").resolve("report.pdf"), "%PDF-1.4 placeholder\n");
        Files.writeString(root.resolve("docs").resolve("other.pdf"), "%PDF-1.4 placeholder\n");
        Files.writeString(root.resolve("archive").resolve("report.pdf"), "%PDF-1.4 placeholder\n");
        ctx = Context.builder(privateModeConfig())
                .sandbox(new Sandbox(root, Map.of()))
                .llm(LlmClient.scripted(List.of()))
                .build();
    }

    @Test
    void relativeTargetAllowsBothSpellingsOfTheNamedDocument() {
        TaskContract contract = readOnlyContract("Summarize docs/report.pdf.", Set.of("docs/report.pdf"), Set.of());
        String absolute = root.resolve("docs").resolve("report.pdf").toString();

        assertNull(diagnostic(contract, "docs/report.pdf"));
        assertNull(diagnostic(contract, absolute));
        assertTrue(diagnostic(contract, "archive/report.pdf").contains(OUTSIDE_TARGET_SET));
    }

    @Test
    void absoluteTargetAllowsReadingThatDocumentUnderEverySpelling() {
        String absolute = root.resolve("docs").resolve("report.pdf").toString();
        String target = absolute.replace('\\', '/');
        TaskContract contract = readOnlyContract("Summarize " + target + ".", Set.of(target), Set.of());

        assertNull(diagnostic(contract, "docs/report.pdf"));
        assertNull(diagnostic(contract, absolute));
        assertNull(diagnostic(contract, target));
    }

    @Test
    void absoluteTargetStillBlocksADifferentPrivateDocument() {
        String target = root.resolve("docs").resolve("report.pdf").toString().replace('\\', '/');
        TaskContract contract = readOnlyContract("Summarize " + target + ".", Set.of(target), Set.of());

        for (String path : List.of(
                "archive/report.pdf",
                root.resolve("archive").resolve("report.pdf").toString(),
                "docs/other.pdf")) {
            String diagnostic = diagnostic(contract, path);
            assertNotNull(diagnostic, path);
            assertTrue(diagnostic.contains(OUTSIDE_TARGET_SET), diagnostic);
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void driveQualifiedTargetFromTheRequestAllowsThatDocument() {
        String named = root.resolve("docs").resolve("report.pdf").toString();
        String target = named.replace('\\', '/');
        TaskContract contract = TaskContractResolver.fromUserRequest("Read " + named + " and tell me what it says.");
        assertTrue(contract.expectedTargets().contains(target) || contract.sourceEvidenceTargets().contains(target),
                contract.expectedTargets() + " " + contract.sourceEvidenceTargets());

        assertNull(diagnostic(contract, "docs/report.pdf"));
        assertNull(diagnostic(contract, named));
        assertTrue(diagnostic(contract, "archive/report.pdf").contains(OUTSIDE_TARGET_SET));
    }

    private String diagnostic(TaskContract contract, String path) {
        return PrivateDocumentNamedTargetGuard.diagnostic(
                new ToolCall("talos.read_file", Map.of("path", path)), ctx, root, contract, path);
    }

    private static Config privateModeConfig() {
        Config cfg = new Config(null);
        cfg.data.put("privacy", new LinkedHashMap<>(Map.of("mode", "private")));
        return cfg;
    }

    private static TaskContract readOnlyContract(String request, Set<String> expectedTargets, Set<String> sourceTargets) {
        return new TaskContract(TaskType.READ_ONLY_QA, false, false, false,
                expectedTargets, sourceTargets, Set.of(), request, "test-contract");
    }
}
