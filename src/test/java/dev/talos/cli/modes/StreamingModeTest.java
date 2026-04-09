package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
import dev.talos.core.Config;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for streaming output in AskMode and RagMode.
 *
 * <p>When a {@code streamSink} is present in the Context, modes should:
 * <ol>
 *   <li>Use {@code chatStream()} instead of blocking {@code chat()}</li>
 *   <li>Deliver chunks via the sink as they arrive</li>
 *   <li>Return a {@link Result.Streamed} instead of {@link Result.Ok}</li>
 * </ol>
 *
 * <p>Without a streamSink (null), modes fall back to the non-streaming path
 * and return {@link Result.Ok} as before.
 */
class StreamingModeTest {

    private static final Path WS = Path.of(".").toAbsolutePath().normalize();

    // ═══════════════════════════════════════════════════════════════════════
    //  AskMode streaming
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void askMode_with_streamSink_returns_streamed_result() throws Exception {
        List<String> chunks = new ArrayList<>();
        var ctx = Context.builder(new Config())
                .streamSink(chunks::add)
                .build();
        var mode = new AskMode();

        Optional<Result> result = mode.handle("hello streaming", WS, ctx);

        assertTrue(result.isPresent());
        assertInstanceOf(Result.Streamed.class, result.get(),
                "When streamSink is present, should return Streamed");

        Result.Streamed streamed = (Result.Streamed) result.get();
        assertFalse(streamed.fullText.isBlank(),
                "Streamed result should contain the full response text");
    }

    @Test
    void askMode_with_streamSink_delivers_chunks() throws Exception {
        List<String> chunks = new ArrayList<>();
        var ctx = Context.builder(new Config())
                .streamSink(chunks::add)
                .build();
        var mode = new AskMode();

        mode.handle("hello streaming", WS, ctx);

        assertFalse(chunks.isEmpty(),
                "Stream sink should have received at least one chunk");
    }

    @Test
    void askMode_without_streamSink_returns_ok_result() throws Exception {
        var ctx = Context.builder(new Config()).build();
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

