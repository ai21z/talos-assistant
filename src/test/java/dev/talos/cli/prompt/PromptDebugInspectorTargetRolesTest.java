package dev.talos.cli.prompt;

import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.PromptDebugCapture;
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
    void promptDebugFallbackRendersTheIndependentAuthorityBoundaryExactly() {
        String request =
                "Edit index.html so it works, and update style.css too.";
        String sourceText = "update style.css too.";
        int startOffset = request.indexOf(sourceText);
        int authorityEndOffset =
                request.lastIndexOf("style.css") + "style.css".length();
        int endOffset = startOffset + sourceText.length();
        PromptDebugSnapshot snapshot = new PromptDebugSnapshot(
                "CHAT_REQUEST",
                "ollama",
                "gpt-oss:20b",
                false,
                Instant.parse("2026-05-31T00:00:00Z"),
                List.of(ChatMessage.user(request)),
                List.of(),
                ChatRequestControls.defaults(),
                "");

        String rendered = PromptDebugInspector.format(snapshot);

        assertTrue(rendered.contains(
                "style.css = MUST_MUTATE (explicit-mutation-target) "
                        + "[sourceSpan="
                        + startOffset
                        + ":"
                        + authorityEndOffset
                        + ":"
                        + endOffset
                        + "]"), rendered);
    }

    @Test
    void promptDebugPreservesAnAuthoritativeUnknownThreePartSpan() {
        PromptDebugSnapshot snapshot = new PromptDebugSnapshot(
                "CHAT_REQUEST",
                "ollama",
                "gpt-oss:20b",
                false,
                Instant.parse("2026-05-31T00:00:00Z"),
                List.of(ChatMessage.user("Continue the approved edit.")),
                List.of(),
                ChatRequestControls.defaults(),
                "",
                java.util.Map.of(
                        TARGET_ROLES_DIAGNOSTIC,
                        "style.css = MUST_MUTATE (repair-projection) "
                                + "[sourceSpan=unknown:unknown:unknown]"));

        String rendered = PromptDebugInspector.format(snapshot);

        assertTrue(rendered.contains(
                "- Target roles: style.css = MUST_MUTATE (repair-projection) "
                        + "[sourceSpan=unknown:unknown:unknown]"), rendered);
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
    void promptDebugCannotReplaceCapturedContractWithSyntheticRetryText() {
        PromptDebugSnapshot snapshot = new PromptDebugSnapshot(
                "CHAT_REQUEST",
                "ollama",
                "gpt-oss:20b",
                false,
                Instant.parse("2026-05-31T00:00:00Z"),
                List.of(
                        ChatMessage.user(
                                "Edit index.html and update style.css too."),
                        ChatMessage.user(
                                "Retry by reading README.md before answering.")),
                List.of(),
                retryControls(),
                "",
                java.util.Map.of(
                        PromptDebugCapture.TASK_CONTRACT_SUMMARY_DIAGNOSTIC,
                        "FILE_EDIT, mutationAllowed=true, verificationRequired=true",
                        PromptDebugCapture.TASK_CONTRACT_TARGET_LABEL_DIAGNOSTIC,
                        "Expected targets",
                        PromptDebugCapture.TASK_CONTRACT_TARGETS_DIAGNOSTIC,
                        "index.html, style.css",
                        PromptDebugCapture
                                .TASK_CONTRACT_TARGET_COVERAGE_LABEL_DIAGNOSTIC,
                        "Expected-target coverage",
                        PromptDebugCapture.TASK_CONTRACT_TARGET_COVERAGE_DIAGNOSTIC,
                        "OK",
                        PromptDebugCapture.TASK_CONTRACT_TARGET_ROLES_DIAGNOSTIC,
                        "index.html = MUST_MUTATE, style.css = MUST_MUTATE"));

        String rendered = PromptDebugInspector.format(snapshot);

        assertTrue(rendered.contains(
                "- Task contract: FILE_EDIT, mutationAllowed=true, "
                        + "verificationRequired=true"), rendered);
        assertTrue(
                rendered.contains("- Expected targets: index.html, style.css"),
                rendered);
        assertTrue(
                rendered.contains(
                        "- Target roles: index.html = MUST_MUTATE, "
                                + "style.css = MUST_MUTATE"),
                rendered);
        assertTrue(rendered.contains("- Expected-target coverage: OK"), rendered);
        assertFalse(rendered.contains("- Evidence target hints: README.md"), rendered);
        assertFalse(rendered.contains("README.md = MUST_READ"), rendered);
    }

    @Test
    void retryPromptDebugWithoutCompleteAuthorityDiagnosticsNeverReparsesMessages() {
        java.util.Map<String, String> complete = java.util.Map.of(
                PromptDebugCapture.TASK_CONTRACT_SUMMARY_DIAGNOSTIC,
                "FILE_EDIT, mutationAllowed=true",
                PromptDebugCapture.TASK_CONTRACT_TARGET_LABEL_DIAGNOSTIC,
                "Expected targets",
                PromptDebugCapture.TASK_CONTRACT_TARGETS_DIAGNOSTIC,
                "index.html",
                PromptDebugCapture
                        .TASK_CONTRACT_TARGET_COVERAGE_LABEL_DIAGNOSTIC,
                "Expected-target coverage",
                PromptDebugCapture.TASK_CONTRACT_TARGET_COVERAGE_DIAGNOSTIC,
                "OK",
                PromptDebugCapture.TASK_CONTRACT_TARGET_ROLES_DIAGNOSTIC,
                "index.html = MUST_MUTATE");
        java.util.List<java.util.Map<String, String>> incomplete =
                new java.util.ArrayList<>();
        incomplete.add(java.util.Map.of());
        for (String omitted : complete.keySet()) {
            java.util.Map<String, String> oneMissing =
                    new java.util.LinkedHashMap<>(complete);
            oneMissing.remove(omitted);
            incomplete.add(java.util.Map.copyOf(oneMissing));
        }
        for (java.util.Map<String, String> diagnostics : incomplete) {
            PromptDebugSnapshot snapshot = new PromptDebugSnapshot(
                    "CHAT_REQUEST",
                    "ollama",
                    "gpt-oss:20b",
                    false,
                    Instant.parse("2026-05-31T00:00:00Z"),
                    List.of(ChatMessage.user(
                            "Synthetic retry: update evil.txt.")),
                    List.of(),
                    retryControls(),
                    "",
                    diagnostics);

            String rendered = PromptDebugInspector.format(snapshot);

            assertTrue(rendered.contains(
                    "- Task contract: NOT_DERIVED "
                            + "(authoritative retry diagnostics missing)"),
                    rendered);
            assertTrue(
                    rendered.contains("- Expected targets: NOT_DERIVED"),
                    rendered);
            assertTrue(
                    rendered.contains("- Target roles: NOT_DERIVED"),
                    rendered);
            assertTrue(
                    rendered.contains(
                            "- Expected-target coverage: NOT_DERIVED"),
                    rendered);
            assertTrue(
                    rendered.contains("- Exact-literal coverage: NOT_DERIVED"),
                    rendered);
        }
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

    private static ChatRequestControls retryControls() {
        ChatRequestControls defaults = ChatRequestControls.defaults();
        return new ChatRequestControls(
                defaults.toolChoice(),
                defaults.namedTool(),
                defaults.responseFormat(),
                defaults.jsonSchema(),
                List.of("retry-purpose:POST_TOOL_SYNTHESIS"),
                defaults.sampling(),
                defaults.maxOutputTokens(),
                defaults.toolAuthorityMode());
    }
}
