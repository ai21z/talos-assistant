package dev.loqj.cli.ui;

import dev.loqj.cli.CliUtil;
import dev.loqj.core.CfgUtil;
import dev.loqj.core.Config;
import dev.loqj.core.IndexPathResolver;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Renders the Loqs startup banner with gradient logo, live context info,
 * and a concise help hint.
 */
public final class LoqsBanner {

    private static final String VERSION = "0.9.0-beta";

    private LoqsBanner() {}

    // ── Logo segments: 4 letters × 5 lines, each part exactly 9 chars wide ──

    private static final String[][] LOGO = {
        //  L             O              Q              S
        {"██       ", " █████   ", " █████   ", " █████  "},   // 0
        {"██       ", "██   ██  ", "██   ██  ", "██      "},   // 1
        {"██       ", "██   ██  ", "██   ██  ", " █████  "},   // 2
        {"██       ", "██   ██  ", "██  ▄██  ", "     ██ "},   // 3
        {"███████  ", " █████   ", " ████▀   ", " █████  "},   // 4
    };

    /** Brand gradient: purple → violet → blue → orange. */
    private static final String[] LETTER_COLORS = {
        AnsiColor.PURPLE,   // L
        AnsiColor.VIOLET,   // O
        AnsiColor.BLUE,     // Q
        AnsiColor.ORANGE,   // S
    };

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Prints the full startup banner including logo, context info, and help hint.
     */
    public static void print(Path workspace, Config cfg, String activeMode, PrintStream out) {
        out.println();
        printLogo(out);
        printTagline(out);
        printSeparator(out);
        printContextInfo(workspace, cfg, activeMode, out);
        printHint(out);
    }

    /**
     * Prints a compact one-liner for --no-logo mode.
     */
    public static void printCompact(Path workspace, Config cfg, String activeMode, PrintStream out) {
        String model = resolveModel(cfg);
        String ws = CliUtil.shortenPath(workspace);
        out.println("  " + AnsiColor.brand("Loqs") + " " + AnsiColor.dim("v" + VERSION)
                + AnsiColor.grey(" · ") + model
                + AnsiColor.grey(" · ") + ws
                + AnsiColor.grey(" [") + AnsiColor.blue(activeMode) + AnsiColor.grey("]"));
        out.println();
    }

    // ── Logo rendering ────────────────────────────────────────────────────

    private static void printLogo(PrintStream out) {
        String reset = AnsiColor.RESET;

        for (int line = 0; line < LOGO.length; line++) {
            StringBuilder sb = new StringBuilder("  ");  // left indent
            for (int letter = 0; letter < 4; letter++) {
                sb.append(LETTER_COLORS[letter])
                  .append(LOGO[line][letter])
                  .append(reset);
            }
            out.println(sb);
        }
    }

    // ── Tagline + separator ───────────────────────────────────────────────

    private static void printTagline(PrintStream out) {
        out.println();
        out.println("  " + AnsiColor.brand("Loqs")
                + AnsiColor.grey(" · Local Knowledge Engine · ")
                + AnsiColor.dim("v" + VERSION));
    }

    private static void printSeparator(PrintStream out) {
        out.println("  " + AnsiColor.dim("─".repeat(52)));
    }

    // ── Context info ──────────────────────────────────────────────────────

    private static void printContextInfo(Path workspace, Config cfg, String activeMode, PrintStream out) {
        String model = resolveModel(cfg);
        String embed = resolveEmbed(cfg);
        boolean vectorsOn = vectorsEnabled(cfg);
        String wsDisplay = CliUtil.shortenPath(workspace);
        int chunks = getChunkCount(workspace);

        out.println();
        printInfoLine(out, "Model", model);

        String embedVal = embed;
        if (!vectorsOn) embedVal += AnsiColor.yellow(" (vectors off)");
        printInfoLine(out, "Embed", embedVal);

        String wsVal = wsDisplay;
        if (chunks > 0) {
            wsVal += AnsiColor.grey(" · ") + AnsiColor.green(chunks + " chunks");
        } else if (chunks == 0) {
            wsVal += AnsiColor.grey(" · ") + AnsiColor.yellow("not indexed");
        } else {
            wsVal += AnsiColor.grey(" · ") + AnsiColor.dim("no index");
        }
        printInfoLine(out, "Workspace", wsVal);
        printInfoLine(out, "Mode", AnsiColor.blue(activeMode));
    }

    private static void printInfoLine(PrintStream out, String label, String value) {
        out.println("  " + AnsiColor.grey(String.format("%-10s", label)) + value);
    }

    // ── Help hint ─────────────────────────────────────────────────────────

    private static void printHint(PrintStream out) {
        out.println();
        out.println("  " + AnsiColor.grey("Type a question or ")
                + AnsiColor.blue(":help")
                + AnsiColor.grey(" for commands"));
        out.println();
    }

    // ── Config readers ────────────────────────────────────────────────────

    static String resolveModel(Config cfg) {
        // Match LlmClient priority: env var > config
        String env = System.getenv("LOQJ_OLLAMA_MODEL");
        if (env != null && !env.isBlank()) return env;

        Map<String, Object> oll = CfgUtil.map(cfg.data.get("ollama"));
        return oll == null ? "unknown" : String.valueOf(oll.getOrDefault("model", "unknown"));
    }

    private static String resolveEmbed(Config cfg) {
        Map<String, Object> oll = CfgUtil.map(cfg.data.get("ollama"));
        return oll == null ? "bge-m3" : String.valueOf(oll.getOrDefault("embed", "bge-m3"));
    }

    private static boolean vectorsEnabled(Config cfg) {
        Map<String, Object> rag = CfgUtil.map(cfg.data.get("rag"));
        if (rag == null) return true;
        Object v = rag.get("vectors");
        if (v instanceof Map<?, ?> vm) {
            Object en = vm.get("enabled");
            if (en instanceof Boolean b) return b;
        }
        return true;
    }

    private static int getChunkCount(Path workspace) {
        try {
            Path indexDir = IndexPathResolver.getIndexDirectory(workspace);
            if (!Files.exists(indexDir)) return -1;
            try (var dir = FSDirectory.open(indexDir);
                 var reader = DirectoryReader.open(dir)) {
                return reader.numDocs();
            }
        } catch (Exception e) {
            return -1;
        }
    }
}

