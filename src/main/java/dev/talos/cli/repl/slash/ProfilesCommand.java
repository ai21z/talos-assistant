package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.ApprovalGate;
import dev.talos.runtime.ApprovalResponse;
import dev.talos.runtime.Result;
import dev.talos.runtime.command.CommandProfile;
import dev.talos.runtime.command.WorkspaceCommandProfilesLoader;
import dev.talos.runtime.command.WorkspaceProfileTrustStore;
import dev.talos.runtime.policy.ProtectedContentPolicy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * {@code /profiles} — inspect, trust, or revoke the workspace verification
 * profile declaration ({@code .talos/profiles.yaml}).
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

    public ProfilesCommand(Path workspace) {
        this(workspace, new WorkspaceProfileTrustStore());
    }

    /** Test seam: an explicit trust store keeps pins out of the real home. */
    ProfilesCommand(Path workspace, WorkspaceProfileTrustStore trustStore) {
        this.workspace = workspace;
        this.trustStore = trustStore;
    }

    @Override
    public CommandSpec spec() {
        return new CommandSpec("profiles", List.of(),
                "/profiles [list|trust|revoke]",
                "Inspect or trust workspace verification profiles.",
                CommandGroup.SECURITY);
    }

    @Override
    public Result execute(String args, Context ctx) {
        String sub = args == null || args.isBlank() ? "list" : args.strip().toLowerCase();
        WorkspaceCommandProfilesLoader.Loaded loaded =
                WorkspaceCommandProfilesLoader.load(workspace);
        WorkspaceProfileTrustStore.TrustState state = trustStore.state(workspace, loaded);
        return switch (sub) {
            case "list" -> new Result.Info(renderList(loaded, state));
            case "trust" -> trust(loaded, state, ctx);
            case "revoke" -> revoke(state);
            default -> new Result.Error(
                    "Unknown subcommand: " + sub + ". Usage: /profiles [list|trust|revoke]", 200);
        };
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
        StringBuilder sb = new StringBuilder();
        sb.append("Pin the workspace verification profile declaration at its current bytes.\n");
        sb.append("    Any later change to the file returns it to untrusted.\n");
        sb.append(renderProfiles(loaded.profiles().profiles()).indent(4).stripTrailing()).append('\n');
        sb.append("    declaration sha256: ").append(loaded.declarationSha256());
        return ProtectedContentPolicy.sanitizeText(sb.toString());
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
}
