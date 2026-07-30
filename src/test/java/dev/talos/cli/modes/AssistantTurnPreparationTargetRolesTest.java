package dev.talos.cli.modes;

import dev.talos.cli.prompt.PromptDebugInspector;
import dev.talos.runtime.policy.CapabilityPosture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.PromptDebugSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantTurnPreparationTargetRolesTest {
    private static final String TARGET_ROLES_DIAGNOSTIC = "taskContract.targetRoles";

    @AfterEach
    void cleanup() {
        PromptDebugCapture.clear();
    }

    @Test
    void authoritativeEmptyEffectiveRoleSetSurvivesIntoPromptDebug(@TempDir Path workspace) {
        String userRequest = "Create style.css.";
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user(userRequest)));
        PromptDebugCapture.beginTurn();

        AssistantTurnPreparation.PreparedTurn prepared =
                AssistantTurnPreparation.prepare(
                        messages,
                        workspace,
                        null,
                        false,
                        CapabilityPosture.PLAN_READ_ONLY);
        assertTrue(prepared.plan().taskContract().expectedTargets().isEmpty());

        PromptDebugCapture.record(new PromptDebugSnapshot(
                "CHAT_REQUEST",
                "test",
                "model",
                false,
                Instant.parse("2026-07-30T00:00:00Z"),
                List.of(ChatMessage.user(userRequest)),
                List.of(),
                ChatRequestControls.defaults(),
                ""));

        PromptDebugSnapshot snapshot = PromptDebugCapture.latest().orElseThrow();
        assertEquals(
                "(none)",
                snapshot.diagnostics().get(TARGET_ROLES_DIAGNOSTIC));
        String rendered = PromptDebugInspector.format(snapshot);
        assertTrue(rendered.contains("- Target roles: (none)"), rendered);
        assertFalse(rendered.contains("style.css = MUST_MUTATE"), rendered);
    }
}
