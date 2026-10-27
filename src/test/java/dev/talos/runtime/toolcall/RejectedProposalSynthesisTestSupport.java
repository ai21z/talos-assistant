package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.task.TaskContract;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A selected proposal rejection is produced through the existing scripted synthesis path. */
public final class RejectedProposalSynthesisTestSupport {
    private RejectedProposalSynthesisTestSupport() {}

    public static ToolCallLoop.LoopResult reject(Path workspace, TaskContract contract, LlmClient client,
                                                Map<String, String> sources) {
        var seed = ReadOnlyFinalizationEvidenceTestSupport.recordedState(workspace, contract, sources);
        var state = new LoopState("", List.of(), new ArrayList<>(seed.messages), workspace,
                Context.builder(new Config()).llm(client).build(), null, 10, 0);
        state.seedReadOnlyEvidence(ReadOnlyEvidenceHandoff.capture(seed));
        for (var source : sources.entrySet()) {
            state.toolNames.add("talos.read_file");
            state.toolOutcomes.add(new ToolCallLoop.ToolOutcome("talos.read_file", source.getKey(),
                    true, false, false, source.getValue(), ""));
        }
        state.iterations = sources.size();
        state.totalToolsInvoked = sources.size();
        var config = new Config(null);
        var registry = new dev.talos.tools.ToolRegistry();
        registry.register(new DocumentComparisonTool());
        var context = Context.builder(config)
                .sandbox(new dev.talos.core.security.Sandbox(workspace, config.data)).build();
        var processor = new dev.talos.runtime.TurnProcessor(null, new dev.talos.runtime.NoOpApprovalGate(), registry);
        for (var requirement : dev.talos.runtime.task.DocumentComparisonRequest.from(contract).requirements()) {
            var pair = requirement.pair();
            var result = processor.executeTool(new dev.talos.runtime.Session(workspace, config),
                    new dev.talos.tools.ToolCall(DocumentComparisonTool.NAME,
                            Map.of("left", pair.left(), "right", pair.right())), context);
            if (!result.success()) throw new IllegalStateException("Comparison fixture evidence was not established.");
            state.toolNames.add(DocumentComparisonTool.NAME);
            state.toolOutcomes.add(new ToolCallLoop.ToolOutcome(DocumentComparisonTool.NAME, pair.left(),
                    true, false, false, result.output(), ""));
            state.iterations++;
            state.totalToolsInvoked++;
        }
        var selection = ReadOnlyEvidenceContext.select(state);
        if (!selection.complete() || !ReadOnlyFinalizationEvidence.freeze(state, selection).proposalSelected()) {
            throw new IllegalStateException("Eligible observed source coverage was not established.");
        }
        var iteration = new ToolCallExecutionStage.IterationOutcome(0, List.of(), 0, false, false, false, 0);
        ReadOnlyEvidenceSynthesizer.tryHandle(state, iteration);
        ReadOnlyEvidenceSynthesizer.tryHandle(state, iteration);
        return new ToolCallLoop.LoopResult(state.currentText, state.iterations, state.totalToolsInvoked,
                List.copyOf(state.toolNames), state.messages, state.failedCalls, state.retriedCalls, false,
                state.mutatingToolSuccesses, List.copyOf(sources.keySet()), 0, 0, 0, 0,
                state.failureDecision, List.copyOf(state.toolOutcomes), Map.of(), state.currentAnswerOutputLimitReached,
                state.readOnlySynthesisStatus, null, state.readOnlyFinalizationEvidence);
    }
}
