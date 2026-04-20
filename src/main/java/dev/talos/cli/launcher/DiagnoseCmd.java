package dev.talos.cli.launcher;

import dev.talos.cli.ManifestVersionProvider;
import dev.talos.core.CfgUtil;
import dev.talos.core.Config;
import dev.talos.core.context.ContextPacker;
import dev.talos.core.context.ContextResult;
import dev.talos.core.context.TokenBudget;
import dev.talos.core.embed.EmbeddingsClient;
import dev.talos.core.rag.RagService;
import picocli.CommandLine;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

@CommandLine.Command(
        name = "diagnose",
        mixinStandardHelpOptions = true,
        versionProvider = ManifestVersionProvider.class,
        description = "Diagnose RAG configuration and prompt sizing for troubleshooting"
)
public class DiagnoseCmd implements Runnable {

    @CommandLine.Option(names = {"--mode"}, description = "Mode to diagnose (rag, ask, etc.)", defaultValue = "rag")
    String mode;

    @CommandLine.Option(names = {"--root"}, description = "Workspace root directory")
    Path root;

    @CommandLine.Option(names = {"-q", "--question"}, description = "Question to test with", required = true)
    String question;

    @CommandLine.Option(names = {"--k"}, description = "Top-K retrieval count")
    Integer k;

    @CommandLine.Option(names = {"--print-prompt-head"}, description = "Print first N chars of assembled prompt")
    boolean printPromptHead;

    @CommandLine.Option(names = {"--print-stats"}, description = "Print detailed statistics")
    boolean printStats;

    @CommandLine.Option(names = {"--print-trace"}, description = "Print retrieval pipeline trace")
    boolean printTrace;

    @Override
    public void run() {
        try {
            // Resolve root
            if (root == null) {
                String envWs = System.getenv("TALOS_WORKSPACE");
                root = (envWs == null || envWs.isBlank()) ? Paths.get(".").toAbsolutePath().normalize() : Paths.get(envWs);
            }

            Config cfg = new Config();

            System.out.println("=== Talos Diagnostics ===");
            System.out.println();

            // 1. Configuration info
            System.out.println("Configuration:");
            Config.Report report = cfg.getReport();
            System.out.println("  Default config: " + report.loadedFrom);
            System.out.println("  User config:    " + report.userConfigPath);
            System.out.println("  ENV overrides:  " + report.envOverridesApplied);
            System.out.println();

            // 2. Ollama connection
            Map<String, Object> ollama = CfgUtil.map(cfg.data.get("ollama"));
            String ollamaHost = String.valueOf(ollama.getOrDefault("host", "http://127.0.0.1:11434"));
            String ollamaModel = String.valueOf(ollama.getOrDefault("model", "qwen3:8b"));
            System.out.println("Ollama:");
            System.out.println("  Host:  " + ollamaHost);
            System.out.println("  Model: " + ollamaModel);
            System.out.println();

            // 2b. Embedding health check
            String embedModel = String.valueOf(ollama.getOrDefault("embed", "bge-m3"));
            System.out.println("Embedding Health:");
            System.out.println("  Model: " + embedModel);
            try {
                EmbeddingsClient embedClient = new EmbeddingsClient(cfg);
                float[] probe = embedClient.embed("hello world");
                if (probe != null && probe.length > 0 && EmbeddingsClient.isValidVector(probe)) {
                    System.out.println("  Status:    OK");
                    System.out.println("  Dimension: " + probe.length);
                } else {
                    System.out.println("  Status:    WARN — probe returned invalid vector (NaN/zero)");
                }
            } catch (Exception embErr) {
                System.out.println("  Status:    ERROR — " + embErr.getMessage());
            }
            System.out.println();

            // 3. Limits and caps
            Map<String, Object> limits = CfgUtil.map(cfg.data.get("limits"));
            int contextMaxTokens = CfgUtil.intAt(limits, "llm_context_max_tokens", 8192);
            long responseMaxChars = CfgUtil.longAt(limits, "response_max_chars", 10485760L);
            long llmTimeoutMs = CfgUtil.longAt(limits, "llm_timeout_ms", 300000L);

            System.out.println("Limits:");
            System.out.println("  Context tokens (budget): " + contextMaxTokens);
            System.out.println("  Response max chars:      " + responseMaxChars);
            System.out.println("  LLM timeout:             " + llmTimeoutMs + " ms");
            System.out.println();

            // 4. RAG-specific diagnostics
            if ("rag".equalsIgnoreCase(mode)) {
                Map<String, Object> rag = CfgUtil.map(cfg.data.get("rag"));
                int defaultK = CfgUtil.intAt(rag, "top_k", 6);
                int effectiveK = (k != null ? k : defaultK);

                System.out.println("RAG Settings:");
                System.out.println("  Workspace:   " + root);
                System.out.println("  Top-K:       " + effectiveK + (k != null ? " (override)" : " (default)"));
                System.out.println("  Question:    " + question);
                System.out.println();

                // 5. Prepare retrieval and validate prompt
                RagService ragService = new RagService(cfg);
                String systemPrompt = ragService.buildSystemPrompt();

                System.out.println("Retrieving snippets...");
                RagService.Prepared prepared = ragService.prepare(root, question, effectiveK);
                int retrievedCount = prepared.snippets().size();
                System.out.println("  Retrieved: " + retrievedCount + " snippets");
                System.out.println();

                // 5b. Print pipeline trace if requested
                if (printTrace && prepared.trace() != null) {
                    System.out.println("Retrieval Pipeline Trace:");
                    System.out.print(prepared.trace().summary());
                    System.out.println();
                }

                // 6. Pack context and validate token budget
                ContextPacker packer = new ContextPacker(TokenBudget.fromConfig(cfg));
                ContextResult packed = packer.pack(systemPrompt, question, java.util.List.of(), prepared.snippets());

                System.out.println("Prompt Validation:");
                System.out.println("  Original snippets:   " + packed.originalCount());
                System.out.println("  Final snippets:      " + packed.finalCount());
                System.out.println("  Was trimmed:         " + (packed.wasTrimmed() ? "YES" : "no"));
                System.out.println("  Estimated tokens:    " + packed.estimatedTokens());
                System.out.println("  Budget tokens:       " + packed.budgetTokens());
                System.out.println("  Budget utilization:  " +
                    String.format("%.1f%%", packed.utilization() * 100.0));
                System.out.println();

                // 7. Print prompt head if requested
                if (printPromptHead) {
                    StringBuilder promptSample = new StringBuilder();
                    promptSample.append("System: ").append(systemPrompt.substring(0, Math.min(200, systemPrompt.length())));
                    promptSample.append("\n...\nUser: ").append(question);
                    promptSample.append("\nContext snippets: ").append(packed.finalCount());

                    System.out.println("Prompt Head (first 400 chars):");
                    System.out.println(promptSample.toString().substring(0, Math.min(400, promptSample.length())));
                    System.out.println("...");
                    System.out.println();
                }

                // 8. Detailed stats if requested
                if (printStats) {
                    System.out.println("Detailed Statistics:");
                    int totalSnippetChars = packed.snippets().stream()
                        .mapToInt(s -> s.text().length())
                        .sum();
                    System.out.println("  Total snippet chars: " + totalSnippetChars);
                    System.out.println("  Avg chars per snippet: " +
                        (packed.finalCount() > 0 ? totalSnippetChars / packed.finalCount() : 0));
                    System.out.println();
                }

                // 9. Try to generate answer and check for empty body
                System.out.println("Generating answer (this may take a moment)...");
                RagService.Answer answer = ragService.ask(root, question, effectiveK);
                String answerText = answer.text().trim();

                System.out.println();
                System.out.println("Answer Result:");
                System.out.println("  Body length:  " + answerText.length() + " chars");
                System.out.println("  Body empty:   " + (answerText.isEmpty() ? "YES (WARN)" : "no"));
                System.out.println("  Citations:    " + answer.citations().size());
                System.out.println();

                if (!answerText.isEmpty()) {
                    System.out.println("Answer preview (first 200 chars):");
                    System.out.println(answerText.substring(0, Math.min(200, answerText.length())));
                    if (answerText.length() > 200) System.out.println("...");
                    System.out.println();
                }

                // 10. Exit code: non-zero if we retrieved snippets but got empty answer
                if (retrievedCount > 0 && answerText.isEmpty()) {
                    System.err.println("FAIL: Retrieved " + retrievedCount + " snippets but answer is empty!");
                    System.err.println("Possible causes:");
                    System.err.println("  - Model context window exceeded (reduce --k)");
                    System.err.println("  - Model not responding (check Ollama service)");
                    System.err.println("  - Network disabled (check config)");
                    System.exit(1);
                }

                System.out.println("✓ Diagnosis complete. No critical issues detected.");
                System.exit(0);
            } else {
                System.out.println("Mode '" + mode + "' diagnostics not yet implemented.");
                System.out.println("Currently supported: --mode rag");
                System.exit(0);
            }

        } catch (Exception e) {
            System.err.println("Error during diagnosis: " + e.getMessage());
            e.printStackTrace();
            System.exit(2);
        }
    }
}

