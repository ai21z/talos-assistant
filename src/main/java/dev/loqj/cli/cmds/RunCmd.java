package dev.loqj.cli.cmds;

import dev.loqj.core.Config;
import dev.loqj.core.CfgUtil;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@CommandLine.Command(name="run", description="Interactive LOQ-J REPL")
public class RunCmd implements Runnable {
    @CommandLine.Option(names="--root", description="Workspace root (default: .)") Path root;
    @CommandLine.Option(names="--k", description="Top-K (default from config)") Integer kOverride;
    @CommandLine.Option(names="--bm25-only", description="Disable vectors") boolean bm25Only;

    // Session state (read/written by commands via reflection in RunCmdGlue)
    enum Mode { ASK, RAG, RAG_MEMORY, DEV, WEB, AUTO }
    private Mode mode = Mode.RAG;
    private int k = 8;
    private boolean debug = false;

    // Rate limiter (simple 1s bucket)
    private long rlWindowStartMs = System.currentTimeMillis();
    private final AtomicInteger rlTokens = new AtomicInteger(10);
    private final Object rlLock = new Object();

    @Override public void run() {
        Path ws = (root == null ? Path.of(".") : root).toAbsolutePath().normalize();
        try { ws = ws.toRealPath(); } catch (Exception ignore) {}
        if (!Files.isDirectory(ws)) {
            System.err.println("Not a directory: " + maskPath(ws));
            return;
        }

        Config cfg = new Config();

        // Limits from config
        Map<String,Object> limitsMap = CfgUtil.map(cfg.data.get("limits"));
        Limits lim = new Limits(limitsMap == null ? Map.of() : limitsMap);
        rlTokens.set(lim.ratePerSec);

        // --bm25-only flag: mutate cfg copy
        if (bm25Only) {
            Map<String,Object> rag = new LinkedHashMap<>(CfgUtil.map(cfg.data.get("rag")));
            Map<String,Object> vectors = new LinkedHashMap<>(CfgUtil.map(rag.get("vectors")));
            vectors.put("enabled", Boolean.FALSE);
            rag.put("vectors", vectors);
            cfg.data.put("rag", rag);
        }

        // Glue: commands + modes
        RunCmdGlue glue = new RunCmdGlue(this, cfg, System.out);

        banner(ws, cfg);
        System.out.println("Type your question. Commands: :help  :models  :set model <name>  :mode <m>  :k <int>  :debug on|off  :status [--verbose]  :reindex  :memory clear  :q");
        System.out.println();

        try {
            Terminal term = TerminalBuilder.builder().system(true).jna(true).build();
            LineReader reader = LineReaderBuilder.builder().terminal(term).build();
            String prompt = color("loqj", 36) + "@" + shortenPath(ws) + color(" > ", 90);

            boolean quit = false;
            while (!quit) {
                String line;
                try { line = reader.readLine(prompt); }
                catch (EndOfFileException eof) { break; }
                if (line == null) break;
                line = sanitizeOutput(line).trim();
                if (line.isEmpty()) continue;

                // Rate limit
                if (!checkRateLimit(lim)) {
                    System.out.println("Too many requests. Please slow down.\n");
                    continue;
                }

                // Handle colon commands via glue registry first
                if (line.startsWith(":")) {
                    if (glue.tryHandle(line)) {
                        if (glue.shouldQuit()) { quit = true; }
                        continue;
                    }
                    // Native minimal handlers that still live here (status/mode/reindex/memory/help)
                    String after = line.substring(1).trim();
                    String[] parts = after.split("\\s+", 2);
                    String cmd = parts[0].toLowerCase(Locale.ROOT);
                    String args = parts.length > 1 ? parts[1].trim() : "";

                    switch (cmd) {
                        case "status" -> {
                            boolean verbose = args.equalsIgnoreCase("--verbose") || args.equalsIgnoreCase("-v") || args.equalsIgnoreCase("verbose");
                            printStatus("Current configuration:", cfg, mode, ws, lim);
                            if (verbose) printConfigReport(cfg);
                        }
                        case "help", "h", "man" -> printMan();
                        case "mode" -> {
                            if (args.isEmpty()) { printUsageMode(mode); printStatus("Current configuration:", cfg, mode, ws, lim); break; }
                            Mode newMode = parseMode(args);
                            if (newMode == null) { printUsageMode(mode); break; }
                            mode = newMode;
                            printStatus("Current configuration:", cfg, mode, ws, lim);
                            System.out.println();
                        }
                        case "reindex" -> {
                            try {
                                var idx = new dev.loqj.core.rag.RagService(cfg).getIndexer();
                                var summary = idx.reindex(ws);
                                System.out.println(summary == null ? "Reindexed.\n" : (summary.toString() + "\n"));
                            } catch (Exception ex) {
                                System.out.println("Reindex failed: " + sanitizeErrorMessage(ex.getMessage()) + "\n");
                            }
                        }
                        case "memory" -> {
                            if (args.equalsIgnoreCase("clear")) {
                                new dev.loqj.core.rag.RagService(cfg).clearMemory();
                                System.out.println("Memory cleared.\n");
                            } else {
                                System.out.println("Usage: :memory clear\n");
                            }
                        }
                        case "q", "quit" -> quit = true; // <-- unified arrow-style case labels
                        default -> {
                            System.out.println("Unknown command: :" + cmd + "\n");
                            printMan();
                        }
                    }
                    continue;
                }

                // Non-command prompt: route via modes with the active mode name
                if (glue.tryHandlePrompt(line, ws, mode.name().toLowerCase(Locale.ROOT))) {
                    if (glue.shouldQuit()) { quit = true; }
                    continue;
                }

                // Fallback (should rarely hit)
                System.out.println("unhandled prompt (no mode accepted): " + line + "\n");
            }

            System.out.println("Goodbye!");
        } catch (Exception e) {
            System.err.println("run failed: " + e.getClass().getName() +
                    (e.getMessage() == null ? "" : (": " + sanitizeErrorMessage(e.getMessage()))));
            if (Boolean.getBoolean("loqj.debug")) e.printStackTrace(System.err);
        }
    }

    /* -------------------- helpers -------------------- */

    private boolean checkRateLimit(Limits lim) {
        long now = System.currentTimeMillis();
        synchronized (rlLock) {
            if (now - rlWindowStartMs >= 1000) {
                rlWindowStartMs = now;
                rlTokens.set(lim.ratePerSec);
            }
            if (rlTokens.get() > 0) { rlTokens.decrementAndGet(); return true; }
            return false;
        }
    }

    private static Mode parseMode(String arg) {
        String m = arg.toLowerCase(Locale.ROOT).replaceAll("\\bon\\b|\\boff\\b", "").trim();
        return switch (m) {
            case "ask" -> Mode.ASK;
            case "rag" -> Mode.RAG;
            case "rag+memory", "ragmemory", "rag_memory", "cag" -> Mode.RAG_MEMORY;
            case "dev" -> Mode.DEV;
            case "web" -> Mode.WEB;
            case "auto" -> Mode.AUTO;
            default -> null;
        };
    }

    /* ===== Limits struct ===== */
    private static final class Limits {
        final int topKMax;
        final long responseMaxChars;
        final int dirDepthMax;
        final int fileBytesMax;
        final int fileLinesMax;
        final int dirEntriesMax;
        final Duration llmTimeout;
        final Duration fileTimeout;
        final int ratePerSec;
        Limits(Map<String,Object> m) {
            this.topKMax          = getInt(m,"top_k_max",100);
            this.responseMaxChars = getLong(m,"response_max_chars",10*1024*1024L);
            this.dirDepthMax      = getInt(m,"dir_depth_max",10);
            this.fileBytesMax     = getInt(m,"file_bytes_max",20_000);
            this.fileLinesMax     = getInt(m,"file_lines_max",500);
            this.dirEntriesMax    = getInt(m,"dir_entries_max",1000);
            this.llmTimeout       = Duration.ofMillis(getLong(m,"llm_timeout_ms",300_000));
            this.fileTimeout      = Duration.ofMillis(getLong(m,"file_timeout_ms",10_000));
            this.ratePerSec       = getInt(m,"rate_per_sec",10);
        }
        private static int getInt(Map<String,Object> m, String k, int d) {
            if (m == null) return d;
            Object v = m.get(k); if (v instanceof Number) return ((Number)v).intValue();
            try { return v==null?d:Integer.parseInt(String.valueOf(v)); } catch(Exception e){ return d; }
        }
        private static long getLong(Map<String,Object> m, String k, long d) {
            if (m == null) return d;
            Object v = m.get(k); if (v instanceof Number) return ((Number)v).longValue();
            try { return v==null?d:Long.parseLong(String.valueOf(v)); } catch(Exception e){ return d; }
        }
    }

    /* ===== UI ===== */

    private static void banner(Path ws, Config cfg) {
        final String BORDER = "█████████████████████████████████████████████████████████████████████████";
        final int inner = BORDER.length() - 4;

        String[] logo = new String[] {
                "                                                                     ",
                " ██╗      ██████╗  ██████╗      ██╗               ██████╗██╗     ██╗ ",
                " ██║     ██╔═══██╗██╔═══██╗     ██║              ██╔════╝██║     ██║ ",
                " ██║     ██║   ██║██║   ██║     ██║    █████╗    ██║     ██║     ██║ ",
                " ██║     ██║   ██║██║▄▄ ██║██   ██║    ╚════╝    ██║     ██║     ██║ ",
                " ███████╗╚██████╔╝╚██████╔╝╚█████╔╝              ╚██████╗███████╗██║ ",
                " ╚══════╝ ╚═════╝  ╚══▀▀═╝  ╚════╝                ╚═════╝╚══════╝╚═╝ ",
                "                                                                     "
        };

        System.out.println(BORDER);
        for (String ln : logo) printBoxLine(ln, inner);
        printBoxLine("", inner);
        printBoxLine("Quickstart", inner);
        printBoxLine("Use :mode rag for project-aware answers. Ask something like:", inner);
        printBoxLine("  \"How does Indexer build the Lucene store?\"", inner);
        System.out.println(BORDER);
        System.out.println();
    }

    private static void printStatus(String title, Config cfg, Mode mode, Path ws, Limits lim) {
        var report = cfg.getReport();
        System.out.println(title);
        System.out.println("  Mode:        " + mode);
        System.out.println("  Scope:       " + shortenPath(ws));
        System.out.println("  Limits:");
        System.out.println("    top_k_max=" + lim.topKMax + ", response_max_chars=" + lim.responseMaxChars);
        System.out.println("    dir_depth_max=" + lim.dirDepthMax + ", dir_entries_max=" + lim.dirEntriesMax);
        System.out.println("    file_bytes_max=" + lim.fileBytesMax + ", file_lines_max=" + lim.fileLinesMax);
        System.out.println("    llm_timeout=" + lim.llmTimeout.toSeconds() + "s, file_timeout=" + lim.fileTimeout.toSeconds() + "s, rate_per_sec=" + lim.ratePerSec);
        System.out.println("  Config:");
        System.out.println("    loadedFrom=" + report.loadedFrom + ", strict=" + report.strictMode + ", defaults=" + report.defaultedKeys.size() + "  (use :status --verbose)");
        System.out.println();
    }

    private static void printMan() {
        System.out.println("""
Commands:
  :help                 show this help
  :models               list installed models
  :set model <name>     switch active model
  :mode ask|rag|rag+memory|dev|web|auto
  :k <int>              set retrieval top-K (max from config)
  :debug on|off         toggle debug snippet view
  :status [--verbose]   show current configuration (with limits)
  :reindex              rebuild local index
  :memory clear         clear session memory (RAG+MEMORY)
  :q                    quit
""");
    }

    private static void printUsageMode(Mode current) {
        System.out.println("Usage: :mode ask|rag|rag+memory|dev|web|auto");
        System.out.println("Current: " + current + "\n");
    }

    private static String color(String s, int code) { return "\u001B[" + code + "m" + s + "\u001B[0m"; }

    private static void printBoxLine(String content, int inner) {
        String c = content == null ? "" : content;
        if (c.length() > inner) c = c.substring(0, inner);
        int pad = inner - c.length();
        System.out.println("█▌ " + c + " ".repeat(pad) + " ▐█");
    }

    private static String maskPath(Path path) { return path.getFileName().toString(); }
    private static String shortenPath(Path path) {
        String home = System.getProperty("user.home");
        String pathStr = path.toString();
        if (home != null && !home.isBlank() && pathStr.startsWith(home)) {
            return "~" + pathStr.substring(home.length()).replace('\\', '/');
        }
        return path.getFileName().toString();
    }
    private static String sanitizeOutput(String text) {
        if (text == null) return "";
        return text.replaceAll("\u001B\\[[;\\d]*m", "")
                .replaceAll("[\u0000-\u0008\u000E-\u001F\u007F]", "");
    }
    private static String sanitizeErrorMessage(String message) {
        if (message == null) return "(no details)";
        return message.replaceAll("([A-Za-z]:)?[\\\\/][^\\\\/]+(?:[\\\\/][^\\\\/]+)*", "[path]")
                .replaceAll("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b", "[ip]");
    }

    /* ===== Config report (verbose) ===== */
    private static void printConfigReport(Config cfg) {
        var r = cfg.getReport();
        System.out.println("Config Report");
        System.out.println("  loadedFrom : " + r.loadedFrom);
        System.out.println("  strict     : " + r.strictMode);
        System.out.println("  defaults   : " + (r.defaultedKeys.isEmpty() ? "(none)" : r.defaultedKeys.size()));
        if (!r.defaultedKeys.isEmpty()) {
            System.out.println("  defaulted keys:");
            for (String k : r.defaultedKeys) System.out.println("    - " + k);
        }
        System.out.println();
    }
}
