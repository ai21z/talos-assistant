package dev.talos.runtime.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.cli.modes.ModeController;
import dev.talos.core.Config;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.ApprovalGate;
import dev.talos.runtime.ApprovalResponse;
import dev.talos.runtime.Session;
import dev.talos.runtime.SessionApprovalPolicy;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.TurnUserRequestCapture;
import dev.talos.runtime.command.CommandPlan;
import dev.talos.runtime.command.CommandResult;
import dev.talos.runtime.command.CommandRunner;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.phase.ExecutionPhaseState;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.ToolResult;
import dev.talos.runtime.command.RunCommandTool;
import dev.talos.cli.repl.Context;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalTurnTraceCommandTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void cleanup() {
        TurnUserRequestCapture.clear();
        TurnTaskContractCapture.clear();
        LocalTurnTraceCapture.clear();
    }

    @Test
    void recordsCommandLifecycleWithoutRawOutput(@TempDir Path workspace) throws Exception {
        createGradleWrapper(workspace);
        AtomicInteger approvals = new AtomicInteger();
        TurnProcessor processor = processor(
                approvals,
                ApprovalResponse.APPROVED,
                plan -> new CommandResult(
                        plan,
                        1,
                        42,
                        false,
                        false,
                        "SECRET_TOKEN=raw-value\n",
                        "compilation failed\n",
                        true,
                        false,
                        true,
                        ""));
        String request = "Verify that the Gradle tests pass.";
        ToolCall call = new ToolCall("talos.run_command", Map.of("profile", "gradle_test"));

        beginTrace(request);
        ToolResult result = processor.executeTool(
                new Session(workspace, new Config()),
                call,
                context(workspace, ExecutionPhase.VERIFY));
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertFalse(result.success());
        assertEquals(1, approvals.get());
        List<String> eventTypes = trace.events().stream().map(TurnTraceEvent::type).toList();
        assertTrue(eventTypes.contains("COMMAND_PLAN_CREATED"), eventTypes.toString());
        assertTrue(eventTypes.contains("COMMAND_POLICY_DECISION"), eventTypes.toString());
        assertTrue(eventTypes.contains("COMMAND_APPROVAL_REQUIRED"), eventTypes.toString());
        assertTrue(eventTypes.contains("COMMAND_APPROVAL_GRANTED"), eventTypes.toString());
        assertTrue(eventTypes.contains("COMMAND_STARTED"), eventTypes.toString());
        assertTrue(eventTypes.contains("COMMAND_OUTPUT_TRUNCATED"), eventTypes.toString());
        assertTrue(eventTypes.contains("COMMAND_FAILED"), eventTypes.toString());
        assertCommandEvent(trace, "COMMAND_FAILED", "exitCode", 1);
        assertCommandEvent(trace, "COMMAND_FAILED", "redactionApplied", true);

        String json = MAPPER.writeValueAsString(trace);
        assertFalse(json.contains("SECRET_TOKEN=raw-value"), "trace must not store raw command output");
        assertFalse(json.contains("compilation failed"), "trace must not store raw stderr");
    }

    @Test
    void recordsCommandDeniedBeforeApproval(@TempDir Path workspace) {
        AtomicInteger approvals = new AtomicInteger();
        TurnProcessor processor = processor(
                approvals,
                ApprovalResponse.APPROVED,
                plan -> new CommandResult(plan, 0, 1, false, false, "", "", false, false, false, ""));
        String request = "Verify that the Gradle tests pass.";

        beginTrace(request);
        ToolResult result = processor.executeTool(
                new Session(workspace, new Config()),
                new ToolCall("talos.run_command", Map.of("command", "powershell -Command Get-ChildItem")),
                context(workspace, ExecutionPhase.VERIFY));
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertFalse(result.success());
        assertEquals(0, approvals.get());
        List<String> eventTypes = trace.events().stream().map(TurnTraceEvent::type).toList();
        assertTrue(eventTypes.contains("COMMAND_POLICY_DECISION"), eventTypes.toString());
        assertTrue(eventTypes.contains("COMMAND_DENIED"), eventTypes.toString());
        assertFalse(eventTypes.contains("COMMAND_APPROVAL_REQUIRED"), eventTypes.toString());
        assertFalse(eventTypes.contains("COMMAND_STARTED"), eventTypes.toString());
    }

    @Test
    void commandTraceEventConstructionIsOwnedByFactory() throws Exception {
        Path capturePath = Path.of("src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java");
        Path factoryPath = Path.of("src/main/java/dev/talos/runtime/trace/CommandTraceEventFactory.java");

        assertTrue(Files.exists(factoryPath), "command trace event construction should have a dedicated owner");

        String capture = Files.readString(capturePath);
        String factory = Files.readString(factoryPath);
        assertTrue(capture.contains("CommandTraceEventFactory."), capture);
        assertFalse(capture.contains("import dev.talos.runtime.command.CommandToolPlanner;"), capture);
        assertFalse(capture.contains("private static Map<String, Object> commandPlanData"), capture);
        assertFalse(capture.contains("private static Map<String, Object> commandResultData"), capture);
        assertFalse(capture.contains("CommandToolPlanner.displayCommand"), capture);
        assertFalse(capture.contains("\"COMMAND_"), capture);
        assertTrue(factory.contains("CommandToolPlanner.displayCommand"), factory);
        assertTrue(factory.contains("COMMAND_OUTPUT_TRUNCATED"), factory);
        assertTrue(factory.contains("COMMAND_FAILED"), factory);
    }

    private static TurnProcessor processor(
            AtomicInteger approvals,
            ApprovalResponse response,
            CommandRunner runner
    ) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new RunCommandTool(runner));
        ApprovalGate gate = new ApprovalGate() {
            @Override public boolean approve(String description, String detail) {
                return approveFull(description, detail).isApproved();
            }

            @Override public ApprovalResponse approveFull(String description, String detail) {
                approvals.incrementAndGet();
                return response;
            }
        };
        return new TurnProcessor(
                ModeController.defaultController(),
                gate,
                registry,
                new SessionApprovalPolicy());
    }

    private static Context context(Path workspace, ExecutionPhase phase) {
        return Context.builder(new Config())
                .sandbox(new Sandbox(workspace, Map.of()))
                .executionPhaseState(new ExecutionPhaseState(phase))
                .build();
    }

    private static void beginTrace(String request) {
        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(TaskContractResolver.fromUserRequest(request));
        LocalTurnTraceCapture.begin(
                "trc-command",
                "sid",
                1,
                "2026-05-05T12:00:00Z",
                "workspace-hash",
                "auto",
                "test",
                "model",
                request);
    }

    private static void createGradleWrapper(Path workspace) throws Exception {
        String executable = gradleWrapperExecutable();
        String fileName = executable.endsWith("gradlew.bat") ? "gradlew.bat" : "gradlew";
        String content = executable.endsWith("gradlew.bat") ? "@echo off\r\n" : "#!/bin/sh\n";
        Files.writeString(workspace.resolve(fileName), content);
    }

    private static String gradleWrapperExecutable() {
        String osName = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        return osName.contains("win") ? ".\\gradlew.bat" : "./gradlew";
    }

    private static void assertCommandEvent(
            LocalTurnTrace trace,
            String eventType,
            String key,
            Object expected
    ) {
        TurnTraceEvent event = trace.events().stream()
                .filter(candidate -> eventType.equals(candidate.type()))
                .findFirst()
                .orElseThrow();
        assertEquals(expected, event.data().get(key));
    }
}
