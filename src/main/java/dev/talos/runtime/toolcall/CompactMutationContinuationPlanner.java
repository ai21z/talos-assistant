package dev.talos.runtime.toolcall;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.capability.StaticWebCapabilityProfile;
import dev.talos.runtime.repair.RepairPolicy;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.workspace.WorkspaceOperationIntent;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.ResponseFormatMode;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolAliasPolicy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

final class CompactMutationContinuationPlanner {
    private static final int COMPACT_MUTATION_READBACK_MAX_CHARS = 4_000;

    private CompactMutationContinuationPlanner() {}

    record Plan(
            List<ChatMessage> messages,
            List<ToolSpec> tools,
            ChatRequestControls controls
    ) {}

    static Optional<Plan> planForContextBudget(
            LoopState state,
            List<ToolSpec> baseTools,
            String retryName
    ) {
        if (state == null || state.ctx == null || state.ctx.llm() == null) return Optional.empty();
        if (state.hasPendingActionObligation()) return Optional.empty();
        if (state.mutationSinceStart || state.mutatingToolSuccesses > 0) return Optional.empty();
        if (!readOnlyProgressOnly(state)) return Optional.empty();

        TaskContract contract = TaskContractResolver.fromMessages(state.messages);
        if (contract == null || !contract.mutationAllowed() || !contract.mutationRequested()) {
            return Optional.empty();
        }
        if (WorkspaceOperationIntent.detect(contract).isPresent()) {
            return Optional.empty();
        }
        if (!hasMutationTargets(state, contract)) {
            return Optional.empty();
        }

        List<ToolSpec> tools = compactMutationContinuationToolSpecs(state, baseTools);
        if (tools.isEmpty()) return Optional.empty();

        List<ChatMessage> messages = compactMutationContinuationMessages(state, contract, retryName);
        ChatRequestControls controls = compactMutationContinuationControls(state, tools);
        return Optional.of(new Plan(messages, tools, controls));
    }

    static boolean hasMutationTargets(LoopState state, TaskContract contract) {
        return !compactMutationTargets(state, contract).isEmpty();
    }

    private static boolean readOnlyProgressOnly(LoopState state) {
        if (state == null || state.toolOutcomes.isEmpty()) return false;
        for (ToolCallLoop.ToolOutcome outcome : state.toolOutcomes) {
            if (outcome == null || !outcome.success()) return false;
            if (!ToolCallSupport.isReadOnlyTool(outcome.toolName()) || outcome.mutating()) {
                return false;
            }
        }
        return true;
    }

    private static List<ToolSpec> compactMutationContinuationToolSpecs(
            LoopState state,
            List<ToolSpec> baseTools
    ) {
        List<String> allowed = hasStaticRepairContext(state)
                ? List.of("talos.write_file")
                : List.of("talos.write_file", "talos.edit_file");
        List<ToolSpec> narrowed = filterTools(baseTools, allowed);
        if (narrowed.isEmpty()) return List.of();
        return narrowed.stream()
                .map(CompactMutationContinuationPlanner::compactMutationToolSpec)
                .toList();
    }

    private static ToolSpec compactMutationToolSpec(ToolSpec spec) {
        if (spec == null) return null;
        return switch (spec.name()) {
            case "talos.write_file" -> new ToolSpec(
                    "talos.write_file",
                    "Write complete file content.",
                    "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}},\"required\":[\"path\",\"content\"]}");
            case "talos.edit_file" -> new ToolSpec(
                    "talos.edit_file",
                    "Replace exact text in a file.",
                    "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"old_string\":{\"type\":\"string\"},\"new_string\":{\"type\":\"string\"}},\"required\":[\"path\",\"old_string\",\"new_string\"]}");
            default -> spec;
        };
    }

    private static ChatRequestControls compactMutationContinuationControls(
            LoopState state,
            List<ToolSpec> tools
    ) {
        boolean required = state != null
                && state.ctx != null
                && state.ctx.llm() != null
                && state.ctx.llm().supportsRequiredToolChoice()
                && hasMutatingTool(tools);
        return new ChatRequestControls(
                required ? ToolChoiceMode.REQUIRED : ToolChoiceMode.AUTO,
                "",
                ResponseFormatMode.TEXT,
                "",
                List.of("compact-mutation-continuation"));
    }

    private static List<ChatMessage> compactMutationContinuationMessages(
            LoopState state,
            TaskContract contract,
            String retryName
    ) {
        String userTask = ToolCallSupport.latestUserRequestIn(state.messages);
        if (userTask == null || userTask.isBlank()) {
            userTask = contract == null ? "" : contract.originalUserRequest();
        }
        StringBuilder frame = new StringBuilder();
        frame.append("[CompactMutationContinuation]\n")
                .append("Normal tool-loop continuation exceeded the local context budget during ")
                .append(retryName == null || retryName.isBlank() ? "tool-call loop continuation" : retryName)
                .append(".\n")
                .append("Continue only the current mutation request. Older conversation history is intentionally omitted.\n")
                .append("Prose/manual snippets do not change files; call the provided write/edit tools now.\n");
        appendCompactMutationContract(frame, state, contract);
        appendCompactMutationReadbacks(frame, state, contract);

        String currentRequest = userTask == null ? "" : userTask.strip();
        return List.of(
                ChatMessage.system("""
                        You are Talos, a local-first workspace assistant.
                        This is a compact mutation continuation after the full-history continuation exceeded the local context budget.
                        Use only the current request, expected targets, and readback evidence in this compact frame.
                        Do not answer in prose instead of calling a file mutation tool.
                        Do not claim completion until tool-backed changes have executed and runtime verification has run.
                        """),
                ChatMessage.system(frame.toString()),
                ChatMessage.user("Current mutation request:\n" + currentRequest
                        + "\n\nCall talos.write_file or talos.edit_file now."));
    }

    private static void appendCompactMutationContract(StringBuilder frame, LoopState state, TaskContract contract) {
        if (frame == null || contract == null) return;
        frame.append("\n[TaskContract]\n")
                .append("type: ").append(contract.type().name()).append('\n')
                .append("mutationAllowed: ").append(contract.mutationAllowed()).append('\n')
                .append("verificationRequired: ").append(contract.verificationRequired()).append('\n');
        List<String> targets = compactMutationTargets(state, contract);
        if (!targets.isEmpty()) {
            frame.append("[ExpectedTargets]\n")
                    .append("requiredTargets: ").append(String.join(", ", targets)).append('\n')
                    .append("You must write or edit these exact target paths for this turn.\n")
                    .append("Similar filenames are not substitutes for required target paths.\n")
                    .append("script.js and scripts.js are different target paths; preserve the exact requested spelling.\n");
            String staticWebGuidance = StaticWebCapabilityProfile.repairCoherenceGuidance(targets);
            if (!staticWebGuidance.isBlank()) {
                frame.append('\n').append(staticWebGuidance).append('\n');
            }
        }
    }

    private static void appendCompactMutationReadbacks(
            StringBuilder frame,
            LoopState state,
            TaskContract contract
    ) {
        if (frame == null || state == null) return;
        List<String> targets = compactMutationReadbackTargets(state, contract);
        boolean wroteHeader = false;
        for (String target : targets) {
            if (target == null || target.isBlank() || isSensitiveReadbackPath(target)) continue;
            String readback = latestSuccessfulReadbackForPath(state, target);
            if (readback == null || readback.isBlank()) continue;
            if (!wroteHeader) {
                frame.append("\n[CurrentReadbackEvidence]\n");
                wroteHeader = true;
            }
            frame.append("Path: ").append(target).append('\n')
                    .append(truncateForCompactMutation(readback))
                    .append("\n---\n");
        }
        appendCompactMutationSourceEvidenceReadbacks(frame, state, contract);
    }

    private static void appendCompactMutationSourceEvidenceReadbacks(
            StringBuilder frame,
            LoopState state,
            TaskContract contract
    ) {
        if (frame == null || state == null || contract == null || contract.sourceEvidenceTargets().isEmpty()) {
            return;
        }
        List<SourceDerivedEvidenceGuard.SourceReadback> sourceReadbacks =
                SourceDerivedEvidenceGuard.sourceReadbacks(state, contract);
        if (sourceReadbacks.isEmpty()) return;
        frame.append("\n[RequiredSourceEvidence]\n")
                .append("Each listed source must contribute at least one exact copied phrase to the output. ")
                .append("Use these snippets or another exact phrase from the matching source readback; ")
                .append("do not substitute paraphrases or invented office facts.\n");
        for (SourceDerivedEvidenceGuard.SourceReadback sourceReadback : sourceReadbacks) {
            String snippet = SourceDerivedEvidenceGuard.evidenceSnippet(sourceReadback.readback());
            if (snippet.isBlank()) continue;
            frame.append("- ").append(sourceReadback.path())
                    .append(": include exact phrase `")
                    .append(snippet)
                    .append("`\n");
        }
        frame.append("\n[SourceEvidenceReadbacks]\n")
                .append("Use these already-read source files as evidence for the current output. ")
                .append("Do not invent exact facts that are not present here.\n");
        for (SourceDerivedEvidenceGuard.SourceReadback sourceReadback : sourceReadbacks) {
            frame.append("Path: ").append(sourceReadback.path()).append('\n')
                    .append(truncateForCompactMutation(sourceReadback.readback()))
                    .append("\n---\n");
        }
    }

    private static List<String> compactMutationReadbackTargets(LoopState state, TaskContract contract) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        List<String> expected = compactMutationTargets(state, contract);
        out.addAll(expected);
        for (ToolCallLoop.ToolOutcome outcome : state.toolOutcomes) {
            if (outcome == null || !outcome.success()) continue;
            if (!"talos.read_file".equals(canonicalToolName(outcome.toolName()))) continue;
            String path = ToolCallSupport.normalizePath(outcome.pathHint());
            if (path.isBlank() || isSensitiveReadbackPath(path)) continue;
            if (expected.contains(path) || isSimilarSiblingTarget(path, expected)) {
                out.add(path);
            }
        }
        return new ArrayList<>(out);
    }

    private static List<String> compactMutationTargets(LoopState state, TaskContract contract) {
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        Set<String> repairTargets = state == null
                ? Set.of()
                : RepairPolicy.fullRewriteTargetsFromRepairContext(state.messages);
        if (repairTargets != null && !repairTargets.isEmpty()) {
            repairTargets.stream()
                    .map(ToolCallSupport::normalizePath)
                    .filter(path -> !path.isBlank())
                    .sorted(Comparator.naturalOrder())
                    .forEach(targets::add);
            return new ArrayList<>(targets);
        }
        if (contract != null && contract.expectedTargets() != null) {
            contract.expectedTargets().stream()
                    .map(ToolCallSupport::normalizePath)
                    .filter(path -> !path.isBlank())
                    .sorted(Comparator.naturalOrder())
                    .forEach(targets::add);
        }
        return new ArrayList<>(targets);
    }

    private static boolean isSimilarSiblingTarget(String readPath, List<String> expectedTargets) {
        if (readPath == null || readPath.isBlank() || expectedTargets == null || expectedTargets.isEmpty()) {
            return false;
        }
        String normalizedRead = ToolCallSupport.normalizePath(readPath).toLowerCase(Locale.ROOT);
        for (String expected : expectedTargets) {
            String normalizedExpected = ToolCallSupport.normalizePath(expected).toLowerCase(Locale.ROOT);
            if (sameParent(normalizedRead, normalizedExpected)
                    && sameExtension(normalizedRead, normalizedExpected)
                    && singularPluralStemMatch(fileStem(normalizedRead), fileStem(normalizedExpected))) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameParent(String left, String right) {
        return parentPath(left).equals(parentPath(right));
    }

    private static String parentPath(String path) {
        if (path == null) return "";
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static boolean sameExtension(String left, String right) {
        return extension(left).equals(extension(right));
    }

    private static String extension(String path) {
        if (path == null) return "";
        String file = fileName(path);
        int dot = file.lastIndexOf('.');
        return dot < 0 ? "" : file.substring(dot);
    }

    private static String fileStem(String path) {
        String file = fileName(path);
        int dot = file.lastIndexOf('.');
        return dot < 0 ? file : file.substring(0, dot);
    }

    private static String fileName(String path) {
        if (path == null) return "";
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static boolean singularPluralStemMatch(String left, String right) {
        if (left == null || right == null || left.isBlank() || right.isBlank()) return false;
        if (left.equals(right)) return false;
        return (left + "s").equals(right) || (right + "s").equals(left);
    }

    private static boolean hasStaticRepairContext(LoopState state) {
        return state != null && !RepairPolicy.fullRewriteTargetsFromRepairContext(state.messages).isEmpty();
    }

    private static String canonicalToolName(String toolName) {
        ToolAliasPolicy.Decision decision = ToolAliasPolicy.resolve(toolName);
        if (decision.accepted() && decision.canonicalToolName() != null && !decision.canonicalToolName().isBlank()) {
            return decision.canonicalToolName();
        }
        return toolName == null ? "" : toolName;
    }

    private static boolean isSensitiveReadbackPath(String path) {
        if (path == null || path.isBlank()) return true;
        String normalized = ToolCallSupport.normalizePath(path).toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) return true;
        for (String segment : normalized.split("/")) {
            if (segment.equals(".env") || segment.startsWith(".env.")) return true;
            if (segment.equals(".git") || segment.equals(".ssh") || segment.equals(".gnupg")) return true;
        }
        return normalized.contains("id_rsa")
                || normalized.contains("credentials")
                || normalized.contains("secret");
    }

    private static String latestSuccessfulReadbackForPath(LoopState state, String normalizedPath) {
        if (state == null || normalizedPath == null || normalizedPath.isBlank()) {
            return null;
        }
        String target = ToolCallSupport.canonicalizeReadPath(normalizedPath)
                .toLowerCase(Locale.ROOT);
        String fullBody = latestSuccessfulReadbackForPath(state.successfulReadCallBodies, target);
        if (fullBody != null) return fullBody;
        return latestSuccessfulReadbackForPath(state.successfulReadCalls, target);
    }

    private static String latestSuccessfulReadbackForPath(java.util.Map<String, String> readbacksBySignature,
                                                          String target) {
        if (readbacksBySignature == null || readbacksBySignature.isEmpty()
                || target == null || target.isBlank()) {
            return null;
        }
        for (var entry : readbacksBySignature.entrySet()) {
            String signature = entry.getKey() == null
                    ? ""
                    : entry.getKey().replace('\\', '/').toLowerCase(Locale.ROOT);
            if (signature.startsWith("talos.read_file:")
                    && signature.contains("path=" + target + ";")) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String truncateForCompactMutation(String readback) {
        if (readback == null || readback.length() <= COMPACT_MUTATION_READBACK_MAX_CHARS) {
            return readback;
        }
        return readback.substring(0, COMPACT_MUTATION_READBACK_MAX_CHARS)
                + "\n... [readback truncated for compact mutation continuation]";
    }

    private static List<ToolSpec> filterTools(List<ToolSpec> specs, List<String> allowedNames) {
        if (specs == null || specs.isEmpty() || allowedNames == null || allowedNames.isEmpty()) return List.of();
        return specs.stream()
                .filter(spec -> spec != null && allowedNames.contains(spec.name()))
                .toList();
    }

    private static boolean hasMutatingTool(List<ToolSpec> specs) {
        if (specs == null || specs.isEmpty()) return false;
        for (ToolSpec spec : specs) {
            String name = spec == null ? "" : spec.name();
            if ("talos.write_file".equals(name) || "talos.edit_file".equals(name)) {
                return true;
            }
        }
        return false;
    }
}
