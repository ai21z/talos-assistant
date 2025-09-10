package dev.loqj.cli.cmds;

import dev.loqj.core.Config;
import dev.loqj.core.CfgUtil;
import dev.loqj.core.ingest.ParserUtil;
import dev.loqj.core.llm.LlmClient;
import dev.loqj.core.llm.OllamaModels;
import dev.loqj.core.rag.RagService;
import dev.loqj.core.search.SnippetBuilder;
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
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@CommandLine.Command(name="run", description="Interactive LOQ-J REPL")
public class RunCmd implements Runnable {
    @CommandLine.Option(names="--root", description="Workspace root (default: .)") Path root;
    @CommandLine.Option(names="--k", description="Top-K (default from config)") Integer kOverride;
    @CommandLine.Option(names="--bm25-only", description="Disable vectors") boolean bm25Only;

    private static final Pattern SET_MODEL = Pattern.compile("^:set\\s+model\\s+(.+)$", Pattern.CASE_INSENSITIVE);

    // Heuristic for file-like mentions in user questions (pinning, routing)
    private static final Pattern FILE_TOKEN = Pattern.compile("([A-Za-z0-9_./\\\\-]+\\.(?:java|md|txt|yaml|yml|xml|gradle|kts|json|properties))");

    // Generic "first arg" extractor for commands like: open "foo bar.txt" | ls ./src | view `weird name.md`
    private static final Pattern FIRST_PATH_PATTERN = Pattern.compile(
            "^[^\\s:]+\\s+(?:\"([^\"]+)\"|'([^']+)'|`([^`]+)`|(\\S+))"
    );

    /* ===================== Security/limits ===================== */
    private static final int MAX_TOP_K = 100;
    private static final int MAX_DIR_DEPTH = 10;
    private static final long MAX_RESPONSE_SIZE = 10 * 1024 * 1024; // 10MB chars
    private static final int MAX_FILE_BYTES = 20_000;
    private static final int MAX_FILE_LINES = 500;
    private static final int MAX_DIR_ENTRIES = 1000;
    private static final int MAX_MODELS_DISPLAY = 200;
    private static final Duration FILE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration LLM_TIMEOUT = Duration.ofMinutes(5);
    private static final int MAX_COMMANDS_PER_SECOND = 10;

    // Simple 1s window token bucket for rate limiting
    private long rlWindowStartMs = System.currentTimeMillis();
    private int rlTokens = MAX_COMMANDS_PER_SECOND;
    private final Object rlLock = new Object();

    enum Mode { ASK, RAG, RAG_MEMORY, DEV, WEB, AUTO }

    @Override public void run() {
        Path ws = (root == null ? Path.of(".") : root).toAbsolutePath().normalize();
        try { ws = ws.toRealPath(); } catch (Exception ignore) {}

        if (!Files.isDirectory(ws)) {
            System.err.println("Not a directory: " + maskPath(ws));
            return;
        }

        Config cfg = new Config();

        // --bm25-only: write into MUTABLE copies, then put back into cfg.data
        if (bm25Only) {
            Map<String,Object> rag = new LinkedHashMap<>(CfgUtil.map(cfg.data.get("rag")));
            Map<String,Object> vectors = new LinkedHashMap<>(CfgUtil.map(rag.get("vectors")));
            vectors.put("enabled", Boolean.FALSE);
            rag.put("vectors", vectors);
            cfg.data.put("rag", rag);
        }

        var svc = new RagService(cfg);
        var llm = new LlmClient(cfg); // model switchable

        // Session state
        Mode mode = Mode.RAG;
        boolean memoryOn = false;
        boolean citationsOn = true;
        boolean webOn = false; // explicit gate for later web work
        boolean debug = false;
        Integer topK = kOverride;
        String activeModel = llm.getModel();

        // Installed models for banner panel
        var models = OllamaModels.list(cfg);

        banner(ws, cfg, models);
        printStatus("Current configuration:", mode, activeModel, ws, vectorsEnabled(cfg), memoryOn, webOn, topK, citationsOn);

        System.out.println("Type your question. Commands: :help  :models  :set model <name>  :mode <m>  :k <int>  :debug on|off  :status  :q");
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

                // Rate limiting
                if (!checkRateLimit()) {
                    System.out.println("Too many requests. Please slow down.\n");
                    continue;
                }

                // ---------- Strict colon-command gating ----------
                if (line.startsWith(":")) {
                    String after = line.substring(1).trim();
                    if (after.isEmpty()) { printMan(mode, debug, topK, activeModel); continue; }

                    // Missing-args fast paths
                    if (after.equalsIgnoreCase("mode"))   { printUsageMode(mode); printStatus("Current configuration:", mode, activeModel, ws, vectorsEnabled(cfg), (mode==Mode.RAG_MEMORY), webOn, topK, (mode==Mode.RAG||mode==Mode.RAG_MEMORY||mode==Mode.WEB)); continue; }
                    if (after.equalsIgnoreCase("k"))      { printUsageK(); continue; }
                    if (after.equalsIgnoreCase("debug"))  { printUsageDebug(debug); continue; }
                    if (after.matches("(?i)^set\\s+model\\s*$")) { printUsageSetModel(); continue; }

                    // Parse command + args
                    String[] parts = after.split("\\s+", 2);
                    String cmd = parts[0].toLowerCase(Locale.ROOT);
                    String args = parts.length > 1 ? parts[1].trim() : "";

                    switch (cmd) {
                        case "q":
                        case "quit": {
                            quit = true;
                            break;
                        }
                        case "help":
                        case "h":
                        case "man": {
                            printMan(mode, debug, topK, activeModel);
                            break;
                        }
                        case "status": {
                            printStatus("Current configuration:", mode, activeModel, ws, vectorsEnabled(cfg), (mode==Mode.RAG_MEMORY), webOn, topK, (mode==Mode.RAG || mode==Mode.RAG_MEMORY || mode==Mode.WEB));
                            break;
                        }
                        case "models": {
                            var m2 = OllamaModels.list(cfg);
                            if (m2.isEmpty()) {
                                System.out.println("Installed models: (none found)\n");
                            } else {
                                int shown = Math.min(m2.size(), MAX_MODELS_DISPLAY);
                                System.out.println("Installed models (" + shown + (m2.size() > shown ? " of " + m2.size() : "") + "):");
                                System.out.println(String.join(", ", m2.subList(0, shown)));
                                if (m2.size() > shown) System.out.println("… truncated; run `ollama list` to see all.");
                                System.out.println();
                            }
                            break;
                        }
                        case "k": {
                            if (args.isEmpty()) { printUsageK(); break; }
                            try {
                                int v = Integer.parseInt(args);
                                v = Math.max(1, Math.min(MAX_TOP_K, v));
                                topK = v;
                                System.out.println("top_k = " + topK + " (max " + MAX_TOP_K + ")\n");
                            } catch (NumberFormatException nfe) {
                                printUsageK();
                            }
                            break;
                        }
                        case "debug": {
                            if (args.isEmpty()) { printUsageDebug(debug); break; }
                            if (args.equalsIgnoreCase("on")) { debug = true; System.out.println("debug = ON\n"); }
                            else if (args.equalsIgnoreCase("off")) { debug = false; System.out.println("debug = OFF\n"); }
                            else printUsageDebug(debug);
                            break;
                        }
                        case "set": {
                            if (args.isEmpty() || !args.toLowerCase(Locale.ROOT).startsWith("model")) { printUsageSetModel(); break; }
                            String rest = args.substring("model".length()).trim();
                            if (rest.isEmpty()) { printUsageSetModel(); break; }
                            String name = sanitizeModelName(rest);
                            if (!isValidModelName(name)) {
                                System.out.println("Invalid model name: " + sanitizeOutput(rest) + "\n");
                                break;
                            }
                            var known = OllamaModels.list(cfg);
                            if (!known.isEmpty() && !known.contains(name)) {
                                System.out.println("Model not found: " + name + "\n");
                                System.out.println("Tip: run :models, or `ollama list`, or `ollama pull " + name + "`.\n");
                                break;
                            }
                            llm.setModel(name);
                            activeModel = name;
                            printStatus("Current configuration:", mode, activeModel, ws, vectorsEnabled(cfg), (mode==Mode.RAG_MEMORY), webOn, topK, (mode==Mode.RAG || mode==Mode.RAG_MEMORY || mode==Mode.WEB));
                            break;
                        }
                        case "mode": {
                            if (args.isEmpty()) { printUsageMode(mode); printStatus("Current configuration:", mode, activeModel, ws, vectorsEnabled(cfg), (mode==Mode.RAG_MEMORY), webOn, topK, (mode==Mode.RAG||mode==Mode.RAG_MEMORY||mode==Mode.WEB)); break; }
                            String m = args.toLowerCase(Locale.ROOT).replaceAll("\\bon\\b|\\boff\\b", "").trim();
                            Mode newMode = switch (m) {
                                case "ask" -> Mode.ASK;
                                case "rag" -> Mode.RAG;
                                case "rag+memory", "ragmemory", "rag_memory", "cag" -> Mode.RAG_MEMORY;
                                case "dev" -> Mode.DEV;
                                case "web" -> Mode.WEB;
                                case "auto" -> Mode.AUTO;
                                default -> null;
                            };
                            if (newMode == null) { printUsageMode(mode); break; }
                            mode = newMode;
                            // Mode-driven toggles
                            citationsOn = (mode == Mode.RAG || mode == Mode.RAG_MEMORY || mode == Mode.WEB);
                            memoryOn = (mode == Mode.RAG_MEMORY);
                            // ALWAYS print status on :mode, even if unchanged
                            printStatus("Current configuration:", mode, activeModel, ws, vectorsEnabled(cfg), memoryOn, webOn, topK, citationsOn);
                            System.out.println();
                            break;
                        }
                        default: {
                            String cmdWord = after.split("\\s+")[0];
                            System.out.println("Unknown command: :" + (cmdWord.isEmpty() ? "(empty)" : cmdWord) + "\n");
                            printMan(mode, debug, topK, activeModel);
                            break;
                        }
                    }
                    continue;
                }

                // ---------- Not a command: route to REPL logic ----------
                Mode route = (mode == Mode.AUTO ? routeFor(line) : mode);

                // DEV mode handlers
                String lower = line.toLowerCase(Locale.ROOT);
                if (route == Mode.DEV && isOpenIntent(lower)) {
                    Path target = resolveFirstPathToken(ws, line);
                    if (target != null) showFile(ws, target, MAX_FILE_BYTES, MAX_FILE_LINES);
                    else System.out.println("File not found or invalid path.\n");
                    continue;
                }
                if (route == Mode.DEV && isListIntent(lower)) {
                    Path dir = resolveFirstPathToken(ws, line);
                    if (dir == null) dir = ws;
                    listDir(ws, dir, MAX_DIR_ENTRIES);
                    continue;
                }
                if (mode == Mode.ASK && (isOpenIntent(lower) || isListIntent(lower))) {
                    System.out.println("Tip: you are in Ask mode. Use :mode dev for local file operations.\n");
                }

                // WEB gating (no external network in this phase)
                if (route == Mode.WEB) {
                    Map<String,Object> net = CfgUtil.map(cfg.data.get("net"));
                    boolean enabled = Boolean.TRUE.equals(net.getOrDefault("enabled", false));
                    if (!enabled) {
                        System.out.println("Web access is disabled by config (net.enabled=false).");
                        System.out.println("Enable it in src/main/resources/config/default-config.yaml and restart,");
                        System.out.println("or use :mode rag for local-only answers.\n");
                        continue;
                    }
                    System.out.println("Web mode is reserved. No external network calls are performed in this build.\n");
                    continue;
                }

                // Build RAG snippets (pinned-first packing)
                List<Map<String,String>> snippets = new ArrayList<>();
                if (route == Mode.RAG || route == Mode.RAG_MEMORY) {
                    List<Map<String,String>> pinned = pinFiles(ws, line, 3, 1600);
                    var prepared = svc.prepare(ws, line, topK);
                    List<SnippetBuilder.Snippet> pinnedSnips = new ArrayList<>();
                    for (var p : pinned) pinnedSnips.add(new SnippetBuilder.Snippet(p.get("path"), p.get("text")));
                    List<SnippetBuilder.Snippet> regSnips = new ArrayList<>();
                    for (var p : prepared.snippetMaps()) regSnips.add(new SnippetBuilder.Snippet(p.get("path"), p.get("text")));
                    var finalSnips = SnippetBuilder.packWithPinned(pinnedSnips, regSnips, 3000);
                    for (var s : finalSnips) snippets.add(Map.of("path", s.path(), "text", s.text()));

                    if (debug) {
                        System.out.println("[DEBUG] snippets:");
                        for (var s : snippets) {
                            String p = String.valueOf(s.get("path"));
                            int len = String.valueOf(s.getOrDefault("text", "")).length();
                            System.out.println("  - " + p + " (" + len + " chars)");
                        }
                        System.out.println();
                    }
                }

                // Choose system prompt by route
                String system = switch (route) {
                    case ASK -> readOrFallback("prompts/ask-system.txt", svc);
                    case RAG, RAG_MEMORY, DEV, AUTO -> readOrFallback("prompts/cli-system.txt", svc);
                    case WEB -> readOrFallback("prompts/rag-system.txt", svc);
                };

                // Stream with output cap; fallback to non-stream with timeout
                final StringBuilder finalText = new StringBuilder();
                final int[] used = {0};
                final boolean[] truncated = {false};
                System.out.println();

                try {
                    // Make final/effectively-final copies for the lambdas
                    final String sys = system;
                    final String q = line;
                    final List<Map<String,String>> ctx = List.copyOf(snippets); // immutable snapshot

                    CompletableFuture<String> future = CompletableFuture.supplyAsync(() ->
                            llm.chatStream(sys, q, ctx, chunk -> {
                                int remaining = (int)Math.max(0, MAX_RESPONSE_SIZE - used[0]);
                                if (remaining <= 0) {
                                    if (!truncated[0]) {
                                        System.out.print("\n\n[output truncated]\n");
                                        truncated[0] = true;
                                    }
                                    return;
                                }
                                if (chunk.length() > remaining) {
                                    System.out.print(chunk.substring(0, remaining));
                                    finalText.append(chunk, 0, remaining);
                                    used[0] += remaining;
                                    System.out.print("\n\n[output truncated]\n");
                                    truncated[0] = true;
                                } else {
                                    System.out.print(chunk);
                                    finalText.append(chunk);
                                    used[0] += chunk.length();
                                }
                                System.out.flush();
                            })
                    );

                    String answer = future.get(LLM_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

                    if ((answer == null || answer.isBlank()) && finalText.length() == 0) {
                        System.out.println("(falling back to non-streaming)");
                        CompletableFuture<String> fb = CompletableFuture.supplyAsync(() -> llm.chat(sys, q, ctx));
                        answer = fb.get(LLM_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                        if (answer != null) {
                            if (answer.length() > MAX_RESPONSE_SIZE) {
                                System.out.print(answer.substring(0, (int)MAX_RESPONSE_SIZE));
                                System.out.println("\n\n[output truncated]");
                            } else {
                                System.out.print(answer);
                            }
                        }
                    }
                } catch (TimeoutException e) {
                    System.out.println("\n[Timeout: LLM response took too long]");
                } catch (Exception e) {
                    System.out.println("\n[Error during LLM call]");
                    if (debug) System.err.println("Debug: " + e.getClass().getName() + ": " + e.getMessage());
                }
                System.out.println("\n");

                // Citations
                if (citationsOn && (route == Mode.RAG || route == Mode.RAG_MEMORY)) {
                    var prepared = svc.prepare(ws, line, topK);
                    if (!prepared.citations().isEmpty()) {
                        System.out.println("[Citations]");
                        // Show pinned first (if any were used)
                        List<Map<String,String>> pinned = pinFiles(ws, line, 3, 1600);
                        for (var p : pinned) System.out.println(" - " + p.get("path"));
                        for (var c : prepared.citations()) System.out.println(" - " + c);
                        System.out.println();
                    }
                }
            }

            System.out.println("Goodbye!");
        } catch (Exception e) {
            System.err.println("run failed: " + e.getClass().getName() +
                    (e.getMessage() == null ? "" : (": " + sanitizeErrorMessage(e.getMessage()))));
            // Stacktrace only when explicit debug JVM prop is set
            if (Boolean.getBoolean("loqj.debug")) e.printStackTrace(System.err);
        }
    }

    /* ===================== Rate limiter ===================== */
    private boolean checkRateLimit() {
        long now = System.currentTimeMillis();
        synchronized (rlLock) {
            if (now - rlWindowStartMs >= 1000) {
                rlWindowStartMs = now;
                rlTokens = MAX_COMMANDS_PER_SECOND;
            }
            if (rlTokens > 0) { rlTokens--; return true; }
            return false;
        }
    }

    /* ===================== Small helpers from earlier code ===================== */

    private static String readOrFallback(String resource, RagService svc) throws Exception {
        try (var in = RunCmd.class.getClassLoader().getResourceAsStream(resource)) {
            if (in != null) return new String(in.readAllBytes());
        }
        return svc.readCliSystemPromptOrDefault();
    }

    private static boolean vectorsEnabled(Config cfg) {
        Map<String,Object> rag = CfgUtil.map(cfg.data.get("rag"));
        return Boolean.TRUE.equals(CfgUtil.map(rag.get("vectors")).getOrDefault("enabled", true));
    }

    private static Mode routeFor(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (isOpenIntent(lower) || isListIntent(lower)) return Mode.DEV;
        if (FILE_TOKEN.matcher(line).find()) return Mode.RAG;
        return Mode.ASK;
    }
    private static String routeReason(String line, Mode route) {
        return switch (route) {
            case DEV -> "file operation intent";
            case RAG -> "workspace term/file mentioned";
            case ASK -> "general question";
            default -> "heuristics";
        };
    }

    private static boolean isOpenIntent(String lower) {
        return lower.startsWith("open ") || lower.startsWith("show ") || lower.startsWith("view ") ||
                lower.contains("can you open") || lower.contains("can you show") || lower.contains("open?");
    }
    private static boolean isListIntent(String lower) {
        return lower.startsWith("ls ") || lower.startsWith("list ") || lower.startsWith("dir ") ||
                lower.startsWith("what is inside ") || lower.contains("what is inside") ||
                lower.startsWith("what's inside ");
    }

    /* ===================== Secure path helpers (double-guard) ===================== */

    /** True iff candidate resolves inside base (symlinks honored when possible). */
    private static boolean under(Path base, Path candidate) {
        try {
            Path b = base.toRealPath(java.nio.file.LinkOption.NOFOLLOW_LINKS);
            Path c = candidate.toRealPath(java.nio.file.LinkOption.NOFOLLOW_LINKS);
            return c.startsWith(b);
        } catch (Exception e) {
            Path b = base.toAbsolutePath().normalize();
            Path c = candidate.toAbsolutePath().normalize();
            return c.startsWith(b);
        }
    }

    /** Very light binary sniffing: flags NULs or many non-printables in the prefix. */
    private static boolean isProbablyBinary(byte[] buf, int n) {
        int nonPrintable = 0;
        for (int i = 0; i < n; i++) {
            int b = buf[i] & 0xff;
            if (b == 0) return true;
            if (b < 9 || (b > 13 && b < 32)) nonPrintable++;
        }
        return nonPrintable > n / 5;
    }

    /** Extract the first path-like token after the command name and resolve it under the workspace. */
    private static Path resolveFirstPathToken(Path ws, String line) {
        if (line == null) return null;
        String s = line.trim();
        if (s.isEmpty()) return null;

        // Prefer explicit "first argument" pattern: open "foo", ls `bar`, show ./baz
        Matcher m = FIRST_PATH_PATTERN.matcher(s);
        if (m.find()) {
            String raw = m.group(1);
            if (raw == null) raw = m.group(2);
            if (raw == null) raw = m.group(3);
            if (raw == null) raw = m.group(4);
            if (raw != null && !raw.isBlank()) {
                String exp = expandTilde(raw);
                Path cand;
                try {
                    cand = Path.of(exp);
                } catch (Exception bad) {
                    System.out.println("Invalid path syntax: " + sanitizeOutput(raw));
                    return null;
                }
                if (!cand.isAbsolute()) cand = ws.resolve(cand);
                cand = cand.normalize();

                if (!under(ws, cand)) {
                    System.out.println("Refusing path outside workspace.\n");
                    return null;
                }
                return cand;
            }
        }

        // Fallback 1: explicit file-like token with known extensions (pinning-style)
        Matcher f = FILE_TOKEN.matcher(line);
        if (f.find()) {
            String token = f.group(1);
            Path cand = ws.resolve(token).normalize();
            if (Files.exists(cand) && under(ws, cand)) return cand;

            // basename search (bounded depth)
            String base = Path.of(token).getFileName().toString();
            try (var walk = Files.walk(ws, MAX_DIR_DEPTH)) {
                Optional<Path> hit = walk.filter(Files::isRegularFile)
                        .filter(fp -> fp.getFileName().toString().equalsIgnoreCase(base))
                        .findFirst();
                if (hit.isPresent()) return hit.get();
            } catch (Exception ignore) {}
        }

        // Fallback 2: any token with '/', '\' or '.' that resolves inside ws
        for (String raw : line.split("\\s+")) {
            String candToken = raw.replaceAll("^[\"'<]+|[\"'>,.;:]+$", "");
            if (!candToken.contains("/") && !candToken.contains("\\") && !candToken.contains(".")) continue;
            String exp = expandTilde(candToken);
            Path cand;
            try {
                cand = Path.of(exp);
            } catch (Exception bad) {
                continue;
            }
            if (!cand.isAbsolute()) cand = ws.resolve(cand);
            cand = cand.normalize();
            if ((Files.isDirectory(cand) || Files.isRegularFile(cand)) && under(ws, cand)) return cand;
        }
        return null;
    }

    private static String expandTilde(String raw) {
        if (raw == null) return null;
        if (raw.equals("~")) return getSecureUserHome();
        if (raw.startsWith("~" + java.io.File.separator) || raw.startsWith("~/")) {
            return getSecureUserHome() + raw.substring(1);
        }
        return raw;
    }

    private static String getSecureUserHome() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) return System.getProperty("user.dir", ".");
        return home;
    }

    /* ===================== Secured file ops (double-guard) ===================== */

    private static void showFile(Path ws, Path path, int maxBytes, int maxLines) {
        try {
            Path abs = path.toAbsolutePath().normalize();
            if (!Files.exists(abs)) {
                System.out.println("Not found: " + relativizePath(ws, path) + "\n");
                return;
            }
            if (!under(ws, abs)) {
                System.out.println("Refusing to read outside workspace.\n");
                return;
            }
            if (Files.isDirectory(abs, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                System.out.println("Path is a directory. Use :ls to list it: " + relativizePath(ws, abs) + "\n");
                return;
            }

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    long size = Files.size(abs);
                    System.out.printf("\n── file: %s (%,d bytes)%n%n", relativizePath(ws, abs), size);

                    List<String> lines = new ArrayList<>();
                    try (var reader = Files.newBufferedReader(abs)) {
                        String ln;
                        int totalBytes = 0;
                        while ((ln = reader.readLine()) != null && lines.size() < maxLines && totalBytes < maxBytes) {
                            lines.add(ln);
                            totalBytes += ln.length() + 1;
                        }
                    }
                    for (String ln : lines) System.out.println(ln);

                    if (lines.size() >= maxLines || size > maxBytes) {
                        System.out.println("\n… (truncated)\n");
                    } else {
                        System.out.println();
                    }
                } catch (Exception e) {
                    System.out.printf("Read error: %s%n", sanitizeErrorMessage(e.getMessage()));
                }
            });

            future.get(FILE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            System.out.println("File operation timed out\n");
        } catch (Exception e) {
            System.out.printf("Error: %s%n", sanitizeErrorMessage(e.getMessage()));
        }
    }

    private static void listDir(Path ws, Path dir, int maxEntries) {
        try {
            Path abs = dir.toAbsolutePath().normalize();
            if (!Files.exists(abs)) {
                System.out.println("Not found: " + relativizePath(ws, dir) + "\n");
                return;
            }
            if (!under(ws, abs)) {
                System.out.println("Refusing to list outside workspace.\n");
                return;
            }
            if (!Files.isDirectory(abs, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                System.out.println("Not a directory: " + relativizePath(ws, abs) + "\n");
                return;
            }

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    System.out.printf("\n── dir: %s%n%n", relativizePath(ws, abs));

                    List<Path> entries = new ArrayList<>();
                    try (var stream = Files.list(abs)) {
                        stream.limit(maxEntries + 1).forEach(entries::add);
                    }
                    boolean clipped = entries.size() > maxEntries;
                    if (clipped) entries = entries.subList(0, maxEntries);

                    List<Path> dirs = new ArrayList<>();
                    List<Path> files = new ArrayList<>();
                    for (Path e : entries) {
                        if (Files.isDirectory(e, java.nio.file.LinkOption.NOFOLLOW_LINKS)) dirs.add(e); else files.add(e);
                    }
                    dirs.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));
                    files.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));

                    for (Path d : dirs)  System.out.println("  [DIR]  "  + d.getFileName());
                    for (Path f : files) System.out.println("  [FILE] " + f.getFileName());

                    if (clipped) System.out.println("\n(showing first " + maxEntries + " entries)\n");
                    else System.out.println();
                } catch (Exception e) {
                    System.out.printf("List error: %s%n", sanitizeErrorMessage(e.getMessage()));
                }
            });

            future.get(FILE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            System.out.println("Directory operation timed out\n");
        } catch (Exception e) {
            System.out.printf("Error: %s%n", sanitizeErrorMessage(e.getMessage()));
        }
    }

    /* ===================== Pinning helpers (RAG) ===================== */

    private static List<Map<String,String>> pinFiles(Path ws, String question, int maxPins, int maxChars) {
        List<Map<String,String>> out = new ArrayList<>();
        Matcher m = FILE_TOKEN.matcher(question);
        Set<String> seen = new LinkedHashSet<>();
        while (m.find() && out.size() < maxPins) {
            String token = m.group(1);
            if (!seen.add(token)) continue;

            Path p = ws.resolve(token).normalize();
            if (Files.isRegularFile(p)) {
                addSnippet(ws, out, p, maxChars);
                continue;
            }
            String base = Path.of(token).getFileName().toString();
            try (var walk = Files.walk(ws, MAX_DIR_DEPTH)) {
                Optional<Path> hit = walk
                        .filter(Files::isRegularFile)
                        .filter(fp -> fp.getFileName().toString().equalsIgnoreCase(base))
                        .findFirst();
                hit.ifPresent(path -> addSnippet(ws, out, path, maxChars));
            } catch (Exception ignore) {}
        }
        return out;
    }

    private static void addSnippet(Path ws, List<Map<String,String>> out, Path p, int maxChars) {
        try {
            String rel = relativizePath(ws, p);
            String text = ParserUtil.smartParse(p);
            if (text.length() > maxChars) text = text.substring(0, maxChars);
            out.add(Map.of("path", rel + "#0", "text", text));
        } catch (Exception ignore) {}
    }

    /* ===================== Security helpers ===================== */

    private static String sanitizeModelName(String raw) {
        if (raw == null) return "";
        String s = raw.trim();

        // Strip simple wrappers
        if ((s.startsWith("<") && s.endsWith(">")) ||
                (s.startsWith("\"") && s.endsWith("\"")) ||
                (s.startsWith("'") && s.endsWith("'"))) {
            s = s.substring(1, s.length() - 1);
        }

        // Remove leading punctuation/error chars and trailing '>'
        while (!s.isEmpty() && (s.charAt(0) == '-' || s.charAt(0) == '<')) s = s.substring(1);
        while (!s.isEmpty() && (s.charAt(s.length() - 1) == '>')) s = s.substring(0, s.length() - 1);

        // Keep only allowed chars
        s = s.replaceAll("[^A-Za-z0-9._:-]", "");

        // Security checks
        if (s.contains("..") || s.contains("//") || s.contains("\\\\")) return "";

        // Length limit
        if (s.length() > 64) s = s.substring(0, 64);

        // Must start with alphanumeric
        if (s.isEmpty() || !Character.isLetterOrDigit(s.charAt(0))) return "";
        return s;
    }

    private static boolean isValidModelName(String name) {
        return name != null && !name.isBlank();
    }

    private static String maskPath(Path path) {
        return path.getFileName().toString();
    }

    private static String relativizePath(Path base, Path path) {
        try {
            return base.relativize(path).toString().replace('\\','/');
        } catch (Exception e) {
            return path.getFileName().toString();
        }
    }

    private static String shortenPath(Path path) {
        String home = getSecureUserHome();
        String pathStr = path.toString();
        if (pathStr.startsWith(home)) {
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

    /* ===================== UI helpers: banner & status ===================== */

    private static void banner(Path ws, Config cfg, List<String> models) {
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

        List<String> panel = new ArrayList<>();
        panel.add("Commands");
        panel.add(":help                 - show this help");
        panel.add(":models               - list installed models");
        panel.add(":set model <name>     - switch active model (e.g., :set model qwen3:8b)");
        panel.add(":mode ask|rag|rag+memory|dev|web|auto");
        panel.add(":k <int>              - set retrieval Top-K (max " + MAX_TOP_K + ")");
        panel.add(":debug on|off         - toggle debug snippet view");
        panel.add(":status               - show current configuration");
        panel.add(":q                    - quit");
        panel.add("");
        panel.add("Modes");
        panel.add("ASK           - General Q&A. No project context.");
        panel.add("RAG           - Answers grounded in your current folder.");
        panel.add("RAG+MEMORY    - RAG with a tiny session memory to improve retrieval across turns.");
        panel.add("DEV           - Local workspace tools. Use: open <file>, ls <dir>.");
        panel.add("WEB           - Reserved for safe web lookups (off by default).");
        panel.add("AUTO          - I route to DEV/RAG/ASK based on your prompt.");
        panel.add("");
        panel.add("Installed models");
        if (models == null || models.isEmpty()) {
            panel.add("(none found)  — install via `ollama pull <model>`");
        } else {
            int shown = Math.min(models.size(), MAX_MODELS_DISPLAY);
            panel.add(String.join(", ", models.subList(0, shown)));
            if (models.size() > shown) panel.add("… truncated; run `:models` for the first 200.");
        }

        System.out.println(BORDER);
        for (String ln : logo) printBoxLine(ln, inner);
        printBoxLine("", inner);
        printBoxLine("Quickstart", inner);
        printBoxLine("Use :mode rag for project-aware answers. Ask something like:", inner);
        printBoxLine("  \"How does Indexer build the Lucene store?\"", inner);
        printBoxLine("", inner);
        for (String ln : panel) {
            if (ln.isEmpty()) { printBoxLine("", inner); continue; }
            for (String wrap : wrap(ln, inner)) printBoxLine(wrap, inner);
        }
        System.out.println(BORDER);
        System.out.println();
    }

    private static void printStatus(String title, Mode mode, String model, Path ws, boolean vectors, boolean memory, boolean web, Integer k, boolean cites) {
        System.out.println(title);
        System.out.println("  Mode:        " + mode);
        System.out.println("  Model:       " + model);
        System.out.println("  Scope:       " + shortenPath(ws));
        System.out.println("  Vectors:     " + (vectors ? "ON" : "OFF"));
        System.out.println("  Memory:      " + (memory ? "ON" : "OFF"));
        System.out.println("  Web:         " + (web ? "ON" : "OFF"));
        System.out.println("  TopK:        " + (k == null ? "(cfg)" : k));
        System.out.println("  Citations:   " + (cites ? "ON" : "OFF"));
        System.out.println();
    }

    private static void printHelp() {
        System.out.println("""
Commands:
  :help                 show this help
  :models               list installed models
  :set model <name>     switch active model
  :mode ask|rag|rag+memory|dev|web|auto
  :k <int>              set retrieval top-K (max %d)
  :debug on|off         toggle debug snippet view
  :status               show current configuration
  :q                    quit
""".formatted(MAX_TOP_K));
    }

    private static void printMan(Mode mode, boolean debug, Integer topK, String model) {
        printHelp();
        System.out.println("Current quick refs:");
        System.out.println("  mode   : " + mode);
        System.out.println("  model  : " + model);
        System.out.println("  debug  : " + (debug ? "ON" : "OFF"));
        System.out.println("  top_k  : " + (topK == null ? "(cfg)" : topK));
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  :mode rag");
        System.out.println("  :set model qwen3:8b");
        System.out.println("  :k 6");
        System.out.println("  :debug on");
        System.out.println();
    }

    private static void printUsageMode(Mode current) {
        System.out.println("Usage: :mode ask|rag|rag+memory|dev|web|auto");
        System.out.println("Current: " + current + "\n");
    }

    private static void printUsageDebug(boolean debug) {
        System.out.println("Usage: :debug on|off");
        System.out.println("Current: " + (debug ? "ON" : "OFF") + "\n");
    }

    private static void printUsageK() {
        System.out.println("Usage: :k <int> (1-" + MAX_TOP_K + ")\n");
    }

    private static void printUsageSetModel() {
        System.out.println("Usage: :set model <name>");
        System.out.println("Example: :set model qwen3:8b\n");
    }

    private static String color(String s, int code) { return "\u001B[" + code + "m" + s + "\u001B[0m"; }

    private static void printBoxLine(String content, int inner) {
        String c = content == null ? "" : content;
        if (c.length() > inner) c = c.substring(0, inner);
        int pad = inner - c.length();
        System.out.println("█▌ " + c + " ".repeat(pad) + " ▐█");
    }

    private static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) { out.add(""); return out; }
        String[] words = text.split("\\s+");
        StringBuilder line = new StringBuilder();
        for (String w : words) {
            if (w.length() > width) {
                if (line.length() > 0) { out.add(line.toString()); line.setLength(0); }
                int i = 0;
                while (i < w.length()) {
                    out.add(w.substring(i, Math.min(i + width, w.length())));
                    i += width;
                }
                continue;
            }
            if (line.length() == 0) line.append(w);
            else if (line.length() + 1 + w.length() <= width) line.append(' ').append(w);
            else { out.add(line.toString()); line.setLength(0); line.append(w); }
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }
}
