package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.ApprovalGate;
import dev.talos.runtime.ApprovalResponse;
import dev.talos.runtime.Result;
import dev.talos.runtime.checkpoint.CheckpointCaptureResult;
import dev.talos.runtime.checkpoint.CheckpointService;
import dev.talos.runtime.command.CommandProfile;
import dev.talos.runtime.command.CommandPlanRejectedException;
import dev.talos.runtime.command.WorkspaceCommandProfilesLoader;
import dev.talos.runtime.command.WorkspaceProfileDeclarationEditor;
import dev.talos.runtime.command.WorkspaceProfileTrustStore;
import dev.talos.runtime.policy.ProtectedContentPolicy;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.tools.ToolCall;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /profiles} - inspect, declare, trust, or revoke the workspace
 * verification profile declaration ({@code .talos/profiles.yaml}).
 *
 * <p>{@code trust} is the explicit-consent step of the T789 trust chain: it
 * shows the resolved profiles (with absolute executable paths) plus the
 * declaration's SHA-256 and pins that exact byte content behind an approval.
 * The declaration is reloaded live on every invocation, so an edit made
 * mid-session is always judged against its current bytes.
 */
public final class ProfilesCommand implements Command {

    private final Path workspace;
    private final WorkspaceProfileTrustStore trustStore;
    private final CheckpointService checkpointService;

    public ProfilesCommand(Path workspace) {
        this(workspace, new WorkspaceProfileTrustStore(), new CheckpointService());
    }

    public ProfilesCommand(Path workspace, CheckpointService checkpointService) {
        this(workspace, new WorkspaceProfileTrustStore(), checkpointService);
    }

    /** Test seam: an explicit trust store keeps pins out of the real home. */
    ProfilesCommand(Path workspace, WorkspaceProfileTrustStore trustStore) {
        this(workspace, trustStore, new CheckpointService());
    }

    /** Test seam: explicit trust and checkpoint stores keep writes local. */
    ProfilesCommand(Path workspace,
                    WorkspaceProfileTrustStore trustStore,
                    CheckpointService checkpointService) {
        this.workspace = workspace;
        this.trustStore = trustStore;
        this.checkpointService = checkpointService;
    }

    @Override
    public CommandSpec spec() {
        return new CommandSpec("profiles", List.of(),
                "/profiles [list|configure|trust|revoke]",
                "Inspect, configure, or trust workspace verification profiles (.talos/profiles.yaml; not model/GGUF profiles).",
                CommandGroup.SECURITY);
    }

    @Override
    public Result execute(String args, Context ctx) {
        String trimmed = args == null ? "" : args.strip();
        String sub = trimmed.isBlank() ? "list" : firstToken(trimmed).toLowerCase(Locale.ROOT);
        String rest = trimmed.isBlank() ? "" : trimmed.substring(firstToken(trimmed).length()).strip();
        WorkspaceCommandProfilesLoader.Loaded loaded =
                WorkspaceCommandProfilesLoader.load(workspace);
        WorkspaceProfileTrustStore.TrustState state = trustStore.state(workspace, loaded);
        return switch (sub) {
            case "list" -> new Result.Info(renderList(loaded, state));
            case "configure", "config", "set" -> configure(rest, ctx);
            case "trust" -> trust(loaded, state, ctx);
            case "revoke" -> revoke(state);
            default -> new Result.Error(
                    "Unknown subcommand: " + sub
                            + ". Usage: /profiles [list|configure|trust|revoke]", 200);
        };
    }

    private Result configure(String args, Context ctx) {
        WorkspaceProfileDeclarationEditor.Proposal proposal;
        try {
            proposal = WorkspaceProfileDeclarationEditor.propose(
                    workspace,
                    parseConfigureArgs(args));
        } catch (CommandPlanRejectedException | IllegalArgumentException e) {
            return new Result.Error(e.getMessage(), 200);
        }

        ApprovalGate gate = ctx == null ? null : ctx.approvalGate();
        if (gate == null) {
            return new Result.Error("Configuring workspace profiles requires an approval gate.", 500);
        }
        ApprovalResponse response = gate.approveFull(
                "configure workspace verification profile: " + proposal.profileId(),
                configureDetail(proposal));
        if (!response.isApproved()) {
            String trustState = currentTrustStateName();
            recordConfigureTrace(proposal, "DENIED", trustState, "", "");
            auditConfigure(ctx, proposal, "DENIED", trustState, "", "");
            return new Result.Info("Workspace profile declaration was NOT written. Nothing changed.");
        }

        CheckpointCaptureResult checkpoint = checkpointBeforeConfigure(ctx, proposal);
        LocalTurnTraceCapture.recordCheckpoint(
                checkpoint.status(),
                checkpoint.checkpointId(),
                checkpoint.message(),
                checkpoint.capturedFiles());
        if (!checkpoint.success()) {
            String trustState = currentTrustStateName();
            recordConfigureTrace(proposal, "APPROVED", trustState, checkpoint.status(), checkpoint.checkpointId());
            auditConfigure(ctx, proposal, "APPROVED", trustState, checkpoint.status(), checkpoint.checkpointId());
            return new Result.Error(
                    "Required checkpoint failed before writing .talos/profiles.yaml: "
                            + checkpoint.message(),
                    500);
        }

        try {
            Path declaration = declarationPath();
            Files.createDirectories(declaration.getParent());
            Files.writeString(declaration, proposal.content(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            String trustState = currentTrustStateName();
            recordConfigureTrace(proposal, "APPROVED", trustState, checkpoint.status(), checkpoint.checkpointId());
            auditConfigure(ctx, proposal, "APPROVED", trustState, checkpoint.status(), checkpoint.checkpointId());
            return new Result.Error(
                    "Failed to write .talos/profiles.yaml: " + e.getMessage(),
                    500);
        }

        WorkspaceCommandProfilesLoader.Loaded loadedAfter =
                WorkspaceCommandProfilesLoader.load(workspace);
        WorkspaceProfileTrustStore.TrustState stateAfter =
                trustStore.state(workspace, loadedAfter);
        recordConfigureTrace(
                proposal,
                "APPROVED",
                stateAfter.name(),
                checkpoint.status(),
                checkpoint.checkpointId());
        auditConfigure(
                ctx,
                proposal,
                "APPROVED",
                stateAfter.name(),
                checkpoint.status(),
                checkpoint.checkpointId());

        return new Result.Info((proposal.replacedExisting() ? "Updated" : "Declared")
                + " " + proposal.profileId()
                + " in .talos/profiles.yaml (sha256 "
                + shortSha(proposal.declarationSha256()) + ").\n"
                + "Workspace profiles are declared but untrusted ("
                + stateLabel(stateAfter, loadedAfter) + "). Run /profiles trust to review and pin.");
    }

    private Result trust(WorkspaceCommandProfilesLoader.Loaded loaded,
                         WorkspaceProfileTrustStore.TrustState state,
                         Context ctx) {
        switch (state) {
            case NONE_DECLARED -> {
                return new Result.Info(
                        "No workspace profiles are declared (.talos/profiles.yaml). Nothing to trust.");
            }
            case INVALID -> {
                return new Result.Error("The declaration is invalid and cannot be trusted: "
                        + loaded.profiles().rejectionReason(), 200);
            }
            case TRUSTED -> {
                return new Result.Info("Workspace profiles are already trusted (sha256 "
                        + shortSha(loaded.declarationSha256()) + "). Nothing changed.");
            }
            case UNTRUSTED_NEW, UNTRUSTED_CHANGED -> { /* fall through to consent */ }
        }
        ApprovalGate gate = ctx == null ? null : ctx.approvalGate();
        if (gate == null) {
            return new Result.Error("Trusting workspace profiles requires an approval gate.", 500);
        }
        ApprovalResponse response = gate.approveFull(
                "trust workspace verification profiles",
                trustDetail(loaded));
        if (!response.isApproved()) {
            return new Result.Info("Workspace profiles were NOT trusted. Nothing changed.");
        }
        trustStore.pin(workspace, loaded.declarationSha256(),
                loaded.profiles().profiles().size(), Instant.now());
        return new Result.TrustedInfo("Pinned " + loaded.profiles().profiles().size()
                + " workspace profile(s) (sha256 " + shortSha(loaded.declarationSha256()) + ").\n"
                + "/verify can run them now; the model's run_command surface picks them up"
                + " on the next session start.");
    }

    private Result revoke(WorkspaceProfileTrustStore.TrustState state) {
        if (state == WorkspaceProfileTrustStore.TrustState.NONE_DECLARED) {
            return new Result.Info("No workspace profiles are declared. Nothing to revoke.");
        }
        trustStore.unpin(workspace);
        return new Result.Info("Workspace profile trust revoked. Declared profiles are"
                + " untrusted until /profiles trust pins them again.");
    }

    private CheckpointCaptureResult checkpointBeforeConfigure(
            Context ctx,
            WorkspaceProfileDeclarationEditor.Proposal proposal
    ) {
        return checkpointService.captureBeforeMutation(
                workspace,
                ctx == null ? null : ctx.cfg(),
                new ToolCall("profiles configure", Map.of(
                        "path", WorkspaceCommandProfilesLoader.DECLARATION_RELATIVE_PATH,
                        "content", proposal.content())),
                LocalTurnTraceCapture.currentTraceId(),
                LocalTurnTraceCapture.currentTurnNumber());
    }

    private static String renderList(WorkspaceCommandProfilesLoader.Loaded loaded,
                                     WorkspaceProfileTrustStore.TrustState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("Workspace verification profiles (.talos/profiles.yaml)\n");
        sb.append("  state: ").append(stateLabel(state, loaded)).append('\n');
        if (!loaded.profiles().profiles().isEmpty()) {
            sb.append(renderProfiles(loaded.profiles().profiles()));
            sb.append("  declaration sha256: ")
                    .append(shortSha(loaded.declarationSha256())).append('\n');
        }
        sb.append(switch (state) {
            case NONE_DECLARED -> "  declare profiles in .talos/profiles.yaml to enable /verify";
            case INVALID -> "  fix the declaration; nothing is registered";
            case UNTRUSTED_NEW, UNTRUSTED_CHANGED -> "  run /profiles trust to review and pin";
            case TRUSTED -> "  run /verify ws:<id> to execute one (approval required per run)";
        });
        return sb.toString();
    }

    private static String trustDetail(WorkspaceCommandProfilesLoader.Loaded loaded) {
        String detail = "Pin the workspace verification profile declaration at its current bytes.\n"
                + "    Any later change to the file returns it to untrusted.\n"
                + renderProfiles(loaded.profiles().profiles()).indent(4).stripTrailing() + '\n'
                + "    declaration sha256: " + loaded.declarationSha256();
        return ProtectedContentPolicy.sanitizeText(detail);
    }

    private static String configureDetail(WorkspaceProfileDeclarationEditor.Proposal proposal) {
        return "Write the proposed .talos/profiles.yaml bytes below.\n"
                + "    This only DECLARES a workspace verification profile; it does not trust it.\n"
                + "    Run /profiles trust afterward to review and pin the SHA-256.\n"
                + "    declaration sha256: " + proposal.declarationSha256() + "\n"
                + "    proposed .talos/profiles.yaml bytes:\n"
                + indent(proposal.content(), "      ").stripTrailing();
    }

    private static String renderProfiles(List<CommandProfile> profiles) {
        StringBuilder sb = new StringBuilder();
        for (CommandProfile profile : profiles) {
            sb.append("  ").append(profile.id())
                    .append(": ").append(profile.executable());
            if (!profile.fixedArgs().isEmpty()) {
                sb.append(' ').append(String.join(" ", profile.fixedArgs()));
            }
            sb.append("  (timeout ").append(profile.defaultTimeoutMs() / 1000).append("s)\n");
        }
        return sb.toString();
    }

    private static String stateLabel(WorkspaceProfileTrustStore.TrustState state,
                                     WorkspaceCommandProfilesLoader.Loaded loaded) {
        return switch (state) {
            case NONE_DECLARED -> "none declared";
            case INVALID -> "invalid - " + loaded.profiles().rejectionReason();
            case UNTRUSTED_NEW -> "declared, untrusted (never pinned)";
            case UNTRUSTED_CHANGED -> "declared, untrusted (file changed since the last pin)";
            case TRUSTED -> "trusted";
        };
    }

    private static String shortSha(String sha256) {
        return sha256 == null || sha256.length() < 12 ? sha256 : sha256.substring(0, 12);
    }

    private Path declarationPath() {
        return workspace.toAbsolutePath().normalize()
                .resolve(WorkspaceCommandProfilesLoader.DECLARATION_RELATIVE_PATH);
    }

    private String currentTrustStateName() {
        WorkspaceCommandProfilesLoader.Loaded loaded =
                WorkspaceCommandProfilesLoader.load(workspace);
        return trustStore.state(workspace, loaded).name();
    }

    private static WorkspaceProfileDeclarationEditor.Request parseConfigureArgs(String args) {
        List<String> tokens = tokenize(args);
        if (tokens.isEmpty()) {
            throw new CommandPlanRejectedException(configureUsage());
        }
        String id = tokens.getFirst();
        String executable = "";
        Long timeoutMs = null;
        List<String> fixedArgs = new ArrayList<>();
        List<String> expectedWrites = new ArrayList<>();
        int i = 1;
        while (i < tokens.size()) {
            String token = tokens.get(i);
            switch (token) {
                case "--exec", "--executable" -> {
                    executable = requireValue(tokens, ++i, token);
                    i++;
                }
                case "--arg" -> {
                    fixedArgs.add(requireValue(tokens, ++i, token));
                    i++;
                }
                case "--timeout-ms" -> {
                    String raw = requireValue(tokens, ++i, token);
                    try {
                        timeoutMs = Long.parseLong(raw);
                    } catch (NumberFormatException e) {
                        throw new CommandPlanRejectedException("--timeout-ms must be an integer");
                    }
                    i++;
                }
                case "--expected-write", "--expected-writes" -> {
                    expectedWrites.add(requireValue(tokens, ++i, token));
                    i++;
                }
                default -> throw new CommandPlanRejectedException(
                        "Unknown /profiles configure option `" + token + "`. " + configureUsage());
            }
        }
        if (executable.isBlank()) {
            throw new CommandPlanRejectedException(configureUsage());
        }
        return new WorkspaceProfileDeclarationEditor.Request(
                id,
                executable,
                fixedArgs,
                timeoutMs,
                expectedWrites);
    }

    private static List<String> tokenize(String input) {
        String value = input == null ? "" : input.strip();
        if (value.isBlank()) return List.of();
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean escaping = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (escaping) {
                current.append(ch);
                escaping = false;
                continue;
            }
            if (quote == '"' && ch == '\\') {
                escaping = true;
                continue;
            }
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                } else {
                    current.append(ch);
                }
                continue;
            }
            if (ch == '"' || ch == '\'') {
                quote = ch;
                continue;
            }
            if (Character.isWhitespace(ch)) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(ch);
        }
        if (escaping) {
            current.append('\\');
        }
        if (quote != 0) {
            throw new CommandPlanRejectedException("Unclosed quote in /profiles configure arguments");
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return List.copyOf(tokens);
    }

    private static String requireValue(List<String> tokens, int index, String option) {
        if (index >= tokens.size()) {
            throw new CommandPlanRejectedException(option + " requires a value");
        }
        String value = tokens.get(index);
        if (value == null || value.isBlank()) {
            throw new CommandPlanRejectedException(option + " requires a non-blank value");
        }
        return value;
    }

    private static String configureUsage() {
        return "Usage: /profiles configure <id> --exec <executable>"
                + " [--arg <argv>]... [--timeout-ms <ms>]"
                + " [--expected-write <workspace-path>]...";
    }

    private static String firstToken(String value) {
        int idx = value.indexOf(' ');
        return idx < 0 ? value : value.substring(0, idx);
    }

    private static String indent(String text, String prefix) {
        String value = text == null ? "" : text;
        StringBuilder sb = new StringBuilder();
        for (String line : value.split("\n", -1)) {
            if (line.isEmpty() && sb.length() + 1 >= value.length()) {
                continue;
            }
            sb.append(prefix).append(line).append('\n');
        }
        return sb.toString();
    }

    private static void recordConfigureTrace(
            WorkspaceProfileDeclarationEditor.Proposal proposal,
            String approval,
            String trustStateAfter,
            String checkpointStatus,
            String checkpointId
    ) {
        LocalTurnTraceCapture.recordWorkspaceProfileDeclarationConfigured(
                proposal.profileId(),
                proposal.declarationSha256(),
                approval,
                trustStateAfter,
                checkpointStatus,
                checkpointId,
                proposal.replacedExisting());
    }

    private static void auditConfigure(
            Context ctx,
            WorkspaceProfileDeclarationEditor.Proposal proposal,
            String approval,
            String trustStateAfter,
            String checkpointStatus,
            String checkpointId
    ) {
        if (ctx == null || ctx.audit() == null) return;
        ctx.audit().log("workspace_profile_declaration", Map.of(
                "profileId", proposal.profileId(),
                "declarationSha256", proposal.declarationSha256(),
                "approval", approval,
                "trustStateAfter", trustStateAfter,
                "checkpointStatus", checkpointStatus,
                "checkpointId", checkpointId,
                "replacedExisting", proposal.replacedExisting()));
    }
}
