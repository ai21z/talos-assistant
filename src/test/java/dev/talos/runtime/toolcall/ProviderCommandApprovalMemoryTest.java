package dev.talos.runtime.toolcall;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.ApprovalPolicy;
import dev.talos.runtime.Session;
import dev.talos.runtime.SessionApprovalPolicy;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.TurnUserRequestCapture;
import dev.talos.runtime.command.CommandResult;
import dev.talos.runtime.command.RunCommandTool;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.phase.ExecutionPhaseState;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatMessage.NativeToolCall;
import dev.talos.spi.types.ObservedProviderTransportFixture;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ProviderTransportHandoff;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.ToolRiskLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Provider transport receipts are deterministic fixtures; no model or command process is launched. */
class ProviderCommandApprovalMemoryTest {
    private static final String TOOL = "talos.run_command";
    private static final String REQUEST = "Run the Gradle tests with profile gradle_test. Do not edit files.";
    @TempDir Path workspace;

    @AfterEach
    void clearCaptures() {
        LocalTurnTraceCapture.clear();
        ProviderResponseCapture.clear();
        TurnTaskContractCapture.clear();
        TurnUserRequestCapture.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"path", "file_path", "filepath", "file", "filename"})
    void nativeProposalCannotBorrowRememberedFileWriteApproval(String key) throws Exception {
        assertDenialPreventsExecution(key, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"path", "file_path", "filepath", "file", "filename"})
    void textualProposalCannotBorrowRememberedFileWriteApproval(String key) throws Exception {
        assertDenialPreventsExecution(key, false);
    }

    private void assertDenialPreventsExecution(String key, boolean nativeMode) throws Exception {
        Files.writeString(workspace.resolve("gradlew"), "#!/bin/sh\n");
        Files.writeString(workspace.resolve("gradlew.bat"), "@echo off\r\n");
        LocalTurnTraceCapture.begin("trace-command-approval-" + key, "test-session", 1,
                "2026-09-05T00:00:00Z", "test-workspace", "test", "fixture", "fixture-model", REQUEST);
        TurnUserRequestCapture.set(REQUEST);
        TurnTaskContractCapture.set(TaskContractResolver.resolveFromUserRequest(REQUEST));
        SessionApprovalPolicy memory = new SessionApprovalPolicy();
        ToolCall ordinaryWrite = new ToolCall("talos.write_file", Map.of("path", "README.md", "content", "x"));
        memory.rememberApproval(workspace, ordinaryWrite, ToolRiskLevel.WRITE);
        assertEquals(ApprovalPolicy.Decision.AUTO_APPROVE,
                memory.decide(workspace, ordinaryWrite, ToolRiskLevel.WRITE));

        AtomicInteger approvals = new AtomicInteger();
        AtomicInteger executions = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new RunCommandTool(plan -> {
            executions.incrementAndGet();
            return new CommandResult(plan, 0, 1, false, false, "ok", "", false, false, false, "");
        }));
        TurnProcessor processor = new TurnProcessor(null, (description, detail) -> {
            approvals.incrementAndGet();
            assertTrue(detail.contains("profile: gradle_test"));
            return false;
        }, registry, memory);
        Config config = new Config(null);
        Context context = Context.builder(config).sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry).executionPhaseState(new ExecutionPhaseState(ExecutionPhase.VERIFY)).build();
        Map<String, Object> arguments = Map.of("profile", "gradle_test", key, "README.md");
        List<NativeToolCall> proposals = nativeMode
                ? List.of(new NativeToolCall("command-fixture", TOOL, arguments)) : List.of();
        String text = nativeMode ? "" : "```json\n" + new ObjectMapper().writeValueAsString(
                Map.of("name", TOOL, "arguments", arguments)) + "\n```";
        var receipt = capture(text, proposals, nativeMode);
        assertTrue(receipt.providerToolCallEligible(), receipt.toString());
        LoopState state = new LoopState(text, proposals, receipt,
                new ArrayList<>(List.of(ChatMessage.user(REQUEST))), workspace, context,
                new Session(workspace, config), 1, 0);
        var parsed = new ToolCallParseStage().parse(state, 1);
        assertEquals(1, parsed.calls().size(), "the fixture must reach the real command authority path");
        ToolCall call = parsed.calls().getFirst();
        var authority = LocalTurnTraceCapture.authorizeParsedToolCallForExecution("tool_loop", call, parsed.lineage(0));
        assertTrue(authority.authorized(), authority.toString());

        var result = processor.executeToolAfterParsed(state.toolSession, call, context, authority.permit());

        assertFalse(result.success());
        assertEquals(ToolError.DENIED, result.error().code(), result.errorMessage());
        assertEquals(1, approvals.get(), "a valid current-generation command still requires its own consent");
        assertEquals(0, executions.get(), "denied command consent must leave runner invocations at zero");
        assertEquals(ApprovalPolicy.Decision.AUTO_APPROVE,
                memory.decide(workspace, ordinaryWrite, ToolRiskLevel.WRITE), "legitimate file memory survives");
    }

    private static ProviderResponseCapture.Receipt capture(
            String text, List<NativeToolCall> proposals, boolean nativeMode) {
        var generation = ProviderResponseCapture.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE,
                ProviderResponseCapture.ToolAuthorityContract.bound(nativeMode
                                ? ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE
                                : ProviderResponseCapture.ToolAuthorityMode.PROVIDER_TEXT_JSON,
                        List.of(TOOL), ToolChoiceMode.AUTO, ""));
        var attempt = generation.startAttempt(ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        ObservedProviderTransportFixture.completeTerminal(attempt,
                new ProviderTransportHandoff.DispatchEvidence("sha256:" + "b".repeat(64),
                        nativeMode ? List.of(TOOL) : List.of()));
        var receipt = attempt.respond(new ProviderResponseCapture.Response(text, proposals,
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Completion.PROVIDER_EOS, false, false, !nativeMode));
        generation.complete(receipt);
        return receipt;
    }
}
