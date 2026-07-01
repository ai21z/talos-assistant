package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.llm.LlmClient;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.ToolCallParser;
import dev.talos.runtime.capability.StaticWebCapabilityProfile;
import dev.talos.runtime.outcome.MutationFailureAnswerRenderer;
import dev.talos.runtime.policy.ActionObligation;
import dev.talos.runtime.policy.ConditionalReviewFixPolicy;
import dev.talos.runtime.policy.ResponseObligationVerifier;
import dev.talos.runtime.repair.RepairPolicy;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.toolcall.ToolCallSupport;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.runtime.workspace.WorkspaceOperationIntent;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import dev.talos.safety.SafeLogFormatter;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Missing-mutation retry gate and compact retry envelope. */
final class MissingMutationRetry {
    private static final Logger LOG = LoggerFactory.getLogger(MissingMutationRetry.class);

    private static final String COMPACT_MUTATION_RETRY_SYSTEM_PROMPT = """
            Talos bounded mutation retry.
            Use only listed tools. Do not claim changes unless the required mutation or workspace operation tool succeeds.
            """;

    private MissingMutationRetry() {}

    @FunctionalInterface
    interface ChatFunction {
        LlmClient.StreamResult chat(
                List<ChatMessage> messages,
                CurrentTurnPlan plan,
                List<ToolSpec> toolSpecs
        ) throws Exception;
    }

    /** Result of the missing-mutation retry gate. */
    record Result(
            String answer,
            int mutationsInRetry,
            String extraSummary,
            ToolCallLoop.LoopResult retryLoopResult,
            boolean actionObligationFailed
    ) {
        Result(String answer, int mutationsInRetry, String extraSummary) {
            this(answer, mutationsInRetry, extraSummary, null, false);
        }

        Result(
                String answer,
                int mutationsInRetry,
                String extraSummary,
                ToolCallLoop.LoopResult retryLoopResult
        ) {
            this(answer, mutationsInRetry, extraSummary, retryLoopResult, false);
        }
    }

    static Result retryIfNeeded(
            String answer,
            List<ChatMessage> messages,
            CurrentTurnPlan safePlan,
            ToolCallLoop.LoopResult loopResult,
            Path workspace,
            Context ctx,
            ChatFunction chat
    ) {
        if (answer == null) answer = "";
        if (loopResult == null) return new Result(answer, 0, null);
        if (loopResult.mutatingToolSuccesses() > 0) return new Result(answer, 0, null);
        if (ctx == null || ctx.llm() == null) return new Result(answer, 0, null);
        if (ctx.toolCallLoop() == null || chat == null) return new Result(answer, 0, null);
        if (hasDeniedMutation(loopResult)) return new Result(answer, 0, null);
        if (loopResult.failureDecision().shouldStop()) return new Result(answer, 0, null);
        // T743: a mutating call that failed with genuinely invalid parameters
        // now gets one bounded corrected retry with the tool error echoed.
        // Previously the model that ATTEMPTED a call got zero retries while a
        // do-nothing response got one - inverted incentives observed in the
        // 0.10.1 banks. Policy/evidence blocks that reuse INVALID_PARAMS
        // (e.g. source-derived write blocked before approval) keep the
        // original suppression - their dedicated repair planners own them.
        String invalidParamsError = firstInvalidMutatingFailureMessage(loopResult);
        if (invalidParamsError == null && hasInvalidMutatingFailure(loopResult)) {
            return new Result(answer, 0, null);
        }

        String userRequest = safePlan.originalUserRequest();
        TaskContract retryContract = safePlan.taskContract();
        if (!retryContract.mutationAllowed()) {
            return new Result(answer, 0, null);
        }
        Optional<String> conditionalNoChange = ConditionalReviewFixPolicy
                .noChangeAnswerIfCurrentWorkspacePasses(retryContract, loopResult, workspace, answer);
        if (conditionalNoChange.isPresent()) {
            return new Result(conditionalNoChange.get(), 0, null);
        }
        ActionObligation obligation = safePlan.actionObligation();
        if (!ResponseObligationVerifier.unsatisfiedNoToolResponse(obligation, answer)) {
            return new Result(answer, 0, null);
        }
        String priorMutationRequest = retryShouldReissuePriorMutationRequest(retryContract)
                ? previousMutationUserRequest(messages, userRequest)
                : null;

        LOG.info("Missing-mutation retry fired: user asked for a change but 0 mutating "
                + "tool calls succeeded. Re-prompting with an explicit write nudge.");

        List<String> retryToolNames = toolNames(safePlan, messages);
        LocalTurnTraceCapture.recordActionObligation(
                obligation.name(),
                "UNSATISFIED",
                invalidParamsError != null
                        ? "mutating tool call failed with invalid parameters; bounded corrected retry"
                        : "model response had no " + requiredToolCallLabel(obligation, retryToolNames));
        String retrySummary = ResponseObligationVerifier.retryFailureSummary(obligation, answer);
        List<ToolSpec> retryToolSpecs = toolSpecs(ctx, retryToolNames);
        String retryInstruction = mutationRetryInstruction(
                obligation,
                userRequest,
                priorMutationRequest,
                retryToolNames);
        if (invalidParamsError != null) {
            retryInstruction = retryInstruction
                    + "\nPrevious mutating tool call was rejected with invalid parameters: "
                    + invalidParamsError
                    + "\nRe-issue one corrected tool call with valid parameters.";
        }
        String retryFrame = compactMutationRetryFrame(safePlan, retryToolSpecs, retryToolNames);
        messages.add(ChatMessage.assistant(retrySummary));
        messages.add(ChatMessage.system(retryFrame));
        messages.add(ChatMessage.user(retryInstruction));
        List<ChatMessage> retryMessages = compactMutationRetryMessages(
                messages, safePlan, retryInstruction, retryToolSpecs, retryToolNames);

        try {
            LlmClient.StreamResult retry = chat.chat(retryMessages, safePlan, retryToolSpecs);
            String retryText = retry.text() == null ? "" : retry.text();

            if (retry.hasToolCalls() || hasAnyTextToolCalls(retryText)) {
                ToolCallLoop.LoopResult retryLoop = ctx.toolCallLoop().run(
                        retryText, retry.toolCalls(), retryMessages, workspace, ctx);
                String mergedAnswer = retryLoop.finalAnswer();
                String summary = retryLoop.summary();
                boolean retryIssuedMutatingTool = retryLoop.toolOutcomes().stream()
                        .anyMatch(ToolCallLoop.ToolOutcome::mutating);
                if (hasDeniedMutation(retryLoop)) {
                    mergedAnswer = MutationFailureAnswerRenderer.summarizeDeniedMutationOutcomesIfNeeded(
                            mergedAnswer, safePlan, messages, retryLoop, 0);
                }
                if (isStaticRepairWrongToolRetry(retryLoop)) {
                    List<String> targets = staticRepairWrongToolTargets(retryLoop);
                    String targetReason = targets.isEmpty() ? "" : " for " + String.join(", ", targets);
                    boolean partialMutation = retryLoop.mutatingToolSuccesses() > 0;
                    LocalTurnTraceCapture.recordActionObligation(
                            obligation.name(),
                            "FAILED",
                            "static repair required talos.write_file but retry used talos.edit_file"
                                    + targetReason,
                            "STATIC_REPAIR_WRONG_TOOL");
                    return new Result(
                            ResponseObligationVerifier.deterministicStaticRepairWrongToolAnswer(
                                    targets, partialMutation),
                            0,
                            summary,
                            retryLoop,
                            true);
                } else if (retryLoop.mutatingToolSuccesses() > 0) {
                    ToolCallLoop.LoopResult continued =
                            continueRemainingExpectedTargetsAfterPartialRetry(
                                    safePlan,
                                    retryLoop,
                                    workspace,
                                    ctx,
                                    chat,
                                    retryToolNames);
                    if (continued != retryLoop) {
                        retryLoop = continued;
                        mergedAnswer = retryLoop.finalAnswer();
                        summary = retryLoop.summary();
                    }
                    LOG.info("Missing-mutation retry succeeded: {} mutation(s) performed.",
                            retryLoop.mutatingToolSuccesses());
                    LocalTurnTraceCapture.recordActionObligation(
                            obligation.name(),
                            "SATISFIED_AFTER_RETRY",
                            "retry response issued " + requiredToolCallLabel(obligation, retryToolNames));
                } else if (hasDeniedMutation(retryLoop)) {
                    LocalTurnTraceCapture.recordActionObligation(
                            obligation.name(),
                            "BLOCKED_AFTER_RETRY",
                            "retry response issued mutating tool calls but policy blocked them");
                } else if (retryIssuedMutatingTool) {
                    if (hasInvalidMutatingFailure(retryLoop)) {
                        LocalTurnTraceCapture.recordActionObligation(
                                obligation.name(),
                                "FAILED",
                                "retry response issued invalid mutating tool arguments",
                                "INVALID_MUTATION_AFTER_RETRY");
                        return new Result(
                                mergedAnswer == null || mergedAnswer.isBlank() ? answer : mergedAnswer,
                                0,
                                summary,
                                retryLoop,
                                false);
                    }
                    List<String> failedTargets = failedMutatingToolTargets(retryLoop);
                    LocalTurnTraceCapture.recordActionObligation(
                            obligation.name(),
                            "FAILED",
                            "retry response issued mutating tool calls but no mutation completed"
                                    + (failedTargets.isEmpty()
                                    ? ""
                                    : " for " + String.join(", ", failedTargets)),
                            "CONDITIONAL_REVIEW_FAILED_MUTATION");
                    return new Result(
                            ResponseObligationVerifier.deterministicFailedMutationAttemptAnswer(failedTargets),
                            0,
                            summary,
                            retryLoop,
                            true);
                } else {
                    boolean repairInspectionOnly = isRepairInspectionOnlyRetry(safePlan, retryLoop);
                    String failureReason = repairInspectionOnly
                            ? "repair/fix retry response used only read-only inspection tools"
                            : "retry response issued tool calls but no "
                            + requiredToolCallLabel(obligation, retryToolNames);
                    String failureKind = repairInspectionOnly ? "REPAIR_INSPECTION_ONLY" : "";
                    if (repairInspectionOnly) {
                        LocalTurnTraceCapture.recordActionObligation(
                                obligation.name(),
                                "FAILED",
                                failureReason,
                                failureKind);
                    } else {
                        LocalTurnTraceCapture.recordActionObligation(
                                obligation.name(),
                                "FAILED",
                                failureReason);
                    }
                    return new Result(
                            repairInspectionOnly
                                    ? ResponseObligationVerifier.deterministicRepairInspectionOnlyAnswer()
                                    : ResponseObligationVerifier.deterministicNoActionAnswer(obligation),
                            0,
                            summary,
                            retryLoop,
                            true);
                }
                return new Result(
                        mergedAnswer == null || mergedAnswer.isBlank() ? answer : mergedAnswer,
                        retryLoop.mutatingToolSuccesses(),
                        summary,
                        retryLoop);
            }

            if (!retryText.isBlank() && !retryText.equals(answer)) {
                String deterministic = ResponseObligationVerifier.deterministicNoActionAnswer(obligation);
                LocalTurnTraceCapture.recordActionObligation(
                        obligation.name(),
                        "FAILED",
                        "retry response still had no " + requiredToolCallLabel(obligation, retryToolNames));
                return new Result(deterministic, 0, null, null, true);
            }
        } catch (EngineException.ContextBudgetExceeded budget) {
            String detail = ResponseObligationVerifier.contextBudgetRetrySkippedDetail(budget);
            LOG.info("Skipping missing-mutation retry because it exceeded the local context budget.");
            LocalTurnTraceCapture.warning("CONTEXT_BUDGET_RETRY_SKIPPED", detail);
            LocalTurnTraceCapture.recordActionObligation(
                    obligation.name(),
                    "FAILED",
                    detail,
                    "CONTEXT_BUDGET_RETRY_SKIPPED");
            return new Result(
                    ResponseObligationVerifier.deterministicContextBudgetRetrySkippedAnswer(
                            "missing-mutation retry", budget),
                    0,
                    null,
                    null,
                    true);
        } catch (Exception e) {
            LOG.warn("Missing-mutation retry failed: {}", SafeLogFormatter.throwableMessage(e));
        }
        LocalTurnTraceCapture.recordActionObligation(
                obligation.name(),
                "FAILED",
                "retry failed before " + requiredToolCallLabel(obligation, retryToolNames) + " executed");
        return new Result(
                ResponseObligationVerifier.deterministicNoActionAnswer(obligation),
                0,
                null,
                null,
                true);
    }

    static List<ToolSpec> toolSpecs(Context ctx, List<String> allowed) {
        List<ToolSpec> base = requestToolSpecsForControls(ctx);
        if (base.isEmpty()) return base;
        List<ToolSpec> narrowed = filterToolSpecs(base, allowed);
        return narrowed.isEmpty() ? List.of() : compactMutationRetryToolSpecs(narrowed);
    }

    static ChatMessage compactStaticVerificationRepairInstructionForRetry(ChatMessage message) {
        if (message == null || message.content() == null) {
            return message;
        }
        String content = message.content();
        if (!content.startsWith("[Static verification repair context]")) {
            return message;
        }

        String expectedTargets = firstRepairContextValue(content, "Expected targets:");
        String missingTargets = firstRepairContextValue(content, "Missing expected targets:");
        String fullWriteTargets = firstRepairContextValue(content, "Full-file replacement targets:");
        String staticWebRequirements = repairContextSectionKeyValues(
                content,
                "[StaticWebRequirements]",
                4);
        List<String> problems = repairContextSectionBullets(
                content,
                "Previous static verification problems:",
                6);
        List<String> similarTargets = repairContextSectionBullets(
                content,
                "Similar changed targets that do not satisfy missing expected targets:",
                4);
        List<String> cssSelectorConstraint = repairContextSectionBullets(
                content,
                "CSS selector repair constraint:",
                4);
        String currentSelectorFacts = repairContextSectionLines(
                content,
                "[Current static selector facts]",
                18);

        if (fullWriteTargets.isBlank()) {
            Set<String> parsed = RepairPolicy.fullRewriteTargetsFromRepairContext(List.of(message));
            if (!parsed.isEmpty()) {
                fullWriteTargets = String.join(", ", parsed.stream().sorted().toList());
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("[Static verification repair context]\n")
                .append("Previous mutation task ended incomplete after static verification.\n");
        if (!expectedTargets.isBlank()) {
            out.append("\nExpected targets: ").append(expectedTargets).append('\n');
        }
        if (!missingTargets.isBlank()) {
            out.append("\nMissing expected targets: ").append(missingTargets).append('\n');
        }
        if (!staticWebRequirements.isBlank()) {
            out.append("\n[StaticWebRequirements]\n")
                    .append(staticWebRequirements)
                    .append('\n');
        }
        if (!similarTargets.isEmpty()) {
            out.append("\nSimilar changed targets that do not satisfy missing expected targets:\n");
            similarTargets.forEach(line -> out.append(line).append('\n'));
        }
        if (!problems.isEmpty()) {
            out.append("\nPrevious static verification problems:\n");
            problems.forEach(line -> out.append(line).append('\n'));
        }
        out.append("\nRepair plan:\n");
        if (!fullWriteTargets.isBlank()) {
            out.append("Full-file replacement targets: ").append(fullWriteTargets).append('\n')
                    .append("Use talos.write_file with complete corrected content for these targets.\n");
        }
        if (!cssSelectorConstraint.isEmpty()) {
            out.append("\nCSS selector repair constraint:\n");
            cssSelectorConstraint.forEach(line -> out.append(line).append('\n'));
        }
        if (!currentSelectorFacts.isBlank() && selectorDiagnosticsAreControlling(problems, cssSelectorConstraint)) {
            out.append("\n[Current static selector facts]\n")
                    .append(currentSelectorFacts)
                    .append('\n');
        }
        out.append("Preserve exact target spelling; script.js and scripts.js are different paths.\n")
                .append("After tool-backed changes, answer only from tool results and static verification.");
        return ChatMessage.system(out.toString());
    }

    private static boolean selectorDiagnosticsAreControlling(
            List<String> problems,
            List<String> cssSelectorConstraint
    ) {
        if (cssSelectorConstraint != null && !cssSelectorConstraint.isEmpty()) return true;
        if (problems == null || problems.isEmpty()) return false;
        for (String problem : problems) {
            String lower = problem == null ? "" : problem.toLowerCase(Locale.ROOT);
            if (lower.contains("selector")
                    || lower.contains("class selectors")
                    || lower.contains("missing class")
                    || lower.contains("missing ids")
                    || lower.contains("duplicate id")) {
                return true;
            }
        }
        return false;
    }

    static ToolCallLoop.LoopResult mergeEvidence(
            ToolCallLoop.LoopResult original,
            ToolCallLoop.LoopResult retry
    ) {
        if (retry == null) return original;
        if (original == null) return retry;
        List<String> mergedReadPaths = mergeReadPaths(original.readPaths(), retry.readPaths());
        LinkedHashSet<String> mergedToolNames = new LinkedHashSet<>();
        if (original.toolNames() != null) mergedToolNames.addAll(original.toolNames());
        if (retry.toolNames() != null) mergedToolNames.addAll(retry.toolNames());
        List<ToolCallLoop.ToolOutcome> mergedOutcomes = new ArrayList<>();
        if (original.toolOutcomes() != null) mergedOutcomes.addAll(original.toolOutcomes());
        if (retry.toolOutcomes() != null) mergedOutcomes.addAll(retry.toolOutcomes());
        List<ChatMessage> mergedMessages = new ArrayList<>();
        if (original.messages() != null) mergedMessages.addAll(original.messages());
        if (retry.messages() != null) mergedMessages.addAll(retry.messages());
        Map<String, String> mergedReadFileBodies = new LinkedHashMap<>();
        if (original.readFileBodies() != null) mergedReadFileBodies.putAll(original.readFileBodies());
        if (retry.readFileBodies() != null) mergedReadFileBodies.putAll(retry.readFileBodies());
        return new ToolCallLoop.LoopResult(
                retry.finalAnswer(),
                original.iterations() + retry.iterations(),
                original.toolsInvoked() + retry.toolsInvoked(),
                List.copyOf(mergedToolNames),
                List.copyOf(mergedMessages),
                original.failedCalls() + retry.failedCalls(),
                original.retriedCalls() + retry.retriedCalls(),
                original.hitIterLimit() || retry.hitIterLimit(),
                original.mutatingToolSuccesses() + retry.mutatingToolSuccesses(),
                mergedReadPaths,
                original.cushionFiresRedundantRead() + retry.cushionFiresRedundantRead(),
                original.cushionFiresAliasRescue() + retry.cushionFiresAliasRescue(),
                original.cushionFiresB3EditShortCircuit() + retry.cushionFiresB3EditShortCircuit(),
                original.cushionFiresE1Suggestion() + retry.cushionFiresE1Suggestion(),
                retry.failureDecision(),
                mergedOutcomes,
                mergedReadFileBodies);
    }

    private static ToolCallLoop.LoopResult continueRemainingExpectedTargetsAfterPartialRetry(
            CurrentTurnPlan plan,
            ToolCallLoop.LoopResult retryLoop,
            Path workspace,
            Context ctx,
            ChatFunction chat,
            List<String> retryToolNames
    ) {
        List<String> remainingTargets = remainingExpectedTargetsAfterRetry(plan, retryLoop);
        if (remainingTargets.isEmpty()) return retryLoop;
        if (ctx == null || ctx.toolCallLoop() == null || chat == null) return retryLoop;

        List<ToolSpec> continuationToolSpecs = toolSpecs(ctx, retryToolNames);
        if (continuationToolSpecs.isEmpty()) return retryLoop;
        List<ChatMessage> continuationMessages = remainingExpectedTargetContinuationMessages(
                plan,
                remainingTargets);
        try {
            LlmClient.StreamResult continuation =
                    chat.chat(continuationMessages, plan, continuationToolSpecs);
            String continuationText = continuation.text() == null ? "" : continuation.text();
            if (!continuation.hasToolCalls() && !ToolCallParser.containsToolCalls(continuationText)) {
                return retryLoop;
            }
            ToolCallLoop.LoopResult continuationLoop = ctx.toolCallLoop().run(
                    continuationText,
                    continuation.toolCalls(),
                    new ArrayList<>(continuationMessages),
                    workspace,
                    ctx);
            return mergeEvidence(retryLoop, continuationLoop);
        } catch (Exception e) {
            LOG.warn("Remaining expected-target continuation after missing-mutation retry failed: {}",
                    SafeLogFormatter.throwableMessage(e));
            return retryLoop;
        }
    }

    private static List<ChatMessage> remainingExpectedTargetContinuationMessages(
            CurrentTurnPlan plan,
            List<String> remainingTargets
    ) {
        String request = plan == null || plan.originalUserRequest() == null
                ? ""
                : plan.originalUserRequest().strip();
        String targets = String.join(", ", remainingTargets);
        StringBuilder frame = new StringBuilder();
        frame.append("[RemainingExpectedTargetsAfterMutationRetry]\n")
                .append("A bounded mutation retry changed some files, but required expected target progress ")
                .append("is still incomplete.\n")
                .append("Remaining expected target(s): ").append(targets).append('\n')
                .append("Write or edit only these remaining exact target path(s). ")
                .append("Similar filenames are not substitutes.\n");
        if (!request.isBlank()) {
            frame.append("[CurrentRequest]\n")
                    .append(request)
                    .append('\n');
        }
        return List.of(
                ChatMessage.system(COMPACT_MUTATION_RETRY_SYSTEM_PROMPT),
                ChatMessage.system(frame.toString()),
                ChatMessage.user("Continue the same mutation task. Remaining expected target(s): "
                        + targets
                        + ". Call write_file/edit_file for these remaining exact path(s) only."));
    }

    private static List<String> remainingExpectedTargetsAfterRetry(
            CurrentTurnPlan plan,
            ToolCallLoop.LoopResult retryLoop
    ) {
        TaskContract contract = plan == null ? null : plan.taskContract();
        if (contract == null || contract.expectedTargets().isEmpty()) return List.of();
        if (retryLoop == null || retryLoop.mutatingToolSuccesses() <= 0) return List.of();

        Set<String> satisfied = new LinkedHashSet<>();
        for (ToolCallLoop.ToolOutcome outcome : retryLoop.toolOutcomes()) {
            if (outcome == null || !outcome.success() || !outcome.mutating()) continue;
            WorkspaceOperationPlan operationPlan = outcome.workspaceOperationPlan();
            if (operationPlan != null && !operationPlan.pathEffects().isEmpty()) {
                for (WorkspaceOperationPlan.PathEffect effect : operationPlan.pathEffects()) {
                    addSatisfiedExpectedTarget(satisfied, effect.path());
                }
            } else {
                addSatisfiedExpectedTarget(satisfied, outcome.pathHint());
            }
        }
        return orderedExpectedTargets(contract).stream()
                .filter(target -> !satisfied.contains(expectedTargetKey(target)))
                .toList();
    }

    private static void addSatisfiedExpectedTarget(Set<String> satisfied, String path) {
        String key = expectedTargetKey(path);
        if (key.isBlank()) return;
        satisfied.add(key);
        int slash = key.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < key.length()) {
            satisfied.add(key.substring(slash + 1));
        }
    }

    private static String expectedTargetKey(String path) {
        return ToolCallSupport.normalizePath(path).toLowerCase(Locale.ROOT);
    }

    private static List<String> failedMutatingToolTargets(ToolCallLoop.LoopResult retryLoop) {
        if (retryLoop == null || retryLoop.toolOutcomes() == null) return List.of();
        return retryLoop.toolOutcomes().stream()
                .filter(outcome -> outcome != null
                        && outcome.mutating()
                        && !outcome.success()
                        && !outcome.denied())
                .map(ToolCallLoop.ToolOutcome::pathHint)
                .filter(path -> path != null && !path.isBlank())
                .map(ToolCallSupport::normalizePath)
                .filter(path -> !path.isBlank())
                .distinct()
                .toList();
    }

    private static List<String> toolNames(CurrentTurnPlan plan, List<ChatMessage> messages) {
        TaskContract contract = plan == null ? null : plan.taskContract();
        Optional<WorkspaceOperationIntent.Intent> workspaceOperation = WorkspaceOperationIntent.detect(contract);
        if (workspaceOperation.isPresent()) {
            return workspaceOperation.get().toolNames();
        }
        if (StaticWebCapabilityProfile.prefersFullFileWriteForInitialApply(contract)) {
            return List.of("talos.write_file");
        }
        return RepairPolicy.fullRewriteTargetsFromRepairContext(messages).isEmpty()
                ? List.of("talos.write_file", "talos.edit_file")
                : List.of("talos.write_file");
    }

    private static String requiredToolCallLabel(ActionObligation obligation, List<String> toolNames) {
        if (obligation == ActionObligation.WORKSPACE_OPERATION_REQUIRED) {
            String tools = toolNames == null || toolNames.isEmpty()
                    ? "workspace operation"
                    : String.join("/", toolNames);
            return tools + " workspace operation tool calls";
        }
        return "write/edit tool calls";
    }

    private static List<ToolSpec> requestToolSpecsForControls(Context ctx) {
        if (ctx != null && ctx.nativeToolSpecs() != null) return ctx.nativeToolSpecs();
        if (ctx != null && ctx.llm() != null) return ctx.llm().getToolSpecs();
        return List.of();
    }

    private static List<ToolSpec> filterToolSpecs(List<ToolSpec> specs, List<String> allowedNames) {
        if (specs == null || specs.isEmpty() || allowedNames == null || allowedNames.isEmpty()) {
            return List.of();
        }
        return specs.stream()
                .filter(Objects::nonNull)
                .filter(spec -> allowedNames.contains(spec.name()))
                .toList();
    }

    private static List<ToolSpec> compactMutationRetryToolSpecs(List<ToolSpec> specs) {
        if (specs == null || specs.isEmpty()) return List.of();
        return specs.stream()
                .filter(Objects::nonNull)
                .map(MissingMutationRetry::compactMutationRetryToolSpec)
                .toList();
    }

    private static ToolSpec compactMutationRetryToolSpec(ToolSpec spec) {
        if (spec == null) return null;
        return switch (spec.name()) {
            case "talos.write_file" -> new ToolSpec(
                    "talos.write_file",
                    "Write file.",
                    "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}},\"required\":[\"path\",\"content\"]}");
            case "talos.edit_file" -> new ToolSpec(
                    "talos.edit_file",
                    "Edit exact text.",
                    "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"old_string\":{\"type\":\"string\"},\"new_string\":{\"type\":\"string\"}},\"required\":[\"path\",\"old_string\",\"new_string\"]}");
            default -> spec;
        };
    }

    private static List<ChatMessage> compactMutationRetryMessages(
            List<ChatMessage> messages,
            CurrentTurnPlan plan,
            String retryInstruction,
            List<ToolSpec> retryToolSpecs,
            List<String> fallbackToolNames
    ) {
        List<ChatMessage> out = new ArrayList<>();
        out.add(ChatMessage.system(COMPACT_MUTATION_RETRY_SYSTEM_PROMPT));
        if (messages != null) {
            lastStaticVerificationRepairInstruction(messages)
                    .map(MissingMutationRetry::compactStaticVerificationRepairInstructionForRetry)
                    .ifPresent(out::add);
        }
        out.add(ChatMessage.system(compactMutationRetryFrame(plan, retryToolSpecs, fallbackToolNames)));
        out.add(ChatMessage.user(retryInstruction));
        return out;
    }

    private static String firstRepairContextValue(String content, String prefix) {
        if (content == null || prefix == null || prefix.isBlank()) {
            return "";
        }
        String prefixLower = prefix.toLowerCase(Locale.ROOT);
        for (String rawLine : content.split("\\R")) {
            String line = rawLine.strip();
            if (line.toLowerCase(Locale.ROOT).startsWith(prefixLower)) {
                return line.substring(prefix.length()).strip();
            }
        }
        return "";
    }

    private static List<String> repairContextSectionBullets(
            String content,
            String sectionHeader,
            int maxLines
    ) {
        if (content == null || sectionHeader == null || sectionHeader.isBlank() || maxLines <= 0) {
            return List.of();
        }
        String sectionLower = sectionHeader.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        boolean inSection = false;
        for (String rawLine : content.split("\\R")) {
            String line = rawLine.strip();
            if (!inSection) {
                if (line.toLowerCase(Locale.ROOT).equals(sectionLower)) {
                    inSection = true;
                }
                continue;
            }
            if (line.isBlank()) {
                if (!out.isEmpty()) break;
                continue;
            }
            if (!line.startsWith("- ")) {
                break;
            }
            out.add(line);
            if (out.size() >= maxLines) {
                break;
            }
        }
        return out;
    }

    private static String repairContextSectionLines(
            String content,
            String sectionHeader,
            int maxLines
    ) {
        if (content == null || sectionHeader == null || sectionHeader.isBlank() || maxLines <= 0) {
            return "";
        }
        String sectionLower = sectionHeader.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        boolean inSection = false;
        for (String rawLine : content.split("\\R")) {
            String line = rawLine.stripTrailing();
            if (!inSection) {
                if (line.strip().toLowerCase(Locale.ROOT).equals(sectionLower)) {
                    inSection = true;
                }
                continue;
            }
            if (line.strip().startsWith("[") && !out.isEmpty()) {
                break;
            }
            out.add(line.strip());
            if (out.size() >= maxLines) {
                break;
            }
        }
        return String.join("\n", out).strip();
    }

    private static String repairContextSectionKeyValues(
            String content,
            String sectionHeader,
            int maxLines
    ) {
        if (content == null || sectionHeader == null || sectionHeader.isBlank() || maxLines <= 0) {
            return "";
        }
        String sectionLower = sectionHeader.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        boolean inSection = false;
        for (String rawLine : content.split("\\R")) {
            String line = rawLine.strip();
            if (!inSection) {
                if (line.toLowerCase(Locale.ROOT).equals(sectionLower)) {
                    inSection = true;
                }
                continue;
            }
            if (line.isBlank()) {
                if (!out.isEmpty()) break;
                continue;
            }
            if (!line.contains(":")) {
                break;
            }
            out.add(line);
            if (out.size() >= maxLines) {
                break;
            }
        }
        return String.join("\n", out).strip();
    }

    private static String compactMutationRetryFrame(
            CurrentTurnPlan plan,
            List<ToolSpec> retryToolSpecs,
            List<String> fallbackToolNames
    ) {
        TaskContract contract = plan == null ? TaskContract.unknown("") : plan.taskContract();
        ActionObligation obligation = plan == null ? ActionObligation.UNKNOWN : plan.actionObligation();
        String request = plan == null ? "" : Objects.toString(plan.originalUserRequest(), "");
        List<String> allowedTools = retryToolSpecs == null || retryToolSpecs.isEmpty()
                ? (fallbackToolNames == null || fallbackToolNames.isEmpty()
                ? List.of("talos.write_file", "talos.edit_file")
                : fallbackToolNames)
                : retryToolSpecs.stream()
                .filter(Objects::nonNull)
                .map(ToolSpec::name)
                .sorted()
                .toList();

        StringBuilder frame = new StringBuilder();
        frame.append("[MutationRetryCapability]\n")
                .append("type: ").append(contract.type().name()).append('\n')
                .append("obligation: ").append(obligation == null ? ActionObligation.UNKNOWN.name() : obligation.name()).append('\n')
                .append("tools: ").append(String.join(", ", allowedTools)).append('\n')
                .append("Current request only. Prose/manual snippets do not change files.\n");
        appendCompactRetryExpectedTargets(frame, contract);
        appendCompactRetryStaticWebRequirements(frame, contract);
        appendCompactRetryExpectations(frame, plan);
        if (!request.isBlank()) {
            frame.append("[CurrentRequest]\n")
                    .append(request.strip())
                    .append('\n');
        }
        return frame.toString();
    }

    private static void appendCompactRetryExpectedTargets(StringBuilder frame, TaskContract contract) {
        if (frame == null || contract == null || contract.expectedTargets().isEmpty()) {
            return;
        }
        List<String> targets = orderedExpectedTargets(contract);
        frame.append("[ExpectedTargets]\n")
                .append("requiredTargets: ").append(String.join(", ", targets)).append('\n')
                .append("Exact paths required; similar names do not count.\n")
                .append("script.js and scripts.js are different target paths; preserve the exact requested spelling.\n");
    }

    private static void appendCompactRetryStaticWebRequirements(StringBuilder frame, TaskContract contract) {
        if (frame == null
                || contract == null
                || contract.staticWebRequirements().isEmpty()) {
            return;
        }
        var requirements = contract.staticWebRequirements();
        frame.append("[StaticWebRequirements]\n");
        if (!requirements.requiredVisibleFacts().isEmpty()) {
            frame.append("requiredVisibleFacts: ")
                    .append(String.join(", ", requirements.requiredVisibleFacts()))
                    .append('\n')
                    .append("Preserve these facts as visible site content; do not invent replacements.\n");
        }
        if (!requirements.forbiddenArtifacts().isEmpty()) {
            frame.append("forbiddenArtifacts: ")
                    .append(String.join(", ", requirements.forbiddenArtifacts().stream().sorted().toList()))
                    .append('\n')
                    .append("Do not create, edit, or rely on these forbidden local artifacts.\n");
        }
    }

    private static List<String> orderedExpectedTargets(TaskContract contract) {
        if (contract == null || contract.expectedTargets().isEmpty()) {
            return List.of();
        }
        String request = contract.originalUserRequest() == null
                ? ""
                : contract.originalUserRequest().toLowerCase(Locale.ROOT);
        return contract.expectedTargets().stream()
                .sorted(Comparator
                        .comparingInt((String target) -> targetIndex(request, target))
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
    }

    private static int targetIndex(String requestLower, String target) {
        if (requestLower == null || requestLower.isBlank() || target == null) {
            return Integer.MAX_VALUE;
        }
        int index = requestLower.indexOf(target.toLowerCase(Locale.ROOT));
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    private static void appendCompactRetryExpectations(StringBuilder frame, CurrentTurnPlan plan) {
        if (frame == null || plan == null || plan.taskExpectations().isEmpty()) {
            return;
        }
        frame.append("[TaskExpectations]\n")
                .append("Current-turn exact write expectations remain active. ")
                .append("Use the latest user request literal payload exactly; do not reuse older literals.\n");
    }

    private static Optional<ChatMessage> lastStaticVerificationRepairInstruction(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return Optional.empty();
        ChatMessage found = null;
        for (ChatMessage message : messages) {
            if (isStaticVerificationRepairInstruction(message)) {
                found = message;
            }
        }
        return Optional.ofNullable(found);
    }

    private static boolean isStaticVerificationRepairInstruction(ChatMessage message) {
        return message != null
                && message.content() != null
                && message.content().startsWith("[Static verification repair context]");
    }

    private static boolean isRepairInspectionOnlyRetry(
            CurrentTurnPlan plan,
            ToolCallLoop.LoopResult retryLoop
    ) {
        if (plan == null || retryLoop == null || retryLoop.toolsInvoked() <= 0) return false;
        if (!isRepairOrFixContract(plan.taskContract())) return false;
        if (retryLoop.toolOutcomes() == null || retryLoop.toolOutcomes().isEmpty()) {
            return retryLoop.toolNames().stream().anyMatch(ToolCallSupport::isReadOnlyTool)
                    && retryLoop.toolNames().stream().noneMatch(ToolCallSupport::isMutatingTool);
        }
        boolean sawReadOnly = false;
        for (ToolCallLoop.ToolOutcome outcome : retryLoop.toolOutcomes()) {
            if (outcome == null) continue;
            String toolName = outcome.toolName();
            if (ToolCallSupport.isMutatingTool(toolName) || outcome.mutating()) {
                return false;
            }
            if (ToolCallSupport.isReadOnlyTool(toolName)) {
                sawReadOnly = true;
            }
        }
        return sawReadOnly;
    }

    private static boolean isStaticRepairWrongToolRetry(ToolCallLoop.LoopResult retryLoop) {
        if (retryLoop == null) return false;
        if (retryLoop.toolOutcomes() != null
                && retryLoop.toolOutcomes().stream()
                .anyMatch(ToolCallLoop.ToolOutcome::fullRewriteRepairRedirect)) {
            return true;
        }
        String reason = retryLoop.failureDecision() == null ? "" : retryLoop.failureDecision().reason();
        return reason.contains("STATIC_REPAIR_TARGETS_REMAINING")
                && reason.contains("Static web repair requires talos.write_file")
                && reason.contains("talos.edit_file");
    }

    private static List<String> staticRepairWrongToolTargets(ToolCallLoop.LoopResult retryLoop) {
        if (retryLoop == null || retryLoop.toolOutcomes() == null) return List.of();
        List<String> outcomeTargets = retryLoop.toolOutcomes().stream()
                .filter(ToolCallLoop.ToolOutcome::fullRewriteRepairRedirect)
                .map(ToolCallLoop.ToolOutcome::pathHint)
                .filter(path -> path != null && !path.isBlank())
                .distinct()
                .toList();
        if (!outcomeTargets.isEmpty()) {
            return outcomeTargets;
        }
        return staticRepairWrongToolTargetsFromFailureReason(
                retryLoop.failureDecision() == null ? "" : retryLoop.failureDecision().reason());
    }

    private static List<String> staticRepairWrongToolTargetsFromFailureReason(String reason) {
        if (reason == null || reason.isBlank()) return List.of();
        String marker = "Remaining target(s): ";
        int start = reason.indexOf(marker);
        if (start < 0) return List.of();
        start += marker.length();
        int end = reason.indexOf(". Static web repair", start);
        if (end < 0) return List.of();
        String targetList = reason.substring(start, end).strip();
        if (targetList.isBlank() || "(unknown)".equals(targetList)) return List.of();
        return java.util.Arrays.stream(targetList.split(","))
                .map(String::strip)
                .filter(path -> !path.isBlank())
                .distinct()
                .toList();
    }

    private static boolean isRepairOrFixContract(TaskContract contract) {
        if (contract == null) return false;
        String reason = contract.classificationReason();
        return "explicit-review-and-fix-request".equals(reason)
                || "repair-follow-up-inherits-previous-mutation-contract".equals(reason);
    }

    private static String mutationRetryRequestContext(String userRequest, String priorMutationRequest) {
        if (priorMutationRequest != null && !priorMutationRequest.isBlank()
                && !Objects.equals(priorMutationRequest, userRequest)) {
            return "The current user message is a retry/repair follow-up:\n\n«"
                    + pinForRetryPrompt(userRequest)
                    + "»\n\n"
                    + "The previous mutation request to reissue is:\n\n«"
                    + pinForRetryPrompt(priorMutationRequest)
                    + "»\n\n";
        }
        return "The user's request was:\n\n«"
                + pinForRetryPrompt(userRequest)
                + "»\n\n";
    }

    private static String mutationRetryInstruction(
            ActionObligation obligation,
            String userRequest,
            String priorMutationRequest,
            List<String> retryToolNames
    ) {
        if (obligation == ActionObligation.CONDITIONAL_REVIEW_FIX) {
            return "Review/fix retry. "
                    + mutationRetryRequestContext(userRequest, priorMutationRequest)
                    + "If a browser blocker remains, call write_file/edit_file. "
                    + "If none, answer exactly: No file change is required.";
        }
        if (obligation == ActionObligation.WORKSPACE_OPERATION_REQUIRED) {
            String tools = retryToolNames == null || retryToolNames.isEmpty()
                    ? "the visible workspace operation tool"
                    : String.join(", ", retryToolNames);
            return "Retry required: the previous model response did not issue the required workspace operation tool call. "
                    + mutationRetryRequestContext(userRequest, priorMutationRequest)
                    + "Call " + tools + ". Do not emulate move, copy, rename, or mkdir by writing/editing file content. "
                    + "If impossible, name the operation target and reason in one sentence.";
        }
        return "Retry required: the previous model response did not issue required write/edit tool calls. "
                + mutationRetryRequestContext(userRequest, priorMutationRequest)
                + "Call write_file/edit_file. If impossible, name the file and reason in one sentence.";
    }

    private static boolean retryShouldReissuePriorMutationRequest(TaskContract retryContract) {
        return retryContract != null
                && "repair-follow-up-inherits-previous-mutation-contract"
                .equals(retryContract.classificationReason());
    }

    private static String previousMutationUserRequest(List<ChatMessage> messages, String latestUserRequest) {
        if (messages == null || messages.isEmpty()) return null;
        boolean skippedLatest = false;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message == null || !"user".equals(message.role())) continue;
            String content = message.content();
            if (ToolCallSupport.isSyntheticToolResultContent(content)) continue;
            if (content == null || content.isBlank()) continue;
            if (!skippedLatest && Objects.equals(content, latestUserRequest)) {
                skippedLatest = true;
                continue;
            }
            TaskContract prior = TaskContractResolver.fromUserRequest(content);
            if (prior.mutationAllowed()) {
                return content;
            }
        }
        return null;
    }

    private static String pinForRetryPrompt(String text) {
        if (text == null) return "";
        return text.length() <= 1000 ? text : text.substring(0, 1000) + "…";
    }

    private static boolean hasInvalidMutatingFailure(ToolCallLoop.LoopResult loopResult) {
        if (loopResult == null || loopResult.toolOutcomes() == null) return false;
        return loopResult.toolOutcomes().stream()
                .anyMatch(outcome -> outcome.mutating()
                        && !outcome.success()
                        && !outcome.denied()
                        && ToolError.INVALID_PARAMS.equals(outcome.errorCode()));
    }

    private static String firstInvalidMutatingFailureMessage(ToolCallLoop.LoopResult loopResult) {
        if (loopResult == null || loopResult.toolOutcomes() == null) return null;
        for (ToolCallLoop.ToolOutcome outcome : loopResult.toolOutcomes()) {
            if (outcome == null || outcome.success() || !outcome.mutating() || outcome.denied()) continue;
            if (!ToolError.INVALID_PARAMS.equals(outcome.errorCode())) continue;
            String message = outcome.errorMessage() == null ? "" : outcome.errorMessage();
            // Pre-approval policy/validation rejections (sandbox escapes,
            // source-evidence blocks, forbidden targets) reuse INVALID_PARAMS
            // and consistently carry "before approval" in their messages; they
            // must not be re-prompted toward - dedicated planners or fail-fast
            // truth-check rendering own them. The generic corrected retry
            // targets genuinely malformed parameters only (T743).
            if (message.contains("before approval")) continue;
            return message.isBlank() ? "invalid tool parameters" : message.strip();
        }
        return null;
    }

    private static boolean hasDeniedMutation(ToolCallLoop.LoopResult loopResult) {
        if (loopResult == null || loopResult.toolOutcomes() == null) return false;
        return loopResult.toolOutcomes().stream()
                .anyMatch(outcome -> outcome.mutating() && outcome.denied());
    }

    private static boolean hasAnyTextToolCalls(String answer) {
        return !ToolCallParser.looksLikeMalformedToolProtocol(answer)
                && ToolCallParser.containsToolCalls(answer);
    }

    private static List<String> mergeReadPaths(List<String> original, List<String> retry) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        addNormalizedReadPaths(merged, original);
        addNormalizedReadPaths(merged, retry);
        return List.copyOf(merged);
    }

    private static void addNormalizedReadPaths(Set<String> merged, List<String> paths) {
        if (paths == null || paths.isEmpty()) return;
        for (String path : paths) {
            String normalized = ToolCallSupport.normalizePath(path);
            if (!normalized.isBlank()) {
                merged.add(normalized);
            }
        }
    }
}
