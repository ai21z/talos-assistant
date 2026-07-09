package dev.talos.runtime.toolcall;

import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.RuntimeTurnContext;
import dev.talos.runtime.phase.ExecutionPhaseState;
import dev.talos.runtime.policy.ProtectedContentPolicy;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T1003: eligibility gates for the bounded full-rewrite recovery. Every gate
 * fails closed: only a small, completely read, unredacted text file under a
 * mutation-allowed contract becomes a recovery target.
 */
class EditRecoveryFullRewritePlannerTest {

    private static final String MUTATION_REQUEST =
            "Append a short Contributing section to README.md that says: "
                    + "Contributions welcome via pull request.";

    @Test
    void twoEmptyEditStrikesOnAReadSmallTextFileBecomeARecoveryTarget() {
        LoopState state = state(MUTATION_REQUEST);
        state.pathsReadThisTurn.add("README.md");
        state.emptyEditArgumentFailuresByPath.put("README.md", 2);
        state.readFileBodiesThisTurn.put("README.md", numberedBody("# Title", "Hello"));

        EditRecoveryFullRewritePlanner.plan(state);

        assertTrue(state.editRecoveryFullRewriteTargets.contains("README.md"));
    }

    @Test
    void aSingleStrikeIsNotEnough() {
        LoopState state = state(MUTATION_REQUEST);
        state.pathsReadThisTurn.add("README.md");
        state.emptyEditArgumentFailuresByPath.put("README.md", 1);
        state.readFileBodiesThisTurn.put("README.md", numberedBody("# Title"));

        EditRecoveryFullRewritePlanner.plan(state);

        assertTrue(state.editRecoveryFullRewriteTargets.isEmpty());
    }

    @Test
    void anUnreadPathIsNeverATarget() {
        LoopState state = state(MUTATION_REQUEST);
        state.emptyEditArgumentFailuresByPath.put("README.md", 2);
        state.readFileBodiesThisTurn.put("README.md", numberedBody("# Title"));

        EditRecoveryFullRewritePlanner.plan(state);

        assertTrue(state.editRecoveryFullRewriteTargets.isEmpty());
    }

    @Test
    void aRedactedReadExcludesThePath() {
        LoopState state = state(MUTATION_REQUEST);
        state.pathsReadThisTurn.add("README.md");
        state.emptyEditArgumentFailuresByPath.put("README.md", 2);
        state.readFileBodiesThisTurn.put(
                "README.md",
                numberedBody("api_key: " + ProtectedContentPolicy.REDACTED_VALUE));

        EditRecoveryFullRewritePlanner.plan(state);

        assertTrue(state.editRecoveryFullRewriteTargets.isEmpty());
    }

    @Test
    void aTruncatedReadBodyExcludesThePath() {
        LoopState state = state(MUTATION_REQUEST);
        state.pathsReadThisTurn.add("README.md");
        state.emptyEditArgumentFailuresByPath.put("README.md", 2);
        state.readFileBodiesThisTurn.put(
                "README.md", numberedBody("# Title") + "... (output truncated)");

        EditRecoveryFullRewritePlanner.plan(state);

        assertTrue(state.editRecoveryFullRewriteTargets.isEmpty());
    }

    @Test
    void aBodyOverTheSizeCapExcludesThePath() {
        LoopState state = state(MUTATION_REQUEST);
        state.pathsReadThisTurn.add("README.md");
        state.emptyEditArgumentFailuresByPath.put("README.md", 2);
        state.readFileBodiesThisTurn.put(
                "README.md",
                numberedBody("x".repeat(EditRecoveryFullRewritePlanner.MAX_RECOVERY_BODY_CHARS + 1)));

        EditRecoveryFullRewritePlanner.plan(state);

        assertTrue(state.editRecoveryFullRewriteTargets.isEmpty());
    }

    @Test
    void aNonTextExtensionExcludesThePath() {
        LoopState state = state(MUTATION_REQUEST);
        state.pathsReadThisTurn.add("logo.png");
        state.emptyEditArgumentFailuresByPath.put("logo.png", 2);
        state.readFileBodiesThisTurn.put("logo.png", numberedBody("binaryish"));

        EditRecoveryFullRewritePlanner.plan(state);

        assertTrue(state.editRecoveryFullRewriteTargets.isEmpty());
    }

    @Test
    void anAlreadyPromptedPathIsNotReplanned() {
        LoopState state = state(MUTATION_REQUEST);
        state.pathsReadThisTurn.add("README.md");
        state.emptyEditArgumentFailuresByPath.put("README.md", 2);
        state.readFileBodiesThisTurn.put("README.md", numberedBody("# Title"));
        state.editRecoveryPromptedPaths.add("README.md");

        EditRecoveryFullRewritePlanner.plan(state);

        assertFalse(state.editRecoveryFullRewriteTargets.contains("README.md"));
    }

    @Test
    void aReadOnlyContractExcludesRecovery() {
        LoopState state = state("What does README.md say?");
        state.pathsReadThisTurn.add("README.md");
        state.emptyEditArgumentFailuresByPath.put("README.md", 2);
        state.readFileBodiesThisTurn.put("README.md", numberedBody("# Title"));

        EditRecoveryFullRewritePlanner.plan(state);

        assertTrue(state.editRecoveryFullRewriteTargets.isEmpty());
    }

    @Test
    void aSurfaceWithoutWriteFileExcludesRecovery() {
        // The recovery instruction demands one talos.write_file call, so a
        // turn whose native surface lacks that tool (or a text-protocol turn
        // with no native surface at all) keeps the existing truthful stop.
        LoopState state = state(
                MUTATION_REQUEST,
                List.of(new ToolSpec("talos.edit_file", "Edit a file.", "{}")));
        state.pathsReadThisTurn.add("README.md");
        state.emptyEditArgumentFailuresByPath.put("README.md", 2);
        state.readFileBodiesThisTurn.put("README.md", numberedBody("# Title"));

        EditRecoveryFullRewritePlanner.plan(state);

        assertTrue(state.editRecoveryFullRewriteTargets.isEmpty());
    }

    @Test
    void aTurnWithoutANativeSurfaceExcludesRecovery() {
        LoopState state = state(MUTATION_REQUEST, List.of());
        state.pathsReadThisTurn.add("README.md");
        state.emptyEditArgumentFailuresByPath.put("README.md", 2);
        state.readFileBodiesThisTurn.put("README.md", numberedBody("# Title"));

        EditRecoveryFullRewritePlanner.plan(state);

        assertTrue(state.editRecoveryFullRewriteTargets.isEmpty());
    }

    private static String numberedBody(String... lines) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            body.append(i + 1).append(" | ").append(lines[i]).append('\n');
        }
        return body.toString();
    }

    private static LoopState state(String userRequest) {
        // The default fixture is a native turn whose surface exposes both
        // write_file and edit_file, like the observed QA turn.
        return state(userRequest, List.of(
                new ToolSpec("talos.write_file", "Write a file.", "{}"),
                new ToolSpec("talos.edit_file", "Edit a file.", "{}")));
    }

    private static LoopState state(String userRequest, List<ToolSpec> nativeToolSpecs) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("You are Talos."));
        messages.add(ChatMessage.user(userRequest));
        return new LoopState(
                "",
                List.of(),
                messages,
                Path.of(".").toAbsolutePath().normalize(),
                ctxWithNativeSurface(nativeToolSpecs),
                null,
                10,
                0);
    }

    private static RuntimeTurnContext ctxWithNativeSurface(List<ToolSpec> nativeToolSpecs) {
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
                return nativeToolSpecs;
            }
        };
    }
}
