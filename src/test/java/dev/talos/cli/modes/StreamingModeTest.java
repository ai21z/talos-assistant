package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.Result;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for settled answer delivery when provider streaming is configured.
 *
 * <p>When a {@code streamSink} is present in the Context, modes should:
 * <ol>
 *   <li>Use {@code chatStream()} instead of blocking {@code chat()}</li>
 *   <li>Keep provider fragments out of the presentation sink</li>
 *   <li>Return the complete selected answer as {@link Result.Ok}</li>
 * </ol>
 *
 * <p>Without a streamSink (null), modes fall back to the non-streaming path
 * and return {@link Result.Ok} as before.
 */
class StreamingModeTest {

    private static final Path WS = Path.of(".").toAbsolutePath().normalize();

    private static Context scriptedStreamingContext(List<String> chunks) {
        return Context.builder(new Config())
                .llm(LlmClient.scripted("hello streaming"))
                .streamSink(chunks::add)
                .onStreamComplete(() -> assertTrue(chunks.isEmpty(),
                        "Transport completion must not commit answer fragments"))
                .build();
    }

    private static Context scriptedContext(String response) {
        return Context.builder(new Config())
                .llm(LlmClient.scripted(response))
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  AskMode streaming
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void askMode_with_streamSink_returns_complete_ok_result() throws Exception {
        List<String> chunks = new ArrayList<>();
        var ctx = scriptedStreamingContext(chunks);
        var mode = new AskMode();

        Optional<Result> result = mode.handle("hello streaming", WS, ctx);

        assertTrue(result.isPresent());
        assertInstanceOf(Result.Ok.class, result.get(),
                "The selected answer must be returned for settled presentation");

        Result.Ok selected = (Result.Ok) result.get();
        assertEquals("\nhello streaming\n\n", selected.text);
        assertTrue(chunks.isEmpty(), "Neither provider fragments nor the selected answer may be emitted");
    }

    @Test
    void askMode_with_streamSink_keeps_sink_empty_through_completion() throws Exception {
        List<String> chunks = new ArrayList<>();
        var ctx = scriptedStreamingContext(chunks);
        var mode = new AskMode();

        mode.handle("hello streaming", WS, ctx);

        assertTrue(chunks.isEmpty(),
                "Provider transport streaming must not expose answer fragments");
    }

    @Test
    void askMode_without_streamSink_returns_ok_result() throws Exception {
        var ctx = scriptedContext("hello no streaming");
        var mode = new AskMode();

        Optional<Result> result = mode.handle("hello no streaming", WS, ctx);

        assertTrue(result.isPresent());
        assertInstanceOf(Result.Ok.class, result.get(),
                "Without streamSink, should return Ok (non-streaming)");
    }

    @Test
    void askMode_fast_path_bypasses_streaming() throws Exception {
        List<String> chunks = new ArrayList<>();
        var ctx = Context.builder(new Config())
                .streamSink(chunks::add)
                .build();
        var mode = new AskMode();

        // Exact-echo fast-path should return Ok, not Streamed
        Optional<Result> result = mode.handle("Respond with exactly: test", WS, ctx);

        assertTrue(result.isPresent());
        assertInstanceOf(Result.Ok.class, result.get(),
                "Fast-path responses should bypass streaming");
        assertTrue(chunks.isEmpty(),
                "Stream sink should not receive chunks for fast-path responses");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Result.Streamed contract
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void streamed_result_carries_full_text() {
        var streamed = new Result.Streamed("Hello world", "\n[Sources]\n - file.txt");
        assertEquals("Hello world", streamed.fullText);
        assertEquals("\n[Sources]\n - file.txt", streamed.suffix);
        assertEquals("Hello world\n[Sources]\n - file.txt", streamed.toString());
    }

    @Test
    void streamed_result_null_safe() {
        var streamed = new Result.Streamed(null, null);
        assertEquals("", streamed.fullText);
        assertEquals("", streamed.suffix);
    }

    @Test
    void streamed_result_in_sealed_hierarchy() {
        Result r = new Result.Streamed("text", "suffix");
        assertInstanceOf(Result.class, r);
        assertInstanceOf(Result.Streamed.class, r);
    }
}

