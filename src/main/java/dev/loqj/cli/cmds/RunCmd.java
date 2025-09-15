package dev.loqj.cli.cmds;

import dev.loqj.cli.repl.ReplRouter;
import dev.loqj.cli.repl.SessionState;
import dev.loqj.core.CfgUtil;
import dev.loqj.core.Config;
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
public class RunCmd implements Runnable, SessionState {

    @CommandLine.Option(names="--root", description="Workspace root (default: .)")
    Path root;

    @CommandLine.Option(names="--k", description="Top-K (default from config)")
    Integer kOverride;

    @CommandLine.Option(names="--bm25-only", description="Disable vectors")
    boolean bm25Only;

    // Minimal session state for commands
    private int k = 8;
    private boolean debug = false;

    // Simple 1s token bucket - FIXED VERSION
    private long rlWindowStartMs = System.currentTimeMillis();
    private int rlTokens = 10; // will be set from config
    private final Object rlLock = new Object();

    // ---- SessionState impl ----
    @Override public int getK() { return k; }
    @Override public void setK(int k) { this.k = Math.max(1, k); }
    @Override public boolean isDebug() { return debug; }
    @Override public void setDebug(boolean on) { this.debug = on; }

    @Override
    public void run() {
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
        rlTokens = lim.ratePerSec;

        // --bm25-only flag: mutate cfg copy
        if (bm25Only) {
            Map<String,Object> rag = new LinkedHashMap<>(CfgUtil.map(cfg.data.get("rag")));
            Map<String,Object> vectors = new LinkedHashMap<>(CfgUtil.map(rag.get("vectors")));
            vectors.put("enabled", Boolean.FALSE);
            rag.put("vectors", vectors);
            cfg.data.put("rag", rag);
        }

        // Router: commands + modes (workspace-aware), with *this* as SessionState
        ReplRouter router = new ReplRouter(this, cfg, System.out, ws);

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

                // Colon-commands: router handles *all* registered commands
                if (line.startsWith(":")) {
                    if (router.tryHandle(line)) {
                        if (router.shouldQuit()) { quit = true; }
                        continue;
                    }
                    // Unknown -> show minimal help
                    System.out.println("Unknown command: " + line + "\n");
                    printMan();
                    continue;
                }

                // Non-command prompt: route via modes (controller uses its own active mode)
                if (router.tryHandlePrompt(line, ws, null)) {
                    if (router.shouldQuit()) { quit = true; }
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
                rlTokens = lim.ratePerSec;
            }
            if (rlTokens > 0) { rlTokens--; return true; }
            return false;
        }
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
}
