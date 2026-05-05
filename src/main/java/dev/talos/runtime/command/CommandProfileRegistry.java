package dev.talos.runtime.command;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Registry of supported non-shell command profiles. Does not execute commands. */
public final class CommandProfileRegistry {
    private final Map<String, CommandProfile> profiles;

    public CommandProfileRegistry(List<CommandProfile> profiles) {
        Map<String, CommandProfile> out = new LinkedHashMap<>();
        if (profiles != null) {
            for (CommandProfile profile : profiles) {
                if (profile != null) {
                    out.put(profile.id(), profile);
                }
            }
        }
        this.profiles = Map.copyOf(out);
    }

    public static CommandProfileRegistry defaultRegistry() {
        return new CommandProfileRegistry(List.of(
                gradle("gradle_test", "Gradle test", "test"),
                gradle("gradle_check", "Gradle check", "check"),
                gradle("gradle_build", "Gradle build", "build"),
                gradle("gradle_install_dist", "Gradle installDist", "installDist"),
                gradle("gradle_e2e_test", "Gradle e2eTest", "e2eTest"),
                diagnostic("git_status", "Git status", "git", List.of("status", "--short")),
                diagnostic("git_diff", "Git diff", "git", List.of("diff", "--")),
                diagnostic("git_log", "Git log", "git", List.of("log", "--oneline", "-20")),
                diagnostic("java_version", "Java version", "java", List.of("-version")),
                diagnostic("talos_version", "Talos version", "talos", List.of("--version"))));
    }

    public Set<String> profileIds() {
        return profiles.keySet();
    }

    public CommandPlan plan(String profileId, List<String> callerArgs, Path workspace, String cwd) {
        String id = profileId == null ? "" : profileId.strip();
        CommandProfile profile = profiles.get(id);
        if (profile == null) {
            throw new CommandPlanRejectedException("Unknown command profile: " + id);
        }
        Path workspaceRoot = workspaceRoot(workspace);
        Path resolvedCwd = resolveCwd(workspaceRoot, cwd);
        List<String> validatedArgs = CommandArgumentPolicy.validate(
                profile, callerArgs, workspaceRoot, resolvedCwd);
        List<String> argv = new ArrayList<>(profile.fixedArgs());
        argv.addAll(validatedArgs);
        return new CommandPlan(
                profile.id(),
                profile.displayName(),
                profile.executable(),
                argv,
                resolvedCwd,
                profile.risk(),
                profile.networkAccess(),
                profile.interactive(),
                profile.expectedWrites(),
                profile.requiresApproval(),
                profile.requiresCheckpoint(),
                profile.defaultTimeoutMs(),
                profile.defaultIdleTimeoutMs(),
                profile.outputLimits());
    }

    private static CommandProfile gradle(String id, String displayName, String task) {
        return new CommandProfile(
                id,
                displayName,
                ".\\gradlew.bat",
                List.of("--no-daemon", task),
                CommandRisk.BUILD_OR_TEST,
                false,
                false,
                List.of("build/", ".gradle/"),
                true,
                false,
                CommandProfile.DEFAULT_TIMEOUT_MS,
                CommandProfile.DEFAULT_IDLE_TIMEOUT_MS,
                CommandOutputLimits.defaults());
    }

    private static CommandProfile diagnostic(
            String id,
            String displayName,
            String executable,
            List<String> fixedArgs
    ) {
        return new CommandProfile(
                id,
                displayName,
                executable,
                fixedArgs,
                CommandRisk.READ_ONLY_DIAGNOSTIC,
                false,
                false,
                List.of(),
                true,
                false,
                CommandProfile.DEFAULT_TIMEOUT_MS,
                CommandProfile.DEFAULT_IDLE_TIMEOUT_MS,
                CommandOutputLimits.defaults());
    }

    private static Path workspaceRoot(Path workspace) {
        if (workspace == null) {
            throw new CommandPlanRejectedException("Command workspace is required.");
        }
        return workspace.toAbsolutePath().normalize();
    }

    private static Path resolveCwd(Path workspace, String cwd) {
        String raw = cwd == null || cwd.isBlank() ? "." : cwd.strip();
        Path candidate;
        try {
            Path requested = Path.of(raw);
            candidate = requested.isAbsolute()
                    ? requested.toAbsolutePath().normalize()
                    : workspace.resolve(requested).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            throw new CommandPlanRejectedException("Invalid command cwd: " + raw);
        }
        if (!candidate.startsWith(workspace)) {
            throw new CommandPlanRejectedException("Command cwd escapes workspace: " + raw);
        }
        return candidate;
    }
}
