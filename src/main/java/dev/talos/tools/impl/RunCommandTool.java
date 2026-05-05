package dev.talos.tools.impl;

import dev.talos.core.capability.CapabilityKind;
import dev.talos.runtime.command.CommandPlan;
import dev.talos.runtime.command.CommandPlanRejectedException;
import dev.talos.runtime.command.CommandProfileRegistry;
import dev.talos.runtime.command.CommandResult;
import dev.talos.runtime.command.CommandRunner;
import dev.talos.runtime.command.CommandToolPlanner;
import dev.talos.runtime.command.ProcessCommandRunner;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.tools.TalosTool;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolContext;
import dev.talos.tools.ToolDescriptor;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolOperationMetadata;
import dev.talos.tools.ToolResult;
import dev.talos.tools.ToolRiskLevel;

import java.util.Map;
import java.util.Objects;

/** Runs approved, bounded command profiles. V1 exposes Gradle verification only. */
public final class RunCommandTool implements TalosTool {
    private static final String NAME = CommandToolPlanner.TOOL_NAME;

    private final CommandProfileRegistry registry;
    private final CommandRunner runner;

    public RunCommandTool() {
        this(CommandProfileRegistry.defaultRegistry(), new ProcessCommandRunner());
    }

    public RunCommandTool(CommandRunner runner) {
        this(CommandProfileRegistry.defaultRegistry(), runner);
    }

    public RunCommandTool(CommandProfileRegistry registry, CommandRunner runner) {
        this.registry = Objects.requireNonNullElseGet(registry, CommandProfileRegistry::defaultRegistry);
        this.runner = Objects.requireNonNull(runner, "runner must not be null");
    }

    @Override public String name() { return NAME; }

    @Override
    public String description() {
        return "Run an approved bounded command profile. V1 supports Gradle verification profiles only.";
    }

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(
                NAME,
                description(),
                """
                {"type":"object","properties":{
                  "profile":{"type":"string","description":"Approved Gradle profile: gradle_test, gradle_check, gradle_build, gradle_install_dist, gradle_e2e_test"},
                  "args_json":{"type":"string","description":"Optional JSON array of validated profile arguments, e.g. [\\"--tests\\",\\"dev.talos.SomeTest\\"]"},
                  "cwd":{"type":"string","description":"Optional workspace-relative working directory. Defaults to workspace root."},
                  "timeout_ms":{"type":"string","description":"Optional timeout in milliseconds, 1000-600000."}
                },"required":["profile"]}""",
                ToolRiskLevel.WRITE,
                new ToolOperationMetadata(
                        NAME,
                        CapabilityKind.EXECUTE,
                        ToolRiskLevel.WRITE,
                        Map.of(),
                        false,
                        false,
                        true,
                        false,
                        false,
                        false,
                        "COMMAND_EXECUTED",
                        ""));
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext ctx) {
        if (ctx == null) {
            return ToolResult.fail(ToolError.internal("talos.run_command requires a ToolContext."));
        }

        CommandPlan plan;
        try {
            plan = CommandToolPlanner.planGradleV1(call, ctx.workspace(), registry);
        } catch (CommandPlanRejectedException | IllegalArgumentException e) {
            LocalTurnTraceCapture.recordCommandDenied(
                    "",
                    call,
                    CommandToolPlanner.invalidMessage(e.getMessage()));
            return ToolResult.fail(ToolError.invalidParams(CommandToolPlanner.invalidMessage(e.getMessage())));
        }

        LocalTurnTraceCapture.recordCommandStarted("", call, plan);
        CommandResult result = runner.run(plan);
        LocalTurnTraceCapture.recordCommandFinished("", call, result);
        if (result.success()) {
            return ToolResult.ok(renderSuccess(result));
        }
        return ToolResult.fail(ToolError.internal(renderFailure(result)));
    }

    private static String renderSuccess(CommandResult result) {
        CommandPlan plan = result.plan();
        return "Command succeeded: " + plan.profileId() + " exited with code " + result.exitCode()
                + " after " + result.durationMs() + "ms.\n"
                + renderCommon(result);
    }

    private static String renderFailure(CommandResult result) {
        CommandPlan plan = result.plan();
        String profile = plan == null ? "unknown" : plan.profileId();
        String prefix;
        if (result.timedOut()) {
            prefix = "Command timed out: " + profile + " after " + result.durationMs() + "ms"
                    + (result.killed() ? " (process killed)." : ".");
        } else if (!result.errorMessage().isBlank()) {
            prefix = "Command failed: " + profile + " could not run after " + result.durationMs()
                    + "ms. Reason: " + result.errorMessage();
        } else {
            prefix = "Command failed: " + profile + " exited with code " + result.exitCode()
                    + " after " + result.durationMs() + "ms.";
        }
        return prefix + "\n" + renderCommon(result);
    }

    private static String renderCommon(CommandResult result) {
        CommandPlan plan = result.plan();
        StringBuilder sb = new StringBuilder();
        if (plan != null) {
            sb.append("profile: ").append(plan.profileId()).append('\n');
            sb.append("cwd: ").append(plan.cwd()).append('\n');
            sb.append("argv: ").append(CommandToolPlanner.displayCommand(plan)).append('\n');
        }
        sb.append("exitCode: ").append(result.exitCode()).append('\n');
        sb.append("timedOut: ").append(result.timedOut()).append('\n');
        sb.append("stdoutTruncated: ").append(result.stdoutTruncated()).append('\n');
        sb.append("stderrTruncated: ").append(result.stderrTruncated()).append('\n');
        sb.append("redactionApplied: ").append(result.redactionApplied()).append('\n');
        sb.append("stdout:\n").append(blankIfEmpty(result.stdout())).append('\n');
        sb.append("stderr:\n").append(blankIfEmpty(result.stderr()));
        return sb.toString();
    }

    private static String blankIfEmpty(String value) {
        return value == null || value.isBlank() ? "(empty)" : value;
    }
}
