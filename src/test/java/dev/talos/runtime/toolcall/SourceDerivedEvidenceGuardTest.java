package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
                "src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java"));

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
}
