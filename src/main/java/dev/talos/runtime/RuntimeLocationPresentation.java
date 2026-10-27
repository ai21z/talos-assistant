package dev.talos.runtime;

import dev.talos.core.security.Redactor;
import dev.talos.core.util.Hash;
import dev.talos.runtime.intent.RequestedResultInventory;
import dev.talos.runtime.observation.TurnRequestFrame;
import dev.talos.runtime.policy.ProtectedContentPolicy;
import dev.talos.runtime.policy.WorkspaceResultEvidencePolicy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;

/** An in-memory terminal field, issued from a live admitted runtime result. */
public final class RuntimeLocationPresentation {
    private final TurnRequestFrame frame;
    private final String request;
    private final Path workspace;
    private final int revision;
    private final String resultId;
    private final String answer;
    private final int start;
    private final String answerIdentity;

    private RuntimeLocationPresentation(TurnRequestFrame frame, String resultId, String answer, int start) {
        this.frame = frame;
        this.request = frame.originalRequest();
        this.workspace = frame.workspace();
        this.revision = frame.revision();
        this.resultId = resultId;
        this.answer = answer;
        this.start = start;
        this.answerIdentity = Hash.sha256Hex(answer.getBytes(StandardCharsets.UTF_8));
    }

    /** No inferred request or model-authored matching path can issue this field. */
    public static RuntimeLocationPresentation issue(WorkspaceResultEvidencePolicy.Assessment assessment,
                                                    String selectedAnswer) {
        if (assessment == null || !assessment.admittedRuntimeLocation()) return null;
        TurnRequestFrame frame = assessment.publicationFrame();
        if (frame == null) return null;
        try { frame.requireCurrent(); } catch (SecurityException stale) { return null; }
        if (frame.state() != TurnRequestFrame.State.ADOPTED || !frame.hasControlledSource()
                || frame.meaning() == null || !frame.meaning().locationPublic()
                || !assessment.location().equals(frame.workspace().toString())) return null;
        var result = assessment.results().stream()
                .filter(value -> value.requested().kind() == RequestedResultInventory.Kind.WORKSPACE_LOCATION
                    && value.evidence().status() == dev.talos.runtime.policy.EvidenceObligationVerifier.Status.SATISFIED)
                .map(WorkspaceResultEvidencePolicy.ResultAssessment::requested).findFirst().orElse(null);
        if (result == null || !frame.meaning().inventory().results().contains(result)) return null;
        String answer = assessment.compose(selectedAnswer);
        int start = assessment.locationOffset(selectedAnswer);
        String location = frame.workspace().toString();
        if (answer == null || start < 0 || start + location.length() > answer.length()
                || !answer.regionMatches(start, location, 0, location.length())) return null;
        return new RuntimeLocationPresentation(frame, result.id(), answer, start);
    }

    /** Only an identity is exposed. Raw answer or path fields are not serializable grants. */
    public String answerIdentity() { return answerIdentity; }

    /**
     * The complete answer is projected before output clipping or chunking. A changed candidate,
     * boundary or sanitation result receives ordinary redaction. Outer whitespace is layout only.
     */
    public String project(Redactor redactor, String candidate, String currentRequest,
                          Path currentWorkspace, int currentRevision) {
        Objects.requireNonNull(redactor);
        String text = candidate == null ? "" : candidate;
        if (!binds(currentRequest, currentWorkspace, currentRevision))
            return redactor.redactBlock(ProtectedContentPolicy.sanitizeText(text));
        String sanitized = ProtectedContentPolicy.sanitizeText(answer);
        if (!sanitized.equals(answer)) return redactor.redactBlock(ProtectedContentPolicy.sanitizeText(text));
        String projected = redactor.redactBlockWithValidatedLocation(answer, start,
                start + workspace.toString().length(), workspace.toString());
        String logical = text.strip();
        if (!logical.equals(answer.strip()) && !logical.equals(projected.strip()))
            return redactor.redactBlock(ProtectedContentPolicy.sanitizeText(text));
        int leading = text.length() - text.stripLeading().length();
        int trailing = text.stripTrailing().length();
        return text.substring(0, leading) + projected.strip() + text.substring(trailing);
    }

    private boolean binds(String currentRequest, Path currentWorkspace, int currentRevision) {
        try { frame.requireCurrent(); } catch (SecurityException stale) { return false; }
        return frame.state() == TurnRequestFrame.State.ADOPTED && currentRevision == revision
                && request.equals(currentRequest) && currentWorkspace != null
                && workspace.equals(currentWorkspace.toAbsolutePath().normalize())
                && frame.meaning() != null && frame.meaning().locationPublic()
                && frame.meaning().inventory().results().stream().anyMatch(result -> result.id().equals(resultId)
                    && result.kind() == RequestedResultInventory.Kind.WORKSPACE_LOCATION);
    }
}
