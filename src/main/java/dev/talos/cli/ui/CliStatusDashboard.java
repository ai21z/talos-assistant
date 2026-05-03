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
import java.util.Map;
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
        return new Snapshot(
                BuildInfo.version(),
                CliUtil.shortenPath(ws),
                blankDefault(mode, "auto"),
                blankDefault(model, "unknown"),
                indexState(ws),
                policyState(safeCfg),
                blankDefault(debug, "off"),
                blankDefault(next, "Type a request or /help"));
    }

    public static String render(Snapshot snapshot) {
        Snapshot s = snapshot == null
                ? new Snapshot(BuildInfo.version(), ".", "auto", "unknown",
                "unknown", "unknown", "off", "Type a request or /help")
                : snapshot;
        StringBuilder out = new StringBuilder();
        out.append("Talos ").append("v").append(s.version()).append("\n\n");
        append(out, "Workspace", s.workspace());
        append(out, "Mode", s.mode());
        append(out, "Model", s.model());
        append(out, "Index", s.index());
        append(out, "Policy", s.policy());
        append(out, "Debug", s.debug());
        append(out, "Next", s.next());
        out.append("\n");
        return out.toString();
    }

    public static String resolveModel(Config cfg) {
        return EngineRuntimeConfig.from(cfg).displayModel();
    }

    private static void append(StringBuilder out, String label, String value) {
        out.append("  ")
                .append(String.format("%-10s", label))
                .append(blankDefault(value, "unknown"))
                .append("\n");
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

    private static String policyState(Config cfg) {
        return EngineRuntimeConfig.from(cfg).policyLabel();
    }

    private static String blankDefault(String value, String fallback) {
        return Objects.toString(value, "").isBlank() ? fallback : value;
    }
}
