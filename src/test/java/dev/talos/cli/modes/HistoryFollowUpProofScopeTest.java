package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.runtime.SessionMemory;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A "did it work?" question about a turn whose pass was checks only reports that turn as not verified complete. It
 * neither skips that turn for an older verified one nor reads it as verified (MICA invariant I3, decision D5, P4).
 */
class HistoryFollowUpProofScopeTest {
    private static final String VERIFIED = "[Static verification: passed - Exact content verification passed.]\n\n"
            + "Created todo.md.";
    private static final String CHECKS = "[Checks passed - Static web coherence checks passed for 1 mutated target(s).]"
            + "\n\nUpdated index.html.";
    private static final String CHECKS_WITH_LIMITS = CHECKS + "\n\nStatic verification limitations:\n"
            + "- First-viewport render verification was unavailable.";

    @Test
    void aLaterChecksPassIsNotAnsweredFromAnOlderVerifiedTurn(@TempDir Path workspace) {
        String answer = ask(workspace, CHECKS);

        assertFalse(answer.startsWith("Yes"), answer);
        assertTrue(answer.contains("not verified complete"), answer);
    }

    @Test
    void aChecksPassWithLimitationsIsNotReadAsVerified(@TempDir Path workspace) {
        String answer = ask(workspace, CHECKS_WITH_LIMITS);

        assertFalse(answer.startsWith("Yes"), answer);
        assertFalse(answer.startsWith("The previous turn included a verified outcome"), answer);
        assertTrue(answer.contains("not verified complete"), answer);
    }

    @Test
    void aLaterReadbackOnlyOutcomeIsNotAnsweredFromAnOlderVerifiedTurn(@TempDir Path workspace) {
        String answer = ask(workspace, "[File write/readback passed. No task-specific verifier was applicable, "
                + "so task completion was not verified. Readback confirmed notes.md.]\n\nUpdated notes.md.");

        assertFalse(answer.startsWith("Yes"), answer);
        assertTrue(answer.startsWith("No. The previous outcome confirmed the file readback only"), answer);
    }

    @Test
    void anIncompleteVerificationIsNotReadAsVerified(@TempDir Path workspace) {
        String answer = ask(workspace, "[Static verification incomplete: browser behavior was unavailable.]\n\n"
                + "Updated index.html.");

        assertFalse(answer.startsWith("The previous turn included a verified outcome"), answer);
        assertTrue(answer.startsWith("No. Static verification was incomplete"), answer);
    }

    private static String ask(Path workspace, String latestOutcome) {
        var ctx = Context.builder(new Config())
                .memory(new SessionMemory())
                .llm(ScriptedNativeLlmClient.textToolFallbackStrings("Yes, everything worked."))
                .build();
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Create todo.md containing exactly: Review the local setup."));
        messages.add(ChatMessage.assistant(VERIFIED));
        messages.add(ChatMessage.user("Now edit index.html so it looks like a modern landing page."));
        messages.add(ChatMessage.assistant(latestOutcome));
        messages.add(ChatMessage.user("did it work?"));

        return AssistantTurnExecutor.execute(messages, workspace, ctx, new AssistantTurnExecutor.Options()).text();
    }
}
