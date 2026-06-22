package dev.talos.cli.launcher;

import dev.talos.cli.ManifestVersionProvider;
import dev.talos.core.CfgUtil;
import dev.talos.core.Config;
import dev.talos.core.EngineRuntimeConfig;
import dev.talos.core.context.ContextPacker;
import dev.talos.core.context.ContextResult;
import dev.talos.core.context.TokenBudget;
import dev.talos.core.embed.EmbeddingsFactory;
import dev.talos.spi.Embeddings;
import dev.talos.core.rag.RagService;
import dev.talos.core.util.Sanitize;
import dev.talos.cli.ui.TerminalCapabilities;
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
            boolean unicodeSafe = TerminalCapabilities.detectDefault().unicodeSafe();
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
            if (report.userConfigPresent) {
                System.out.println("  User status:    " + (report.userConfigLoaded
                        ? "loaded"
                        : "parse failed - " + report.userConfigError));
            } else {
                System.out.println("  User status:    not found");
            }
            System.out.println("  ENV overrides:  " + report.envOverridesApplied);
            System.out.println();

            // 2. Active engine
            System.out.print(renderEngineSection(cfg, unicodeSafe));
            System.out.println();

            // 2b. Embedding health check
            EngineRuntimeConfig runtime = EngineRuntimeConfig.from(cfg);
            System.out.println("Embedding Health:");
            System.out.println("  Provider: " + runtime.embeddingProvider());
            System.out.println("  Model:    " + runtime.embeddingModel());
            try {
                Embeddings embedClient = EmbeddingsFactory.forQuery(cfg);
                try {
                    float[] probe = embedClient.embed("hello world");
                    if (probe != null && probe.length > 0 && dev.talos.core.embed.EmbeddingsClient.isValidVector(probe)) {
                        System.out.println("  Status:    OK");
                        System.out.println("  Dimension: " + probe.length);
                    } else {
                        System.out.println(term("  Status:    WARN — probe returned invalid vector (NaN/zero)", unicodeSafe));
                    }
                } finally {
                    closeEmbeddingProbe(embedClient);
                }
            } catch (Exception embErr) {
                System.out.println(term("  Status:    ERROR — " + embErr.getMessage(), unicodeSafe));
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
                    System.out.print(term(prepared.trace().summary(), unicodeSafe));
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
                    System.out.println(term(
                            promptSample.toString().substring(0, Math.min(400, promptSample.length())),
                            unicodeSafe));
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
                    System.out.println(term(answerText.substring(0, Math.min(200, answerText.length())), unicodeSafe));
                    if (answerText.length() > 200) System.out.println("...");
                    System.out.println();
                }

                // 10. Exit code: non-zero for critical configuration or answer-generation failures.
                String criticalFailure = criticalDiagnosisFailure(report, answerText, retrievedCount);
                if (!criticalFailure.isBlank()) {
                    System.err.println("FAIL: " + criticalFailure);
                    if (retrievedCount > 0 && answerText.isEmpty()) {
                        System.err.println("Possible causes:");
                        System.err.println("  - Model context window exceeded (reduce --k)");
                        System.err.println("  - Model not responding (check selected engine service)");
                        System.err.println("  - Network disabled (check config)");
                    }
                    System.exit(1);
                }

                System.out.println(term("✓ Diagnosis complete. No critical issues detected.", unicodeSafe));
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

    private static String term(String text, boolean unicodeSafe) {
        return Sanitize.sanitizeForTerminalOutput(text, unicodeSafe);
    }

    private static void closeEmbeddingProbe(Embeddings embedClient) throws Exception {
        if (embedClient instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    static String criticalDiagnosisFailure(Config.Report report, String answerText, int retrievedCount) {
        if (report != null && report.userConfigPresent && !report.userConfigLoaded) {
            return "User config could not be loaded: " + report.userConfigPath;
        }
        String text = answerText == null ? "" : answerText.trim();
        if (text.startsWith("Error:")) {
            return "Answer generation failed: " + text;
        }
        if (retrievedCount > 0 && text.isEmpty()) {
            return "Retrieved " + retrievedCount + " snippets but answer is empty";
        }
        return "";
    }

    static String renderEngineSection(Config cfg, boolean unicodeSafe) {
        EngineRuntimeConfig runtime = EngineRuntimeConfig.from(cfg);
        StringBuilder out = new StringBuilder();
        out.append("Engine:\n");
        out.append("  Backend: ").append(runtime.backend()).append("\n");
        out.append("  Model:   ").append(runtime.model()).append("\n");
        out.append("  Host:    ").append(runtime.hostLabel()).append("\n");
        out.append("  Policy:  ").append(term(runtime.policyLabel(), unicodeSafe)).append("\n");
        return out.toString();
    }
}

