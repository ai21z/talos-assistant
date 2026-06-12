package dev.talos.runtime.command;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Registry of supported non-shell command profiles. Does not execute commands. */
public final class CommandProfileRegistry {
    private final Map<String, CommandProfile> profiles;
    private final WorkspaceProfileTrustStore.TrustState workspaceTrustState;
    private final String workspaceRejectionReason;
    private final Set<String> workspaceProfileIds;

    public CommandProfileRegistry(List<CommandProfile> profiles) {
        this(profiles, WorkspaceProfileTrustStore.TrustState.NONE_DECLARED, "", Set.of());
    }

    private CommandProfileRegistry(List<CommandProfile> profiles,
                                   WorkspaceProfileTrustStore.TrustState workspaceTrustState,
                                   String workspaceRejectionReason,
                                   Set<String> workspaceProfileIds) {
        Map<String, CommandProfile> out = new LinkedHashMap<>();
        if (profiles != null) {
            for (CommandProfile profile : profiles) {
                if (profile != null) {
                    out.put(profile.id(), profile);
                }
            }
        }
        this.profiles = Map.copyOf(out);
        this.workspaceTrustState = workspaceTrustState == null
                ? WorkspaceProfileTrustStore.TrustState.NONE_DECLARED
                : workspaceTrustState;
        this.workspaceRejectionReason = workspaceRejectionReason == null ? "" : workspaceRejectionReason;
        this.workspaceProfileIds = Set.copyOf(workspaceProfileIds == null ? Set.of() : workspaceProfileIds);
    }

    /**
     * Merge a workspace declaration into this registry (T790). Declared
     * profiles register ONLY when the trust state is {@code TRUSTED} — an
     * untrusted, changed, or invalid declaration leaves the registry's
     * runnable surface unchanged and only records the state for instructive
     * plan-time rejections. Built-ins win on id collision, which the
     * {@code ws:} namespace makes structurally impossible (built-in ids
     * carry no colon) — asserted anyway.
     */
    public CommandProfileRegistry withWorkspaceDeclaration(
            WorkspaceCommandProfiles declaration,
            WorkspaceProfileTrustStore.TrustState state) {
        WorkspaceCommandProfiles safe = declaration == null
                ? WorkspaceCommandProfiles.none()
                : declaration;
        WorkspaceProfileTrustStore.TrustState effectiveState = state == null
                ? WorkspaceProfileTrustStore.TrustState.NONE_DECLARED
                : state;
        if (effectiveState != WorkspaceProfileTrustStore.TrustState.TRUSTED || !safe.valid()) {
            return new CommandProfileRegistry(
                    new ArrayList<>(profiles.values()),
                    effectiveState,
                    safe.rejectionReason(),
                    Set.of());
        }
        List<CommandProfile> merged = new ArrayList<>(profiles.values());
        Set<String> declaredIds = new LinkedHashSet<>();
        for (CommandProfile profile : safe.profiles()) {
            if (profiles.containsKey(profile.id())) {
                throw new IllegalStateException(
                        "workspace profile id collides with a built-in: " + profile.id());
            }
            merged.add(profile);
            declaredIds.add(profile.id());
        }
        return new CommandProfileRegistry(merged, effectiveState, "", declaredIds);
    }

    /** Trust state of the workspace declaration this registry was built with. */
    public WorkspaceProfileTrustStore.TrustState workspaceTrustState() {
        return workspaceTrustState;
    }

    /** Why an invalid declaration registered nothing (blank otherwise). */
    public String workspaceRejectionReason() {
        return workspaceRejectionReason;
    }

    /** The ws:-prefixed ids that actually registered (empty unless TRUSTED). */
    public Set<String> workspaceProfileIds() {
        return workspaceProfileIds;
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
