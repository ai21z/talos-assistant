package dev.talos.runtime.policy;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.intent.RequestedResultInventory;
import dev.talos.runtime.intent.RequestedResultInventory.Kind;
import dev.talos.runtime.observation.TurnRequestFrame;
import dev.talos.runtime.turn.CurrentTurnPlan;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Settlement and presentation for the captured bounded workspace-result inventory. */
public final class WorkspaceResultEvidencePolicy {
    private WorkspaceResultEvidencePolicy() {}

    public record ResultAssessment(RequestedResultInventory.RequestedResult requested,
                                   EvidenceObligationVerifier.Result evidence,
                                   ToolCallLoop.ToolOutcome observation) {}
    public static final class Assessment {
        private final boolean applicable;
        private final List<ResultAssessment> results;
        private final String location;
        private final PublicationSnapshot publication;
        private final boolean unresolvedMeaning;

        Assessment(boolean applicable, List<ResultAssessment> results, String location) {
            this(applicable, results, location,
                    new PublicationSnapshot(null, false, true, true, TurnRequestFrame.Publication.ALL_NAMES,
                            dev.talos.spi.WorkspaceObservationBoundary.Fields.all()), false);
        }

        private Assessment(boolean applicable, List<ResultAssessment> results, String location,
                           PublicationSnapshot publication, boolean unresolvedMeaning) {
            this.applicable = applicable;
            this.results = List.copyOf(results);
            this.location = location;
            this.publication = publication;
            this.unresolvedMeaning = unresolvedMeaning;
        }

        public boolean applicable() { return applicable; }
        public List<ResultAssessment> results() { return results; }
        public String location() { return location; }

        /** A retained identity is used only to validate issuance, never to recover publication choices. */
        public TurnRequestFrame publicationFrame() { return publication.frame(); }

        public boolean admittedRuntimeLocation() {
            return applicable && publication.controlled() && publication.resolved() && publication.locationPublic()
                    && results.stream().anyMatch(result -> result.requested().kind() == Kind.WORKSPACE_LOCATION
                        && result.evidence().status() == EvidenceObligationVerifier.Status.SATISFIED)
                    && location.equals(ProtectedContentPolicy.sanitizeText(location));
        }

        /** The offset is assembled by the runtime and is never found by matching model prose. */
        public int locationOffset(String selectedAnswer) { return composeValue(selectedAnswer).locationOffset(); }
        public EvidenceObligationVerifier.Result aggregate() {
            for (ResultAssessment result : results) {
                if (result.evidence().status() == EvidenceObligationVerifier.Status.BLOCKED) return result.evidence();
            }
            for (ResultAssessment result : results) {
                if (result.evidence().status() != EvidenceObligationVerifier.Status.SATISFIED) return result.evidence();
            }
            if (unresolvedMeaning) return EvidenceObligationVerifier.Result.unsatisfied(
                    "Some requested meaning remains unresolved, independent supported results were retained.");
            return EvidenceObligationVerifier.Result.satisfied(
                    "The requested workspace evidence bases were established. Answer meaning remains unassessed.");
        }

        /** Supported runtime facts survive unavailable source-dependent results. */
        public String compose(String selectedAnswer) {
            return composeValue(selectedAnswer).text();
        }

        private Composed composeValue(String selectedAnswer) {
            if (!applicable) return new Composed(selectedAnswer, -1);
            List<String> parts = new ArrayList<>();
            boolean purposeSupported = false;
            int locationOffset = -1;
            int length = 0;
            for (ResultAssessment result : results) {
                boolean supported = result.evidence().status() == EvidenceObligationVerifier.Status.SATISFIED;
                if (result.requested().disposition() != RequestedResultInventory.Disposition.ACTIVE) {
                    parts.add(switch (result.requested().disposition()) {
                        case CANCELLED -> "A requested result was cancelled and was not acquired or shown.";
                        case REPLACED -> "A requested result was replaced and was not acquired or shown.";
                        case WITHHELD -> "A requested result was withheld by the current request.";
                        default -> "A requested result remains unresolved.";
                    });
                    length = parts.stream().mapToInt(String::length).sum() + parts.size() * 2;
                    continue;
                }
                switch (result.requested().kind()) {
                    case WORKSPACE_LOCATION -> {
                        if (supported && admittedRuntimeLocation() && locationOffset < 0)
                            locationOffset = length + LOCATION_PREFIX.length();
                        parts.add(supported ? locationText(location, publication)
                                : "The current workspace location is unavailable.");
                    }
                    case DIRECTORY_CONTENTS, DIRECTORY_COUNT, DIRECTORY_EMPTINESS ->
                            parts.add(directoryText(result, publication));
                    case PROJECT_PURPOSE -> {
                        purposeSupported = supported;
                        if (!supported) parts.add(result.evidence().status() == EvidenceObligationVerifier.Status.BLOCKED
                                ? "The required project source evidence was blocked. The project purpose remains unresolved."
                                : "The project purpose cannot be established from the available "
                                    + "project source evidence. That requested result remains unresolved.");
                    }
                    default -> parts.add("A requested result remains unresolved.");
                }
                length = parts.stream().mapToInt(String::length).sum() + parts.size() * 2;
            }
            String composed = String.join("\n\n", parts);
            if (unresolvedMeaning) composed += "\n\nAdditional requested meaning remains unresolved.";
            if (purposeSupported && selectedAnswer != null && !selectedAnswer.isBlank()) {
                if (locationOffset >= 0) locationOffset += "Runtime workspace facts:\n".length();
                composed = (composed.isBlank() ? "" : "Runtime workspace facts:\n" + composed + "\n\n")
                        + "Model explanation from admitted project sources, with meaning and completeness unassessed:\n"
                        + selectedAnswer;
            }
            if (aggregate().status() != EvidenceObligationVerifier.Status.SATISFIED) {
                if (locationOffset >= 0) locationOffset += EvidenceObligationVerifier.MISSING_EVIDENCE_PREFIX.length() + 2;
                composed = EvidenceObligationVerifier.MISSING_EVIDENCE_PREFIX + "\n\n" + composed;
            }
            return new Composed(composed, locationOffset);
        }
    }

    private static final String LOCATION_PREFIX = "The current workspace is:\n";
    private record Composed(String text, int locationOffset) {}
    private record PublicationSnapshot(TurnRequestFrame frame, boolean controlled, boolean resolved,
                                       boolean locationPublic, TurnRequestFrame.Publication names,
                                       dev.talos.spi.WorkspaceObservationBoundary.Fields fields) {}

    private static PublicationSnapshot capturePublication(CurrentTurnPlan plan, Path workspace) {
        var frame = TurnRequestFrame.current();
        if (frame == null) return new PublicationSnapshot(null, false, true, true, TurnRequestFrame.Publication.ALL_NAMES,
                dev.talos.spi.WorkspaceObservationBoundary.Fields.all());
        frame.requireCurrent();
        if (!frame.hasControlledSource())
            return new PublicationSnapshot(frame, false, true, true, TurnRequestFrame.Publication.ALL_NAMES,
                    dev.talos.spi.WorkspaceObservationBoundary.Fields.all());
        boolean resolved = frame.state() == TurnRequestFrame.State.ADOPTED && frame.meaning() != null
                && plan != null && workspace != null && frame.workspace().equals(workspace.toAbsolutePath().normalize())
                && frame.originalRequest().equals(plan.originalUserRequest())
                && frame.meaning().inventory().equals(plan.requestedResults());
        return new PublicationSnapshot(frame, true, resolved,
                resolved && frame.meaning().locationPublic(),
                resolved ? frame.meaning().publication() : TurnRequestFrame.Publication.NO_NAMES,
                resolved ? frame.meaning().fields() : new dev.talos.spi.WorkspaceObservationBoundary.Fields(false, false, false, false));
    }

    private static String locationText(String location, PublicationSnapshot publication) {
        if (publication.controlled() && !publication.locationPublic())
            return "The workspace location was withheld by the current request.";
        return LOCATION_PREFIX + ProtectedContentPolicy.sanitizeText(location);
    }

    public static Assessment assess(CurrentTurnPlan plan, ToolCallLoop.LoopResult loop, Path workspace) {
        PublicationSnapshot publication = capturePublication(plan, workspace);
        if (plan == null || !plan.workspaceResultsApplicable())
            return new Assessment(false, List.of(), "", publication, false);
        String location = workspace == null ? "" : workspace.toAbsolutePath().normalize().toString();
        List<ResultAssessment> results = new ArrayList<>();
        for (var requested : plan.requestedResults().results()) {
            EvidenceObligationVerifier.Result evidence;
            ToolCallLoop.ToolOutcome observation = null;
            if (requested.disposition() != RequestedResultInventory.Disposition.ACTIVE) {
                evidence = requested.disposition() == RequestedResultInventory.Disposition.UNSUPPORTED
                        ? EvidenceObligationVerifier.Result.unsatisfied("The requested result remains unresolved.")
                        : EvidenceObligationVerifier.Result.satisfied("The result was not requested for disclosure.");
            } else if (requested.kind() == Kind.WORKSPACE_LOCATION) {
                evidence = location.isBlank() ? EvidenceObligationVerifier.Result.unsatisfied("Turn workspace is unavailable.")
                        : EvidenceObligationVerifier.Result.satisfied("The active workspace was supplied by the runtime for this turn.");
            } else if (requested.kind() == Kind.PROJECT_PURPOSE) {
                evidence = ProjectSummaryEvidencePolicy.assess(workspace, plan.taskContract(), loop).result();
            } else if (loop == null) {
                evidence = EvidenceObligationVerifier.Result.unsatisfied("The requested directory was not observed.");
            } else {
                var directory = loop.readEvidenceCoverage().assessDirectory(
                        loop.toolOutcomes(), workspace, plan.taskContract(), ".");
                evidence = directory.result();
                observation = directory.observation();
                if (publication.controlled() && publication.resolved() && requested.kind() == Kind.DIRECTORY_EMPTINESS
                        && observation != null && observation.directoryObservation() != null
                        && observation.directoryObservation().maxDepth() == 1
                        && observation.directoryObservation().returnedEntries() > 0) {
                    evidence = EvidenceObligationVerifier.Result.satisfied(
                            "An admitted current top-level observation establishes that the directory is not empty.");
                }
            }
            results.add(new ResultAssessment(requested, evidence, observation));
        }
        return new Assessment(true, results, location, publication, !plan.requestedResults().unresolvedRegions().isEmpty());
    }

    private static String directoryText(ResultAssessment result, PublicationSnapshot publication) {
        if (publication.controlled() && !publication.resolved())
            return "The requested directory result remains unresolved.";
        if (publication.controlled() && result.requested().kind() == Kind.DIRECTORY_COUNT
                && (!publication.fields().countPublic() || !publication.fields().hiddenFactsPublic()))
            return "The directory count was withheld by the current request.";
        if (publication.controlled() && result.requested().kind() == Kind.DIRECTORY_EMPTINESS
                && (!publication.fields().existencePublic() || !publication.fields().hiddenFactsPublic()))
            return "Directory emptiness was withheld by the current request.";
        var outcome = result.observation();
        if (outcome == null || outcome.directoryObservation() == null)
            return "The requested directory contents and emptiness were not established by a successful current observation.";
        var observation = outcome.directoryObservation();
        if (publication.controlled()) {
            boolean complete = result.evidence().status() == EvidenceObligationVerifier.Status.SATISFIED
                    && observation.maxDepth() == 1;
            if (result.requested().kind() == Kind.DIRECTORY_COUNT)
                return complete ? "The observed top-level entry count is " + observation.returnedEntries() + "."
                        : "The complete top-level entry count remains unresolved.";
            if (result.requested().kind() == Kind.DIRECTORY_EMPTINESS)
                return complete ? "The observed top-level directory is " + (observation.returnedEntries() == 0 ? "empty." : "not empty.")
                        : "Directory emptiness remains unresolved.";
            if (publication.names() == TurnRequestFrame.Publication.NO_NAMES)
                return "Entry names were withheld by the current request.";
            if (publication.names() == TurnRequestFrame.Publication.VISIBLE_NAMES)
                return "Visible entry observations" + (complete ? ":\n" : ", with incomplete coverage:\n")
                        + ProtectedContentPolicy.sanitizeText(outcome.summary()).strip();
            return "Observed top-level entries" + (complete ? ":\n" : ", with incomplete coverage:\n")
                    + ProtectedContentPolicy.sanitizeText(outcome.summary()).strip();
        }
        return observedDirectoryText(result.evidence(), outcome, false, Integer.MAX_VALUE);
    }

    /**
     * The admitted current observation of the workspace root, for an answer whose broader obligation was not met.
     * Eligibility is decided by ReadEvidenceCoverage.assessDirectory, never by model prose. A listing of another
     * directory, an unbound, failed or denied listing, or a listing followed by mutation yields no text.
     * Names are withheld when the request carries an output-restriction cue.
     */
    public static String observedRoot(CurrentTurnPlan plan, ToolCallLoop.LoopResult loop, Path workspace) {
        if (plan == null || loop == null || workspace == null || !loop.readEvidenceCoverage().captured()) return "";
        var directory = loop.readEvidenceCoverage().assessDirectory(
                loop.toolOutcomes(), workspace, plan.taskContract(), ".");
        var outcome = directory.observation();
        if (outcome == null || outcome.directoryObservation() == null) return "";
        boolean restricted = dev.talos.runtime.intent.OutputRestrictionCue.present(plan.originalUserRequest());
        return observedDirectoryText(directory.result(), outcome, restricted, MAX_OBSERVED_ROOT_LINES);
    }

    private static final int MAX_OBSERVED_ROOT_LINES = 50;

    private static String observedDirectoryText(EvidenceObligationVerifier.Result evidence,
                                                ToolCallLoop.ToolOutcome outcome, boolean namesWithheld, int maxLines) {
        var observation = outcome.directoryObservation();
        boolean complete = evidence.status() == EvidenceObligationVerifier.Status.SATISFIED;
        if (observation.returnedEntries() == 0) {
            return complete ? "The inspected top-level directory is empty. Hidden entries were included in this observation."
                    : "The directory observation was limited. Its complete contents and emptiness remain unresolved.";
        }
        String lead = complete ? "The inspected directory is not empty. Hidden entries were included. "
                : "The inspected directory is not empty. The observation was limited, so complete contents remain unresolved. ";
        if (namesWithheld) return lead + "Entry names and counts are withheld because the request may limit what is shown.";
        return lead + "Observed entries within depth " + observation.maxDepth() + ":\n"
                + boundedLines(ProtectedContentPolicy.sanitizeText(outcome.summary()).strip(), maxLines);
    }

    private static String boundedLines(String body, int maxLines) {
        var lines = body.lines().toList();
        if (lines.size() <= maxLines) return body;
        return String.join("\n", lines.subList(0, maxLines)) + "\n"
                + (lines.size() - maxLines) + " more observed lines are not shown.";
    }
}
