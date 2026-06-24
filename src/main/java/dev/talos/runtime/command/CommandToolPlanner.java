package dev.talos.runtime.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.tools.ToolAliasPolicy;
import dev.talos.tools.ToolCall;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Builds and validates command plans from the talos.run_command tool surface. */
public final class CommandToolPlanner {
    public static final String TOOL_NAME = "talos.run_command";
    public static final long MIN_TIMEOUT_MS = 1_000;
    public static final long MAX_TIMEOUT_MS = 600_000;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> GRADLE_V1_PROFILES = List.of(
            "gradle_test",
            "gradle_check",
            "gradle_build",
            "gradle_install_dist",
            "gradle_e2e_test");
    private static final List<String> RAW_COMMAND_KEYS = List.of(
            "command", "cmd", "shell", "executable", "argv", "command_line");

    private CommandToolPlanner() {}

    public static boolean isRunCommandTool(String toolName) {
        return "run_command".equals(ToolAliasPolicy.localCanonicalName(toolName));
    }

    /** True for workspace-declared profile ids ({@code ws:<id>}, T789/T790). */
    public static boolean isWorkspaceProfile(String profileId) {
        return profileId != null && profileId.strip()
                .startsWith(WorkspaceCommandProfilesLoader.PROFILE_ID_PREFIX);
    }

    /**
     * Dispatching entry point (T790): {@code ws:} profile ids plan through
     * the workspace branch (trust-gated at plan time, no caller args);
     * everything else keeps the byte-identical gradle V1 path.
     */
    public static CommandPlan plan(ToolCall call, Path workspace, CommandProfileRegistry registry) {
        if (call == null) {
            throw new CommandPlanRejectedException("Command tool call is required.");
        }
        String profileId = param(call, "profile", "profile_id", "id");
        if (isWorkspaceProfile(profileId)) {
            return planWorkspace(call, profileId.strip(), workspace, registry);
        }
        return planGradleV1(call, workspace, registry);
    }

    /**
     * Workspace-profile planning. The trust gate fires HERE, at plan time,
     * before any approval prompt is spent: an untrusted, changed, invalid,
     * or undeclared workspace declaration can never reach the approval gate
     * (T790 proof obligation).
     */
    private static CommandPlan planWorkspace(
            ToolCall call,
            String profile,
            Path workspace,
            CommandProfileRegistry registry
    ) {
        rejectRawCommandShape(call);
        CommandProfileRegistry effectiveRegistry = registry == null
                ? CommandProfileRegistry.defaultRegistry()
                : registry;
        if (!effectiveRegistry.workspaceProfileIds().contains(profile)) {
            throw new CommandPlanRejectedException(
                    workspaceUnavailableMessage(profile, effectiveRegistry));
        }
        String rawArgs = param(call, "args_json", "arguments_json", "args");
        if (rawArgs != null && !rawArgs.isBlank()) {
            throw new CommandPlanRejectedException(
                    "Workspace profiles run their declared fixed argv only; caller"
                            + " arguments are not accepted.");
        }
        CommandPlan plan = effectiveRegistry.plan(
                profile,
                List.of(),
                workspace,
                param(call, "cwd", "working_dir", "working_directory"));
        validateRisk(plan);
        long timeout = timeoutMs(call);
        return timeout > 0 ? plan.withTimeoutMs(timeout) : plan;
    }

    private static String workspaceUnavailableMessage(
            String profile, CommandProfileRegistry registry) {
        return switch (registry.workspaceTrustState()) {
            case NONE_DECLARED -> "Workspace profile " + profile
                    + " is not available: this workspace declares no verification"
                    + " profiles (.talos/profiles.yaml).";
            case INVALID -> "Workspace profile " + profile
                    + " is not available: the declaration is invalid — "
                    + registry.workspaceRejectionReason()
                    + " Nothing was registered.";
            case UNTRUSTED_NEW, UNTRUSTED_CHANGED -> "Workspace profile " + profile
                    + " is not available: the workspace declaration is untrusted or"
                    + " has changed. Review and pin it with /profiles trust, then retry.";
            case TRUSTED -> "Unknown workspace profile " + profile + ". Declared: "
                    + String.join(", ", registry.workspaceProfileIds()) + ".";
        };
    }

    public static Optional<String> validateBeforeApproval(ToolCall call, Path workspace) {
        return validateBeforeApproval(call, workspace, CommandProfileRegistry.defaultRegistry());
    }

    public static Optional<String> validateBeforeApproval(
            ToolCall call, Path workspace, CommandProfileRegistry registry) {
        if (call == null || !isRunCommandTool(call.toolName())) return Optional.empty();
        try {
            plan(call, workspace, registry);
            return Optional.empty();
        } catch (CommandPlanRejectedException | IllegalArgumentException e) {
            return Optional.of(invalidMessage(e.getMessage()));
        }
    }

    public static CommandPlan planGradleV1(
            ToolCall call,
            Path workspace,
            CommandProfileRegistry registry
    ) {
        if (call == null) {
            throw new CommandPlanRejectedException("Command tool call is required.");
        }
        rejectRawCommandShape(call);
        String profileId = param(call, "profile", "profile_id", "id");
        if (profileId == null || profileId.isBlank()) {
            throw new CommandPlanRejectedException("Missing required parameter `profile`.");
        }
        String profile = profileId.strip();
        if (!GRADLE_V1_PROFILES.contains(profile)) {
            throw new CommandPlanRejectedException("Profile " + profile
                    + " is not available for talos.run_command V1. Supported profiles: "
                    + String.join(", ", GRADLE_V1_PROFILES) + ".");
        }

        CommandProfileRegistry effectiveRegistry = registry == null
                ? CommandProfileRegistry.defaultRegistry()
                : registry;
        CommandPlan plan = effectiveRegistry.plan(
                profile,
                args(call),
                workspace,
                param(call, "cwd", "working_dir", "working_directory"));
        validateRisk(plan);
        validateGradleWrapperAvailable(plan);
        long timeout = timeoutMs(call);
        return timeout > 0 ? plan.withTimeoutMs(timeout) : plan;
    }

    public static String invalidMessage(String reason) {
        String detail = reason == null || reason.isBlank() ? "Invalid command request." : reason.strip();
        return "Invalid talos.run_command call: " + detail
                + " No approval was requested and no command was executed.";
    }

    public static String approvalDetail(ToolCall call, Path workspace) {
        return approvalDetail(call, workspace, CommandProfileRegistry.defaultRegistry());
    }

    public static String approvalDetail(
            ToolCall call, Path workspace, CommandProfileRegistry registry) {
        return approvalDetail(plan(call, workspace, registry));
    }

    public static String approvalDetail(CommandPlan plan) {
        if (plan == null) return "command: unavailable";
        StringBuilder sb = new StringBuilder();
        sb.append("profile: ").append(plan.profileId()).append('\n');
        sb.append("    risk: ").append(plan.risk()).append('\n');
        sb.append("    cwd: ").append(plan.cwd()).append('\n');
        sb.append("    argv: ").append(displayCommand(plan)).append('\n');
        sb.append("    timeoutMs: ").append(plan.timeoutMs()).append('\n');
        sb.append("    outputCaps: stdout=")
                .append(plan.outputLimits().stdoutLimitBytes())
                .append(" bytes, stderr=")
                .append(plan.outputLimits().stderrLimitBytes())
                .append(" bytes\n");
        sb.append("    expectedWrites: ")
                .append(plan.expectedWrites().isEmpty()
                        ? "(none)"
                        : String.join(", ", plan.expectedWrites()))
                .append('\n');
        sb.append("    checkpoint: ")
                .append(plan.requiresCheckpoint() ? "required" : "not required")
                .append('\n');
        sb.append("    network: ")
                .append(plan.networkAccess() ? "allowed" : "disabled")
                .append(", interactive: ")
                .append(plan.interactive() ? "allowed" : "disabled");
        return sb.toString();
    }

    public static String displayCommand(CommandPlan plan) {
        if (plan == null) return "";
        List<String> parts = new ArrayList<>();
        parts.add(plan.executable());
        parts.addAll(plan.argv());
        return String.join(" ", parts);
    }

    public static List<String> gradleV1Profiles() {
        return GRADLE_V1_PROFILES;
    }

    private static void rejectRawCommandShape(ToolCall call) {
        for (String key : RAW_COMMAND_KEYS) {
            String value = call.param(key);
            if (value != null && !value.isBlank()) {
                throw new CommandPlanRejectedException(
                        "Raw shell commands are not supported. Use an approved command profile.");
            }
        }
    }

    private static void validateRisk(CommandPlan plan) {
        if (plan.networkAccess()) {
            throw new CommandPlanRejectedException("Command profile requires network access.");
        }
        if (plan.interactive()) {
            throw new CommandPlanRejectedException("Command profile is interactive.");
        }
        if (plan.risk() != CommandRisk.BUILD_OR_TEST) {
            throw new CommandPlanRejectedException("Command risk is not available in V1: " + plan.risk());
        }
    }

    private static void validateGradleWrapperAvailable(CommandPlan plan) {
        if (plan == null || plan.cwd() == null) {
            throw new CommandPlanRejectedException("Command working directory is unavailable.");
        }
        Path wrapper = selectedGradleWrapper(plan.cwd(), plan.executable());
        if (!Files.isRegularFile(wrapper)) {
            throw new CommandPlanRejectedException(
                    "Gradle command profile requires selected wrapper `" + plan.executable()
                            + "` in the selected workspace/cwd.");
        }
    }

    private static Path selectedGradleWrapper(Path cwd, String executable) {
        if (executable == null || executable.isBlank()) {
            throw new CommandPlanRejectedException("Gradle command executable is unavailable.");
        }
        String normalized = executable.strip().replace('\\', '/');
        if ("./gradlew.bat".equals(normalized) || "gradlew.bat".equals(normalized)) {
            return cwd.resolve("gradlew.bat").toAbsolutePath().normalize();
        }
        if ("./gradlew".equals(normalized) || "gradlew".equals(normalized)) {
            return cwd.resolve("gradlew").toAbsolutePath().normalize();
        }
        try {
            Path requested = Path.of(executable.strip());
            return requested.isAbsolute()
                    ? requested.toAbsolutePath().normalize()
                    : cwd.resolve(requested).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            throw new CommandPlanRejectedException("Invalid Gradle command executable: " + executable);
        }
    }

    private static List<String> args(ToolCall call) {
        String raw = param(call, "args_json", "arguments_json", "args");
        if (raw == null || raw.isBlank()) return List.of();
        try {
            JsonNode root = MAPPER.readTree(raw);
            if (!root.isArray()) {
                throw new CommandPlanRejectedException("args_json must be a JSON array of strings.");
            }
            List<String> args = new ArrayList<>();
            for (JsonNode node : root) {
                if (!node.isTextual()) {
                    throw new CommandPlanRejectedException("args_json values must be strings.");
                }
                args.add(node.asText());
            }
            return List.copyOf(args);
        } catch (CommandPlanRejectedException e) {
            throw e;
        } catch (Exception e) {
            throw new CommandPlanRejectedException("Invalid args_json: " + e.getMessage());
        }
    }

    private static long timeoutMs(ToolCall call) {
        String raw = param(call, "timeout_ms", "timeoutMs");
        if (raw == null || raw.isBlank()) return -1;
        long value;
        try {
            value = Long.parseLong(raw.strip());
        } catch (NumberFormatException e) {
            throw new CommandPlanRejectedException("timeout_ms must be an integer.");
        }
        if (value < MIN_TIMEOUT_MS || value > MAX_TIMEOUT_MS) {
            throw new CommandPlanRejectedException(
                    "timeout_ms must be between " + MIN_TIMEOUT_MS + " and " + MAX_TIMEOUT_MS + ".");
        }
        return value;
    }

    private static String param(ToolCall call, String canonical, String... aliases) {
        if (call == null) return null;
        String value = call.param(canonical);
        if (value != null) return value;
        for (String alias : aliases) {
            value = call.param(alias);
            if (value != null) return value;
        }
        return null;
    }
}
