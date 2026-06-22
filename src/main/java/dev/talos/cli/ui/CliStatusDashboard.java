package dev.talos.cli.ui;

import dev.talos.cli.CliUtil;
import dev.talos.core.CfgUtil;
import dev.talos.core.Config;
import dev.talos.core.EngineRuntimeConfig;
import dev.talos.core.IndexPathResolver;
import dev.talos.core.util.BuildInfo;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Compact startup/status dashboard for normal CLI output.
 */
public final class CliStatusDashboard {
    private CliStatusDashboard() {}

    public record Snapshot(
            String version,
            String workspace,
            String mode,
            String model,
            String engine,
            String index,
            String policy,
            String debug,
            String next,
            String verify
    ) {
        /** Pre-T791 shape: no verify row (renders byte-identically when blank). */
        public Snapshot(String version, String workspace, String mode, String model,
                        String engine, String index, String policy, String debug,
                        String next) {
            this(version, workspace, mode, model, engine, index, policy, debug, next, "");
        }
    }

    public static Snapshot snapshot(
            Path workspace,
            Config cfg,
            String mode,
            String model,
            String debug,
            String next) {
        return snapshot(workspace, cfg, mode, model, debug, next, null);
    }

    public static Snapshot snapshot(
            Path workspace,
            Config cfg,
            String mode,
            String model,
            String debug,
            String next,
            EngineRuntimeConfig runtimeOverride) {
        Config safeCfg = cfg == null ? new Config() : cfg;
        Path ws = workspace == null ? Path.of(".") : workspace.toAbsolutePath().normalize();
        EngineRuntimeConfig runtime = runtimeOverride == null
                ? EngineRuntimeConfig.from(safeCfg)
                : runtimeOverride;
        return new Snapshot(
                BuildInfo.version(),
                CliUtil.shortenPath(ws),
                blankDefault(mode, "auto"),
                blankDefault(model, "unknown"),
                engineState(runtime),
                indexState(ws),
                trustPolicy(mode),
                blankDefault(debug, "off"),
                blankDefault(next, "Type a request or /help"),
                verifyState(ws));
    }

    /** T791: the workspace verification-profile declaration state, one line. */
    static String verifyState(Path workspace) {
        return verifyState(workspace, null);
    }

    /** Test seam: an explicit trust store keeps reads out of the real home. */
    static String verifyState(Path workspace,
                              dev.talos.runtime.command.WorkspaceProfileTrustStore trustStore) {
        try {
            var loaded = dev.talos.runtime.command.WorkspaceCommandProfilesLoader.load(workspace);
            var store = trustStore != null
                    ? trustStore
                    : new dev.talos.runtime.command.WorkspaceProfileTrustStore();
            var state = store.state(workspace, loaded);
            int count = loaded.profiles().profiles().size();
            return switch (state) {
                case NONE_DECLARED -> "none declared";
                case INVALID -> fitReason("invalid: " + loaded.profiles().rejectionReason());
                case UNTRUSTED_NEW, UNTRUSTED_CHANGED ->
                        count + " profile(s) (untrusted - run /profiles trust)";
                case TRUSTED -> count + " profile(s) (trusted)";
            };
        } catch (Exception e) {
            return "unavailable";
        }
    }

    private static String fitReason(String text) {
        return text.length() <= 72 ? text : text.substring(0, 69) + "...";
    }

    public static String render(Snapshot snapshot) {
        return render(snapshot, TerminalCapabilities.detectDefault(), StartupBannerRenderer.DEFAULT_WIDTH);
    }

    /**
     * Renders at the live terminal width (T773). COLUMNS only refines a
     * terminal that cannot report its width; terminal-less callers keep the
     * fixed default so redirected output stays byte-identical.
     */
    public static String render(Snapshot snapshot, java.util.function.IntSupplier terminalWidth) {
        return render(snapshot, TerminalCapabilities.detectDefault(),
                TerminalWidths.resolve(
                        terminalWidth,
                        terminalWidth != null ? System.getenv() : java.util.Map.of(),
                        StartupBannerRenderer.DEFAULT_WIDTH));
    }

    public static String render(Snapshot snapshot, TerminalCapabilities capabilities, int width) {
        return StartupBannerRenderer.render(
                snapshot,
                capabilities,
                width,
                StartupBannerRenderer.Variant.STATUS_NO_ICON);
    }

    public static String resolveModel(Config cfg) {
        return EngineRuntimeConfig.from(cfg).displayModel();
    }

    private static String indexState(Path workspace) {
        try {
            Path indexDir = IndexPathResolver.getIndexDirectory(workspace);
            if (!Files.exists(indexDir)) return "not indexed";
            try (var dir = FSDirectory.open(indexDir);
                 var reader = DirectoryReader.open(dir)) {
                int docs = reader.numDocs();
                if (docs > 0) return "ready (" + docs + " chunks)";
                return "empty";
            }
        } catch (Exception e) {
            return "unavailable";
        }
    }

    private static String engineState(EngineRuntimeConfig runtime) {
        String backend = runtime == null ? "unknown" : runtime.backend();
        if ("llama_cpp".equals(backend)) return "llama.cpp (managed)";
        if ("ollama".equals(backend)) return "ollama";
        return blankDefault(backend, "unknown");
    }

    private static String trustPolicy(String mode) {
        String normalized = Objects.toString(mode, "").trim().toLowerCase(java.util.Locale.ROOT);
        if ("dev".equals(normalized)) return "writes require approval";
        return "ask before mutation";
    }

    private static String blankDefault(String value, String fallback) {
        return Objects.toString(value, "").isBlank() ? fallback : value;
    }
}
