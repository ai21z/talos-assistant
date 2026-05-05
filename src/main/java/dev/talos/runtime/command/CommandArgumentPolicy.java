package dev.talos.runtime.command;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Profile-specific argument validator for non-shell command plans. */
public final class CommandArgumentPolicy {
    private static final List<String> SHELL_SYNTAX = List.of(
            ";", "&&", "||", "|", ">", "<", "`", "$(", "\n", "\r");
    private static final List<String> NETWORK_TOKENS = List.of(
            "curl", "wget", "invoke-webrequest", "iwr", "fetch", "pull", "push", "--scan");
    private static final List<String> DESTRUCTIVE_TOKENS = List.of(
            "clean", "delete", "del", "rm", "rmdir", "remove", "--delete", "reset", "checkout");

    private CommandArgumentPolicy() {}

    public static List<String> validate(
            CommandProfile profile,
            List<String> callerArgs,
            Path workspace,
            Path cwd
    ) {
        List<String> args = clean(callerArgs);
        rejectUniversalRisk(profile, args);
        if (args.isEmpty()) return List.of();

        return switch (profile.id()) {
            case "gradle_test", "gradle_check", "gradle_build",
                    "gradle_install_dist", "gradle_e2e_test" -> validateGradle(profile, args);
            case "git_diff" -> validateGitDiff(args, workspace, cwd);
            case "git_status", "git_log", "java_version", "talos_version" ->
                    rejectNoCallerArgs(profile, args);
            default -> throw new CommandPlanRejectedException(
                    "Command profile does not accept caller arguments: " + profile.id());
        };
    }

    private static List<String> validateGradle(CommandProfile profile, List<String> args) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if ("--tests".equals(arg)) {
                if (i + 1 >= args.size() || args.get(i + 1).startsWith("-")) {
                    throw new CommandPlanRejectedException(
                            "Gradle --tests requires a test selector.");
                }
                out.add(arg);
                out.add(args.get(++i));
            } else if ("--stacktrace".equals(arg) || "--info".equals(arg)) {
                out.add(arg);
            } else {
                throw new CommandPlanRejectedException(
                        "Argument `" + arg + "` is not allowed for profile " + profile.id() + ".");
            }
        }
        return List.copyOf(out);
    }

    private static List<String> validateGitDiff(List<String> args, Path workspace, Path cwd) {
        List<String> out = new ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith("-")) {
                throw new CommandPlanRejectedException(
                        "Argument `" + arg + "` is not allowed for profile git_diff.");
            }
            ensurePathInsideWorkspace(arg, workspace, cwd);
            out.add(arg);
        }
        return List.copyOf(out);
    }

    private static List<String> rejectNoCallerArgs(CommandProfile profile, List<String> args) {
        if (!args.isEmpty()) {
            throw new CommandPlanRejectedException(
                    "Profile " + profile.id() + " does not accept caller arguments.");
        }
        return List.of();
    }

    private static void rejectUniversalRisk(CommandProfile profile, List<String> args) {
        for (String arg : args) {
            String lower = arg.toLowerCase(Locale.ROOT);
            for (String marker : SHELL_SYNTAX) {
                if (lower.contains(marker)) {
                    throw new CommandPlanRejectedException(
                            "Argument contains unsupported shell syntax for profile "
                                    + profile.id() + ": " + marker);
                }
            }
            for (String marker : NETWORK_TOKENS) {
                if (lower.equals(marker) || lower.contains(marker)) {
                    throw new CommandPlanRejectedException(
                            "Argument has network command risk for profile "
                                    + profile.id() + ": " + arg);
                }
            }
            for (String marker : DESTRUCTIVE_TOKENS) {
                if (lower.equals(marker) || lower.contains(marker)) {
                    throw new CommandPlanRejectedException(
                            "Argument has destructive command risk for profile "
                                    + profile.id() + ": " + arg);
                }
            }
        }
    }

    private static void ensurePathInsideWorkspace(String value, Path workspace, Path cwd) {
        Path workspaceRoot = workspace.toAbsolutePath().normalize();
        Path base = cwd == null ? workspaceRoot : cwd.toAbsolutePath().normalize();
        Path resolved;
        try {
            Path requested = Path.of(value);
            resolved = requested.isAbsolute()
                    ? requested.toAbsolutePath().normalize()
                    : base.resolve(requested).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            throw new CommandPlanRejectedException("Invalid path argument: " + value);
        }
        if (!resolved.startsWith(workspaceRoot)) {
            throw new CommandPlanRejectedException("Command argument escapes workspace: " + value);
        }
    }

    private static List<String> clean(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .toList();
    }
}
