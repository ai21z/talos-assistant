package dev.talos.runtime.verification;

import dev.talos.core.diff.DocumentTextDiff;
import dev.talos.core.privacy.DocumentContentDecision;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnAuditCapture;
import dev.talos.runtime.task.DocumentComparisonRequest;
import dev.talos.runtime.task.ReadOnlyRequestMeaning;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidence;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static dev.talos.runtime.verification.ReadOnlyAnswerComponents.Support;

/** Only current runtime computations can close a comparison requirement. */
public final class DocumentComparisonAnswer {
    private DocumentComparisonAnswer() { }

    public static ReadOnlyWebDiagnosticResult compose(TaskContract contract, Path workspace,
            ToolCallLoop.LoopResult loop, int extraMutationSuccesses, ReadOnlyWebDiagnosticResult siblings) {
        var request = DocumentComparisonRequest.from(contract);
        if (!request.requiresAssessment()) return siblings;
        if (extraMutationSuccesses > 0 || loop != null && (loop.mutatingToolSuccesses() > 0
                || loop.toolOutcomes().stream().anyMatch(value -> value.success() && value.mutating()
                    || value.mutationEffects().hasEffects()))) {
            return new ReadOnlyWebDiagnosticResult("[Answer incomplete]\n\nMutation evidence was retained during the requested read-only comparison. An unchanged workspace and complete read-only result are not established.",
                    ReadOnlyWebAnswerAssessment.limited(ReadOnlyWebAnswerAssessment.Reason.ANSWER_REQUIREMENTS_UNMET),
                    siblings.diagnostics());
        }
        var meaning = ReadOnlyRequestMeaning.compile(request.request());
        if (!request.comparisonResultsOnly() && siblings.assessment().limited() && siblings.components().components().stream().noneMatch(component ->
                meaning.requirements().slots().stream().anyMatch(slot -> slot.id().equals(component.requirementId())))) {
            return siblings;
        }
        var observations = TurnAuditCapture.currentComparisons(request.request(), workspace);
        boolean failed = loop == null || loop.failedCalls() > 0 || loop.failureDecision().shouldStop()
                || loop.toolOutcomes().stream().anyMatch(value -> value.denied() || !value.success()
                    || value.mutating() || value.mutationEffects().hasEffects());
        var components = new ArrayList<ReadOnlyAnswerComponents.Component>();
        for (var slot : meaning.requirements().slots()) {
            if (request.unresolved().contains(slot.id())) {
                components.add(unavailable(slot.id(), "COMPARISON_MEANING_UNRESOLVED",
                        "The requested comparison could not be bound to a supported ordered operation. File reads and model prose do not complete it."));
                continue;
            }
            var binding = request.requirements().stream().filter(value -> value.id().equals(slot.id())).findFirst();
            if (binding.isEmpty()) {
                var retained = siblings.components().components().stream()
                        .filter(value -> value.requirementId().equals(slot.id())).toList();
                components.add(retained.size() == 1 ? retained.getFirst() : unavailable(slot.id(), "REQUIRED_RESULT_UNAVAILABLE",
                        "A separately requested result remains unresolved."));
                continue;
            }
            var requirement = binding.get();
            var selected = observations.stream().filter(value ->
                    value.receipt().left().relativePath().equals(requirement.pair().left())
                    && value.receipt().right().relativePath().equals(requirement.pair().right())).toList();
            if (failed || selected.size() != 1 || selected.getFirst().result().status() != DocumentTextDiff.Status.COMPLETE) {
                components.add(unavailable(slot.id(), "COMPARISON_EVIDENCE_UNAVAILABLE",
                        "The ordered document comparison remains unresolved without one complete current-turn computation."));
                continue;
            }
            var observation = selected.getFirst();
            TurnAuditCapture.includeLocalComparison(observation);
            var display = DocumentComparisonPresentation.render(observation, 4800);
            String text = display.text();
            boolean supported = display.complete();
            if (requirement.explanationRequired()) {
                var explanation = DocumentStructureExplanation.explain(observation.pair());
                text += "\n\n" + explanation.text();
                supported &= explanation.supported();
            }
            if (siblings.components().active() && !bridges(observation, loop.readOnlyFinalizationEvidence().sources())) {
                supported = false;
                text += "\nThe paired snapshots could not be bound to the earlier source observations used by the other results.";
            }
            String identity = identity(observation);
            var view = observation.pair().view(DocumentContentDecision.Use.LOCAL_DISPLAY).orElseThrow();
            var references = List.of(
                    new ReadOnlyAnswerComponents.Reference(identity + ":left", observation.receipt().left().textSha256(), 0, view.left().text().length()),
                    new ReadOnlyAnswerComponents.Reference(identity + ":right", observation.receipt().right().textSha256(), 0, view.right().text().length()));
            components.add(new ReadOnlyAnswerComponents.Component(slot.id(), "DOCUMENT_COMPARISON",
                    supported ? Support.ESTABLISHED : Support.INSUFFICIENT_EVIDENCE,
                    supported ? "ORDERED_COMPARISON_ESTABLISHED" : "COMPARISON_DELIVERY_OR_EXPLANATION_INCOMPLETE",
                    text, references, identity));
        }
        boolean covered = !components.isEmpty() && meaning.requirements().regionReasons().keySet().stream()
                .allMatch(id -> request.requirements().stream().anyMatch(value -> value.id().equals(id)));
        var content = new ReadOnlyAnswerComponents(components, covered, ReadOnlyAnswerComponents.CoverageOrigin.REQUEST_MEANING);
        var assessment = content.complete()
                ? new ReadOnlyWebAnswerAssessment(ReadOnlyWebAnswerAssessment.Disposition.RUNTIME_DERIVED,
                    ReadOnlyWebAnswerAssessment.Reason.RUNTIME_DERIVED)
                : ReadOnlyWebAnswerAssessment.limited(ReadOnlyWebAnswerAssessment.Reason.ANSWER_REQUIREMENTS_UNMET);
        return new ReadOnlyWebDiagnosticResult(content.render(), assessment, siblings.diagnostics(),
                ReadOnlyWebDiagnosticResult.Association.empty(), content);
    }

    private static ReadOnlyAnswerComponents.Component unavailable(String id, String reason, String text) {
        return new ReadOnlyAnswerComponents.Component(id, "DOCUMENT_COMPARISON", Support.INSUFFICIENT_EVIDENCE,
                reason, text, List.of(), "");
    }

    /** The old read projection is checked explicitly, never treated as exact original bytes. */
    private static boolean bridges(TurnAuditCapture.DocumentComparison observation,
                                   List<ReadOnlyFinalizationEvidence.Source> sources) {
        var view = observation.pair().view(DocumentContentDecision.Use.LOCAL_DISPLAY).orElseThrow();
        return bridge(observation.receipt().left().relativePath(), view.left(), sources)
                && bridge(observation.receipt().right().relativePath(), view.right(), sources);
    }

    private static boolean bridge(String path, DocumentTextDiff.Snapshot snapshot,
                                  List<ReadOnlyFinalizationEvidence.Source> sources) {
        if (snapshot.representation() != DocumentTextDiff.Representation.EXACT_UTF8_TEXT) return false;
        var matches = sources.stream().filter(source -> path.equals(source.path())
                && ReadOnlyFinalizationEvidence.SOURCE_PROJECTION_VERSION.equals(source.projectionVersion())).toList();
        String projected = snapshot.text().lines().map(line -> line + "\n").collect(java.util.stream.Collectors.joining());
        return matches.size() == 1 && projected.equals(matches.getFirst().text());
    }

    private static String identity(TurnAuditCapture.DocumentComparison value) {
        return ReadOnlyWebDiagnosticResult.textHash("document-comparison-answer-v1\n" + DocumentTextDiff.VERSION + "\n"
                + ReadOnlyWebDiagnosticResult.textHash(value.request()) + "\n" + value.receipt().turn() + "\n"
                + value.receipt().left().relativePath() + "\n" + value.receipt().left().rawSha256() + "\n"
                + value.receipt().left().textSha256() + "\n" + value.receipt().left().representation() + "\n"
                + value.receipt().right().relativePath() + "\n" + value.receipt().right().rawSha256() + "\n"
                + value.receipt().right().textSha256() + "\n" + value.receipt().right().representation());
    }
}
