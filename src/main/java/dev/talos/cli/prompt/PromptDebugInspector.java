package dev.talos.cli.prompt;

import dev.talos.core.context.ContextLedgerCapture;
import dev.talos.core.context.ContextLedgerSnapshot;
import dev.talos.runtime.TurnPolicyTrace;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.PromptDebugSnapshot;
import dev.talos.spi.types.ToolSpec;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

/** Formats internal prompt-debug captures for Talos maintainers. */
public final class PromptDebugInspector {
    public static final String PROTECTED_TOOL_RESULT_REDACTION =
            PromptDebugRedactor.PROTECTED_TOOL_RESULT_REDACTION;
    public static final String PROTECTED_ASSISTANT_ANSWER_REDACTION =
            PromptDebugRedactor.PROTECTED_ASSISTANT_ANSWER_REDACTION;
    private static final Pattern ABSOLUTE_PATH = Pattern.compile(
            "(?i)(?:^|\\s)(?:[a-z]:[\\\\/]|/|\\\\\\\\|//)\\S*");
    private static final int MAX_TARGET_ROLE_DIAGNOSTIC_CHARS = 4_096;

    private PromptDebugInspector() {}

    public static String format(PromptDebugSnapshot snapshot) {
        if (snapshot == null) {
            return "No prompt debug capture is available.\n";
        }

        String authoritativeContractSummary = safeDiagnostic(
                snapshot.diagnostics().get(
                        PromptDebugCapture.TASK_CONTRACT_SUMMARY_DIAGNOSTIC));
        boolean retryCapture = snapshot.controls().debugTags().stream()
                .anyMatch(tag -> tag != null
                        && tag.startsWith("retry-purpose:"));
        boolean retryAuthorityUnavailable =
                retryCapture
                        && !hasCompleteAuthoritativeContractDiagnostics(
                                snapshot.diagnostics());
        boolean authoritativeContract =
                !retryAuthorityUnavailable
                        && !authoritativeContractSummary.isBlank();
        TaskContract contract = authoritativeContract
                        || retryAuthorityUnavailable
                ? null
                : TaskContractResolver.fromMessages(snapshot.messages());
        String frame = currentTurnFrame(snapshot.messages());
        String targetLabel = retryAuthorityUnavailable
                ? "Expected targets"
                : authoritativeContract
                ? diagnosticOrDefault(
                        snapshot.diagnostics(),
                        PromptDebugCapture
                                .TASK_CONTRACT_TARGET_LABEL_DIAGNOSTIC,
                        "Expected targets")
                : targetLabel(contract);
        String expectedTargets = retryAuthorityUnavailable
                ? "NOT_DERIVED"
                : authoritativeContract
                ? safeTargetRoleDiagnostic(snapshot.diagnostics().get(
                        PromptDebugCapture.TASK_CONTRACT_TARGETS_DIAGNOSTIC))
                : joinOrNone(contract);
        if (expectedTargets.isBlank()) {
            expectedTargets = "(none)";
        }
        String targetCoverageLabel = retryAuthorityUnavailable
                ? "Expected-target coverage"
                : authoritativeContract
                ? diagnosticOrDefault(
                        snapshot.diagnostics(),
                        PromptDebugCapture
                                .TASK_CONTRACT_TARGET_COVERAGE_LABEL_DIAGNOSTIC,
                        "Expected-target coverage")
                : targetCoverageLabel(contract);
        String expectedCoverage = retryAuthorityUnavailable
                ? "NOT_DERIVED"
                : authoritativeContract
                ? diagnosticOrDefault(
                        snapshot.diagnostics(),
                        PromptDebugCapture
                                .TASK_CONTRACT_TARGET_COVERAGE_DIAGNOSTIC,
                        "NOT_DERIVED")
                : expectedTargetCoverage(contract, frame);
        String exactCoverage = retryAuthorityUnavailable
                ? "NOT_DERIVED"
                : exactLiteralCoverage(frame);

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
        if (snapshot.controls().maxOutputTokens() > 0) {
            out.append("- Max output tokens: ")
                    .append(snapshot.controls().maxOutputTokens()).append('\n');
        }
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
        out.append("- Task contract: ");
        if (retryAuthorityUnavailable) {
            out.append(
                    "NOT_DERIVED (authoritative retry diagnostics missing)");
        } else if (authoritativeContract) {
            out.append(authoritativeContractSummary);
        } else {
            out.append(contract.type())
                    .append(", mutationAllowed=")
                    .append(contract.mutationAllowed())
                    .append(", verificationRequired=")
                    .append(contract.verificationRequired());
        }
        out.append('\n');
        out.append("- ").append(targetLabel).append(": ").append(expectedTargets).append('\n');
        out.append("- Target roles: ")
                .append(retryAuthorityUnavailable
                        ? "NOT_DERIVED"
                        : targetRoles(snapshot, contract))
                .append('\n');
        out.append("- ").append(targetCoverageLabel).append(": ").append(expectedCoverage).append('\n');
        out.append("- Exact-literal coverage: ").append(exactCoverage).append("\n\n");
        appendModelIdentity(out, snapshot.diagnostics());
        appendContextLedger(out);

        if ("OLLAMA_HTTP_BODY".equals(snapshot.stage())) {
            out.append("> Provider shape: Ollama merges system messages into one top-level `system` field. ")
                    .append("Internal message placement and provider HTTP shape are not identical.\n\n");
        }

        out.append("## Structured Messages\n\n");
        Set<String> nativeToolCallIds = new HashSet<>();
        Set<String> protectedToolCallIds = new HashSet<>();
        boolean pendingProtectedReadAnswer = false;
        for (int i = 0; i < snapshot.messages().size(); i++) {
            ChatMessage message = snapshot.messages().get(i);
            if (message != null) {
                nativeToolCallIds.addAll(PromptDebugRedactor.nativeToolCallIds(List.of(message)));
                protectedToolCallIds.addAll(PromptDebugRedactor.protectedToolCallIds(List.of(message)));
            }
            out.append("### Message ").append(i + 1).append(" - ")
                    .append(Objects.toString(message.role(), "")).append("\n\n");
            out.append("```text\n")
                    .append(PromptDebugRedactor.redactMessageContent(
                            message,
                            nativeToolCallIds,
                            protectedToolCallIds,
                            pendingProtectedReadAnswer))
                    .append("\n```\n\n");
            pendingProtectedReadAnswer = PromptDebugRedactor.nextPendingProtectedReadAnswer(
                    pendingProtectedReadAnswer, message, nativeToolCallIds);
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
        String providerRequestLayout = diagnostics.get("provider.requestLayout");
        if (providerRequestLayout != null && !providerRequestLayout.isBlank()) {
            out.append("- Provider request layout: ").append(providerRequestLayout).append('\n');
        }
        String requestObligation =
                diagnostics.get("requestObligation.redactedProjectionJson");
        String requestObligationHash =
                diagnostics.get("requestObligation.fullCanonicalSha256");
        if (requestObligation != null && !requestObligation.isBlank()) {
            out.append("\n## Request Obligation (redacted projection)\n\n");
            out.append("```json\n").append(requestObligation.strip()).append("\n```\n");
            if (requestObligationHash != null && !requestObligationHash.isBlank()) {
                out.append("- Full canonical obligation SHA-256: ")
                        .append(requestObligationHash.strip()).append('\n');
            }
            out.append('\n');
        }
    }

    private static void appendModelIdentity(StringBuilder out, Map<String, String> diagnostics) {
        if (diagnostics == null
                || diagnostics.keySet().stream().noneMatch(key -> key.startsWith("model."))) {
            return;
        }
        out.append("## Model Identity\n\n");
        appendModelFact(out, diagnostics, "model.backend", "Backend");
        appendModelFact(out, diagnostics, "model.configured", "Configured");
        appendModelFact(out, diagnostics, "model.configuredFile", "Configured file");
        appendModelFact(out, diagnostics, "model.declaredProfile", "Declared");
        appendModelFact(out, diagnostics, "model.resolvedProfile", "Resolved");
        appendModelFact(out, diagnostics, "model.observedFile", "Observed file");
        appendModelFact(out, diagnostics, "model.artifact", "Artifact");
        appendModelFact(out, diagnostics, "model.artifactVerification", "Artifact verification");
        appendModelFact(out, diagnostics, "model.engineLane", "Engine lane");
        appendModelFact(out, diagnostics, "model.engineExecutable", "Engine executable");
        appendModelFact(out, diagnostics, "model.servedIdentity", "Serving identity");
        appendModelFact(out, diagnostics, "model.resolution", "Resolution");
        appendModelFact(out, diagnostics, "model.toolMode", "Effective tools");
        appendModelFact(out, diagnostics, "model.requestLayout", "Request layout");
        appendModelFact(out, diagnostics, "model.contextTokens", "Effective context");
        appendModelFact(out, diagnostics, "model.disposition", "Disposition");
        appendModelFact(out, diagnostics, "model.propsState", "Props");
        appendModelFact(out, diagnostics, "model.propsSha256", "Props response");
        appendModelFact(out, diagnostics, "model.engineBuild", "Engine build");
        appendModelFact(out, diagnostics, "model.reasons", "Reasons");
        appendModelFact(out, diagnostics, "model.chatAvailable", "Chat available");
        out.append('\n');
    }

    private static void appendModelFact(
            StringBuilder out,
            Map<String, String> diagnostics,
            String key,
            String label) {
        String value = safeDiagnostic(diagnostics.get(key));
        if (!value.isBlank()) {
            out.append("- ").append(label).append(": ").append(value).append('\n');
        }
    }

    private static String safeDiagnostic(String raw) {
        String clean = Objects.toString(raw, "").replaceAll("[\\r\\n\\t]+", " ").strip();
        if (clean.isBlank()) return "";
        if (ABSOLUTE_PATH.matcher(clean).find()) return "[path redacted]";
        return clean.length() <= 240 ? clean : clean.substring(0, 240);
    }

    private static String diagnosticOrDefault(
            Map<String, String> diagnostics,
            String key,
            String fallback
    ) {
        String value = diagnostics == null
                ? ""
                : safeDiagnostic(diagnostics.get(key));
        return value.isBlank() ? fallback : value;
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
        if (!contract.mutationAllowed()) return "N/A (read-only task)";
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
        return String.join(", ", tags);
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

    private static String targetRoles(PromptDebugSnapshot snapshot, TaskContract contract) {
        if (snapshot != null && snapshot.diagnostics().containsKey(
                PromptDebugCapture.TASK_CONTRACT_TARGET_ROLES_DIAGNOSTIC)) {
            String authoritative = safeTargetRoleDiagnostic(snapshot.diagnostics().get(
                    PromptDebugCapture.TASK_CONTRACT_TARGET_ROLES_DIAGNOSTIC));
            return authoritative.isBlank() ? "(none)" : authoritative;
        }
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
        return rendered + " [sourceSpan="
                + renderOffset(target.startOffset())
                + ":"
                + renderOffset(target.authorityEndOffset())
                + ":"
                + renderOffset(target.endOffset())
                + "]";
    }

    private static String renderOffset(int offset) {
        return offset < 0 ? "unknown" : Integer.toString(offset);
    }

    private static String safeTargetRoleDiagnostic(String raw) {
        String clean = Objects.toString(raw, "").replaceAll("[\\r\\n\\t]+", " ").strip();
        if (clean.isBlank()) return "";
        if (ABSOLUTE_PATH.matcher(clean).find()) return "[path redacted]";
        if (clean.length() <= MAX_TARGET_ROLE_DIAGNOSTIC_CHARS) return clean;
        return "[omitted: authoritative target-role diagnostic length="
                + clean.length()
                + " exceeds "
                + MAX_TARGET_ROLE_DIAGNOSTIC_CHARS
                + " characters]";
    }

    private static boolean hasCompleteAuthoritativeContractDiagnostics(
            Map<String, String> diagnostics
    ) {
        if (diagnostics == null || diagnostics.isEmpty()) return false;
        return List.of(
                        PromptDebugCapture.TASK_CONTRACT_SUMMARY_DIAGNOSTIC,
                        PromptDebugCapture
                                .TASK_CONTRACT_TARGET_LABEL_DIAGNOSTIC,
                        PromptDebugCapture.TASK_CONTRACT_TARGETS_DIAGNOSTIC,
                        PromptDebugCapture
                                .TASK_CONTRACT_TARGET_COVERAGE_LABEL_DIAGNOSTIC,
                        PromptDebugCapture
                                .TASK_CONTRACT_TARGET_COVERAGE_DIAGNOSTIC,
                        PromptDebugCapture
                                .TASK_CONTRACT_TARGET_ROLES_DIAGNOSTIC)
                .stream()
                .allMatch(key -> !safeDiagnostic(diagnostics.get(key)).isBlank());
    }

    private static int targetIndex(String requestLower, String target) {
        if (requestLower == null || requestLower.isBlank() || target == null) {
            return Integer.MAX_VALUE;
        }
        int index = requestLower.indexOf(target.toLowerCase(Locale.ROOT));
        return index < 0 ? Integer.MAX_VALUE : index;
    }

}
