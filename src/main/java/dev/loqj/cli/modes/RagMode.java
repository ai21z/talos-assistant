package dev.loqj.cli.modes;

import dev.loqj.cli.repl.Context;
import dev.loqj.cli.repl.Limits;
import dev.loqj.cli.repl.Result;
import dev.loqj.core.ingest.ParserUtil;
import dev.loqj.core.rag.RagService;
import dev.loqj.core.search.SnippetBuilder;
import dev.loqj.core.util.Sanitize;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** RAG mode: builds snippets (pinned-first), calls LLM once, reuses same prepare-result for citations. */
public final class RagMode implements Mode {

    @Override public String name() { return "rag"; }

    @Override public boolean canHandle(String rawLine) {
        return rawLine != null && !rawLine.isBlank();
    }

    @Override
    public Optional<Result> handle(String rawLine, Path workspace, Context ctx) throws Exception {
        String q = rawLine.trim();
        if (q.isEmpty()) return Optional.of(new Result.Info("(empty query)"));

        final Limits lim = ctx.limits();
        final int topK = Math.max(1, Math.min(lim.topKMax(), ctx.session().getK()));

        // 1) pin by file-like mentions
        var pinnedSnips = pinFiles(workspace, q, 3, 1600, lim.dirDepthMax());

        // 2) prepare once (BM25F + vectors if enabled)
        RagService.Prepared prepared = ctx.rag().prepare(workspace, q, topK);

        // 3) pack pinned-first
        List<SnippetBuilder.Snippet> reg = new ArrayList<>();
        for (var m : prepared.snippetMaps()) {
            reg.add(new SnippetBuilder.Snippet(m.get("path"), m.get("text")));
        }
        var packed = SnippetBuilder.packWithPinned(pinnedSnips, reg, 3000);

        // LLM context payload (path/text pairs)
        List<Map<String,String>> ctxMaps = new ArrayList<>(packed.size());
        for (var s : packed) ctxMaps.add(Map.of("path", s.path(), "text", s.text()));

        // 4) system prompt
        String system = readOrFallback("prompts/rag-system.txt", ctx);

        // 5) call LLM (non-stream), sanitize, then cap
        String answer = ctx.llm().chat(system, q, ctxMaps);
        answer = Sanitize.sanitizeForOutput(answer);
        if (answer.length() > lim.responseMaxChars()) {
            answer = answer.substring(0, (int) lim.responseMaxChars()) + "\n\n[output truncated]";
        }

        // 6) citations (same prepared result)
        StringBuilder out = new StringBuilder();
        out.append(answer);
        if (!prepared.citations().isEmpty() || !pinnedSnips.isEmpty()) {
            out.append("\n\n[Citations]\n");
            for (var p : pinnedSnips) out.append(" - ").append(p.path()).append("\n");
            for (String c : prepared.citations()) out.append(" - ").append(c).append("\n");
        }
        return Optional.of(new Result.Ok(out.toString()));
    }

    /* ---------------- helpers ---------------- */

    private static final Pattern FILE_TOKEN = Pattern.compile(
            "([A-Za-z0-9_./\\\\-]++\\.(?:java|md|txt|yaml|yml|xml|gradle|kts|json|properties))",
            Pattern.UNICODE_CHARACTER_CLASS
    );

    private static List<SnippetBuilder.Snippet> pinFiles(Path ws, String question, int maxPins, int maxChars, int maxDepth) {
        List<SnippetBuilder.Snippet> out = new ArrayList<>();
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

    private static void addSnippet(Path ws, List<SnippetBuilder.Snippet> out, Path p, int maxChars) {
        try {
            String rel = ws.relativize(p).toString().replace('\\','/');
            String text = ParserUtil.smartParse(p);
            if (text.length() > maxChars) text = text.substring(0, maxChars);
            out.add(new SnippetBuilder.Snippet(rel + "#0", text));
        } catch (Exception ignore) {}
    }

    private static String readOrFallback(String resource, Context ctx) throws Exception {
        try (var in = RagMode.class.getClassLoader().getResourceAsStream(resource)) {
            if (in != null) return new String(in.readAllBytes());
        }
        return ctx.rag().readCliSystemPromptOrDefault();
    }
}
