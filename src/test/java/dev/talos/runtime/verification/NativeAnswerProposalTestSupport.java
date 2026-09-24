package dev.talos.runtime.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.talos.runtime.task.AnswerResultRequirements;
import dev.talos.runtime.task.ReadOnlyRequestMeaning;
import dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidence.Source;

import java.util.List;

/** Fixture selections are derived after the actual observation catalog is frozen. */
public final class NativeAnswerProposalTestSupport {
    private static final ObjectMapper JSON = new ObjectMapper();

    private NativeAnswerProposalTestSupport() {}

    public static String candidate(String request, List<Source> sources) {
        return proposal(request, sources).toString();
    }

    public static String candidate(ReadOnlyRequestMeaning meaning, List<Source> sources) {
        return proposal(meaning, sources).toString();
    }

    public static ObjectNode proposal(ReadOnlyRequestMeaning meaning, List<Source> sources) {
        return proposal(NativeReadOnlyAnswerProposal.catalog(meaning, sources), sources);
    }

    public static String decisionCandidate(ReadOnlyRequestMeaning meaning, List<Source> sources) {
        return decisionProposal(meaning, sources).toString();
    }

    public static ObjectNode decisionProposal(ReadOnlyRequestMeaning meaning, List<Source> sources) {
        var catalog = NativeReadOnlyAnswerProposal.decisionCatalog(meaning, sources);
        ObjectNode result = proposal(catalog, sources);
        var rows = result.get("results");
        var decisions = result.putObject("results");
        for (int index = 0; index < catalog.requirements().slots().size(); index++) {
            var slot = catalog.requirements().slots().get(index);
            ObjectNode selected = rows.get(index).deepCopy();
            selected.remove("slotId");
            if (slot.role() == AnswerResultRequirements.Role.UNKNOWN
                    || slot.calculation() && selected.get("formulaId").isNull()) decisions.put(slot.id(), "unsupported");
            else decisions.set(slot.id(), selected);
        }
        return result;
    }

    public static String interpretedCandidate(String request, List<Source> sources) {
        return interpretedCandidate(request, sources, -1);
    }

    public static String interpretedCandidate(String request, List<Source> sources, int regionOffset) {
        var result = proposal(request, sources);
        var catalog = NativeReadOnlyAnswerProposal.catalog(request, sources);
        var interpretations = (ObjectNode) result.path("interpretationDecisions");
        catalog.relations().stream().filter(relation -> relation.targetId().equals(result.path("targetId").asText()))
                .filter(relation -> relation.kind() == NativeReadOnlyAnswerProposal.RelationKind.DIAGNOSIS)
                .filter(relation -> regionOffset < 0 || catalog.requirements().slots().stream().anyMatch(slot ->
                        slot.id().equals(relation.regionId()) && slot.start() <= regionOffset && regionOffset < slot.end()))
                .limit(Math.min(8, 32 - catalog.requirements().slots().size()))
                .forEach(relation -> interpretations.put(relation.regionId(), relation.id()));
        return result.toString();
    }

    public static ObjectNode proposal(String request, List<Source> sources) {
        return proposal(NativeReadOnlyAnswerProposal.catalog(request, sources), sources);
    }

    private static ObjectNode proposal(NativeReadOnlyAnswerProposal.Catalog catalog, List<Source> sources) {
        ObjectNode result = JSON.createObjectNode().put("version", catalog.version()).put("catalogId", catalog.id())
                .put("targetId", catalog.targets().getFirst().id());
        var decisions = result.putObject("interpretationDecisions");
        catalog.requirements().slots().stream().filter(slot -> slot.role() == AnswerResultRequirements.Role.UNKNOWN)
                .forEach(slot -> decisions.put(slot.id(), "unsupported"));
        var entries = result.putArray("results");
        for (var slot : catalog.requirements().slots()) {
            var entry = entries.addObject().put("slotId", slot.id());
            entry.putNull("formulaId");
            var bindings = entry.putArray("bindingIds");
            entry.putNull("precisionId");
            if (!slot.calculation()) continue;
            var formulas = catalog.formulas().stream().filter(formula -> switch (slot.role()) {
                case SOURCE_DEFINITION -> formula.origin() == ReadOnlyAnswerProposal.FormulaOrigin.SOURCE;
                case IMPLEMENTATION -> formula.origin() == ReadOnlyAnswerProposal.FormulaOrigin.IMPLEMENTATION;
                case REQUEST_EXPRESSION -> formula.origin() == ReadOnlyAnswerProposal.FormulaOrigin.REQUEST
                        && formula.span().start() >= slot.start() && formula.span().end() <= slot.end();
                default -> false;
            }).filter(formula -> slot.sourcePath().isEmpty()
                    || formula.origin() != ReadOnlyAnswerProposal.FormulaOrigin.REQUEST
                        && ObservedStaticWebSources.resolve("", slot.sourcePath()).equals(
                            ObservedStaticWebSources.resolve("", ReadOnlyAnswerProposal.source(sources, formula.sourceId()).path())))
                    .toList();
            if (formulas.isEmpty()) continue;
            var formula = formulas.getFirst();
            entry.put("formulaId", formula.id());
            catalog.bindings().stream().filter(binding -> binding.slotId().equals(slot.id())
                            && binding.formulaId().equals(formula.id()))
                    .forEach(binding -> bindings.add(binding.id()));
            catalog.precisions().stream().filter(precision -> precision.slotId().equals(slot.id())).findFirst()
                    .ifPresent(precision -> entry.put("precisionId", precision.id()));
        }
        return result;
    }

    public static String slotId(String request, List<Source> sources, AnswerResultRequirements.Role role) {
        return NativeReadOnlyAnswerProposal.catalog(request, sources).requirements().slots().stream()
                .filter(slot -> slot.role() == role).findFirst().orElseThrow().id();
    }
}
