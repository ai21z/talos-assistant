package dev.talos.cli.prompt;

import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.PromptDebugSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptDebugInspectorTargetRolesTest {
    private static final String TARGET_ROLES_DIAGNOSTIC = "taskContract.targetRoles";

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

    @Test
    void promptDebugPrefersAuthoritativeEffectiveContractRolesOverMessageReparse() {
        PromptDebugSnapshot snapshot = new PromptDebugSnapshot(
                "CHAT_REQUEST",
                "ollama",
                "gpt-oss:20b",
                false,
                Instant.parse("2026-05-31T00:00:00Z"),
                List.of(ChatMessage.user("Update style.css.")),
                List.of(),
                ChatRequestControls.defaults(),
                "",
                java.util.Map.of(
                        TARGET_ROLES_DIAGNOSTIC,
                        "style.css = MUST_MUTATE_AFTER_SOURCE_EVIDENCE "
                                + "(active-contract-projection)"));

        String rendered = PromptDebugInspector.format(snapshot);

        assertTrue(rendered.contains(
                "- Target roles: style.css = MUST_MUTATE_AFTER_SOURCE_EVIDENCE "
                        + "(active-contract-projection)"), rendered);
        assertEquals(
                1,
                rendered.split("MUST_MUTATE_AFTER_SOURCE_EVIDENCE", -1).length - 1,
                rendered);
        assertFalse(java.util.regex.Pattern
                .compile("style\\.css\\s*=\\s*MUST_MUTATE(?!_)")
                .matcher(rendered)
                .find(), rendered);
        assertFalse(rendered.contains("style.css = SOURCE_EVIDENCE"), rendered);
    }

    @Test
    void promptDebugPreservesAuthoritativeEmptyRoleSetWithoutMessageReparse() {
        PromptDebugSnapshot snapshot = new PromptDebugSnapshot(
                "CHAT_REQUEST",
                "ollama",
                "gpt-oss:20b",
                false,
                Instant.parse("2026-05-31T00:00:00Z"),
                List.of(ChatMessage.user("Update style.css.")),
                List.of(),
                ChatRequestControls.defaults(),
                "",
                java.util.Map.of(
                        TARGET_ROLES_DIAGNOSTIC,
                        "(none)"));

        String rendered = PromptDebugInspector.format(snapshot);

        assertTrue(rendered.contains("- Target roles: (none)"), rendered);
        assertFalse(rendered.contains("style.css = MUST_MUTATE"), rendered);
    }

    @Test
    void promptDebugPreservesCompleteAuthoritativeRoleSetBeyondGenericDiagnosticLimit() {
        String ordinaryTargets = java.util.stream.IntStream.range(0, 12)
                .mapToObj(index -> "target-" + index + ".css = MUST_MUTATE (active-contract-projection)")
                .collect(java.util.stream.Collectors.joining(", "));
        String authoritative = ordinaryTargets
                + ", zz-style.css = MUST_MUTATE_AFTER_SOURCE_EVIDENCE "
                + "(active-contract-projection)";
        assertTrue(authoritative.length() > 240);
        PromptDebugSnapshot snapshot = new PromptDebugSnapshot(
                "CHAT_REQUEST",
                "ollama",
                "gpt-oss:20b",
                false,
                Instant.parse("2026-05-31T00:00:00Z"),
                List.of(ChatMessage.user("Update the listed files.")),
                List.of(),
                ChatRequestControls.defaults(),
                "",
                java.util.Map.of(
                        TARGET_ROLES_DIAGNOSTIC,
                        authoritative));

        String rendered = PromptDebugInspector.format(snapshot);

        assertTrue(rendered.contains("- Target roles: " + authoritative), rendered);
        assertTrue(rendered.contains("zz-style.css = MUST_MUTATE_AFTER_SOURCE_EVIDENCE"), rendered);
        assertFalse(rendered.contains("[omitted:"), rendered);
    }

    @Test
    void promptDebugExplicitlyMarksOversizedAuthoritativeRoleSetAsOmitted() {
        String oversized = java.util.stream.IntStream.range(0, 100)
                .mapToObj(index -> "long-target-" + index
                        + ".css = MUST_MUTATE_AFTER_SOURCE_EVIDENCE "
                        + "(active-contract-projection)")
                .collect(java.util.stream.Collectors.joining(", "));
        String finalRole = "long-target-99.css = MUST_MUTATE_AFTER_SOURCE_EVIDENCE";
        assertTrue(oversized.length() > 4_096);
        PromptDebugSnapshot snapshot = new PromptDebugSnapshot(
                "CHAT_REQUEST",
                "ollama",
                "gpt-oss:20b",
                false,
                Instant.parse("2026-05-31T00:00:00Z"),
                List.of(ChatMessage.user("Update target.css.")),
                List.of(),
                ChatRequestControls.defaults(),
                "",
                java.util.Map.of(
                        TARGET_ROLES_DIAGNOSTIC,
                        oversized));

        String rendered = PromptDebugInspector.format(snapshot);

        assertTrue(rendered.contains(
                "- Target roles: [omitted: authoritative target-role diagnostic length="
                        + oversized.length()
                        + " exceeds 4096 characters]"), rendered);
        assertFalse(rendered.contains("long-target-0.css"), rendered);
        assertFalse(rendered.contains(finalRole), rendered);
    }

    @Test
    void promptDebugDoesNotShowReadOnlyTargetHintsAsMustMutate() {
        PromptDebugSnapshot snapshot = new PromptDebugSnapshot(
                "CHAT_REQUEST",
                "ollama",
                "gpt-oss:20b",
                false,
                Instant.parse("2026-05-31T00:00:00Z"),
                List.of(ChatMessage.user(
                        "Check whether scripts.js exists and whether script.js exists. Do not change anything.")),
                List.of(),
                ChatRequestControls.defaults(),
                "");

        String rendered = PromptDebugInspector.format(snapshot);

        assertTrue(rendered.contains("- Task contract: DIAGNOSE_ONLY, mutationAllowed=false"), rendered);
        assertTrue(rendered.contains("scripts.js = MUST_READ"), rendered);
        assertTrue(rendered.contains("script.js = MUST_READ"), rendered);
        assertFalse(rendered.contains("scripts.js = MUST_MUTATE"), rendered);
        assertFalse(rendered.contains("script.js = MUST_MUTATE"), rendered);
    }

    @Test
    void promptDebugShowsPreserveReasonForForbiddenTargets() {
        PromptDebugSnapshot snapshot = new PromptDebugSnapshot(
                "CHAT_REQUEST",
                "ollama",
                "gpt-oss:20b",
                false,
                Instant.parse("2026-05-31T00:00:00Z"),
                List.of(ChatMessage.user(
                        "Keep styles.css unchanged. Update index.html and scripts.js.")),
                List.of(),
                ChatRequestControls.defaults(),
                "");

        String rendered = PromptDebugInspector.format(snapshot);

        assertTrue(rendered.contains("styles.css = FORBIDDEN (preserve-unchanged-target)"), rendered);
        assertTrue(rendered.contains("index.html = MUST_MUTATE"), rendered);
        assertTrue(rendered.contains("scripts.js = MUST_MUTATE"), rendered);
    }
}
