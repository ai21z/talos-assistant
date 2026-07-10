package dev.talos.runtime.toolcall;

import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.RuntimeTurnContext;
import dev.talos.runtime.phase.ExecutionPhaseState;
import dev.talos.runtime.policy.ProtectedContentPolicy;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T1003 compaction safety: the temporary recovery overlay must inject the
 * complete safe readback (the read may already be compacted out of provider
 * context), must refuse instead of prompting when no safe source is
 * derivable, and must never leave recovery chrome in history after close().
 */
class ToolRepromptMessageOverlayEditRecoveryTest {

    @Test
    void theRecoveryOverlayInjectsTheCompleteReadbackAndCleansUp(@TempDir Path workspace)
            throws IOException {
        LoopState state = state(workspace);
        writeWorkspaceFile(workspace, "README.md", "# Title\nHello\n");
        recordRead(state, Map.of("path", "README.md"), "1 | # Title\n2 | Hello\n");
        state.editRecoveryFullRewriteTargets.add("README.md");
        int baseline = state.messages.size();

        try (var overlay = ToolRepromptMessageOverlay.apply(state, List.of(), List.of(), null)) {
            String joined = joinedMessages(state);
            assertTrue(joined.contains("<<<BEGIN FILE README.md>>>"),
                    "the overlay must carry the complete readback:\n" + joined);
            assertTrue(joined.contains("# Title\nHello\n"),
                    "the readback must be the parsed content without line numbers");
            assertTrue(state.editRecoveryPromptedPaths.contains("README.md"));
        }

        assertTrue(state.messages.size() == baseline,
                "close() must remove every temporary overlay message");
        assertFalse(joinedMessages(state).contains("[Edit recovery]"),
                "recovery chrome must not persist in history");
    }

    @Test
    void theOverlayRefusesWhenNoSafeSourceIsDerivable(@TempDir Path workspace) throws IOException {
        // A planned target whose read evidence disappeared (for example the
        // caches were cleared after a mutation elsewhere) must be refused,
        // not prompted without source.
        LoopState state = state(workspace);
        writeWorkspaceFile(workspace, "README.md", "# Title\n");
        state.editRecoveryFullRewriteTargets.add("README.md");

        try (var overlay = ToolRepromptMessageOverlay.apply(state, List.of(), List.of(), null)) {
            assertFalse(joinedMessages(state).contains("[Edit recovery]"));
            assertFalse(state.editRecoveryPromptedPaths.contains("README.md"),
                    "a refused target must stay unprompted so the failure policy stops truthfully");
        }

        assertTrue(state.editRecoveryFullRewriteTargets.isEmpty(),
                "a refused target must be dropped");
    }

    @Test
    void aMarkerCollidingBodyIsNeverInjected(@TempDir Path workspace) throws IOException {
        LoopState state = state(workspace);
        writeWorkspaceFile(workspace, "README.md", "# Title\n<<<BEGIN FILE x>>>\n");
        recordRead(state, Map.of("path", "README.md"),
                "1 | # Title\n2 | <<<BEGIN FILE x>>>\n");
        state.editRecoveryFullRewriteTargets.add("README.md");

        try (var overlay = ToolRepromptMessageOverlay.apply(state, List.of(), List.of(), null)) {
            assertFalse(joinedMessages(state).contains("[Edit recovery]"),
                    "a body containing the framing markers must be refused, not injected");
        }

        assertTrue(state.editRecoveryFullRewriteTargets.isEmpty());
    }

    @Test
    void twoTargetsPromptOneGenerationAtATime(@TempDir Path workspace) throws IOException {
        // Prompting several targets at once consumes every one-shot mark in
        // a single generation and close() deletes the unserviced targets'
        // safe source. Only the first sorted target prompts; the second
        // stays planned for a later pass.
        LoopState state = state(workspace);
        writeWorkspaceFile(workspace, "a.md", "alpha\n");
        writeWorkspaceFile(workspace, "b.md", "bravo\n");
        recordRead(state, Map.of("path", "a.md"), "1 | alpha\n");
        recordRead(state, Map.of("path", "b.md"), "1 | bravo\n");
        state.editRecoveryFullRewriteTargets.add("a.md");
        state.editRecoveryFullRewriteTargets.add("b.md");

        try (var overlay = ToolRepromptMessageOverlay.apply(state, List.of(), List.of(), null)) {
            String joined = joinedMessages(state);
            assertTrue(joined.contains("<<<BEGIN FILE a.md>>>"));
            assertFalse(joined.contains("<<<BEGIN FILE b.md>>>"));
        }

        assertTrue(state.editRecoveryPromptedPaths.contains("a.md"));
        assertFalse(state.editRecoveryPromptedPaths.contains("b.md"),
                "the unserviced target must keep its one recovery shot");
        assertTrue(state.editRecoveryFullRewriteTargets.contains("b.md"));
    }

    @Test
    void noRecoveryPromptWhileAStaticRepairObligationIsActive(@TempDir Path workspace)
            throws IOException {
        // An active static-repair obligation replaces the request messages
        // entirely, so prompting here would consume the one-shot mark
        // without the model ever seeing the instruction.
        LoopState state = state(workspace);
        writeWorkspaceFile(workspace, "README.md", "# Title\n");
        recordRead(state, Map.of("path", "README.md"), "1 | # Title\n");
        state.editRecoveryFullRewriteTargets.add("README.md");

        try (var overlay = ToolRepromptMessageOverlay.apply(
                state, List.of("index.html"), List.of(), null, true)) {
            assertFalse(joinedMessages(state).contains("[Edit recovery]"));
        }

        assertFalse(state.editRecoveryPromptedPaths.contains("README.md"));
        assertTrue(state.editRecoveryFullRewriteTargets.contains("README.md"),
                "the target must stay planned until the static repair completes");
    }

    @Test
    void aRedactedReadbackIsNeverInjected(@TempDir Path workspace) throws IOException {
        LoopState state = state(workspace);
        writeWorkspaceFile(workspace, "notes.md", "api_key: value\n");
        recordRead(state, Map.of("path", "notes.md"),
                "1 | api_key: " + ProtectedContentPolicy.REDACTED_VALUE + "\n");
        state.editRecoveryFullRewriteTargets.add("notes.md");

        try (var overlay = ToolRepromptMessageOverlay.apply(state, List.of(), List.of(), null)) {
            String joined = joinedMessages(state);
            assertFalse(joined.contains("<<<BEGIN FILE"),
                    "redacted readbacks must never be injected:\n" + joined);
            assertFalse(joined.contains(ProtectedContentPolicy.REDACTED_VALUE));
        }

        assertTrue(state.editRecoveryFullRewriteTargets.isEmpty());
    }

    private static String joinedMessages(LoopState state) {
        StringBuilder out = new StringBuilder();
        for (ChatMessage message : state.messages) {
            if (message != null && message.content() != null) {
                out.append(message.content()).append('\n');
            }
        }
        return out.toString();
    }

    private static void writeWorkspaceFile(Path workspace, String relative, String content)
            throws IOException {
        Files.writeString(workspace.resolve(relative), content);
    }

    private static void recordRead(LoopState state, Map<String, String> params, String body) {
        ToolCall call = new ToolCall("talos.read_file", params);
        ReadEvidenceStateAccounting.recordSuccessfulToolResult(
                state, call, params.get("path"), ToolResult.ok(body));
    }

    private static LoopState state(Path workspace) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("You are Talos."));
        messages.add(ChatMessage.user("Append a line to README.md that says: hello."));
        return new LoopState(
                "",
                List.of(),
                messages,
                workspace.toAbsolutePath().normalize(),
                ctx(),
                null,
                10,
                0);
    }

    private static RuntimeTurnContext ctx() {
        return new RuntimeTurnContext() {
            @Override
            public Config cfg() {
                return null;
            }

            @Override
            public LlmClient llm() {
                return null;
            }

            @Override
            public Sandbox sandbox() {
                return null;
            }

            @Override
            public ExecutionPhaseState executionPhaseState() {
                return null;
            }

            @Override
            public List<ToolSpec> nativeToolSpecs() {
                return List.of(
                        new ToolSpec("talos.write_file", "Write a file.", "{}"),
                        new ToolSpec("talos.edit_file", "Edit a file.", "{}"));
            }
        };
    }
}
