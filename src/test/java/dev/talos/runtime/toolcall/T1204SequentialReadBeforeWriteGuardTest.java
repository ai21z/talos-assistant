package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.TurnSourceEvidenceCapture;
import dev.talos.runtime.intent.TargetRole;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskResolution;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class T1204SequentialReadBeforeWriteGuardTest {

    @TempDir
    Path workspace;

    @AfterEach
    void clearTurnEvidence() {
        TurnSourceEvidenceCapture.clear();
    }

    @Test
    void compositeSequentialTargetIsBlockedBeforeItsOwnReadAndAllowedAfterward() {
        String request =
                "Read style.css and edit index.html. Update style.css.";
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(request);
        assertEquals(
                Set.of("style.css"),
                resolution.contract().sourceEvidenceTargets());
        assertEquals(
                TargetRole.MUST_MUTATE_AFTER_SOURCE_EVIDENCE,
                resolution.intent().targets().find("style.css")
                        .orElseThrow()
                        .role());
        LoopState state = loopState(request);
        ToolCall write = new ToolCall(
                "talos.edit_file",
                Map.of(
                        "path", "style.css",
                        "old_string", "color: red",
                        "new_string", "color: blue"));

        var beforeRead =
                SourceDerivedEvidenceGuard.requiredSourceEvidenceDiagnostic(
                        state,
                        resolution.contract(),
                        write,
                        "style.css");
        assertNotNull(beforeRead);
        assertEquals(List.of("style.css"), beforeRead.missingSourceTargets());
        assertTrue(beforeRead.message().contains("before writing `style.css`"));
        assertTrue(beforeRead.message().contains(
                "No approval was requested and no file was changed."));

        state.pathsReadThisTurn.add("index.html");
        assertNotNull(
                SourceDerivedEvidenceGuard.requiredSourceEvidenceDiagnostic(
                        state,
                        resolution.contract(),
                        write,
                        "style.css"));

        state.pathsReadThisTurn.add("./style.css");
        assertNull(
                SourceDerivedEvidenceGuard.requiredSourceEvidenceDiagnostic(
                        state,
                        resolution.contract(),
                        write,
                        "style.css"));
    }

    @Test
    void capturedSameTurnReadSatisfiesTheGuardAcrossLoopStateReplacement() {
        String request =
                "Read style.css and edit index.html, then update style.css.";
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(request);
        ToolCall write = new ToolCall(
                "talos.write_file",
                Map.of(
                        "path", "style.css",
                        "content", "body { color: blue; }"));

        TurnSourceEvidenceCapture.begin();
        LoopState before = loopState(request);
        assertNotNull(
                SourceDerivedEvidenceGuard.requiredSourceEvidenceDiagnostic(
                        before,
                        resolution.contract(),
                        write,
                        "style.css"));

        TurnSourceEvidenceCapture.recordRead("./style.css");
        LoopState replacement = loopState(request);
        assertTrue(replacement.pathsReadThisTurn.isEmpty());
        assertNull(
                SourceDerivedEvidenceGuard.requiredSourceEvidenceDiagnostic(
                        replacement,
                        resolution.contract(),
                        write,
                        "style.css"));
    }

    private LoopState loopState(String request) {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("system"),
                ChatMessage.user(request)));
        Context context = Context.builder(new Config())
                .sandbox(new Sandbox(workspace, Map.of()))
                .llm(LlmClient.scripted(List.of()))
                .build();
        return new LoopState(
                "",
                List.of(),
                messages,
                workspace,
                context,
                null,
                5,
                0);
    }
}
