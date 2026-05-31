package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.EvidenceGate;
import dev.talos.runtime.policy.EvidenceObligation;
import dev.talos.runtime.policy.EvidenceObligationVerifier;
import dev.talos.runtime.policy.ProtectedPathPolicy;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.toolcall.ToolCallSupport;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.safety.SafeLogFormatter;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolAliasPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class ReadEvidenceHandoff {
    private static final Logger LOG = LoggerFactory.getLogger(ReadEvidenceHandoff.class);

    private ReadEvidenceHandoff() {}

    record Result(
            String answer,
            ToolCallLoop.LoopResult loopResult,
            String extraSummary
    ) {}

    static Result unsupportedCapabilityPreflightIfNeeded(
            List<ChatMessage> messages,
            CurrentTurnPlan plan,
            Path workspace,
            Context ctx
    ) {
        CurrentTurnPlan safePlan = safePlan(plan, messages);
        if (EvidenceGate.selectObligation(safePlan, workspace, ctx == null ? null : ctx.cfg())
                != EvidenceObligation.UNSUPPORTED_CAPABILITY_CHECK_REQUIRED) {
            return new Result("", null, null);
        }
        TaskContract contract = safePlan.taskContract();
        if (!EvidenceGate.hasOnlyUnsupportedExpectedTargets(contract, ctx == null ? null : ctx.cfg())) {
            return new Result("", null, null);
        }
        TurnTaskContractCapture.set(contract);
        try {
            return readEvidenceHandoffIfNeeded("", messages, safePlan, workspace, ctx);
        } finally {
            TurnTaskContractCapture.clear();
        }
    }

    static Result readEvidenceHandoffIfNeeded(
            String answer,
            List<ChatMessage> messages,
            CurrentTurnPlan plan,
            Path workspace,
            Context ctx
    ) {
        if (answer == null) answer = "";
        CurrentTurnPlan safePlan = safePlan(plan, messages);
        TaskContract contract = safePlan.taskContract();
        EvidenceObligation obligation = EvidenceGate.selectObligation(
                safePlan,
                workspace,
                ctx == null ? null : ctx.cfg());
        if (!EvidenceGate.requiresReadEvidenceHandoff(obligation)) {
            return new Result(answer, null, null);
        }
        if (contract.mutationRequested() || contract.mutationAllowed()) {
            return new Result(answer, null, null);
        }
        if (ctx == null || ctx.llm() == null || ctx.toolCallLoop() == null || workspace == null) {
            return new Result(answer, null, null);
        }

        if (obligation == EvidenceObligation.PROTECTED_READ_APPROVAL_REQUIRED
                && !EvidenceGate.hasExplicitProtectedReadIntent(
                contract,
                EvidenceGate.protectedExpectedTargets(contract, workspace))) {
            return new Result(answer, null, null);
        }
        List<String> targets = EvidenceGate.handoffTargets(
                contract,
                obligation,
                workspace,
                ctx == null ? null : ctx.cfg());
        if (targets.isEmpty()) {
            return new Result(answer, null, null);
        }

        String handoffCalls = targets.stream()
                .map(ReadEvidenceHandoff::readFileToolCallJson)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        try {
            ToolCallLoop.LoopResult loop = ctx.toolCallLoop().run(
                    handoffCalls,
                    messages,
                    workspace,
                    ctx);
            String mergedAnswer = loop.finalAnswer();
            return new Result(
                    mergedAnswer == null || mergedAnswer.isBlank() ? answer : mergedAnswer,
                    loop,
                    loop.summary());
        } catch (Exception e) {
            LOG.warn("Read evidence handoff failed: {}", SafeLogFormatter.throwableMessage(e));
            return new Result(answer, null, null);
        }
    }

    static Result readEvidenceRecoveryForPartialTargetsIfNeeded(
            String answer,
            List<ChatMessage> messages,
            CurrentTurnPlan plan,
            ToolCallLoop.LoopResult loopResult,
            Path workspace,
            Context ctx
    ) {
        CurrentTurnPlan safePlan = safePlan(plan, messages);
        TaskContract contract = safePlan.taskContract();
        EvidenceObligation obligation = EvidenceGate.selectObligation(
                safePlan,
                workspace,
                ctx == null ? null : ctx.cfg());
        if (obligation != EvidenceObligation.READ_TARGET_REQUIRED
                && obligation != EvidenceObligation.PATH_EXISTENCE_EVIDENCE_REQUIRED) {
            return new Result(answer, null, null);
        }
        if (contract.mutationRequested() || contract.mutationAllowed()) {
            return new Result(answer, null, null);
        }
        if (loopResult == null || loopResult.toolOutcomes() == null || loopResult.toolOutcomes().isEmpty()) {
            return new Result(answer, null, null);
        }
        if (loopResult.failureDecision() != null && loopResult.failureDecision().shouldStop()) {
            return new Result(answer, null, null);
        }
        Set<String> targets = evidenceTargets(contract);
        if (deniedOutcomesBlockReadEvidenceRecovery(loopResult.toolOutcomes(), targets, workspace)) {
            return new Result(answer, null, null);
        }
        EvidenceObligationVerifier.Result evidence = EvidenceObligationVerifier.verify(
                obligation,
                targets,
                loopResult.toolOutcomes(),
                workspace);
        if (evidence.status() != EvidenceObligationVerifier.Status.UNSATISFIED) {
            return new Result(answer, null, null);
        }
        return readEvidenceHandoffIfNeeded("", messages, safePlan, workspace, ctx);
    }

    private static boolean deniedOutcomesBlockReadEvidenceRecovery(
            List<ToolCallLoop.ToolOutcome> outcomes,
            Set<String> evidenceTargets,
            Path workspace
    ) {
        if (outcomes == null || outcomes.isEmpty()) return false;
        for (ToolCallLoop.ToolOutcome outcome : outcomes) {
            if (outcome == null || !outcome.denied()) continue;
            String deniedPath = ToolCallSupport.normalizePath(outcome.pathHint());
            if (deniedPath.isBlank()) return true;
            if (matchesEvidenceTarget(deniedPath, evidenceTargets)) return true;
            if (!"talos.read_file".equals(canonicalToolName(outcome.toolName()))) return true;
            if (workspace == null || !ProtectedPathPolicy.classify(workspace, deniedPath).protectedPath()) return true;
        }
        return false;
    }

    private static boolean matchesEvidenceTarget(String normalizedPath, Set<String> evidenceTargets) {
        if (normalizedPath == null || normalizedPath.isBlank() || evidenceTargets == null) return false;
        for (String target : evidenceTargets) {
            if (normalizedPath.equals(ToolCallSupport.normalizePath(target))) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> evidenceTargets(TaskContract contract) {
        if (contract == null) return Set.of();
        if (!contract.sourceEvidenceTargets().isEmpty()) {
            return contract.sourceEvidenceTargets();
        }
        return contract.expectedTargets();
    }

    private static String readFileToolCallJson(String target) {
        return "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\""
                + jsonEscape(target)
                + "\"}}";
    }

    private static String jsonEscape(String value) {
        if (value == null || value.isBlank()) return "";
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static String canonicalToolName(String toolName) {
        ToolAliasPolicy.Decision decision = ToolAliasPolicy.resolve(toolName);
        if (decision.accepted() && decision.canonicalToolName() != null && !decision.canonicalToolName().isBlank()) {
            return decision.canonicalToolName();
        }
        return toolName == null ? "" : toolName;
    }

    private static CurrentTurnPlan safePlan(CurrentTurnPlan plan, List<ChatMessage> messages) {
        if (plan != null) return plan;
        TaskContract contract = TaskContractResolver.fromMessages(messages);
        return CurrentTurnPlan.compatibility(contract, ExecutionPhase.INSPECT, List.of(), List.of(), List.of());
    }
}
