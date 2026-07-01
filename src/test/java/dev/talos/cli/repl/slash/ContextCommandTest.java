package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.AtFilePins;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.context.ConversationManager;
import dev.talos.core.context.TokenBudget;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.runtime.Result;
import dev.talos.runtime.SessionMemory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T803: the /context meter. Every figure comes from the T798
 * ContextMeter read model - the same estimates the budget and
 * compaction logic actually use, labeled as estimates.
 */
class ContextCommandTest {

    @AfterEach
    void resetPinCapture() {
        AtFilePins.recordLast(AtFilePins.Resolution.empty());
    }

    private static Context ctxWith(ConversationManager cm, Config cfg) {
        return Context.builder(cfg)
                .memory(new SessionMemory())
                .conversationManager(cm)
                .build();
    }

    private static Context ctxWith(ConversationManager cm, Config cfg, LlmClient llm) {
        return Context.builder(cfg)
                .memory(new SessionMemory())
                .conversationManager(cm)
                .llm(llm)
                .build();
    }

    /**
     * {@code new Config()} reads the developer's real ~/.talos/config.yaml
     * (the T783 hermeticity lesson) - strip every key that feeds backend
     * resolution or the engine row before the test plants its own.
     */
    private static Config cleanConfig() {
        Config cfg = new Config();
        cfg.data.remove("llm");
        cfg.data.remove("engines");
        cfg.data.remove("limits");
        return cfg;
    }

    private static ConversationManager managerWithOneTurn() {
        SessionMemory mem = new SessionMemory();
        ConversationManager cm = new ConversationManager(mem, new TokenBudget(1000));
        cm.addTurn("a question long enough to register in the estimate",
                "an answer long enough to register in the estimate");
        cm.setSketch("a sketch of older compacted turns");
        return cm;
    }

    @Test
    void rendersTheMeterFromTheActiveAssistBudget() {
        var command = new ContextCommand(null, true);

        Result result = command.execute("", ctxWith(managerWithOneTurn(), cleanConfig()));

        assertInstanceOf(Result.TrustedInfo.class, result);
        String text = ((Result.TrustedInfo) result).text;
        assertTrue(text.startsWith("Context window\n"));
        assertTrue(text.contains("/ 550 tokens (est.)"), "assist budget is 55% of 1000");
        assertTrue(text.contains("Max context: 1,000 tokens (limits.llm_context_max_tokens)"));
        assertTrue(text.contains("Budgets:     assist ~550 tokens (active) - rag ~250 tokens"));
        assertTrue(text.contains("Turns:       1 exchange\n"));
        assertTrue(text.contains("Sketch:      ~8 tokens (33 chars of compacted history)"));
        assertTrue(text.contains("Compaction:  auto past 10 exchanges when history exceeds the budget"));
        assertTrue(text.contains("Last:        status=NEVER_ATTEMPTED"));
        assertTrue(text.endsWith("(token figures are estimates, chars/4)"));
    }

    @Test
    void ragModeFlipsTheActiveBudgetAndThreshold() {
        var command = new ContextCommand(null, false);

        String text = ((Result.TrustedInfo) command.execute("",
                ctxWith(managerWithOneTurn(), cleanConfig()))).text;

        assertTrue(text.contains("Budgets:     rag ~250 tokens (active) - assist ~550 tokens"));
        assertTrue(text.contains("Compaction:  auto past 6 exchanges"));
    }

    @Test
    void meterBarScalesAndCaps() {
        assertEquals("[------------------------------] 0%", ContextCommand.meterBar(0, 100, 80));
        assertEquals("[###############---------------] 50%", ContextCommand.meterBar(50, 100, 80));
        assertEquals("[##############################] 100%", ContextCommand.meterBar(200, 100, 80),
                "over-budget caps at 100%, never overflows the bar");
        assertTrue(ContextCommand.meterBar(0, 0, 80).endsWith("0%"), "zero budget never divides by zero");
    }

    @Test
    void llamaCppEngineRowWarnsWhenEngineContextIsSmaller() {
        Config cfg = cleanConfig();
        Map<String, Object> llamaCpp = new LinkedHashMap<>();
        llamaCpp.put("context", 500);
        Map<String, Object> engines = new LinkedHashMap<>();
        engines.put("llama_cpp", llamaCpp);
        cfg.data.put("engines", engines);

        String text = ((Result.TrustedInfo) new ContextCommand(null, true)
                .execute("", ctxWith(managerWithOneTurn(), cfg))).text;

        assertTrue(text.contains("Engine:      llama.cpp context 500 tokens (engines.llama_cpp.context)"));
        assertTrue(text.contains("WARNING: smaller than limits.llm_context_max_tokens"));
        assertTrue(text.contains("overflow risk"));
    }

    @Test
    void llamaCppEngineRowNotesWhenEngineContextIsLarger() {
        Config cfg = cleanConfig();
        Map<String, Object> llamaCpp = new LinkedHashMap<>();
        llamaCpp.put("context", 32768);
        Map<String, Object> engines = new LinkedHashMap<>();
        engines.put("llama_cpp", llamaCpp);
        cfg.data.put("engines", engines);

        String text = ((Result.TrustedInfo) new ContextCommand(null, true)
                .execute("", ctxWith(managerWithOneTurn(), cfg))).text;

        assertTrue(text.contains("llama.cpp context 32,768 tokens"));
        assertTrue(text.contains("note: larger than limits.llm_context_max_tokens"));
        assertFalse(text.contains("WARNING"), "the larger direction is safe, not a warning");
    }

    @Test
    void ollamaEngineRowSaysManaged() {
        Config cfg = cleanConfig();
        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("default_backend", "ollama");
        cfg.data.put("llm", llm);

        String text = ((Result.TrustedInfo) new ContextCommand(null, true)
                .execute("", ctxWith(managerWithOneTurn(), cfg))).text;

        assertTrue(text.contains("Engine:      context managed by Ollama"));
    }

    @Test
    void activeOllamaModelRowUsesLiveEffectiveWindowAfterModelSwitch() {
        Config cfg = cleanConfig();
        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("transport", "engine");
        llm.put("default_backend", "llama_cpp");
        cfg.data.put("llm", llm);
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("llm_context_max_tokens", 32_768);
        cfg.data.put("limits", limits);
        LlmClient active = ScriptedNativeLlmClient
                .recordingWithContextWindow(cfg, List.of(new LlmClient.StreamResult("", List.of())), 8_192)
                .client();
        active.setModel("ollama/gpt-oss:20b");

        String text = ((Result.TrustedInfo) new ContextCommand(null, true)
                .execute("", ctxWith(managerWithOneTurn(), cfg, active))).text;

        assertTrue(text.contains("Engine:      ollama/gpt-oss:20b effective context 8,192 tokens"),
                text);
        assertTrue(text.contains("limits.llm_context_max_tokens=32,768"), text);
        assertTrue(text.contains("runtime enforces the smaller active model window"), text);
        assertFalse(text.contains("llama.cpp context 32,768 tokens"),
                "after /set model ollama/... the row must not keep reporting the configured llama.cpp backend");
    }

    @Test
    void activeLlamaCppModelRowKeepsEngineContextDivergenceWarning() {
        Config cfg = cleanConfig();
        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("transport", "engine");
        llm.put("default_backend", "llama_cpp");
        cfg.data.put("llm", llm);
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("llm_context_max_tokens", 1_000);
        cfg.data.put("limits", limits);
        LlmClient active = ScriptedNativeLlmClient
                .recordingWithContextWindow(cfg, List.of(new LlmClient.StreamResult("", List.of())), 32_768)
                .client();
        active.setModel("llama_cpp/qwen2.5-coder-14b");

        String text = ((Result.TrustedInfo) new ContextCommand(null, true)
                .execute("", ctxWith(managerWithOneTurn(), cfg, active))).text;

        assertTrue(text.contains("Engine:      llama.cpp context 32,768 tokens"), text);
        assertTrue(text.contains("active model llama_cpp/qwen2.5-coder-14b"), text);
        assertTrue(text.contains("note: larger than limits.llm_context_max_tokens"), text);
        assertFalse(text.contains("runtime enforces the smaller active model window"),
                "that warning is specific to active Ollama rows");
    }

    @Test
    void pinnedRowReflectsTheLastPromptResolution() {
        AtFilePins.recordLast(new AtFilePins.Resolution(
                List.of(new AtFilePins.PinnedFile("src/Foo.java", "x".repeat(400), false, 400)),
                List.of()));

        String text = ((Result.TrustedInfo) new ContextCommand(null, true)
                .execute("", ctxWith(managerWithOneTurn(), cleanConfig()))).text;

        assertTrue(text.contains("Pinned:      1 file, ~100 tokens (400 chars)"));
    }

    @Test
    void pinnedRowDefaultsToNone() {
        String text = ((Result.TrustedInfo) new ContextCommand(null, true)
                .execute("", ctxWith(managerWithOneTurn(), cleanConfig()))).text;

        assertTrue(text.contains("Pinned:      none this prompt"));
    }

    @Test
    void missingContextIsAnError() {
        Result result = new ContextCommand(null, true).execute("", null);
        assertInstanceOf(Result.Error.class, result);
    }
}
