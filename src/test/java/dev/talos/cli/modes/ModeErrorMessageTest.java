package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.Result;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AskMode and RagMode error message surfacing.
 *
 * <p>These run with an injected deterministic LLM seam (no real engine calls), so they verify
 * that the happy path still works. The actual error-handling paths are
 * tested at the ExecutionPipeline level where exceptions are caught.
 */
class ModeErrorMessageTest {

    private static final Path WS = Path.of(".").toAbsolutePath().normalize();

    private static Context scriptedContext(String response) {
        return Context.builder(new Config())
                .llm(LlmClient.scripted(response))
                .build();
    }

    @Test
    void askMode_placeholder_still_returns_ok() throws Exception {
        var ctx = scriptedContext("hello world");
        var mode = new AskMode();

        Optional<Result> result = mode.handle("hello world", WS, ctx);

        assertTrue(result.isPresent());
        assertInstanceOf(Result.Ok.class, result.get());
        assertFalse(((Result.Ok) result.get()).text.isBlank());
    }

    @Test
    void ragMode_placeholder_still_returns_ok() throws Exception {
        var ctx = scriptedContext("project summary");
        var mode = new RagMode();

        Optional<Result> result = mode.handle("what is this project", WS, ctx);

        assertTrue(result.isPresent());
        assertInstanceOf(Result.Ok.class, result.get());
    }

    @Test
    void askMode_with_streamSink_placeholder_returns_streamed() throws Exception {
        java.util.List<String> chunks = new java.util.ArrayList<>();
        var ctx = Context.builder(new Config())
                .llm(LlmClient.scripted("hello streaming"))
                .streamSink(chunks::add)
                .build();
        var mode = new AskMode();

        Optional<Result> result = mode.handle("hello streaming", WS, ctx);

        assertTrue(result.isPresent());
        assertInstanceOf(Result.Streamed.class, result.get());
    }

    @Test
    void askMode_null_context_returns_empty() throws Exception {
        var mode = new AskMode();
        Optional<Result> result = mode.handle("test", WS, null);
        assertTrue(result.isEmpty());
    }

    @Test
    void askMode_blank_input_returns_empty() throws Exception {
        var ctx = Context.builder(new Config()).build();
        var mode = new AskMode();
        Optional<Result> result = mode.handle("   ", WS, ctx);
        assertTrue(result.isEmpty());
    }
}

