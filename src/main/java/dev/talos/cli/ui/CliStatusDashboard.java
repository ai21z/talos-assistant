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
            String next
    ) {}

    public static Snapshot snapshot(
            Path workspace,
            Config cfg,
            String mode,
            String model,
            String debug,
            String next) {
        Config safeCfg = cfg == null ? new Config() : cfg;
        Path ws = workspace == null ? Path.of(".") : workspace.toAbsolutePath().normalize();
        EngineRuntimeConfig runtime = EngineRuntimeConfig.from(safeCfg);
        return new Snapshot(
                BuildInfo.version(),
                CliUtil.shortenPath(ws),
                blankDefault(mode, "auto"),
                blankDefault(model, "unknown"),
                engineState(runtime),
                indexState(ws),
                trustPolicy(mode),
                blankDefault(debug, "off"),
                blankDefault(next, "Type a request or /help"));
    }

    public static String render(Snapshot snapshot) {
        return render(snapshot, TerminalCapabilities.detectDefault(), StartupBannerRenderer.DEFAULT_WIDTH);
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
