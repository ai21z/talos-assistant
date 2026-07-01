package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.security.Sandbox;
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

class ToolMutationEvidenceFactoryTest {
    @TempDir
    Path workspace;

    @Test
    void exactEditCallReturnsExactEditReplacementEvidence() {
        LoopState state = loopState();
        ToolCall edit = new ToolCall("edit_file", Map.of(
                "path", "README.md",
                "old_string", "status=old",
                "new_string", "status=new"));

        ToolMutationEvidence evidence =
                ToolMutationEvidenceFactory.from(edit, state, "README.md");

        assertTrue(evidence.exactEditReplacement());
        assertEquals("status=old", evidence.oldString());
        assertEquals("status=new", evidence.newString());
    }

    @Test
    void fullWriteCallReturnsFullReplacementEvidenceWhenCompleteReadbackExists() {
        LoopState state = loopState();
        state.successfulReadCallBodies.put(
                "talos.read_file:path=README.md;",
                "1 | # Old\n2 | Body\n");
        ToolCall write = new ToolCall("talos.write_file", Map.of(
                "path", "README.md",
                "content", "# New\nBody\n"));

        ToolMutationEvidence evidence =
                ToolMutationEvidenceFactory.from(write, state, "README.md");

        assertTrue(evidence.fullWriteReplacement());
        assertEquals("# Old\nBody\n", evidence.oldString());
        assertEquals("# New\nBody\n", evidence.newString());
    }

    @Test
    void fullWriteCallWithoutCompleteReadbackReturnsNoEvidence() {
        LoopState state = loopState();
        state.successfulReadCallBodies.put(
                "talos.read_file:path=README.md;",
                "1 | # Old\n... (output truncated)\n");
        ToolCall write = new ToolCall("talos.write_file", Map.of(
                "path", "README.md",
                "content", "# New\n"));

        ToolMutationEvidence evidence =
                ToolMutationEvidenceFactory.from(write, state, "README.md");

        assertFalse(evidence.fullWriteReplacement());
        assertFalse(evidence.exactEditReplacement());
    }

    @Test
    void readOnlyAndMalformedMutationCallsReturnNoEvidence() {
        LoopState state = loopState();
        ToolCall read = new ToolCall("talos.read_file", Map.of("path", "README.md"));
        ToolCall editMissingNewString = new ToolCall("talos.edit_file", Map.of(
                "path", "README.md",
                "old_string", "status=old"));

        assertEquals(ToolMutationEvidence.none(),
                ToolMutationEvidenceFactory.from(read, state, "README.md"));
        assertEquals(ToolMutationEvidence.none(),
                ToolMutationEvidenceFactory.from(editMissingNewString, state, "README.md"));
    }

    @Test
    void executionStageDelegatesMutationEvidenceConstructionToFactory() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java"));

        assertTrue(source.contains("ToolMutationEvidenceFactory.from"), source);
        assertFalse(source.contains("private static ToolMutationEvidence mutationEvidence"),
                source);
        assertFalse(source.contains("private static String priorReadContentForPath"), source);
    }

    @Test
    void mutationEvidenceValueIsOwnedOutsideToolCallLoop() throws Exception {
        String loopSource = Files.readString(Path.of("src/main/java/dev/talos/runtime/ToolCallLoop.java"));
        Path evidencePath = Path.of("src/main/java/dev/talos/runtime/toolcall/ToolMutationEvidence.java");
        String factorySource = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolMutationEvidenceFactory.java"));
        String verifierSource = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/verification/TaskExpectationMutationEvidenceVerifier.java"));

        assertFalse(loopSource.contains("record MutationEvidence"), loopSource);
        assertTrue(Files.exists(evidencePath), "Tool mutation evidence must be a tool-call owned value.");
        assertTrue(Files.readString(evidencePath).contains("public record ToolMutationEvidence"), evidencePath::toString);
        assertTrue(factorySource.contains("ToolMutationEvidence from("), factorySource);
        assertTrue(verifierSource.contains("ToolMutationEvidence evidence"), verifierSource);
    }

    private LoopState loopState() {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("Edit the workspace.")));
        Context ctx = Context.builder(new Config())
                .sandbox(new Sandbox(workspace, Map.of()))
                .llm(LlmClient.scripted(List.of()))
                .build();
        return new LoopState("", List.of(), messages, workspace, ctx, null, 5, 0);
    }
}
