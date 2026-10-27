package dev.talos.core.context;

import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.slash.ClearCommand;
import dev.talos.cli.repl.slash.MemoryCommand;
import dev.talos.cli.repl.slash.SessionCommand;
import dev.talos.core.Config;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.Result;
import dev.talos.runtime.SessionMemory;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConversationClearLifecycleTest {
    @TempDir Path temp;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void clearAfterCompactionAndExplicitRestorePreservesHistoricalSnapshot(boolean useMemoryCommand)
            throws Exception {
        var memory = new SessionMemory();
        var manager = new ConversationManager(memory, new TokenBudget(256));
        for (int i = 0; i < 4; i++) {
            manager.addTurn("old question " + i, "old answer " + i + " detail".repeat(25));
        }
        // The summarizer is deterministic. Compaction, pruning, commands and persistence are real.
        assertTrue(manager.maybeCompactWith((prior, turns) ->
                ConversationCompactor.CompactionResult.succeeded("OLD_COMPACTED_CONTEXT"), 1,
                ConversationManager.HISTORY_BUDGET_FRACTION));
        assertEquals("OLD_COMPACTED_CONTEXT", manager.sketch());
        assertTrue(manager.turnCount() > 0, "A verbatim tail must remain after compaction");

        var cfg = new Config((Path) null);
        var ctx = Context.builder(cfg).memory(memory).conversationManager(manager).build();
        Path workspace = Files.createDirectories(temp.resolve("workspace"));
        Path sessions = temp.resolve("sessions");
        String workspaceId = JsonSessionStore.sessionIdFor(workspace);
        String historicalId = workspaceId + "-20261001080000";
        String activeId = workspaceId + "-20261008090000";
        try (var store = new JsonSessionStore(sessions)) {
            try (var writer = store.acquireSessionWriter(historicalId)) {
                assertInstanceOf(Result.Info.class,
                        new SessionCommand(workspace, writer, historicalId).execute("save", ctx));
            }
            Path historicalFile = sessions.resolve(historicalId + ".json");
            byte[] historicalBytes = Files.readAllBytes(historicalFile);
            var historical = store.load(historicalId).orElseThrow();
            assertEquals("OLD_COMPACTED_CONTEXT", historical.sketch());
            assertFalse(historical.turns().isEmpty());

            clear(useMemoryCommand, ctx);
            assertEmpty(manager, memory);
            assertClearedWireWithIndependentProjectMemory(workspace, manager);
            saveAndAssertEmpty(workspace, store, activeId, ctx);
            assertArrayEquals(historicalBytes, Files.readAllBytes(historicalFile));

            var restored = assertInstanceOf(Result.Info.class,
                    new SessionCommand(workspace, store, activeId).execute("load " + historicalId, ctx));
            assertTrue(restored.text.contains("Session restored"));
            assertEquals(historical.sketch(), manager.sketch());
            assertEquals(historical.turns().size(), memory.getTurns().size());
            assertTrue(manager.buildHistoryForAssist().stream()
                    .anyMatch(message -> message.content().contains("OLD_COMPACTED_CONTEXT")));

            clear(useMemoryCommand, ctx);
            assertEmpty(manager, memory);
            assertClearedWireWithIndependentProjectMemory(workspace, manager);
            saveAndAssertEmpty(workspace, store, activeId, ctx);
            assertArrayEquals(historicalBytes, Files.readAllBytes(historicalFile));
            assertEquals(historical, store.load(historicalId).orElseThrow());
        }
    }

    private static void clear(boolean useMemoryCommand, Context ctx) {
        Result result = useMemoryCommand
                ? new MemoryCommand().execute("clear", ctx)
                : new ClearCommand().execute("", ctx);
        assertInstanceOf(Result.Info.class, result);
    }

    private static void assertEmpty(ConversationManager manager, SessionMemory memory) {
        assertFalse(manager.hasHistory());
        assertFalse(memory.hasContent());
        assertTrue(memory.getTurns().isEmpty());
        assertNull(manager.sketch());
        assertTrue(manager.buildHistoryForAssist().isEmpty());
        assertTrue(manager.buildHistory().isEmpty());
        assertNull(manager.pollCompactionEvent(), "A cleared session must not announce prior compaction");
        assertEquals(ConversationCompactionStatus.neverAttempted(), manager.lastCompactionStatus());
    }

    private void assertClearedWireWithIndependentProjectMemory(Path workspace, ConversationManager manager) throws Exception {
        Files.writeString(workspace.resolve("TALOS.md"), "INDEPENDENT_PROJECT_CONTEXT");
        var contract = new dev.talos.runtime.task.TaskContract(dev.talos.runtime.task.TaskType.WORKSPACE_EXPLAIN,
                false, false, false, java.util.Set.of(), java.util.Set.of(), "Explain this project.");
        var project = new dev.talos.runtime.context.ProjectMemoryLoader(
                dev.talos.runtime.context.ProjectMemoryLimits.defaults()).load(
                new dev.talos.runtime.context.ProjectMemoryRequest(workspace, temp.resolve("home"), contract));
        var messages = new java.util.ArrayList<>(java.util.List.of(dev.talos.spi.types.ChatMessage.system("Read only.")));
        messages.addAll(manager.buildHistoryForAssist());
        messages.add(dev.talos.spi.types.ChatMessage.user("Explain this project."));
        dev.talos.runtime.policy.CurrentTurnPromptInstructions.injectProjectMemoryInstruction(messages, project);
        try (var wire = new dev.talos.core.llm.ContextDeliveryWireFixture(8192, false, "Synthetic reset control.")) {
            String body = wire.send(messages);
            System.out.println("CLEAR_HTTP_BODY " + body);
            assertFalse(body.contains("OLD_COMPACTED_CONTEXT"));
            assertFalse(body.contains("old question"));
            assertFalse(body.contains("old answer"));
            assertTrue(body.contains("INDEPENDENT_PROJECT_CONTEXT"));
        } finally {
            dev.talos.spi.types.PromptDebugCapture.clear();
            dev.talos.spi.types.ProviderResponseCapture.clear();
            ContextLedgerCapture.clear();
        }
    }

    private static void saveAndAssertEmpty(Path workspace, JsonSessionStore store, String activeId,
                                          Context ctx) throws Exception {
        try (var writer = store.acquireSessionWriter(activeId)) {
            assertInstanceOf(Result.Info.class,
                    new SessionCommand(workspace, writer, activeId).execute("save", ctx));
        }
        var saved = store.load(activeId).orElseThrow();
        assertTrue(saved.turns().isEmpty());
        assertTrue(saved.sketch() == null || saved.sketch().isBlank());
    }
}
