package dev.talos.cli.prompt;

import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.PromptDebugSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptDebugInspectorTargetRolesTest {

    @Test
    void promptDebugShowsRolefulTargets() {
        PromptDebugSnapshot snapshot = new PromptDebugSnapshot(
                "CHAT_REQUEST",
                "ollama",
                "gpt-oss:20b",
                false,
                Instant.parse("2026-05-31T00:00:00Z"),
                List.of(ChatMessage.user("Rewrite styles.css so index.html still works.")),
                List.of(),
                ChatRequestControls.defaults(),
                "");

        String rendered = PromptDebugInspector.format(snapshot);

        assertTrue(rendered.contains("- Target roles:"), rendered);
        assertTrue(rendered.contains("styles.css = MUST_MUTATE"), rendered);
        assertTrue(rendered.contains("index.html = VERIFY_ONLY"), rendered);
    }
}
