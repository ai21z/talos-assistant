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
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@CommandLine.Command(name="run", description="Interactive LOQ-J REPL")
public class RunCmd implements Runnable {
    @CommandLine.Option(names="--root", description="Workspace root (default: .)") Path root;
    @CommandLine.Option(names="--k", description="Top-K (default from config)") Integer kOverride;
    @CommandLine.Option(names="--bm25-only", description="Disable vectors") boolean bm25Only;

    private static final Pattern SET_MODEL = Pattern.compile("^:set\\s+model\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILE_TOKEN = Pattern.compile("([A-Za-z0-9_./\\\\-]+\\.(?:java|md|txt|yaml|yml|xml|gradle|kts|json|properties))");

    enum Mode { ASK, RAG, RAG_MEMORY, DEV, WEB, AUTO }

    @Override public void run() {
        Path ws = (root == null ? Path.of(".") : root).toAbsolutePath().normalize();
        if (!Files.isDirectory(ws)) {
            System.err.println("Not a directory: " + ws);
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

        System.out.println("Type your question. Commands: :help  :models  :set model <name>  :mode <m>  :k <int>  :debug on|off  :q");
        System.out.println();

        try {
            Terminal term = TerminalBuilder.builder().system(true).jna(true).build();
            LineReader reader = LineReaderBuilder.builder().terminal(term).build();
            String prompt = color("loqj", 36) + "@" + ws.getFileName() + color(" > ", 90);

            boolean quit = false;
            while (!quit) {
                String line;
                try { line = reader.readLine(prompt); }
                catch (EndOfFileException eof) { break; }
                if (line == null) break;
                line = line.strip();
                if (line.isBlank()) continue;

                // ---------- All commands must begin with ":" at column 0 ----------
                if (line.startsWith(":")) {
                    // ":" alone -> full command list
                    String after = line.substring(1).trim();
                    if (after.isEmpty()) { printMan(mode, debug, topK, activeModel); continue; }

                    // Parse primary command and the rest (case-insensitive)
                    String[] parts = after.split("\\s+", 2);
                    String cmd = parts[0].toLowerCase(Locale.ROOT);
                    String args = parts.length > 1 ? parts[1].trim() : "";

                    switch (cmd) {
                        case "q":
                        case "quit": {
                            quit = true;
                            break;
                        }

                        case "help": {
                            printMan(mode, debug, topK, activeModel);
                            break;
                        }

                        case "models": {
                            var m2 = OllamaModels.list(cfg);
                            System.out.println("Installed models: " + (m2.isEmpty() ? "(none found)" : String.join(", ", m2)) + "\n");
                            break;
                        }

                        case "k": {
                            if (args.isEmpty()) { printUsageK(); break; }
                            try {
                                topK = Integer.parseInt(args);
                                System.out.println("top_k = " + topK + "\n");
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
                            // Only subcommand we support is "model"
                            if (args.isEmpty() || !args.toLowerCase(Locale.ROOT).startsWith("model")) {
                                printUsageSetModel();
                                break;
                            }
                            String rest = args.substring("model".length()).trim();
                            if (rest.isEmpty()) { printUsageSetModel(); break; }
                            String name = sanitizeModelName(rest);
                            if (name.isBlank()) { printUsageSetModel(); break; }

                            var known = OllamaModels.list(cfg);
                            if (!known.isEmpty() && !known.contains(name)) {
                                System.out.println("Model not found: " + name + "\n");
                                System.out.println("Tip: run :models, or `ollama list`, or `ollama pull " + name + "`.\n");
                                break;
                            }
                            activeModel = name;
                            llm.setModel(name);
                            // Reprint status after explicit model change
                            printStatus("Current configuration:", mode, activeModel, ws, vectorsEnabled(cfg), (mode==Mode.RAG_MEMORY), webOn, topK, (mode==Mode.RAG || mode==Mode.RAG_MEMORY || mode==Mode.WEB));
                            break;
                        }

                        case "mode": {
                            if (args.isEmpty()) { printUsageMode(mode); break; }
                            String m = args.toLowerCase(Locale.ROOT).replaceAll("\\bon\\b|\\boff\\b", "").trim();
                            Mode newMode = switch (m) {
                                case "ask" -> Mode.ASK;
                                case "rag" -> Mode.RAG;
                                case "rag+memory", "rag-memory", "cag" -> Mode.RAG_MEMORY;
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
                            break;
                        }

                        default: {
                            System.out.println("Unknown command: :" + cmd + "\n");
                            printMan(mode, debug, topK, activeModel);
                            break;
                        }
                    }
                    // We handled a command; go to next prompt
                    continue;
                }

                // ---------- Not a command: route to REPL logic ----------
                // Router (Auto) → pick a mode for this turn, but do not change global mode
                Mode route = mode;
                if (mode == Mode.AUTO) {
                    route = routeFor(line);
                    System.out.println("→ Routed to " + route + " (reason: " + routeReason(line, route) + ")\n");
                }

                // Local meta answers
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.equals("workspace") || lower.equals("workspace?") ||
                        lower.contains("working directory") || lower.equals("cwd") || lower.equals("cwd?")) {
                    System.out.println(ws + "\n");
                    continue;
                }

                // DEV mode handlers (short-circuit before RAG)
                if (route == Mode.DEV && isOpenIntent(lower)) {
                    Path target = resolveFirstPathToken(ws, line);
                    if (target != null) showFile(target, 8000, 200);
                    else System.out.println("File not found.\n");
                    continue;
                }
                if (route == Mode.DEV && isListIntent(lower)) {
                    Path dir = resolveFirstPathToken(ws, line);
                    if (dir == null) dir = ws;
                    listDir(dir, 200);
                    continue;
                }
                if (route == Mode.DEV && (lower.equals("what files can you see") || lower.equals("what files can you see?"))) {
                    listDir(ws, 200);
                    continue;
                }

                // Hint if wrong mode for file ops
                if (mode == Mode.ASK && (isOpenIntent(lower) || isListIntent(lower))) {
                    System.out.println("Tip: you are in Ask mode. Use :mode dev for local file operations.\n");
                }

                if (route == Mode.WEB) {
                    Map<String,Object> net = CfgUtil.map(cfg.data.get("net"));
                    boolean enabled = Boolean.TRUE.equals(net.getOrDefault("enabled", false));
                    if (!enabled) {
                        System.out.println("Web access is disabled by config (net.enabled=false).");
                        System.out.println("Enable it in src/main/resources/config/default-config.yaml and restart,");
                        System.out.println("or use :mode rag for local-only answers.\n");
                        continue;
                    }
                    // TODO: implement web lookup once net is enabled
                }

                // Build snippets (pinned first)
                List<Map<String,String>> pinned = pinFiles(ws, line, 3, 1600);
                var prepared = svc.prepare(ws, line, topK);
                List<Map<String,String>> snippets = new ArrayList<>(pinned.size() + prepared.snippetMaps().size());

                // pack with pinned-first policy
                List<SnippetBuilder.Snippet> pinnedSnips = new ArrayList<>();
                for (var p : pinned) pinnedSnips.add(new SnippetBuilder.Snippet(p.get("path"), p.get("text")));
                List<SnippetBuilder.Snippet> regSnips = new ArrayList<>();
                for (var p : prepared.snippetMaps()) regSnips.add(new SnippetBuilder.Snippet(p.get("path"), p.get("text")));
                var finalSnips = SnippetBuilder.packWithPinned(pinnedSnips, regSnips, 3000);
                for (var s : finalSnips) snippets.add(Map.of("path", s.path(), "text", s.text()));

                if (debug) {
                    System.out.println("[DEBUG] snippets:");
                    for (var s : snippets) System.out.println(" - " + s.get("path") + " (" + s.getOrDefault("text","").length() + " chars)");
                    System.out.println();
                }

                // Choose system prompt
                String system = switch (route) {
                    case ASK -> readOrFallback("prompts/ask-system.txt", svc);
                    case RAG, RAG_MEMORY, DEV, AUTO -> readOrFallback("prompts/cli-system.txt", svc);
                    case WEB -> readOrFallback("prompts/rag-system.txt", svc);
                };

                // Ask (stream first, fallback to non-stream)
                final StringBuilder finalText = new StringBuilder();
                System.out.println();
                String answer = llm.chatStream(system, line, snippets, chunk -> {
                    System.out.print(chunk);
                    System.out.flush();
                    finalText.append(chunk);
                });
                if (answer == null || answer.isBlank()) {
                    answer = llm.chat(system, line, snippets);
                    System.out.print(answer);
                }
                System.out.println("\n");

                // Citations policy
                if (citationsOn && (!pinned.isEmpty() || !prepared.citations().isEmpty())) {
                    System.out.println("[Citations]");
                    for (var p : pinned) System.out.println(" - " + p.get("path"));
                    for (var c : prepared.citations()) System.out.println(" - " + c);
                }
                System.out.println();
            }
        } catch (Exception e) {
            System.err.println("run failed: " + e.getClass().getName() + (e.getMessage() == null ? "" : (": " + e.getMessage())));
            e.printStackTrace(System.err);
        }
    }

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

    /** Directory-aware resolver: files with extensions first; then any path-like token. */
    private static Path resolveFirstPathToken(Path ws, String line) {
        // 1) explicit file-like tokens
        Matcher m = FILE_TOKEN.matcher(line);
        if (m.find()) {
            String token = m.group(1);
            Path p = ws.resolve(token).normalize();
            if (Files.isRegularFile(p) || Files.isDirectory(p)) return p;

            // basename fallback
            String base = Path.of(token).getFileName().toString();
            try (var walk = Files.walk(ws)) {
                Optional<Path> hit = walk.filter(Files::isRegularFile)
                        .filter(fp -> fp.getFileName().toString().equalsIgnoreCase(base))
                        .findFirst();
                if (hit.isPresent()) return hit.get();
            } catch (Exception ignore) {}
        }

        // 2) any path-like token (contains slash, backslash, or dot)
        for (String raw : line.split("\\s+")) {
            String cand = raw.replaceAll("^[\"'<]+|[\"'>,.;:]+$", "");
            if (!cand.contains("/") && !cand.contains("\\") && !cand.contains(".")) continue;
            Path p = ws.resolve(cand).normalize();
            if (Files.isDirectory(p) || Files.isRegularFile(p)) return p;
        }
        return null;
    }

    private static void showFile(Path path, int maxChars, int maxLines) {
        try {
            String text = ParserUtil.smartParse(path);
            String[] lines = text.split("\\R", -1);
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (String ln : lines) {
                if (sb.length() + ln.length() + 1 > maxChars || count >= maxLines) break;
                sb.append(ln).append("\n");
                count++;
            }
            System.out.println("----- " + path + " -----");
            System.out.print(sb.toString());
            if (count < lines.length || sb.length() < text.length()) System.out.println("----- [truncated] -----");
            System.out.println();
        } catch (Exception e) {
            System.out.println("Failed to read file: " + e.getMessage() + "\n");
        }
    }

    private static void listDir(Path dir, int maxEntries) {
        try (var stream = Files.list(dir)) {
            System.out.println("Directory: " + dir);
            int[] n = {0};
            stream.sorted().forEach(p -> {
                if (n[0] >= maxEntries) return;
                System.out.println(" - " + (Files.isDirectory(p) ? "[dir] " : "      ") + p.getFileName());
                n[0]++;
            });
            if (n[0] >= maxEntries) System.out.println(" ...");
            System.out.println();
        } catch (Exception e) {
            System.out.println("Cannot list: " + e.getMessage() + "\n");
        }
    }

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
            try {
                try (var walk = Files.walk(ws)) {
                    Optional<Path> hit = walk
                            .filter(Files::isRegularFile)
                            .filter(fp -> fp.getFileName().toString().equalsIgnoreCase(base))
                            .findFirst();
                    if (hit.isPresent()) addSnippet(ws, out, hit.get(), maxChars);
                }
            } catch (Exception ignore) {}
        }
        return out;
    }

    private static void addSnippet(Path ws, List<Map<String,String>> out, Path p, int maxChars) {
        try {
            String rel = ws.relativize(p).toString().replace('\\','/');
            String text = ParserUtil.smartParse(p);
            if (text.length() > maxChars) text = text.substring(0, maxChars);
            out.add(Map.of("path", rel + "#0", "text", text));
        } catch (Exception ignore) {}
    }

    /* ===================== UI helpers: banner & status ===================== */

    private static void banner(Path ws, Config cfg, List<String> models) {
        final String BORDER = "█████████████████████████████████████████████████████████████████████████";
        final int inner = BORDER.length() - 4; // account for "█▌" + "▐█"

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
        panel.add(":k <int>              - set retrieval Top-K");
        panel.add(":debug on|off         - toggle debug snippet view");
        panel.add(":q                    - quit");
        panel.add("");
        panel.add("Modes");
        panel.add("ASK           - General Q&A. No project context. (Good for brainstorming or generic questions.)");
        panel.add("RAG           - Answers grounded in your current folder and its subfolders.");
        panel.add("                In simple terms: I search your files and use them to answer.");
        panel.add("                Tech note: hybrid BM25 + vectors over indexed chunks.");
        panel.add("RAG+MEMORY    - Same as RAG, plus a tiny session memory of your current goal");
        panel.add("                and important names (files/classes) to improve retrieval across turns.");
        panel.add("DEV           - Local workspace tools. Use: open <file>, view <file>, ls <dir>.");
        panel.add("WEB           - Reserved for safe web lookups (off by default in config).");
        panel.add("AUTO          - Lets me decide: I route to DEV/RAG/ASK based on your prompt.");
        panel.add("");
        panel.add("Installed models");
        panel.add(models == null || models.isEmpty()
                ? "(none found)  — install via `ollama pull <model>`"
                : String.join(", ", models));

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
        System.out.println("  Scope:       " + ws);
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
  :k <int>              set retrieval top-K
  :debug on|off         toggle debug snippet view
  :q                    quit
""");
    }

    private static void printMan(Mode mode, boolean debug, Integer topK, String model) {
        // “man page” style list + quick reference
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
        System.out.println("Usage: :k <int>\n");
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
            if (line.length() == 0) {
                line.append(w);
            } else if (line.length() + 1 + w.length() <= width) {
                line.append(' ').append(w);
            } else {
                out.add(line.toString());
                line.setLength(0);
                line.append(w);
            }
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    // Normalizes user-entered model names like `<qwen3:8b>`, `"qwen3:8b"`, `-qwen3:8b` → `qwen3:8b`
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

        // Keep only allowed token chars
        Matcher m = Pattern.compile("([A-Za-z0-9._:-]+)").matcher(s);
        if (m.find()) return m.group(1);
        return "";
    }
}
