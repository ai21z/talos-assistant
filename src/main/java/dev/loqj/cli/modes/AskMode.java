package dev.loqj.cli.modes;

import dev.loqj.cli.repl.Context;
import dev.loqj.cli.repl.Result;
import dev.loqj.core.CfgUtil;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Ask mode: plain LLM chat (no RAG context). */
public final class AskMode implements Mode {
    @Override public String name() { return "ask"; }

    @Override public boolean canHandle(String rawLine) {
        return rawLine != null && !rawLine.isBlank();
    }

    @Override
    public Optional<Result> handle(String rawLine, Path workspace, Context ctx) throws Exception {
        if (rawLine == null || rawLine.isBlank() || ctx == null || ctx.llm() == null) return Optional.empty();

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
