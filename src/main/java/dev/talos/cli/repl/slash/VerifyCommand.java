package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.ApprovalGate;
import dev.talos.runtime.ApprovalResponse;
import dev.talos.runtime.Result;
import dev.talos.runtime.command.CommandPlan;
import dev.talos.runtime.command.CommandPlanRejectedException;
import dev.talos.runtime.command.CommandProfileRegistry;
import dev.talos.runtime.command.CommandResult;
import dev.talos.runtime.command.CommandRunner;
import dev.talos.runtime.command.CommandToolPlanner;
import dev.talos.runtime.command.ProcessCommandRunner;
import dev.talos.runtime.command.WorkspaceCommandProfilesLoader;
import dev.talos.runtime.command.WorkspaceProfileTrustStore;
import dev.talos.tools.ToolCall;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * {@code /verify} - run one trusted workspace verification profile, behind
 * the same plan validation and a per-run approval.
 *
 * <p>The declaration and trust state are evaluated live, so a profile pinned
 * with {@code /profiles trust} a moment ago is immediately runnable here.
 * (The model-facing {@code run_command} surface, by contrast, registers
 * workspace profiles at session start.)
 */
public final class VerifyCommand implements Command {

    private static final int OUTPUT_TAIL_CHARS = 2_000;

    private final Path workspace;
    private final WorkspaceProfileTrustStore trustStore;
    private final CommandRunner runner;

    public VerifyCommand(Path workspace) {
        this(workspace, new WorkspaceProfileTrustStore(), new ProcessCommandRunner());
    }

    /** Test seam: explicit trust store and runner. */
    VerifyCommand(Path workspace, WorkspaceProfileTrustStore trustStore, CommandRunner runner) {
        this.workspace = workspace;
        this.trustStore = trustStore;
        this.runner = runner;
    }

    @Override
    public CommandSpec spec() {
        return new CommandSpec("verify", List.of(),
                "/verify [ws:<id>]",
                "Run a trusted workspace verification profile.",
                CommandGroup.SECURITY);
    }

    @Override
    public Result execute(String args, Context ctx) {
        WorkspaceCommandProfilesLoader.Loaded loaded =
                WorkspaceCommandProfilesLoader.load(workspace);
        WorkspaceProfileTrustStore.TrustState state = trustStore.state(workspace, loaded);
        CommandProfileRegistry registry = CommandProfileRegistry.defaultRegistry()
                .withWorkspaceDeclaration(loaded.profiles(), state);

        String requested = args == null ? "" : args.strip();
        if (requested.isBlank()) {
            return new Result.Info(renderRunnable(registry, state));
        }
        String profileId = requested.startsWith(WorkspaceCommandProfilesLoader.PROFILE_ID_PREFIX)
                ? requested
                : WorkspaceCommandProfilesLoader.PROFILE_ID_PREFIX + requested;

        ApprovalGate gate = ctx == null ? null : ctx.approvalGate();
        if (gate == null) {
            return new Result.Error("/verify requires an approval gate.", 500);
        }

        CommandPlan plan;
        try {
            plan = CommandToolPlanner.plan(
                    new ToolCall(CommandToolPlanner.TOOL_NAME, Map.of("profile", profileId)),
                    workspace,
                    registry);
        } catch (CommandPlanRejectedException | IllegalArgumentException e) {
            return new Result.Error(e.getMessage(), 200);
        }

        ApprovalResponse response = gate.approveFull(
                "run workspace verification: " + profileId,
                CommandToolPlanner.approvalDetail(plan));
        if (!response.isApproved()) {
            return new Result.Info("Verification cancelled. No command was executed.");
        }

        CommandResult result = runner.run(plan);
        return new Result.Info(renderResult(profileId, result));
    }

    private static String renderRunnable(CommandProfileRegistry registry,
                                         WorkspaceProfileTrustStore.TrustState state) {
        if (!registry.workspaceProfileIds().isEmpty()) {
            return "Runnable workspace verification profiles: "
                    + String.join(", ", registry.workspaceProfileIds())
                    + "\nRun one with /verify ws:<id> (approval required per run).";
        }
        return switch (state) {
            case NONE_DECLARED -> "No workspace verification profiles are declared"
                    + " (.talos/profiles.yaml).";
            case INVALID -> "The workspace declaration is invalid: "
                    + registry.workspaceRejectionReason();
            case UNTRUSTED_NEW, UNTRUSTED_CHANGED -> "Workspace profiles are declared but"
                    + " untrusted. Review and pin them with /profiles trust first.";
            case TRUSTED -> "No workspace profiles are registered.";
        };
    }

    private static String renderResult(String profileId, CommandResult result) {
        StringBuilder sb = new StringBuilder();
        if (result.success()) {
            sb.append("Verification passed: ").append(profileId)
                    .append(" exited 0 after ").append(result.durationMs()).append("ms.");
        } else if (result.timedOut()) {
            sb.append("Verification timed out: ").append(profileId)
                    .append(" after ").append(result.durationMs()).append("ms.");
        } else if (!result.errorMessage().isBlank()) {
            sb.append("Verification failed: ").append(profileId)
                    .append(" could not run - ").append(result.errorMessage());
        } else {
            sb.append("Verification failed: ").append(profileId)
                    .append(" exited ").append(result.exitCode())
                    .append(" after ").append(result.durationMs()).append("ms.");
        }
        appendTail(sb, "stdout", result.stdout());
        appendTail(sb, "stderr", result.stderr());
        return sb.toString();
    }

    private static void appendTail(StringBuilder sb, String label, String content) {
        String text = content == null ? "" : content.strip();
        if (text.isEmpty()) return;
        String tail = text.length() <= OUTPUT_TAIL_CHARS
                ? text
                : "..." + text.substring(text.length() - OUTPUT_TAIL_CHARS);
        sb.append('\n').append(label).append(":\n").append(tail);
    }
}
