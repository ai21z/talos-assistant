package dev.loqj.cli.modes;

import dev.loqj.cli.repl.Context;
import dev.loqj.cli.repl.Result;
import dev.loqj.core.CfgUtil;
import dev.loqj.core.ingest.ParserUtil;
import dev.loqj.core.rag.RagService;
import dev.loqj.core.search.SnippetBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;

/** RAG mode: pinned-first packing + citations. */
public final class RagMode extends BaseMode implements Mode {

    @Override public String name() { return "rag"; }

    @Override
    public boolean canHandle(String rawLine) {
        return rawLine != null && !rawLine.isBlank() && FILE_TOKEN.matcher(rawLine).find();
    }

    @Override
    public Optional<Result> handle(String rawLine, Path workspace, Context ctx) throws Exception {
        if (rawLine == null || rawLine.isBlank() || ctx == null || ctx.rag() == null || ctx.llm() == null)
            return Optional.empty();

        Path ws = (workspace == null ? Path.of(".") : workspace);

        // Limits
        var lim = CfgUtil.map(ctx.cfg().data.get("limits"));
        long responseMaxChars = CfgUtil.longAt(lim, "response_max_chars", 10 * 1024 * 1024L);
        long llmTimeoutMs     = CfgUtil.longAt(lim, "llm_timeout_ms", 300_000L);
        int  dirDepthMax      = CfgUtil.intAt(lim, "dir_depth_max", 10);

        // Build pinned (up to 3 files, 1600 chars each)
        List<Map<String,String>> pinned = pinFiles(ws, rawLine, 3, 1600, dirDepthMax);
        RagService.Prepared prepared = ctx.rag().prepare(ws, rawLine, null);

        // Convert to SnippetBuilder inputs
        List<SnippetBuilder.Snippet> pinnedSnips = new ArrayList<>();
        for (var p : pinned) pinnedSnips.add(new SnippetBuilder.Snippet(p.get("path"), p.get("text")));

        List<SnippetBuilder.Snippet> regSnips = new ArrayList<>();
        for (var p : prepared.snippetMaps()) regSnips.add(new SnippetBuilder.Snippet(p.get("path"), p.get("text")));

        // Pack with sanitized/truncated snippets (budget ~ 3000 chars)
        var finalSnips = SnippetBuilder.packWithPinned(pinnedSnips, regSnips, 3000);

        List<Map<String,String>> snippets = new ArrayList<>(finalSnips.size());
        for (var s : finalSnips) snippets.add(Map.of("path", s.path(), "text", s.text()));

        // System prompt (CLI system for RAG)
        String system = readOrFallback("prompts/cli-system.txt", ctx);

        StringBuilder out = new StringBuilder();
        out.append("\n");
        try {
            final String sys = system;
            final String q   = rawLine;
            final List<Map<String,String>> ctxSnips = List.copyOf(snippets);

            CompletableFuture<String> fut = CompletableFuture.supplyAsync(() -> ctx.llm().chat(sys, q, ctxSnips));
            String answer = fut.get(llmTimeoutMs, TimeUnit.MILLISECONDS);

            if (answer != null) {
                if (answer.length() > responseMaxChars) {
                    out.append(answer, 0, (int) responseMaxChars).append("\n\n[output truncated]\n");
                } else {
                    out.append(answer);
                }
            } else {
                out.append("(no answer)");
            }
        } catch (java.util.concurrent.TimeoutException te) {
            out.append("\n[Timeout: LLM response took too long]\n");
        } catch (Exception e) {
            out.append("\n[Error during LLM call]\n");
        }
        out.append("\n\n");

        // Citations
        if (!prepared.citations().isEmpty() || !pinned.isEmpty()) {
            out.append("[Citations]\n");
            for (var p : pinned) out.append(" - ").append(p.get("path")).append("\n");
            for (var c : prepared.citations()) out.append(" - ").append(c).append("\n");
            out.append("\n");
        }

        return Optional.of(new Result.Ok(out.toString()));
    }

    /* ========= helpers ========= */

    private static List<Map<String,String>> pinFiles(Path ws, String question, int maxPins, int maxChars, int maxDepth) {
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
            try (var walk = Files.walk(ws, maxDepth)) {
                Optional<Path> hit = walk
                        .filter(Files::isRegularFile)
                        .filter(x -> x.getFileName().toString().equalsIgnoreCase(base))
                        .findFirst();
                hit.ifPresent(hitPath -> addSnippet(ws, out, hitPath, maxChars));
            } catch (Exception ignore) {}
        }
        return out;
    }

    private static void addSnippet(Path ws, List<Map<String,String>> out, Path p, int maxChars) {
        try {
            String rel = relativize(ws, p);
            String text = dev.loqj.core.ingest.ParserUtil.smartParse(p);
            if (text.length() > maxChars) text = text.substring(0, maxChars);
            out.add(Map.of("path", rel + "#0", "text", text));
        } catch (Exception ignore) {}
    }

    private static String readOrFallback(String resource, Context ctx) throws Exception {
        try (var in = RagMode.class.getClassLoader().getResourceAsStream(resource)) {
            if (in != null) return new String(in.readAllBytes());
        }
        return ctx.rag().readCliSystemPromptOrDefault();
    }
}
