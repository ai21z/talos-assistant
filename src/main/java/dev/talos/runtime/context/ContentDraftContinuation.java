package dev.talos.runtime.context;

import dev.talos.runtime.intent.*;
import dev.talos.runtime.policy.CapabilityPosture;
import dev.talos.runtime.policy.ProtectedPathPolicy;
import dev.talos.runtime.policy.UnsupportedDocumentMutationPolicy;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskResolution;
import dev.talos.safety.ContentRestrictions;

import java.nio.file.Path;
import java.util.UUID;
import java.util.regex.Pattern;

/** A content choice is separated from filename clarification and execution approval. */
public final class ContentDraftContinuation {
    private static final Pattern SELECT = Pattern.compile("(?i)^save draft ([a-f0-9]{12})[.]?$");
    private static final Pattern CANCEL = Pattern.compile("(?i)^(?:cancel|discard|forget)(?: the)? draft[.!]?$|^no[.!]?$");
    private ContentDraftContinuation() {}

    public record Decision(TaskResolution resolution, PendingContentDraft next, String directAnswer,
                           PendingContentDraft generate, boolean handled) {}

    public static Decision evaluate(TaskResolution current, PendingContentDraft pending, Path workspace,
                                    int turn, CapabilityPosture posture, ContentRestrictions restrictions) {
        String request = current.intent().originalUserRequest();
        String identity = workspace == null ? "" : workspace.toAbsolutePath().normalize().toString();
        boolean permitted = posture == CapabilityPosture.AGENT && ContentRestrictions.UNRESTRICTED.equals(restrictions)
                && !dev.talos.runtime.policy.CapabilityAnswerPolicy.looksLikeWorkspaceSwitchRequest(request)
                && !identity.isBlank() && turn > 0;
        PendingContentDraft live = permitted && pending != null && pending.activeAt(identity, turn) ? pending : null;
        var selection = SELECT.matcher(request.strip());
        if (selection.matches()) {
            if (live == null || live.content().isBlank() || live.destination().isBlank()
                    || !live.id().equals(selection.group(1))) {
                return direct(current, null, "No matching live draft is available for selection. No write was attempted.");
            }
            if (!destinationAllowed(live, workspace, live.destination())) {
                return direct(current, null, "The draft destination is no longer permitted. No write was attempted.");
            }
            var origin = live.origin();
            var selected = origin.intent().contentSaveRequest().selectDraft(new ContentSaveRequest.DraftSelection(
                    live.operationIndex(), live.id(), live.destination(), live.content(), live.originTurn(), turn));
            var targets = origin.intent().targets().with(new TargetRef(live.destination(), TargetRole.MUST_MUTATE,
                    new IntentDerivation(TargetSource.REPAIR_CONTEXT, "explicit-content-draft-selection",
                            IntentDerivation.UNKNOWN_OFFSET, IntentDerivation.UNKNOWN_OFFSET, "", 1.0)));
            var intent = new TaskIntent(origin.intent().type(), true, true, true, targets,
                    origin.intent().originalUserRequest(), "content-draft-selection", selected);
            return new Decision(origin.projectOnto(TaskContractCompiler.compile(intent), intent), null, null, null, true);
        }
        if (live != null && (CANCEL.matcher(request.strip()).matches() || ContentSaveContinuation.cancellation(request))) {
            return direct(current, null, "The draft was discarded. No write was attempted.");
        }
        if (live != null && CurrentRequestClauses.unscopedCoordinated(request).stream().anyMatch(clause ->
                CANCEL.matcher(clause.text()).matches() || ContentSaveContinuation.cancellation(clause.text()))) {
            live = null;
        }
        if (live != null && live.destination().isBlank()) {
            String path = filename(request);
            if (path != null) {
                if (!destinationAllowed(live, workspace, path)) {
                    return direct(current, live, "That destination is not permitted by the original request. No write was attempted.");
                }
                var bound = new PendingContentDraft(live.origin(), live.operationIndex(), live.workspace(),
                        live.originTurn(), live.expiresAfterTurn(), id(), path, live.content());
                return direct(current, bound, render(bound));
            }
        }
        int index = unresolvedOperation(current);
        if (permitted && index >= 0) {
            var operation = current.intent().contentSaveRequest().operations().get(index);
            var next = new PendingContentDraft(current, index, identity, turn, turn + 5, id(),
                    operation.destination().resolved() ? TargetRef.normalizePath(operation.destination().value()) : "", "");
            if (!next.destination().isBlank() && !destinationAllowed(next, workspace, next.destination())) {
                return direct(current, null, "The draft destination is not permitted. No write was attempted.");
            }
            return new Decision(current, null, null, next, true);
        }
        // A read-only interlude cannot authorize execution or renew the original expiry.
        return new Decision(current, live != null && !current.contract().mutationRequested()
                && !TaskContractResolver.explicitlyForbidsMutation(request)
                && current.intent().contentSaveRequest().operations().stream()
                    .noneMatch(op -> op.authority() == ContentSaveRequest.Authority.FORBIDDEN) ? live : null,
                null, null, false);
    }

    public static int unresolvedOperation(TaskResolution resolution) {
        var save = resolution.intent().contentSaveRequest();
        if (!save.clarificationRequired() || save.clarificationBinding() != null || save.draftSelection() != null
                || save.originalRequest().length() > 8192 || save.hasIncompleteQuotedContent()
                || TaskContractResolver.explicitlyForbidsMutation(save.originalRequest())
                || resolution.workspaceOperationPlan().observedWorkspaceOperation()
                || !resolution.contract().sourceEvidenceTargets().isEmpty()
                || !CommandExecutionRequest.parse(save.originalRequest()).commandClauses().isEmpty()
                || UnsupportedDocumentMutationPolicy.answerIfUnsupportedSaveClarification(save).isPresent()) return -1;
        int candidate = -1;
        for (int index = 0; index < save.operations().size(); index++) {
            var operation = save.operations().get(index);
            if (operation.authority() != ContentSaveRequest.Authority.REQUIRED) continue;
            if (candidate >= 0 || operation.content().binding() != ContentSaveRequest.Binding.MISSING
                    || operation.destination().binding() == ContentSaveRequest.Binding.AMBIGUOUS) return -1;
            candidate = index;
        }
        if (candidate < 0) return -1;
        var destination = save.operations().get(candidate).destination();
        if (resolution.intent().targets().targets().stream().anyMatch(target ->
                (target.role() == TargetRole.MUST_MUTATE || target.role() == TargetRole.MUST_MUTATE_AFTER_SOURCE_EVIDENCE
                || target.role() == TargetRole.OUTPUT_DESTINATION || target.role() == TargetRole.MAY_MUTATE
                || target.role() == TargetRole.VERIFY_ONLY)
                && (!destination.resolved() || !target.path().equals(TargetRef.normalizePath(destination.value()))))) return -1;
        return candidate;
    }

    public static String render(PendingContentDraft draft) {
        return render(draft.content(), draft.destination(), draft.id());
    }

    public static String render(String content, String path, String id) {
        String destination = path.isBlank() ? "Not supplied" : path;
        String body = content.replace("\n", "\n> ");
        return "This draft has not been written. Existing files are unchanged. Meaning and complete request coverage remain unverified.\n"
                + "Destination: " + destination + "\n\n> " + body + "\n\n"
                + (path.isBlank() ? "A filename is still required. Supplying it does not select the draft."
                : "To select these exact contents and this destination, enter: Save draft " + id
                    + "\nNormal file permission and approval will still be required.");
    }

    private static Decision direct(TaskResolution current, PendingContentDraft next, String answer) {
        return new Decision(current, next, answer, null, true);
    }

    private static String id() { return UUID.randomUUID().toString().replace("-", "").substring(0, 12); }

    private static String filename(String request) {
        String value = request.strip();
        if (value.length() > 2 && "`\"'".indexOf(value.charAt(0)) >= 0
                && value.charAt(value.length() - 1) == value.charAt(0)) value = value.substring(1, value.length() - 1);
        return value.matches("[\\p{L}\\p{N}_ ./\\\\-]+\\.[\\p{L}\\p{N}_-]+") ? TargetRef.normalizePath(value) : null;
    }

    private static boolean destinationAllowed(PendingContentDraft draft, Path workspace, String path) {
        try {
            Path candidate = workspace.resolve(path).normalize();
            if (draft.origin().contract().forbiddenTargets().stream()
                    .anyMatch(excluded -> workspace.resolve(excluded).normalize().equals(candidate))) return false;
            var policy = ProtectedPathPolicy.classify(workspace, path);
            return policy.insideWorkspace() && !policy.workspaceEscape() && !policy.protectedPath();
        } catch (java.nio.file.InvalidPathException invalid) { return false; }
    }
}
