package dev.talos.runtime.policy;

import dev.talos.runtime.HistoricalAttributionEvidence;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.SessionMemory;
import dev.talos.runtime.intent.HistoricalAttributionRequest;

import java.nio.file.Path;
import java.util.List;

/** Requested action attribution is rendered from retained audit facts, never assistant prose. */
public final class HistoricalAttributionAnswerPolicy {
    private HistoricalAttributionAnswerPolicy() {}

    public static String render(HistoricalAttributionRequest request, SessionMemory memory, Path workspace) {
        var evidence = memory == null ? new HistoricalAttributionEvidence(List.of(), 0, false)
                : memory.historicalAttributionEvidence();
        String workspaceHash = workspace == null ? "" : JsonSessionStore.sessionIdFor(workspace);
        var scoped = evidence.turns().stream().filter(turn -> !workspaceHash.isBlank()
                && workspaceHash.equals(turn.workspaceHash())).toList();
        StringBuilder answer = new StringBuilder("Retained action attribution. This is not a complete session audit.\n");
        for (var result : request.results()) {
            if (result.action() == HistoricalAttributionRequest.Action.FILE_EDIT) {
                boolean applied = scoped.stream().anyMatch(turn -> turn.hasAppliedEdit(workspaceHash, result.target()));
                answer.append("File edit: ").append(result.target()).append(". ");
                if (applied) {
                    answer.append("A Talos edit or write with a matching applied effect is recorded in this workspace. "
                            + "This does not establish the final content, every requested change or verification.\n");
                } else {
                    answer.append("A Talos edit is not established by retained runtime evidence. "
                            + "A current read or a reported external change does not establish Talos authorship.\n");
                }
                boolean uncertain = scoped.stream().flatMap(turn -> turn.calls().stream())
                        .anyMatch(call -> call.uncertainTargets().contains(result.target()));
                if (uncertain) answer.append("An uncertain effect is also recorded for this target. "
                        + "Its state is not certified by this answer.\n");
            } else {
                long commands = scoped.stream().flatMap(turn -> turn.calls().stream())
                        .filter(HistoricalAttributionEvidence.Call::commandReachedExecution).count();
                answer.append("Test execution: not established by retained runtime evidence. ");
                if (commands > 0) answer.append(commands).append(" command calls reached tool execution. "
                        + "That does not establish subprocess launch, executed test cases or passing tests.\n");
                else answer.append("No executed command call is recorded in the retained workspace scope. "
                        + "This does not prove that no tests ran outside that scope.\n");
            }
        }
        long unscoped = evidence.turns().stream().filter(turn -> turn.workspaceHash().isBlank()).count();
        long omittedCalls = evidence.turns().stream().mapToLong(HistoricalAttributionEvidence.Turn::omittedCalls).sum();
        long omittedPaths = evidence.turns().stream().flatMap(turn -> turn.calls().stream())
                .mapToLong(call -> (long) call.omittedAppliedTargets() + call.omittedUncertainTargets()
                        + (call.declaredTargetOmitted() ? 1 : 0)).sum();
        if (!evidence.memoryPresent()) answer.append("Retained runtime memory is unavailable.\n");
        if (workspaceHash.isBlank()) answer.append("Current workspace scope is unavailable.\n");
        if (evidence.evictedTurns() > 0 || omittedCalls > 0 || omittedPaths > 0 || unscoped > 0) {
            answer.append("Retention limits: ").append(evidence.evictedTurns()).append(" evicted turns, ")
                    .append(omittedCalls).append(" omitted calls, ").append(omittedPaths)
                    .append(" omitted targets, ").append(unscoped).append(" turns without workspace binding.\n");
        }
        return answer.toString().stripTrailing();
    }
}
