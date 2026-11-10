package dev.talos.runtime.toolcall;

import dev.talos.runtime.ToolCallDispatch;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnRecord;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import dev.talos.runtime.workspace.WorkspaceOperationPlanner;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolFailureReason;
import dev.talos.tools.ToolResult;

import java.util.LinkedHashSet;
import java.util.List;

final class ToolOutcomeFactory {
    private static final int LIST_DIR_EVIDENCE_SUMMARY_CHARS = 4_000;

    private ToolOutcomeFactory() {}

    static ToolCallLoop.ToolOutcome failedEditPreApproval(
            ToolCall call,
            String pathHint,
            String diagnosticError,
            EditFilePreApprovalGuard.Decision decision
    ) {
        return failedEditPreApproval(call, pathHint, diagnosticError, decision, null);
    }

    static ToolCallLoop.ToolOutcome failedEditPreApproval(
            ToolCall call,
            String pathHint,
            String diagnosticError,
            EditFilePreApprovalGuard.Decision decision,
            LocalTurnTraceCapture.ParsedToolCallPermit permit
    ) {
        return new ToolCallLoop.ToolOutcome(
                toolName(call),
                pathHint,
                false,
                true,
                false,
                "",
                diagnosticError,
                null,
                ToolError.INVALID_PARAMS,
                null,
                null,
                editPreApprovalReason(decision),
                List.of(),
                toolCallId(permit),
                null,
                null,
                null,
                notRun(call));
    }

    static ToolCallLoop.ToolOutcome failedPreExecutionMutation(
            ToolCall call,
            String pathHint,
            String diagnosticError,
            WorkspaceOperationPlan workspaceOperationPlan,
            ToolFailureReason failureReason
    ) {
        return failedPreExecutionMutation(
                call,
                pathHint,
                diagnosticError,
                workspaceOperationPlan,
                failureReason,
                null);
    }

    static ToolCallLoop.ToolOutcome failedPreExecutionMutation(
            ToolCall call,
            String pathHint,
            String diagnosticError,
            WorkspaceOperationPlan workspaceOperationPlan,
            ToolFailureReason failureReason,
            LocalTurnTraceCapture.ParsedToolCallPermit permit
    ) {
        return new ToolCallLoop.ToolOutcome(
                toolName(call),
                pathHint,
                false,
                true,
                false,
                "",
                diagnosticError,
                null,
                ToolError.INVALID_PARAMS,
                workspaceOperationPlan,
                null,
                failureReason,
                List.of(),
                toolCallId(permit),
                null,
                null,
                null,
                notRun(call));
    }

    static ToolCallLoop.ToolOutcome failedPreExecutionRead(
            ToolCall call,
            String pathHint,
            String diagnosticError
    ) {
        return failedPreExecutionRead(call, pathHint, diagnosticError, null);
    }

    static ToolCallLoop.ToolOutcome failedPreExecutionRead(
            ToolCall call,
            String pathHint,
            String diagnosticError,
            LocalTurnTraceCapture.ParsedToolCallPermit permit
    ) {
        return new ToolCallLoop.ToolOutcome(
                toolName(call),
                pathHint,
                false,
                false,
                false,
                "",
                diagnosticError,
                null,
                ToolError.INVALID_PARAMS,
                null,
                null,
                ToolFailureReason.NONE,
                List.of(),
                toolCallId(permit),
                null,
                null,
                null,
                notRun(call));
    }

    static ToolCallLoop.ToolOutcome failedAuthority(
            ToolCall call,
            String diagnosticError
    ) {
        return failedAuthority(call, diagnosticError, null);
    }

    static ToolCallLoop.ToolOutcome requestDenied(ToolCall call, ToolResult blocked,
                                                 LocalTurnTraceCapture.ParsedToolCallPermit permit,
                                                 boolean mutating) {
        return new ToolCallLoop.ToolOutcome(toolName(call), "", false,
                mutating, true, "",
                blocked.error().message(), null, blocked.error().code(), null, null,
                blocked.error().reason(), List.of(), toolCallId(permit), null, null, null, notRun(call));
    }

    static ToolCallLoop.ToolOutcome failedAuthority(
            ToolCall call,
            String diagnosticError,
            LocalTurnTraceCapture.ParsedToolCallPermit permit
    ) {
        dev.talos.runtime.TurnAuditCapture.recordAuthorityRejectedToolCall(call, diagnosticError);
        return new ToolCallLoop.ToolOutcome(
                toolName(call),
                "",
                false,
                call != null && ToolCallSupport.isMutatingTool(call.toolName()),
                false,
                "",
                diagnosticError,
                null,
                ToolError.INVALID_AUTHORITY,
                null,
                null,
                ToolFailureReason.TOOL_EXECUTION_AUTHORITY_INVALID,
                List.of(),
                toolCallId(permit),
                null,
                null,
                null,
                notRun(call));
    }

    static ToolCallLoop.ToolOutcome executed(
            ToolCall call,
            String pathHint,
            ToolResult result,
            ToolExecutionFailureClassifier.Classification classification,
            WorkspaceOperationPlan workspaceOperationPlan,
            ToolMutationEvidence mutationEvidence
    ) {
        return executed(
                call,
                pathHint,
                result,
                classification,
                workspaceOperationPlan,
                mutationEvidence,
                null);
    }

    static ToolCallLoop.ToolOutcome executed(
            ToolCall call,
            String pathHint,
            ToolResult result,
            ToolExecutionFailureClassifier.Classification classification,
            WorkspaceOperationPlan workspaceOperationPlan,
            ToolMutationEvidence mutationEvidence,
            LocalTurnTraceCapture.ParsedToolCallPermit permit
    ) {
        return executed(call, pathHint, result, classification, workspaceOperationPlan, mutationEvidence, permit,
                dev.talos.tools.MutationPreimage.none());
    }

    /** An executed outcome that carries the producer preimage the execution stage kept for it. */
    static ToolCallLoop.ToolOutcome executed(
            ToolCall call,
            String pathHint,
            ToolResult result,
            ToolExecutionFailureClassifier.Classification classification,
            WorkspaceOperationPlan workspaceOperationPlan,
            ToolMutationEvidence mutationEvidence,
            LocalTurnTraceCapture.ParsedToolCallPermit permit,
            dev.talos.tools.MutationPreimage mutationPreimage
    ) {
        return executed(call, pathHint, result, classification, workspaceOperationPlan, mutationEvidence, permit,
                mutationPreimage, TurnRecord.ToolCallDisposition.UNKNOWN);
    }

    /**
     * An outcome of a call the tool processor handled, with the processor's dispatch witness (LA-F7). Only a call
     * that never reached the tool registry keeps its proposed target.
     */
    static ToolCallLoop.ToolOutcome executed(
            ToolCall call,
            String pathHint,
            ToolResult result,
            ToolExecutionFailureClassifier.Classification classification,
            WorkspaceOperationPlan workspaceOperationPlan,
            ToolMutationEvidence mutationEvidence,
            LocalTurnTraceCapture.ParsedToolCallPermit permit,
            dev.talos.tools.MutationPreimage mutationPreimage,
            TurnRecord.ToolCallDisposition disposition
    ) {
        boolean success = result != null && result.success();
        ToolCallDispatch dispatch = result == null
                ? ToolCallDispatch.unknown()
                : ToolCallDispatch.of(disposition, proposedTarget(call), proposedChange(call));
        return new ToolCallLoop.ToolOutcome(
                toolName(call),
                pathHint,
                success,
                call != null && ToolCallSupport.isMutatingTool(call.toolName()),
                classification != null && classification.denied(),
                success ? toolOutcomeSummary(canonicalToolName(call), result.output()) : "",
                success ? "" : errorMessage(result),
                result == null ? null : result.verification(),
                result == null || result.error() == null ? "" : result.error().code(),
                workspaceOperationPlan,
                mutationEvidence,
                result == null || result.error() == null
                        ? ToolFailureReason.NONE
                        : result.error().reason(),
                evidenceSourcePaths(call, pathHint, result),
                toolCallId(permit),
                result == null ? null : result.mutationEffects(),
                result == null ? null : result.contentMetadata().directoryObservation(),
                mutationPreimage,
                dispatch);
    }

    /** LA-F7: an outcome built by a refusal factory precedes dispatch, so its call never reached the registry. */
    private static ToolCallDispatch notRun(ToolCall call) {
        return ToolCallDispatch.blocked(proposedTarget(call), proposedChange(call));
    }

    private static String proposedTarget(ToolCall call) {
        return call == null ? "" : ToolCallSupport.resolvePathHint(call);
    }

    /**
     * D25 option B: the change a call that never ran proposed, by the typed role of each operand. The operation kind
     * comes from the same tool-name resolution that chooses the roles. A copy changes its destination, a move or
     * rename its source and destination, a mkdir or delete its target. The change is complete only when every operand
     * the kind changes is named. A batch names no operand here. Any other mutating call changes its path argument.
     */
    private static ToolCallDispatch.ProposedChange proposedChange(ToolCall call) {
        if (call == null) return ToolCallDispatch.ProposedChange.none();
        if (!WorkspaceOperationPlanner.isWorkspaceOperationTool(call.toolName())) {
            // Round 4: only a path under a key that the file tools read names the file the call would change. A path
            // under any other key is shown, but the change stays incomplete, so the proposal is not identified by it.
            String accepted = firstNonBlankParam(call, FILE_TOOL_PATH_KEYS);
            if (!accepted.isEmpty()) return ToolCallDispatch.ProposedChange.file(accepted);
            String shown = ToolCallSupport.resolvePathHint(call);
            return new ToolCallDispatch.ProposedChange(ToolCallDispatch.OperationKind.FILE, List.of(),
                    shown == null || shown.isBlank() ? List.of() : List.of(shown), false);
        }
        String source = "";
        String destination = "";
        String target = "";
        for (WorkspaceOperationPlanner.NamedOperand operand : WorkspaceOperationPlanner.namedOperands(call)) {
            switch (operand.role()) {
                case SOURCE -> source = operand.path();
                case DESTINATION -> destination = operand.path();
                case TARGET -> target = operand.path();
            }
        }
        List<String> named = present(source, destination, target);
        return switch (dev.talos.tools.ToolAliasPolicy.localCanonicalName(call.toolName())) {
            case "copy_path" -> new ToolCallDispatch.ProposedChange(ToolCallDispatch.OperationKind.COPY,
                    present(destination), named, !destination.isBlank());
            case "move_path" -> new ToolCallDispatch.ProposedChange(ToolCallDispatch.OperationKind.MOVE,
                    present(source, destination), named, !source.isBlank() && !destination.isBlank());
            case "rename_path" -> new ToolCallDispatch.ProposedChange(ToolCallDispatch.OperationKind.RENAME,
                    present(source, destination), named, !source.isBlank() && !destination.isBlank());
            case "delete_path" -> new ToolCallDispatch.ProposedChange(ToolCallDispatch.OperationKind.DELETE,
                    present(target), named, !target.isBlank());
            case "mkdir" -> new ToolCallDispatch.ProposedChange(ToolCallDispatch.OperationKind.MKDIR,
                    present(target), named, !target.isBlank());
            case "apply_workspace_batch" -> new ToolCallDispatch.ProposedChange(ToolCallDispatch.OperationKind.BATCH,
                    List.of(), List.of(), false);
            default -> ToolCallDispatch.ProposedChange.none();
        };
    }

    /** The path parameters that FileWriteTool and FileEditTool read for the file they change, in their order. */
    private static final List<String> FILE_TOOL_PATH_KEYS = List.of("path", "file_path", "filepath", "file", "filename");

    private static String firstNonBlankParam(ToolCall call, List<String> keys) {
        for (String key : keys) {
            String value = call.param(key);
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private static List<String> present(String... paths) {
        List<String> out = new java.util.ArrayList<>();
        for (String path : paths) {
            if (path != null && !path.isBlank()) out.add(path);
        }
        return List.copyOf(out);
    }

    private static String toolCallId(LocalTurnTraceCapture.ParsedToolCallPermit permit) {
        return permit == null ? "" : permit.toolCallId();
    }

    private static List<String> evidenceSourcePaths(ToolCall call, String pathHint, ToolResult result) {
        if (result == null || !result.success()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (result.contentMetadata() != null) {
            out.addAll(result.contentMetadata().sourcePaths());
        }
        if ("talos.read_file".equals(canonicalToolName(call))
                && pathHint != null
                && !pathHint.isBlank()) {
            out.add(pathHint.strip().replace('\\', '/'));
        }
        return List.copyOf(out);
    }

    /** Maps the edit pre-approval guard's typed verdict to a failure reason (T758). */
    private static ToolFailureReason editPreApprovalReason(EditFilePreApprovalGuard.Decision decision) {
        if (decision == null) return ToolFailureReason.NONE;
        if (decision.emptyEditArguments()) return ToolFailureReason.EDIT_EMPTY_ARGUMENTS;
        return switch (decision.kind()) {
            case FULL_REWRITE_REPAIR_REQUIRED -> ToolFailureReason.EDIT_FULL_REWRITE_REQUIRED;
            case STALE_REREAD_REQUIRED -> ToolFailureReason.EDIT_STALE_REREAD_REQUIRED;
            case DUPLICATE_FAILED_EDIT -> ToolFailureReason.EDIT_DUPLICATE_FAILED;
            case NONE -> ToolFailureReason.NONE;
        };
    }

    /**
     * Summary policy keys on the CANONICAL tool name: downstream evidence
     * consumers canonicalize, so an accepted list_dir alias ("ls",
     * "list_dir", ...) must get the same full evidence summary as
     * "talos.list_dir" - a first-sentence summary of a directory listing
     * reads as missing evidence and causes a false refusal. The outcome
     * itself still carries the raw name for trace.
     */
    private static String toolOutcomeSummary(String canonicalToolName, String output) {
        if (!"talos.list_dir".equals(canonicalToolName)) {
            return ToolCallSupport.firstSentenceSummary(output);
        }
        String value = output == null ? "" : output.strip();
        if (value.length() <= LIST_DIR_EVIDENCE_SUMMARY_CHARS) {
            return value;
        }
        return value.substring(0, LIST_DIR_EVIDENCE_SUMMARY_CHARS)
                + "\n... (tool outcome summary truncated)";
    }

    private static String toolName(ToolCall call) {
        return call == null ? "" : call.toolName();
    }

    private static String canonicalToolName(ToolCall call) {
        return call == null ? "" : call.canonicalToolName();
    }

    private static String errorMessage(ToolResult result) {
        return result == null ? "" : result.errorMessage();
    }
}
