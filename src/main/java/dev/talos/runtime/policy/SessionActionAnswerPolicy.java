package dev.talos.runtime.policy;

import dev.talos.runtime.SessionMemory;
import dev.talos.runtime.TurnRecord.ToolCallDisposition;
import dev.talos.runtime.intent.ConversationEvidenceRequest;
import dev.talos.runtime.toolcall.ToolCallSupport;
import dev.talos.tools.ToolAliasPolicy;

import java.util.List;
import java.util.Optional;

/** Retained invocation facts are separated from effects, proposals and test verdicts. */
public final class SessionActionAnswerPolicy {
    private SessionActionAnswerPolicy() {}

    public static Optional<String> answer(String request, SessionMemory memory) {
        if (ConversationEvidenceRequest.interpret(request).source()
                != ConversationEvidenceRequest.Source.SESSION_ACTIONS) return Optional.empty();
        if (memory == null) return Optional.of("Retained runtime action records are unavailable. " + limits());
        List<SessionMemory.ToolEvidence> records = memory.toolEvidence();
        StringBuilder answer = new StringBuilder("Retained runtime action records, not a complete session audit.\n");
        append(answer, "Inspection", records, Category.INSPECT);
        append(answer, "Mutation", records, Category.MUTATE);
        append(answer, "Command", records, Category.COMMAND);
        append(answer, "Other", records, Category.OTHER);
        int evicted = memory.retentionEvictionStats().toolEvidenceEntriesEvicted();
        if (evicted > 0) answer.append("Earlier records evicted: ").append(evicted).append(".\n");
        return Optional.of(answer.append(limits()).toString());
    }

    private enum Category { INSPECT, MUTATE, COMMAND, OTHER }

    private static Category category(String tool) {
        var decision = ToolAliasPolicy.resolve(tool);
        String canonical = decision.accepted() ? decision.canonicalToolName() : tool;
        if ("talos.run_command".equals(canonical)) return Category.COMMAND;
        if (ToolCallSupport.isMutatingTool(canonical)) return Category.MUTATE;
        if (ToolCallSupport.isReadOnlyTool(canonical)) return Category.INSPECT;
        return Category.OTHER;
    }

    private static void append(StringBuilder answer, String label, List<SessionMemory.ToolEvidence> records,
                               Category category) {
        var selected = records.stream().filter(record -> category(record.toolName()) == category).toList();
        long executed = selected.stream().filter(record ->
                record.executionDisposition() == ToolCallDisposition.EXECUTED).count();
        long success = selected.stream().filter(record ->
                record.executionDisposition() == ToolCallDisposition.EXECUTED && record.success()).count();
        long blocked = selected.stream().filter(record ->
                record.executionDisposition() == ToolCallDisposition.BLOCKED).count();
        answer.append(label).append(" calls: ").append(executed).append(" reached execution, ")
                .append(success).append(" reported success, ").append(blocked).append(" blocked, ")
                .append(selected.size() - executed - blocked).append(" execution unknown.\n");
    }

    private static String limits() {
        return "A zero count is not proof that no earlier action occurred. "
                + "Execution means a tool invocation was reached, not that a command launched or files changed. "
                + "Test results and verification are not established by these records. "
                + "External file changes and proposed fixes cannot be reconstructed from this action summary. "
                + "Assistant prose is not execution evidence. No fresh inspection or execution was performed for this answer.";
    }
}
