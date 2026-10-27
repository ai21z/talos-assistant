package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnSourceEvidenceCapture;
import dev.talos.tools.ToolResult;
import dev.talos.tools.WorkspacePathIdentity;
import java.io.IOException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SourceDerivedEvidenceGuardTest {
    @TempDir
    Path workspace;

    @Test
    void sourceDerivedWriteBeforeSourceReadReturnsExactDiagnostic() {
        String request = "Summarize long-notes.txt into docs/summary.md.";
        TaskContract contract = TaskContractResolver.fromUserRequest(request);
        LoopState state = loopState(request);
        ToolCall write = new ToolCall(
                "talos.write_file",
                Map.of("path", "docs/summary.md", "content", "- Ungrounded summary."));

        SourceDerivedEvidenceGuard.RequiredSourceEvidenceDiagnostic diagnostic =
                SourceDerivedEvidenceGuard.requiredSourceEvidenceDiagnostic(
                        state,
                        contract,
                        write,
                        "docs/summary.md");

        assertNotNull(diagnostic);
        assertEquals(List.of("long-notes.txt"), diagnostic.missingSourceTargets());
        assertEquals(
                "Source-derived artifact write blocked before approval: the current task requires reading "
                        + "source target(s) long-notes.txt before writing `docs/summary.md`. "
                        + "Call talos.read_file for the source target(s) first, then retry the write. "
                        + "No approval was requested and no file was changed.",
                diagnostic.message());
    }

    @Test
    void sourceDerivedWriteAfterSourceReadReturnsNoDiagnostic() {
        String request = "Summarize long-notes.txt into docs/summary.md.";
        TaskContract contract = TaskContractResolver.fromUserRequest(request);
        LoopState state = loopState(request);
        state.pathsReadThisTurn.add("long-notes.txt");
        ToolCall write = new ToolCall(
                "talos.write_file",
                Map.of("path", "docs/summary.md", "content", "- Grounded summary."));

        SourceDerivedEvidenceGuard.RequiredSourceEvidenceDiagnostic diagnostic =
                SourceDerivedEvidenceGuard.requiredSourceEvidenceDiagnostic(
                        state,
                        contract,
                        write,
                        "docs/summary.md");

        assertNull(diagnostic);
    }

    @Test
    void overlappingRequiredTargetStillRequiresItsOwnRecordedReadBeforeMutation() {
        TaskContract contract = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                java.util.Set.of("style.css"),
                java.util.Set.of("style.css"),
                java.util.Set.of(),
                "Read style.css, then update style.css.",
                "required-post-read-mutation");
        LoopState state = loopState(contract.originalUserRequest());
        ToolCall write = new ToolCall(
                "talos.write_file",
                Map.of("path", "style.css", "content", "body { color: pink; }"));

        var beforeRead = SourceDerivedEvidenceGuard.requiredSourceEvidenceDiagnostic(
                state,
                contract,
                write,
                "style.css");
        assertNotNull(beforeRead);
        assertEquals(List.of("style.css"), beforeRead.missingSourceTargets());

        state.pathsReadThisTurn.add("wrong.css");
        assertNotNull(SourceDerivedEvidenceGuard.requiredSourceEvidenceDiagnostic(
                state,
                contract,
                write,
                "style.css"));

        state.pathsReadThisTurn.add("style.css");
        var afterRead = SourceDerivedEvidenceGuard.requiredSourceEvidenceDiagnostic(
                state,
                contract,
                write,
                "style.css");
        assertNull(afterRead);
    }

    @Test
    void missingSourceTargetsAreNormalizedUniqueAndDeterministicallySorted() {
        TaskContract contract = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                java.util.Set.of("target.css"),
                new java.util.LinkedHashSet<>(List.of(
                        "z.css",
                        "./a.css",
                        "middle.css",
                        "a.css")),
                java.util.Set.of(),
                "Read the sources, then update target.css.",
                "deterministic-source-order");
        LoopState state = loopState(contract.originalUserRequest());

        assertEquals(
                List.of("a.css", "middle.css", "z.css"),
                SourceDerivedEvidenceGuard.missingSourceEvidenceTargets(state, contract));
    }

    @Test
    void nonSourceDerivedMutationReturnsNoDiagnostic() {
        String request = "Read long-notes.txt.";
        TaskContract contract = TaskContractResolver.fromUserRequest(request);
        LoopState state = loopState(request);
        ToolCall read = new ToolCall("talos.read_file", Map.of("path", "long-notes.txt"));

        SourceDerivedEvidenceGuard.RequiredSourceEvidenceDiagnostic diagnostic =
                SourceDerivedEvidenceGuard.requiredSourceEvidenceDiagnostic(
                        state,
                        contract,
                        read,
                        "long-notes.txt");

        assertNull(diagnostic);
    }

    @Test
    void executionStageDelegatesSourceEvidenceBeforeReadDiagnosticToGuard() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolCallPreExecutionGuardChain.java"));

        assertTrue(source.contains("SourceDerivedEvidenceGuard.requiredSourceEvidenceDiagnostic"), source);
        assertFalse(source.contains("private static List<String> missingSourceEvidenceTargets"), source);
        assertFalse(source.contains("private static String sourceEvidenceRequiredDiagnostic"), source);
    }

    private LoopState loopState(String request) {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user(request)));
        Context ctx = Context.builder(new Config())
                .sandbox(new Sandbox(workspace, Map.of()))
                .llm(LlmClient.scripted(List.of()))
                .build();
        return new LoopState("", List.of(), messages, workspace, ctx, null, 5, 0);
    }

    private static final String BRIEF = "1 | Brief marker: COBALT-RIDGE-27.\n";

    @Test
    void sourceReadUnderItsAbsoluteSpellingIsNotMissing() throws IOException {
        Path root = workspace.toRealPath();
        Path source = writeWorkspaceFile(root, "docs/brief.md", BRIEF);
        writeWorkspaceFile(root, "notes/brief.md", "1 | Other brief.\n");
        TaskContract contract = sourceContract("Summarize docs/brief.md into out/summary.md.");
        LoopState state = loopStateAt(root, contract.originalUserRequest());
        recordRead(state, source.toString(), BRIEF);

        assertEquals(List.of(), SourceDerivedEvidenceGuard.missingSourceEvidenceTargets(state, contract));
        assertNull(SourceDerivedEvidenceGuard.requiredSourceEvidenceDiagnostic(
                state,
                contract,
                writeFile("out/summary.md", "- Grounded summary."),
                "out/summary.md"));
    }

    @Test
    void readOfASameNamedSiblingLeavesTheSourceMissing() throws IOException {
        Path root = workspace.toRealPath();
        writeWorkspaceFile(root, "docs/brief.md", BRIEF);
        Path sibling = writeWorkspaceFile(root, "notes/brief.md", "1 | Other brief.\n");
        TaskContract contract = sourceContract("Summarize docs/brief.md into out/summary.md.");
        LoopState state = loopStateAt(root, contract.originalUserRequest());
        recordRead(state, sibling.toString(), "1 | Other brief.\n");

        assertEquals(List.of("docs/brief.md"),
                SourceDerivedEvidenceGuard.missingSourceEvidenceTargets(state, contract));
        var diagnostic = SourceDerivedEvidenceGuard.requiredSourceEvidenceDiagnostic(
                state,
                contract,
                writeFile("out/summary.md", "- Ungrounded summary."),
                "out/summary.md");
        assertNotNull(diagnostic);
        assertEquals(List.of("docs/brief.md"), diagnostic.missingSourceTargets());
    }

    @Test
    void failedReadUnderTheAbsoluteSpellingLeavesTheSourceMissing() throws IOException {
        Path root = workspace.toRealPath();
        Path source = writeWorkspaceFile(root, "docs/brief.md", BRIEF);
        TaskContract contract = sourceContract("Summarize docs/brief.md into out/summary.md.");
        LoopState state = loopStateAt(root, contract.originalUserRequest());
        state.addToolOutcome(new ToolCallLoop.ToolOutcome(
                "talos.read_file", source.toString(), false, false, false, "", "read failed"));

        assertEquals(List.of("docs/brief.md"),
                SourceDerivedEvidenceGuard.missingSourceEvidenceTargets(state, contract));
    }

    @Test
    void sourceReadInAnEarlierLoopOfTheTurnUnderItsAbsoluteSpellingIsNotMissing() throws IOException {
        Path root = workspace.toRealPath();
        Path source = writeWorkspaceFile(root, "docs/brief.md", BRIEF);
        Path sibling = writeWorkspaceFile(root, "notes/brief.md", "1 | Other brief.\n");
        TaskContract contract = sourceContract("Summarize docs/brief.md into out/summary.md.");
        TurnSourceEvidenceCapture.begin();
        try {
            TurnSourceEvidenceCapture.recordRead(sibling.toString());
            LoopState retry = loopStateAt(root, contract.originalUserRequest());
            assertEquals(List.of("docs/brief.md"),
                    SourceDerivedEvidenceGuard.missingSourceEvidenceTargets(retry, contract));

            TurnSourceEvidenceCapture.recordRead(source.toString());
            assertEquals(List.of(),
                    SourceDerivedEvidenceGuard.missingSourceEvidenceTargets(retry, contract));
        } finally {
            TurnSourceEvidenceCapture.clear();
        }
    }

    @Test
    void exactEvidenceRepairAcceptsTheExpectedTargetUnderItsAbsoluteSpelling() throws IOException {
        Path root = workspace.toRealPath();
        writeWorkspaceFile(root, "docs/brief.md", BRIEF);
        TaskContract contract = exactEvidenceContract();
        LoopState state = loopStateAt(root, contract.originalUserRequest());
        recordRead(state, "docs/brief.md", BRIEF);
        String absoluteTarget = root.resolve("out").resolve("summary.md").toString();
        ToolCall write = writeFile(absoluteTarget, "- A summary without the phrase.");

        assertNotNull(SourceDerivedEvidenceGuard.exactEvidenceCoverageDiagnostic(
                state, contract, write, absoluteTarget));
        ToolCall repaired = SourceDerivedEvidenceGuard.repairedExactEvidenceWrite(
                state, contract, write, absoluteTarget);

        assertNotNull(repaired, "the expected target under another spelling must be repairable");
        assertEquals("talos.write_file", repaired.canonicalToolName());
        assertTrue(WorkspacePathIdentity.matches(root, "out/summary.md", repaired.param("path")),
                repaired.param("path"));
        assertTrue(repaired.param("content").contains("Brief marker: COBALT-RIDGE-27."),
                repaired.param("content"));
    }

    @Test
    void exactEvidenceRepairRefusesASameNamedFileOutsideTheExpectedTarget() throws IOException {
        Path root = workspace.toRealPath();
        writeWorkspaceFile(root, "docs/brief.md", BRIEF);
        TaskContract contract = exactEvidenceContract();
        LoopState state = loopStateAt(root, contract.originalUserRequest());
        recordRead(state, "docs/brief.md", BRIEF);
        String otherTarget = root.resolve("archive").resolve("summary.md").toString();
        ToolCall write = writeFile(otherTarget, "- A summary without the phrase.");

        assertNotNull(SourceDerivedEvidenceGuard.exactEvidenceCoverageDiagnostic(
                state, contract, write, otherTarget));
        assertNull(SourceDerivedEvidenceGuard.repairedExactEvidenceWrite(
                state, contract, write, otherTarget));
    }

    private static TaskContract sourceContract(String request) {
        return new TaskContract(
                TaskType.FILE_CREATE, true, true, true,
                Set.of("out/summary.md"),
                Set.of("docs/brief.md"),
                Set.of(),
                request,
                "identity-source-read");
    }

    private static TaskContract exactEvidenceContract() {
        return new TaskContract(
                TaskType.FILE_CREATE, true, true, true,
                Set.of("out/summary.md"),
                Set.of("docs/brief.md"),
                Set.of(),
                "Create out/summary.md from docs/brief.md. Include one exact evidence phrase from the source.",
                "identity-exact-evidence");
    }

    private LoopState loopStateAt(Path root, String request) {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user(request)));
        Context ctx = Context.builder(new Config())
                .sandbox(new Sandbox(root, Map.of()))
                .llm(LlmClient.scripted(List.of()))
                .build();
        return new LoopState("", List.of(), messages, root, ctx, null, 5, 0);
    }

    private static Path writeWorkspaceFile(Path root, String relative, String content) throws IOException {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        return Files.writeString(path, content);
    }

    private static void recordRead(LoopState state, String path, String body) {
        ReadEvidenceStateAccounting.recordSuccessfulToolResult(
                state,
                new ToolCall("talos.read_file", Map.of("path", path)),
                path,
                ToolResult.ok(body));
        state.addToolOutcome(new ToolCallLoop.ToolOutcome(
                "talos.read_file", path, true, false, false, "Read " + path, ""));
    }

    private static ToolCall writeFile(String path, String content) {
        return new ToolCall("talos.write_file", Map.of("path", path, "content", content));
    }
}
