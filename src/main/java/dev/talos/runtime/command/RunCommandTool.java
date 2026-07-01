package dev.talos.runtime.command;

import dev.talos.core.capability.CapabilityKind;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.tools.TalosTool;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolContentMetadata;
import dev.talos.tools.ToolContext;
import dev.talos.tools.ToolDescriptor;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolFailureReason;
import dev.talos.tools.ToolOperationMetadata;
import dev.talos.tools.ToolResult;
import dev.talos.tools.ToolRiskLevel;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runs approved, bounded command profiles. V1 exposes Gradle verification only. */
public final class RunCommandTool implements TalosTool {
    private static final String NAME = CommandToolPlanner.TOOL_NAME;
    private static final Pattern HIGH_ENTROPY_COMMAND_TOKEN = Pattern.compile(
            "(?<![A-Za-z0-9_+/=-])([A-Za-z0-9_+/-]{40,}={0,2})(?![A-Za-z0-9_+/=-])");

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
        return "Run an approved bounded command profile: Gradle verification profiles,"
                + " plus workspace-declared ws:<id> verification profiles when the"
                + " workspace declaration is trusted.";
    }

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(
                NAME,
                description(),
                """
                {"type":"object","properties":{
                  "profile":{"type":"string","description":"Approved profile: gradle_test, gradle_check, gradle_build, gradle_install_dist, gradle_e2e_test, or a trusted workspace profile ws:<id>"},
                  "args_json":{"type":"string","description":"Optional JSON array of validated profile arguments, e.g. [\\"--tests\\",\\"dev.talos.SomeTest\\"]. Workspace ws: profiles accept no arguments."},
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
            plan = CommandToolPlanner.plan(call, ctx.workspace(), registry);
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
        ToolContentMetadata metadata = commandOutputMetadata(result);
        if (result.success()) {
            return ToolResult.ok(renderSuccess(result), metadata);
        }
        if (result.timedOut()) {
            return commandFailure(ToolError.internal(
                    ToolFailureReason.COMMAND_TIMEOUT, renderFailure(result)), metadata);
        }
        return commandFailure(ToolError.internal(renderFailure(result)), metadata);
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

    private static ToolContentMetadata commandOutputMetadata(CommandResult result) {
        CommandPlan plan = result == null ? null : result.plan();
        boolean redactionApplied = result != null && result.redactionApplied();
        boolean highEntropyOutput = containsHighEntropyCommandOutput(result);
        boolean modelHandoffAllowed = !redactionApplied && !highEntropyOutput;
        return ToolContentMetadata.commandOutput(
                plan == null ? "" : plan.profileId(),
                modelHandoffAllowed,
                redactionApplied
                        ? "command output required redaction before model handoff"
                        : highEntropyOutput
                        ? "command output contained high-entropy content before model handoff"
                        : "command output accepted for model handoff");
    }

    private static ToolResult commandFailure(ToolError error, ToolContentMetadata metadata) {
        return new ToolResult(false, null, error, null, metadata);
    }

    private static boolean containsHighEntropyCommandOutput(CommandResult result) {
        if (result == null) return false;
        return containsHighEntropyToken(result.stdout()) || containsHighEntropyToken(result.stderr());
    }

    private static boolean containsHighEntropyToken(String value) {
        if (value == null || value.isBlank()) return false;
        Matcher matcher = HIGH_ENTROPY_COMMAND_TOKEN.matcher(value);
        while (matcher.find()) {
            if (isHighEntropyToken(matcher.group(1))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHighEntropyToken(String raw) {
        if (raw == null) return false;
        String token = raw.replaceAll("=+$", "");
        if (token.length() < 40 || token.length() > 512) return false;
        if (token.matches("(?i)[a-f0-9]{40}|[a-f0-9]{64}")) return false;
        if (token.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            return false;
        }
        long classes = java.util.stream.Stream.of(
                        token.chars().anyMatch(Character::isLowerCase),
                        token.chars().anyMatch(Character::isUpperCase),
                        token.chars().anyMatch(Character::isDigit),
                        token.chars().anyMatch(ch -> "_+/-".indexOf(ch) >= 0))
                .filter(Boolean::booleanValue)
                .count();
        if (classes < 3) return false;
        return shannonEntropy(token) >= 4.5d;
    }

    private static double shannonEntropy(String value) {
        if (value == null || value.isEmpty()) return 0d;
        Map<Integer, Long> counts = value.chars()
                .boxed()
                .collect(java.util.stream.Collectors.groupingBy(
                        java.util.function.Function.identity(),
                        java.util.stream.Collectors.counting()));
        double length = value.length();
        double entropy = 0d;
        for (long count : counts.values()) {
            double probability = count / length;
            entropy -= probability * (Math.log(probability) / Math.log(2d));
        }
        return entropy;
    }
}
