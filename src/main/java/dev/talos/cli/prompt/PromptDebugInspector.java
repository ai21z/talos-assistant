package dev.talos.cli.prompt;

import dev.talos.core.context.ContextLedgerCapture;
import dev.talos.core.context.ContextLedgerSnapshot;
import dev.talos.runtime.TurnPolicyTrace;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.PromptDebugSnapshot;
import dev.talos.spi.types.ToolSpec;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Formats internal prompt-debug captures for Talos maintainers. */
public final class PromptDebugInspector {
    public static final String PROTECTED_TOOL_RESULT_REDACTION =
            PromptDebugRedactor.PROTECTED_TOOL_RESULT_REDACTION;
    public static final String PROTECTED_ASSISTANT_ANSWER_REDACTION =
            PromptDebugRedactor.PROTECTED_ASSISTANT_ANSWER_REDACTION;

    private PromptDebugInspector() {}

    public static String format(PromptDebugSnapshot snapshot) {
        if (snapshot == null) {
            return "No prompt debug capture is available.\n";
        }

        TaskContract contract = TaskContractResolver.fromMessages(snapshot.messages());
        String frame = currentTurnFrame(snapshot.messages());
        String expectedCoverage = expectedTargetCoverage(contract, frame);
        String exactCoverage = exactLiteralCoverage(frame);

        StringBuilder out = new StringBuilder();
        out.append("# Talos Prompt Debug\n\n");
        out.append("- Stage: ").append(snapshot.stage()).append('\n');
        out.append("- Backend/model: ").append(snapshot.backend()).append('/')
                .append(snapshot.model()).append('\n');
        out.append("- Stream: ").append(snapshot.stream()).append('\n');
        out.append("- Tool choice: ").append(snapshot.controls().toolChoice());
        if (!snapshot.controls().namedTool().isBlank()) {
            out.append(" (").append(snapshot.controls().namedTool()).append(')');
        }
        out.append('\n');
        out.append("- Response format: ").append(snapshot.controls().responseFormat()).append('\n');
        if (snapshot.controls().sampling().anySet()) {
            var sampling = snapshot.controls().sampling();
            out.append("- Sampling: temperature=").append(sampling.temperature())
                    .append(" top_p=").append(sampling.topP())
                    .append(" top_k=").append(sampling.topK())
                    .append(" seed=").append(sampling.seed()).append('\n');
        }
        out.append("- Debug tags: ").append(debugTags(snapshot.controls().debugTags())).append('\n');
        appendDiagnostics(out, snapshot.diagnostics());
        out.append("- Captured: ").append(snapshot.capturedAt()).append('\n');
        out.append("- Messages: ").append(snapshot.messages().size())
                .append(" total, ").append(countRole(snapshot.messages(), "system"))
                .append(" system, ").append(countRole(snapshot.messages(), "user"))
                .append(" user\n");
        out.append("- Tools: ").append(toolNames(snapshot.tools())).append('\n');
        out.append("- Task contract: ").append(contract.type())
                .append(", mutationAllowed=").append(contract.mutationAllowed())
                .append(", verificationRequired=").append(contract.verificationRequired()).append('\n');
        out.append("- ").append(targetLabel(contract)).append(": ").append(joinOrNone(contract)).append('\n');
        out.append("- Target roles: ").append(targetRoles(contract)).append('\n');
        out.append("- ").append(targetCoverageLabel(contract)).append(": ").append(expectedCoverage).append('\n');
        out.append("- Exact-literal coverage: ").append(exactCoverage).append("\n\n");
        appendContextLedger(out);

        if ("OLLAMA_HTTP_BODY".equals(snapshot.stage())) {
            out.append("> Provider shape: Ollama merges system messages into one top-level `system` field. ")
                    .append("Internal message placement and provider HTTP shape are not identical.\n\n");
        }

        out.append("## Structured Messages\n\n");
        Set<String> protectedToolCallIds = PromptDebugRedactor.protectedToolCallIds(snapshot.messages());
        boolean pendingProtectedReadAnswer = false;
        for (int i = 0; i < snapshot.messages().size(); i++) {
            ChatMessage message = snapshot.messages().get(i);
            out.append("### Message ").append(i + 1).append(" - ")
                    .append(Objects.toString(message.role(), "")).append("\n\n");
            out.append("```text\n")
                    .append(PromptDebugRedactor.redactMessageContent(
                            message, protectedToolCallIds, pendingProtectedReadAnswer))
                    .append("\n```\n\n");
            pendingProtectedReadAnswer = PromptDebugRedactor.nextPendingProtectedReadAnswer(
                    pendingProtectedReadAnswer, message);
        }

        if (!snapshot.providerBodyJson().isBlank()) {
            out.append("## Provider Body JSON\n\n");
            out.append("```json\n")
                    .append(redactedProviderBodyJson(snapshot))
                    .append("\n```\n");
        }

        return out.toString();
    }

    private static void appendDiagnostics(StringBuilder out, Map<String, String> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return;
        }
        String compactionStatus = diagnostics.get("compactionStatus");
        if (compactionStatus != null && !compactionStatus.isBlank()) {
            out.append("- Compaction: ").append(compactionStatus).append('\n');
        }
        String memoryRetentionStatus = diagnostics.get("memoryRetentionStatus");
        if (memoryRetentionStatus != null && !memoryRetentionStatus.isBlank()) {
            out.append("- Memory retention (cumulative this session): ").append(memoryRetentionStatus).append('\n');
        }
        String projectMemoryStatus = diagnostics.get("projectMemoryStatus");
        if (projectMemoryStatus != null && !projectMemoryStatus.isBlank()) {
            out.append("- Project memory: ").append(projectMemoryStatus).append('\n');
        }
        String projectMemoryDetails = diagnostics.get("projectMemoryDetails");
        if (projectMemoryDetails != null && !projectMemoryDetails.isBlank()) {
            out.append("\n## Project Memory\n\n");
            for (String line : projectMemoryDetails.split("\\R")) {
                if (!line.isBlank()) {
                    out.append("- ").append(line.strip()).append('\n');
                }
            }
            out.append('\n');
        }
    }

    private static void appendContextLedger(StringBuilder out) {
        ContextLedgerSnapshot ledger = ContextLedgerCapture.snapshot();
        if (ledger == null || ledger.summary().totalItems() <= 0) {
            return;
        }
        out.append("## Context Ledger\n\n");
        out.append("- Items: ").append(ledger.summary().totalItems()).append('\n');
        out.append("- Sources: ").append(ledger.summary().bySource()).append('\n');
        out.append("- Execution boundaries: ").append(ledger.summary().byBoundary()).append('\n');
        out.append("- Privacy classes: ").append(ledger.summary().byPrivacyClass()).append('\n');
        out.append("- Decisions: ").append(ledger.summary().byDecision()).append('\n');
        out.append("- Reasons: ").append(ledger.summary().byReason()).append("\n\n");
    }

    public static String redactedProviderBodyJson(PromptDebugSnapshot snapshot) {
        return PromptDebugRedactor.redactedProviderBodyJson(snapshot);
    }

    private static long countRole(List<ChatMessage> messages, String role) {
        return messages.stream().filter(m -> role.equals(m.role())).count();
    }

    private static String currentTurnFrame(List<ChatMessage> messages) {
        if (messages == null) return "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            String content = message == null ? "" : Objects.toString(message.content(), "");
            if (message != null
                    && "system".equals(message.role())
                    && content.contains("[CurrentTurnCapability]")) {
                return content;
            }
        }
        return "";
    }

    private static String targetLabel(TaskContract contract) {
        return contract != null && !contract.mutationAllowed()
                ? "Evidence target hints"
                : "Expected targets";
    }

    private static String targetCoverageLabel(TaskContract contract) {
        return contract != null && !contract.mutationAllowed()
                ? "Evidence-target frame coverage"
                : "Expected-target coverage";
    }

    private static String expectedTargetCoverage(TaskContract contract, String frame) {
        Set<String> expectedTargets = contract == null ? Set.of() : contract.expectedTargets();
        if (expectedTargets == null || expectedTargets.isEmpty()) return "N/A";
        if (contract != null && !contract.mutationAllowed()) return "N/A (read-only task)";
        if (frame == null || frame.isBlank() || !frame.contains("[ExpectedTargets]")) {
            return "MISSING";
        }
        for (String target : expectedTargets) {
            if (!frame.contains(target)) return "MISSING";
        }
        return "OK";
    }

    private static String exactLiteralCoverage(String frame) {
        if (frame == null || !frame.contains("[ExactFileWrite]")) return "N/A";
        boolean strong = frame.contains("must equal the expectedContent payload exactly")
                && frame.contains("Do not wrap it in HTML")
                && frame.contains("content argument must be exactly");
        return strong ? "OK" : "WEAK";
    }

    private static String toolNames(List<ToolSpec> tools) {
        if (tools == null || tools.isEmpty()) return "(none)";
        return tools.stream().map(ToolSpec::name).collect(Collectors.joining(", "));
    }

    private static String debugTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) return "(none)";
        return tags.stream().collect(Collectors.joining(", "));
    }

    private static String joinOrNone(TaskContract contract) {
        if (contract == null || contract.expectedTargets().isEmpty()) return "(none)";
        String request = Objects.toString(contract.originalUserRequest(), "").toLowerCase(Locale.ROOT);
        return contract.expectedTargets().stream()
                .sorted(Comparator
                        .comparingInt((String target) -> targetIndex(request, target))
                        .thenComparing(Comparator.naturalOrder()))
                .collect(Collectors.joining(", "));
    }

    private static String targetRoles(TaskContract contract) {
        if (contract == null) return "(none)";
        List<TurnPolicyTrace.RolefulTarget> targets = TurnPolicyTrace.from(
                        contract,
                        "unknown",
                        List.of(),
                        List.of())
                .rolefulTargets();
        if (targets.isEmpty()) return "(none)";
        return targets.stream()
                .sorted(Comparator
                        .comparing((TurnPolicyTrace.RolefulTarget target) -> target.path())
                        .thenComparing(TurnPolicyTrace.RolefulTarget::role))
                .map(PromptDebugInspector::formatRolefulTarget)
                .collect(Collectors.joining(", "));
    }

    private static String formatRolefulTarget(TurnPolicyTrace.RolefulTarget target) {
        if (target == null) return "";
        String rendered = target.path() + " = " + target.role();
        if (!target.reason().isBlank()) {
            rendered += " (" + target.reason() + ")";
        }
        return rendered;
    }

    private static int targetIndex(String requestLower, String target) {
        if (requestLower == null || requestLower.isBlank() || target == null) {
            return Integer.MAX_VALUE;
        }
        int index = requestLower.indexOf(target.toLowerCase(Locale.ROOT));
        return index < 0 ? Integer.MAX_VALUE : index;
    }

}
