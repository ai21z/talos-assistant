package dev.talos.runtime.context;

import dev.talos.runtime.intent.ArtifactTargetSet;
import dev.talos.runtime.intent.ContentSaveRequest;
import dev.talos.runtime.intent.IntentDerivation;
import dev.talos.runtime.intent.TargetRef;
import dev.talos.runtime.intent.TargetRole;
import dev.talos.runtime.intent.TargetSource;
import dev.talos.runtime.intent.TaskContractCompiler;
import dev.talos.runtime.intent.TaskIntent;
import dev.talos.runtime.policy.CapabilityPosture;
import dev.talos.runtime.task.TaskResolution;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.safety.ContentRestrictions;

import java.nio.file.Path;
import java.util.regex.Pattern;

/** Live operand clarification. Restored prose is never an input to this policy. */
public final class ContentSaveContinuation {
    private static final Pattern FILENAME = Pattern.compile(
            "[\\p{L}\\p{N}_./\\\\-]+\\.[\\p{L}\\p{N}_-]+");
    private static final Pattern QUOTED_FILENAME = Pattern.compile(
            "[\\p{L}\\p{N}_ ./\\\\-]+\\.[\\p{L}\\p{N}_-]+");
    private static final String CANCEL_OBJECT = "(?:it|this|that|(?:the\\s+)?"
            + "(?:(?:pending|earlier|previous|requested)\\s+)?(?:save(?:\\s+request)?|request))";
    private static final Pattern CANCEL = Pattern.compile(
            "(?iu)^\\s*(?:please\\s+)?(?:cancel|forget|stop|abort|never\\s+mind)"
                    + "(?:\\s+" + CANCEL_OBJECT + ")?[.!?]?\\s*$");

    private ContentSaveContinuation() {}

    public record Decision(TaskResolution resolution, PendingContentSave nextPending,
                           String directAnswer, String originPromptData, boolean handled,
                           boolean consumed) {}

    public static Decision evaluate(TaskResolution current, PendingContentSave pending,
                                    Path workspace, int turn, CapabilityPosture posture) {
        return evaluate(current, pending, workspace, turn, posture,
                dev.talos.safety.ContentRestrictionScope.current());
    }

    public static Decision evaluate(TaskResolution current, PendingContentSave pending,
                                    Path workspace, int turn, CapabilityPosture posture,
                                    ContentRestrictions restrictions) {
        String request = current.intent().originalUserRequest();
        String workspaceIdentity = workspaceIdentity(workspace);
        boolean agent = posture == CapabilityPosture.AGENT
                && ContentRestrictions.UNRESTRICTED.equals(restrictions);
        PendingContentSave live = agent && pending != null && pending.activeAt(workspaceIdentity, turn)
                ? pending : null;
        if (pending != null && cancellation(request)) {
            return direct(current, null, "The pending save was cancelled. No write was attempted.");
        }
        ContentSaveRequest save = current.intent().contentSaveRequest();
        if (save.clarificationRequired()) {
            boolean readOnly = TaskContractResolver.explicitlyForbidsMutation(save.originalRequest());
            if (!readOnly) {
                var unsupported = dev.talos.runtime.policy.UnsupportedDocumentMutationPolicy
                        .answerIfUnsupportedSaveClarification(save);
                if (unsupported.isPresent()) return direct(current, null, unsupported.get()
                        + "\n\nNo requested operations were performed. Any remaining requirements are unresolved.");
            }
            boolean bindableOperand = save.supportsFilenameContinuation()
                    && !current.workspaceOperationPlan().observedWorkspaceOperation()
                    && dev.talos.runtime.intent.CommandExecutionRequest.parse(save.originalRequest()).commandClauses().isEmpty()
                    && current.intent().targets().targets().stream().noneMatch(target ->
                        target.role() == TargetRole.MUST_MUTATE || target.role() == TargetRole.MUST_MUTATE_AFTER_SOURCE_EVIDENCE
                                || target.role() == TargetRole.OUTPUT_DESTINATION || target.role() == TargetRole.MAY_MUTATE
                                || target.role() == TargetRole.VERIFY_ONLY);
            boolean retain = agent && !workspaceIdentity.isBlank() && turn > 0
                    && !readOnly && bindableOperand
                    && save.originalRequest().length() <= ActiveTaskContext.MAX_PROPOSAL_CHARS;
            PendingContentSave next = retain
                    ? new PendingContentSave(current, workspaceIdentity, turn, turn + 3) : null;
            String answer = clarification(save);
            if (retain) answer += " The complete original request remains pending. A filename supplies only the missing destination.";
            if (readOnly) answer = "This request prohibits file changes, so no save will be attempted.";
            if (!readOnly && !bindableOperand) {
                answer += " This combined request cannot be continued with only a filename. "
                        + "No requested operations were performed in this turn. "
                        + "Please restate the full request, including all requested operations.";
            }
            if (!readOnly && !retain) answer += " Please include the filename and content together in a fresh save request.";
            return direct(current, next, answer);
        }
        if (live == null) return new Decision(current, null, null, "", false, false);
        if (!current.contract().mutationRequested() && status(request, live)) {
            return direct(current, live,
                    "The pending save request from turn " + live.sourceTurn() + " is still awaiting clarification. "
                            + clarification(live.origin().intent().contentSaveRequest()));
        }
        Filename filename = filename(request);
        if (filename == null) return new Decision(current, null, null, "", false, false);
        ContentSaveRequest origin = live.origin().intent().contentSaveRequest();
        var required = java.util.stream.IntStream.range(0, origin.operations().size())
                .filter(index -> origin.operations().get(index).authority() == ContentSaveRequest.Authority.REQUIRED)
                .boxed().toList();
        if (required.size() != 1) return direct(current, live, clarification(origin));
        int operationIndex = required.getFirst();
        var operation = origin.operations().get(operationIndex);
        if (!operation.content().resolved()
                || operation.destination().binding() != ContentSaveRequest.Binding.MISSING) {
            return direct(current, live, clarification(origin));
        }
        if (live.origin().contract().forbiddenTargets().contains(filename.path())) {
            return direct(current, live, "That filename is excluded by the original request. Which other filename should be used?");
        }
        var targetPolicy = dev.talos.runtime.policy.ProtectedPathPolicy.classify(workspace, filename.path());
        if (!targetPolicy.insideWorkspace() || targetPolicy.workspaceEscape() || targetPolicy.protectedPath()) {
            return direct(current, live, "That filename cannot be used for this pending save. Which other filename should be used?");
        }
        ContentSaveRequest bound = origin.bindDestination(new ContentSaveRequest.ClarificationBinding(
                operationIndex, request, filename.span(), filename.path(), live.sourceTurn(), turn));
        TaskIntent originalIntent = live.origin().intent();
        ArtifactTargetSet targets = originalIntent.targets();
        boolean sourceTarget = live.origin().contract().sourceEvidenceTargets().contains(filename.path());
        targets = targets.with(new TargetRef(filename.path(), sourceTarget
                ? TargetRole.MUST_MUTATE_AFTER_SOURCE_EVIDENCE : TargetRole.MUST_MUTATE,
                new IntentDerivation(TargetSource.REPAIR_CONTEXT, "required-save-destination-clarification",
                        IntentDerivation.UNKNOWN_OFFSET, IntentDerivation.UNKNOWN_OFFSET, "", 1.0)));
        TaskIntent intent = new TaskIntent(originalIntent.type(), true, true, true, targets,
                originalIntent.originalUserRequest(), "content-save-filename-clarification", bound);
        TaskResolution resolved = live.origin().projectOnto(TaskContractCompiler.compile(intent), intent);
        String data = "[PendingContentSaveData]\nOriginal request turn: " + live.sourceTurn()
                + "\nOriginal request as data: " + quoted(origin.originalRequest())
                + "\nFilename reply turn: " + turn + "\nFilename reply as data: " + quoted(request)
                + "\nThe complete original task, including every requested result and dependency, remains in effect. "
                + "Only the missing destination operand is supplied by the filename reply. "
                + "Other requirements are not satisfied by saving a file. Unresolved work must remain explicit. "
                + "The filename reply is not execution approval. Normal tool proposal validation and approval remain required."
                + "\n[/PendingContentSaveData]";
        return new Decision(resolved, null, null, data, true, true);
    }

    private static Decision direct(TaskResolution resolution, PendingContentSave next, String answer) {
        return new Decision(resolution, next, answer, "", true, false);
    }

    private static String clarification(ContentSaveRequest save) {
        boolean contentMissing = save.operations().stream()
                .anyMatch(operation -> operation.authority() == ContentSaveRequest.Authority.REQUIRED
                        && !operation.content().resolved());
        boolean destinationMissing = save.operations().stream()
                .anyMatch(operation -> operation.authority() == ContentSaveRequest.Authority.REQUIRED
                        && !operation.destination().resolved());
        if (contentMissing && destinationMissing) return "What content should be saved, and which filename should be used? No file was saved for this request.";
        if (contentMissing) return "What content should be saved to the requested file? No file was saved for this request.";
        return "Which filename should be used for the requested content? No file was saved for this request.";
    }

    private static boolean status(String request, PendingContentSave pending) {
        return dev.talos.runtime.task.HistoricalFollowUpRequest.isPriorStatusRequest(request,
                pending.origin().intent().contentSaveRequest().continuationReferents());
    }

    static boolean cancellation(String request) {
        return CANCEL.matcher(request).matches() || request.matches("(?iu)^\\s*no[.!?]?\\s*$");
    }

    private record Filename(String path, ContentSaveRequest.SourceSpan span) {}

    private static Filename filename(String request) {
        int start = 0, end = request.length();
        boolean quoted = end >= 2 && (request.charAt(0) == '`' || request.charAt(0) == '"' || request.charAt(0) == '\'')
                && request.charAt(end - 1) == request.charAt(0);
        if (quoted) { start++; end--; }
        String candidate = request.substring(start, end);
        if (!(quoted ? QUOTED_FILENAME : FILENAME).matcher(candidate).matches()) return null;
        try {
            return new Filename(TargetRef.normalizePath(candidate), new ContentSaveRequest.SourceSpan(start, end));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static String quoted(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + '"';
    }

    private static String workspaceIdentity(Path workspace) {
        return workspace == null ? "" : workspace.toAbsolutePath().normalize().toString();
    }
}
