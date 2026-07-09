package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.core.util.UiChrome;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Abort truth (release-blocker follow-up to T988): an aborted generation
 * must be recorded as FAILED / LLM_ABORTED on BOTH provider paths, must
 * stay honestly visible to the user, and its partial output must never be
 * dressed up as a normal answer. The dangerous shape is partial text PLUS
 * a trailing abort marker - a prefix check on the final text misses it.
 */
@DisplayName("AssistantTurnExecutor abort truth")
class AssistantTurnExecutorAbortTruthTest {

    private static final String PARTIAL = "The first half of an answer that never finished";

    @Test
    void streamingAbortAfterPartialOutputRecordsLlmAbortedAndShowsTheMarker(@TempDir Path workspace) {
        LlmClient llm = ScriptedNativeLlmClient.transientAfterPartialOutput(PARTIAL);
        List<String> chunks = new ArrayList<>();
        Context ctx = Context.builder(new Config())
                .llm(llm)
                .sandbox(new Sandbox(workspace, Map.of()))
                .streamSink(chunks::add)
                .onStreamComplete(() -> { })
                .build();

        LocalTurnTraceCapture.begin(
                "trc-abort-streaming",
                "sid",
                1,
                "2026-07-09T00:00:00Z",
                "workspace-hash",
                "agent",
                "llama_cpp",
                "test-model",
                "Explain briefly.");
        try {
            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages("Explain briefly."),
                    workspace,
                    ctx,
                    new AssistantTurnExecutor.Options());
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();

            assertTrue(out.streamed(), "partial prose was streamed before the abort");
            assertTrue(out.text().contains(PARTIAL), out.text());
            assertTrue(out.text().contains(UiChrome.TURN_ABORTED_PREFIX), out.text());
            assertFalse(out.text().contains("retry-output"),
                    "an aborted streaming turn must not silently re-generate");
            assertNotNull(trace.outcome(), "a streamed abort must record a turn outcome");
            assertEquals("FAILED", trace.outcome().status());
            assertEquals("LLM_ABORTED", trace.outcome().classification());
            assertTrue(String.join("", chunks).contains(UiChrome.TURN_ABORTED_PREFIX),
                    "the abort must be visible on the streamed surface, got chunks: " + chunks);
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void bufferedAbortAfterPartialOutputRecordsLlmAborted(@TempDir Path workspace) {
        LlmClient llm = ScriptedNativeLlmClient.transientAfterPartialOutput(PARTIAL);
        Context ctx = Context.builder(new Config())
                .llm(llm)
                .sandbox(new Sandbox(workspace, Map.of()))
                .build();

        LocalTurnTraceCapture.begin(
                "trc-abort-buffered",
                "sid",
                1,
                "2026-07-09T00:00:00Z",
                "workspace-hash",
                "agent",
                "llama_cpp",
                "test-model",
                "Explain briefly.");
        try {
            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages("Explain briefly."),
                    workspace,
                    ctx,
                    new AssistantTurnExecutor.Options());
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();

            assertTrue(out.text().contains(PARTIAL), out.text());
            assertTrue(out.text().contains(UiChrome.TURN_ABORTED_PREFIX), out.text());
            assertFalse(out.text().contains("retry-output"),
                    "an aborted buffered turn must not silently re-generate");
            assertNotNull(trace.outcome(), "a buffered abort must record a turn outcome");
            assertEquals("FAILED", trace.outcome().status());
            assertEquals("LLM_ABORTED", trace.outcome().classification());
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void bufferedTrailingMarkerWithoutMetadataStillRecordsLlmAborted(@TempDir Path workspace) {
        // Marker text without abort metadata (reassembled from plain chunks):
        // the shared line-anchored fallback must still classify the turn as
        // aborted instead of treating the partial as a normal answer.
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult(
                        PARTIAL + "\n[turn aborted: interrupted]", List.of())),
                4096);
        Context ctx = Context.builder(new Config())
                .llm(recorded.client())
                .sandbox(new Sandbox(workspace, Map.of()))
                .build();

        LocalTurnTraceCapture.begin(
                "trc-abort-lexical",
                "sid",
                1,
                "2026-07-09T00:00:00Z",
                "workspace-hash",
                "agent",
                "llama_cpp",
                "test-model",
                "Explain briefly.");
        try {
            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages("Explain briefly."),
                    workspace,
                    ctx,
                    new AssistantTurnExecutor.Options());
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();

            assertTrue(out.text().contains(UiChrome.TURN_ABORTED_PREFIX), out.text());
            assertNotNull(trace.outcome(), "a trailing abort marker must record a turn outcome");
            assertEquals("FAILED", trace.outcome().status());
            assertEquals("LLM_ABORTED", trace.outcome().classification());
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    private static List<ChatMessage> messages(String request) {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("You are Talos."));
        messages.add(ChatMessage.user(request));
        return messages;
    }
}
