package dev.talos.cli.repl;

import dev.talos.runtime.Result;

import dev.talos.core.Config;
import dev.talos.spi.EngineException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ExecutionPipeline} error classification.
 */
class ExecutionPipelineErrorCodeTest {

    private final ExecutionPipeline pipe = new ExecutionPipeline();

    private Context minimalCtx() {
        return Context.builder(new Config()).build();
    }

    @Test
    void classifyError_modelNotFound_returns_404() {
        assertEquals(404, ExecutionPipeline.classifyError(new EngineException.ModelNotFound("m")));
    }

    @Test
    void classifyError_connectionFailed_returns_503() {
        assertEquals(503, ExecutionPipeline.classifyError(new EngineException.ConnectionFailed("h", null)));
    }

    @Test
    void classifyError_transient_returns_503() {
        assertEquals(503, ExecutionPipeline.classifyError(new EngineException.Transient("t", 503)));
    }

    @Test
    void classifyError_responseError_returns_actual_status() {
        assertEquals(502, ExecutionPipeline.classifyError(new EngineException.ResponseError(502, "gw")));
    }

    @Test
    void classifyError_malformedResponse_returns_502() {
        assertEquals(502, ExecutionPipeline.classifyError(
                new EngineException.MalformedResponse("compat chat response", "bad provider body")));
    }

    @Test
    void classifyError_timeout_returns_408() {
        assertEquals(408, ExecutionPipeline.classifyError(new TimeoutException()));
    }

    @Test
    void classifyError_illegalArgument_returns_400() {
        assertEquals(400, ExecutionPipeline.classifyError(new IllegalArgumentException("bad")));
    }

    @Test
    void classifyError_unknown_returns_500() {
        assertEquals(500, ExecutionPipeline.classifyError(new RuntimeException("boom")));
    }

    @Test
    void run_modelNotFound_produces_404_with_guidance() {
        Result r = pipe.run(() -> { throw new EngineException.ModelNotFound("llama3"); }, minimalCtx(), "t");
        assertInstanceOf(Result.Error.class, r);
        Result.Error err = (Result.Error) r;
        assertEquals(404, err.code);
        assertTrue(err.message.contains("llama3"));
        assertTrue(err.message.contains("selected backend"));
    }

    @Test
    void run_connectionFailed_produces_503_with_guidance() {
        Result r = pipe.run(() -> { throw new EngineException.ConnectionFailed("localhost", null); }, minimalCtx(), "t");
        assertInstanceOf(Result.Error.class, r);
        assertEquals(503, ((Result.Error) r).code);
        assertTrue(((Result.Error) r).message.contains("talos status --verbose"));
    }

    @Test
    void run_success_passes_through() {
        Result r = pipe.run(() -> new Result.Ok("ok"), minimalCtx(), "t");
        assertInstanceOf(Result.Ok.class, r);
    }

    @Test
    void run_null_result_returns_info() {
        Result r = pipe.run(() -> null, minimalCtx(), "t");
        assertInstanceOf(Result.Info.class, r);
    }
}

