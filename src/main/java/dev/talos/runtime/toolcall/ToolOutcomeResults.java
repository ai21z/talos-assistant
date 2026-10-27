package dev.talos.runtime.toolcall;

import dev.talos.runtime.ToolCallLoop.ToolOutcome;
import dev.talos.spi.types.ChatMessage;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Outcome occurrences are bound to emitted carriers, independently of trace and provider IDs. */
final class ToolOutcomeResults {
    private final Map<ToolOutcome, BoundResult> results = new IdentityHashMap<>();
    private final Set<ToolOutcome> invalidOutcomes = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Integer> claimedIndexes = new HashSet<>();
    private final Set<Integer> ambiguousIndexes = new HashSet<>();
    private final Map<ToolOutcome, ReadShape> reads = new IdentityHashMap<>();
    private final Set<ToolOutcome> admittedSources = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<ToolOutcome, DirectoryCall> directories = new IdentityHashMap<>();
    private final Set<ToolOutcome> preExecutionRejections = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Only controlled stage rejections before executor dispatch are marked. */
    void bindPreExecutionRejection(ToolOutcome outcome) {
        if (outcome != null && !outcome.success() && !outcome.mutationEffects().hasEffects())
            preExecutionRejections.add(outcome);
    }

    boolean preExecutionRejected(ToolOutcome outcome, List<ToolOutcome> outcomes, List<ChatMessage> messages) {
        return preExecutionRejections.contains(outcome) && !outcome.success()
                && !outcome.mutationEffects().hasEffects() && executionIndex(outcome, outcomes, messages) >= 0;
    }

    void bindDirectory(ToolOutcome outcome, dev.talos.tools.ToolCall call) {
        if (outcome != null && call != null && "talos.list_dir".equals(call.canonicalToolName()))
            directories.put(outcome, new DirectoryCall(call.localCanonicalToolName(), Map.copyOf(call.parameters())));
    }

    int nativeDirectoryIndex(ToolOutcome outcome, List<ToolOutcome> outcomes, List<ChatMessage> messages) {
        int index = resultIndex(outcome, outcomes, messages);
        DirectoryCall executed = directories.get(outcome);
        if (index < 0 || executed == null) return -1;
        String id = messages.get(index).toolCallId();
        try {
            for (ChatMessage message : messages) {
                if (!"assistant".equals(message.role()) || !message.hasNativeToolCalls()) continue;
                for (var call : message.toolCalls()) {
                    if (!id.equals(call.id())) continue;
                    var converted = ToolCallSupport.convertNativeToolCalls(List.of(call));
                    return converted.size() == 1 && ReadEvidenceLedger.sameCallIdentity(
                            converted.getFirst(), executed.canonicalTool(), executed.parameters()) ? index : -1;
                }
            }
        } catch (RuntimeException invalid) { return -1; }
        return -1;
    }

    void bindSourceAdmission(ToolOutcome outcome, boolean admitted) {
        if (outcome != null && outcome.success() && !outcome.denied() && admitted) admittedSources.add(outcome);
    }

    boolean sourceAdmitted(ToolOutcome outcome) { return admittedSources.contains(outcome); }

    /** Only shape and privacy status are retained for settlement, not another source body. */
    void bindRead(ToolOutcome outcome, ReadEvidence evidence, boolean withheld,
                  dev.talos.tools.ToolContentMetadata metadata, String privacyNotice) {
        if (outcome == null || !outcome.success() || evidence == null || metadata == null) return;
        var coverage = metadata.readCoverage();
        boolean usable = switch (coverage) {
            case COMPLETE, REQUESTED_RANGE -> true;
            case LIMITED -> false;
            case UNSPECIFIED -> !evidence.truncated();
        };
        boolean admittedExtraction = metadata.source()
                == dev.talos.tools.ToolContentMetadata.ContentSource.DOCUMENT_EXTRACTION
                && coverage == dev.talos.tools.ToolContentMetadata.ReadCoverage.COMPLETE;
        boolean full = !evidence.redacted() && (coverage == dev.talos.tools.ToolContentMetadata.ReadCoverage.COMPLETE
                || coverage == dev.talos.tools.ToolContentMetadata.ReadCoverage.UNSPECIFIED && !evidence.ranged());
        String notice = withheld ? dev.talos.runtime.policy.ProtectedContentPolicy.sanitizeText(
                privacyNotice == null ? "" : privacyNotice).strip() : "";
        reads.put(outcome, new ReadShape(!withheld && metadata.modelHandoffAllowed() && usable
                && (!evidence.redacted() || admittedExtraction), full,
                evidence.canonicalTool(), Map.copyOf(evidence.rawParams()), notice,
                !withheld && metadata.modelHandoffAllowed() && !evidence.redacted()
                        ? excerpt(evidence.body()) : "", !withheld && metadata.modelHandoffAllowed()));
    }

    ReadShape readShape(ToolOutcome outcome) { return reads.get(outcome); }

    int nativeReadIndex(ToolOutcome outcome, List<ToolOutcome> outcomes, List<ChatMessage> messages) {
        int index = resultIndex(outcome, outcomes, messages);
        ReadShape read = reads.get(outcome);
        if (index < 0 || read == null) return -1;
        String id = messages.get(index).toolCallId();
        try {
            for (ChatMessage message : messages) {
                if (!"assistant".equals(message.role()) || !message.hasNativeToolCalls()) continue;
                for (var call : message.toolCalls()) {
                    if (!id.equals(call.id())) continue;
                    var converted = ToolCallSupport.convertNativeToolCalls(List.of(call));
                    return converted.size() == 1 && ReadEvidenceLedger.sameCallIdentity(
                            converted.getFirst(), read.canonicalTool(), read.parameters()) ? index : -1;
                }
            }
        } catch (RuntimeException invalid) {
            return -1;
        }
        return -1;
    }

    /** Text compatibility carriers establish execution order, never native source authority. */
    int executionIndex(ToolOutcome outcome, List<ToolOutcome> outcomes, List<ChatMessage> messages) {
        int nativeIndex = resultIndex(outcome, outcomes, messages);
        if (nativeIndex >= 0) return reads.containsKey(outcome)
                ? nativeReadIndex(outcome, outcomes, messages) : directories.containsKey(outcome)
                ? nativeDirectoryIndex(outcome, outcomes, messages) : nativeIndex;
        BoundResult bound = results.get(outcome);
        if (bound == null || invalidOutcomes.contains(outcome) || ambiguousIndexes.contains(bound.index())
                || bound.index() < 0 || bound.index() >= messages.size()
                || outcomes.stream().filter(candidate -> candidate == outcome).count() != 1) return -1;
        ChatMessage original = bound.message();
        ChatMessage current = messages.get(bound.index());
        if (original == null || current == null || !"user".equals(original.role())
                || !"user".equals(current.role()) || original.hasNativeToolCalls() || current.hasNativeToolCalls()
                || original.toolCallId() != null || current.toolCallId() != null
                || !original.contentRestrictions().modelAllowed() || !current.contentRestrictions().modelAllowed()
                || original.content() == null || !(original.content().equals(current.content())
                    || ToolCallSupport.summarizeToolResult(original.content()).equals(current.content()))) return -1;
        return bound.index();
    }

    record ReadShape(boolean usable, boolean full, String canonicalTool, Map<String, String> parameters,
                     String privacyNotice, String excerpt, boolean admitted) {}

    private static String excerpt(String body) {
        String safe = dev.talos.runtime.policy.ProtectedContentPolicy.sanitizeText(body);
        return safe.length() <= 1600 ? safe : safe.substring(0, 1600) + "\n[Excerpt clipped.]";
    }

    void bind(ToolOutcome outcome, int index, ChatMessage message) {
        if (outcome == null) return;
        if (!claimedIndexes.add(index)) ambiguousIndexes.add(index);
        if (results.containsKey(outcome)) {
            invalidOutcomes.add(outcome);
            ambiguousIndexes.add(index);
            ambiguousIndexes.add(results.get(outcome).index());
            return;
        }
        results.put(outcome, new BoundResult(index, message));
    }

    int resultIndex(ToolOutcome outcome, List<ToolOutcome> outcomes, List<ChatMessage> messages) {
        BoundResult bound = results.get(outcome);
        if (bound == null || invalidOutcomes.contains(outcome) || ambiguousIndexes.contains(bound.index())
                || bound.index() < 0 || bound.index() >= messages.size()
                || outcomes.stream().filter(candidate -> candidate == outcome).count() != 1) return -1;
        ChatMessage original = bound.message();
        ChatMessage current = messages.get(bound.index());
        if (!nativeResult(original) || !nativeResult(current)
                || !Objects.equals(original.toolCallId(), current.toolCallId())
                || !(Objects.equals(original.content(), current.content())
                    || Objects.equals(ToolCallSupport.summarizeToolResult(original.content()), current.content())))
            return -1;
        int resultCount = 0;
        int proposalCount = 0;
        for (int index = 0; index < messages.size(); index++) {
            ChatMessage message = messages.get(index);
            if (message == null) return -1;
            if ("tool".equals(message.role()) && original.toolCallId().equals(message.toolCallId())) resultCount++;
            if (!"assistant".equals(message.role()) || !message.hasNativeToolCalls()) continue;
            for (var call : message.toolCalls()) {
                if (call == null) return -1;
                if (!original.toolCallId().equals(call.id())) continue;
                if (index >= bound.index()) return -1;
                proposalCount++;
            }
        }
        return resultCount == 1 && proposalCount == 1 ? bound.index() : -1;
    }

    private static boolean nativeResult(ChatMessage message) {
        return message != null && "tool".equals(message.role()) && !message.hasNativeToolCalls()
                && message.toolCallId() != null && !message.toolCallId().isBlank()
                && message.content() != null && message.contentRestrictions().modelAllowed();
    }

    private record BoundResult(int index, ChatMessage message) {}
    private record DirectoryCall(String canonicalTool, Map<String, String> parameters) {}
}
