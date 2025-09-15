package dev.loqj.cli.modes;

import dev.loqj.cli.repl.Context;
import dev.loqj.cli.repl.Result;
import dev.loqj.core.CfgUtil;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Ask mode: plain LLM chat (no RAG context). */
public final class AskMode implements Mode {
    @Override public String name() { return "ask"; }

    @Override public boolean canHandle(String rawLine) {
        return rawLine != null && !rawLine.isBlank();
    }

    // Helpers to catch exact-echo style prompts
    private static final Pattern EXACT_P =
            Pattern.compile("^\\s*Respond\\s+with\\s+exactly:\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern THINK_STRIP_P =
            Pattern.compile("^\\s*Print\\s+this\\s+without\\s+the\\s+think\\s+tags:\\s*<think>(.*?)</think>\\s*(.*)$",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Override
    public Optional<Result> handle(String rawLine, Path workspace, Context ctx) throws Exception {
        if (rawLine == null || rawLine.isBlank() || ctx == null || ctx.llm() == null) return Optional.empty();

        // Fast-path: exact echo
        Matcher m1 = EXACT_P.matcher(rawLine);
        if (m1.find()) {
            String out = m1.group(1);
            return Optional.of(new Result.Ok(out));
        }
        // Fast-path: <think>…</think> stripping + trailing text preserve
        Matcher m2 = THINK_STRIP_P.matcher(rawLine);
        if (m2.find()) {
            String inner = m2.group(1);
            String tail  = m2.group(2) == null ? "" : m2.group(2);
            String out = (inner + (tail.isBlank() ? "" : " " + tail)).trim();
            return Optional.of(new Result.Ok(out));
        }

        // Limits
        var lim = CfgUtil.map(ctx.cfg().data.get("limits"));
        long responseMaxChars = CfgUtil.longAt(lim, "response_max_chars", 10 * 1024 * 1024L);
        long llmTimeoutMs     = CfgUtil.longAt(lim, "llm_timeout_ms", 300_000L);

        // System prompt for Ask
        String system = readResourceOrDefault("prompts/ask-system.txt");

        StringBuilder out = new StringBuilder();
        out.append("\n");
        try {
            final String sys = system;
            final String q   = rawLine;

            CompletableFuture<String> fut = CompletableFuture.supplyAsync(() -> ctx.llm().chat(sys, q, java.util.List.of()));
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

        return Optional.of(new Result.Ok(out.toString()));
    }

    private static String readResourceOrDefault(String resource) throws Exception {
        try (var in = AskMode.class.getClassLoader().getResourceAsStream(resource)) {
            if (in != null) return new String(in.readAllBytes());
        }
        // minimal default
        return "You are a concise assistant. Answer clearly.\n";
    }
}
