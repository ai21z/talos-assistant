package dev.talos.runtime.verification;

import dev.talos.runtime.task.AnswerRequirements;
import dev.talos.runtime.task.AnswerResultRequirements;
import dev.talos.runtime.task.ReadOnlyRequestMeaning;
import dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidence.Source;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.talos.runtime.verification.ReadOnlyAnswerProposal.*;
import static dev.talos.runtime.verification.ReadOnlyAnswerComponents.Support;

/** Only retained facts and bounded calculations are rendered. */
final class ProposedWebDiagnosticAnswer {
    private static final String DECIMAL = "[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?";
    private static final Pattern OPERAND = Pattern.compile("(?:([A-Za-z_$][A-Za-z0-9_$]*)\\s*(?:=\\s*|\\s+))?("
            + DECIMAL + ")(?:\\s+([A-Za-z][A-Za-z0-9_/%^-]*))?");
    private static final Pattern PRECISION = Pattern.compile(
            "(?i)(?:(?:use|to|rounded to|round to)\\s+)?([0-9]{1,2})\\s+(?:decimal places?|fractional digits?|decimals?)");
    private static final Pattern PRECISION_SUFFIX = Pattern.compile(
            "(?i)(.+?)\\s+((?:rounded\\s+)?to\\s+([0-9]+)\\s+(?:decimal\\s+places?|fractional\\s+digits?|decimals?))");
    private static final Pattern OPERAND_JOIN = Pattern.compile("(?i)(?:,\\s*(?:and\\s+)?|\\band\\b)\\s*");

    private ProposedWebDiagnosticAnswer() {}

    static ReadOnlyWebDiagnosticResult assess(String request, Proposal proposal, List<Source> sources) {
        var selected = ObservedStaticWebSources.select(sources);
        var facts = selected.facts();
        if (facts == null) return limited("PROPOSAL_CONTEXT_UNAVAILABLE");
        var inspected = ObservedWebAnswerSources.inspect(facts.html(), facts.js());
        var html = inspected.html();
        var script = inspected.script();
        Source htmlSource = pathSource(sources, facts.htmlFile());
        Source scriptSource = pathSource(sources, facts.jsFile());
        unique(request, proposal.target().requestQuote());
        List<ObservedWebAnswerSources.Button> buttons = html.buttons().stream()
                .filter(button -> button.id().equals(proposal.target().buttonId())).toList();
        if (buttons.size() != 1) throw rejected("TARGET_REFERENCE_INVALID");
        var button = buttons.getFirst();
        var known = AnswerRequirements.fromRequest(request);
        if (!known.target().isEmpty() && !(known.targetIsId()
                ? known.target().equals(button.id()) : known.target().equalsIgnoreCase(button.label()))) {
            throw rejected("KNOWN_TARGET_MISMATCH");
        }
        if (known.unresolved().contains("AMBIGUOUS_BUTTON_TARGET")) throw rejected("KNOWN_TARGET_AMBIGUOUS");
        Map<Result, Scope> scopes = new LinkedHashMap<>();
        Set<String> consumed = new java.util.HashSet<>();
        boolean covered = proposal.results().stream().noneMatch(item -> unresolved(item.kind()));
        if (known.unresolved().contains("REQUIREMENT_BOUND_EXCEEDED")) covered = false;
        List<ReadOnlyAnswerComponents.Component> components = new ArrayList<>();
        List<Calculated> calculated = new ArrayList<>();
        boolean mismatch = script.supported() && !html.ids().contains(script.selector().substring(1));
        List<StaticWebDiagnostic> diagnostics = mismatch
                ? List.of(new StaticWebDiagnostic(StaticWebDiagnostic.Kind.JAVASCRIPT_MISSING_ID,
                    facts.jsFile(), facts.htmlFile(), List.of(script.selector().substring(1)),
                    "The listener lookup has no matching ID in the retained HTML."))
                : List.of();
        var defaults = AnswerRequirements.standalonePrecision(request);
        for (var entry : proposal.results()) {
            Data data;
            try {
                Span selectedSpan = unique(request, entry.requestQuote());
                if (scopes.entrySet().stream().anyMatch(prior -> prior.getKey().kind() == entry.kind()
                        && prior.getValue().requestSpan().overlaps(selectedSpan.start(), selectedSpan.end()))) {
                    throw rejected("RESULT_SCOPE_REUSED");
                }
                var matching = known.requirements().stream()
                        .filter(item -> item.kind().name().equals(entry.kind().name())
                                && selectedSpan.overlaps(item.start(), item.end())).toList();
                if (matching.size() > 1) throw rejected("RESULT_SCOPE_AMBIGUOUS");
                Span scopeSpan = matching.isEmpty() ? selectedSpan
                        : new Span(matching.getFirst().start(), matching.getFirst().end());
                var local = AnswerRequirements.fromCalculationClause(request, scopeSpan.start(), scopeSpan.end());
                boolean concrete = entry.kind() == Kind.CALCULATION
                        && (!local.quantity().isEmpty() || !local.expression().isEmpty());
                if (concrete && !matching.isEmpty() && !consumed.add(matching.getFirst().id())) {
                    throw rejected("RESULT_SCOPE_REUSED");
                }
                List<Span> otherClauses = known.requirements().stream()
                        .filter(item -> item.kind() != AnswerRequirements.Kind.UNKNOWN && !matching.contains(item))
                        .map(item -> new Span(item.start(), item.end())).toList();
                Scope scope = new Scope(concrete ? scopeSpan : selectedSpan, selectedSpan, local, concrete, otherClauses);
                scopes.put(entry, scope);
                data = switch (entry.kind()) {
                    case DIAGNOSIS -> diagnosis(mismatch, script, button, htmlSource, scriptSource);
                    case SELECTOR -> selector(mismatch, script, button, htmlSource, scriptSource);
                    case SCRIPT_DISTINCTION -> distinction(request, entry, sources, selected, known);
                    case CALCULATION -> {
                        Calculated value = calculation(request, entry.calculation(), sources, html, script,
                                htmlSource, scriptSource, scope, defaults, "proposal-" + (components.size() + 1));
                        calculated.add(value);
                        yield value.data();
                    }
                    case INPUT_VALIDATION -> unmet("INPUT_VALIDATION_UNRESOLVED",
                            "The requested input-validation analysis remains unresolved within the supported source analysis.");
                    case UNKNOWN -> unmet("INTERPRETED_REQUIREMENT_UNRESOLVED",
                            "An additional requested answer component remains unresolved.");
                };
            } catch (Rejected unsupported) {
                data = unmet(unsupported.reason, "The requested " + componentName(entry.kind())
                        + " remains unresolved because its references or supported bindings could not be established.");
            }
            components.add(component("proposal-" + (components.size() + 1), entry.kind().name(), data));
        }
        boolean omissionLimit = false;
        for (var requirement : known.requirements()) {
            if (requirement.kind() == AnswerRequirements.Kind.UNKNOWN) continue;
            boolean supplied = scopes.entrySet().stream().anyMatch(entry ->
                    entry.getKey().kind().name().equals(requirement.kind().name())
                            && entry.getValue().span().overlaps(requirement.start(), requirement.end()));
            if (!supplied) {
                covered = false;
                if (components.size() == MAX_ITEMS) { omissionLimit = true; continue; }
                components.add(component(requirement.id(), requirement.kind().name(),
                        unmet("KNOWN_REQUIREMENT_OMITTED", "An independently identified requested component was omitted from the interpretation.")));
            }
        }
        boolean comparisonLimit = false;
        for (int first = 0; first < calculated.size(); first++) {
            for (int second = first + 1; second < calculated.size(); second++) {
                Data comparison = comparison(calculated.get(first), calculated.get(second));
                if (comparison == null) continue;
                if (components.size() == MAX_ITEMS) { comparisonLimit = true; covered = false; continue; }
                components.add(component("comparison-" + first + "-" + second, "COMPARISON", comparison));
            }
        }
        if (comparisonLimit || omissionLimit || known.unresolved().contains("REQUIREMENT_BOUND_EXCEEDED")) {
            var last = components.getLast();
            String notice = comparisonLimit ? " Further comparisons were withheld by the answer component limit." : "";
            if (omissionLimit) notice += " Additional missing requirements could not be listed within the answer component limit.";
            if (known.unresolved().contains("REQUIREMENT_BOUND_EXCEEDED")) notice += " The request exceeded the supported requirement count.";
            if (last.text().length() + notice.length() <= ReadOnlyAnswerComponents.MAX_COMPONENT_TEXT_CHARS) {
                components.set(components.size() - 1, new ReadOnlyAnswerComponents.Component(last.requirementId(),
                        last.kind(), last.support(), last.reason(), last.text() + notice, last.references(), last.derivationHash()));
            }
        }
        return result(components, covered, diagnostics);
    }

    static ReadOnlyWebDiagnosticResult assessNative(String request, NativeProposalBinding binding, List<Source> sources) {
        return assessNative(request, binding, sources, NativeReadOnlyAnswerProposal.catalog(request, sources));
    }

    static ReadOnlyWebDiagnosticResult assessNative(String request, NativeProposalBinding binding, List<Source> sources,
                                                    NativeReadOnlyAnswerProposal.Catalog catalog) {
        var meaning = catalog.meaning();
        if (meaning != null && !meaning.request().equals(request)) throw rejected("NATIVE_REQUEST_MEANING_MISMATCH");
        var ledger = catalog.requirements();
        if (ledger.slots().size() != binding.results().size()) throw rejected("NATIVE_LEDGER_MISMATCH");
        Set<String> ids = new java.util.HashSet<>();
        for (int index = 0; index < ledger.slots().size(); index++) {
            var bound = binding.results().get(index);
            if (!ledger.slots().get(index).equals(bound.slot()) || !bound.slotId().equals(bound.slot().id())
                    || !ids.add(bound.slotId())) throw rejected("NATIVE_LEDGER_MISMATCH");
        }
        var nativeDecisions = nativeInterpretations(request, binding, sources, ledger, catalog);
        Proposal proposal = binding.proposal();
        nativeReference(request, proposal.target().requestQuote(), binding.targetSpan());
        var selected = ObservedStaticWebSources.select(sources);
        var facts = selected.facts();
        if (facts == null) return limited("PROPOSAL_CONTEXT_UNAVAILABLE");
        var inspected = ObservedWebAnswerSources.inspect(facts.html(), facts.js());
        var html = inspected.html();
        var script = inspected.script();
        Source htmlSource = pathSource(sources, facts.htmlFile());
        Source scriptSource = pathSource(sources, facts.jsFile());
        List<ObservedWebAnswerSources.Button> buttons = html.buttons().stream()
                .filter(button -> button.id().equals(proposal.target().buttonId())).toList();
        if (buttons.size() != 1) throw rejected("TARGET_REFERENCE_INVALID");
        var button = buttons.getFirst();
        var known = meaning == null ? AnswerRequirements.fromRequest(request) : null;
        String requestTarget = meaning == null ? known.target() : meaning.target();
        boolean targetIsId = meaning == null ? known.targetIsId() : meaning.targetIsId();
        if (!requestTarget.isEmpty() && !(targetIsId
                ? requestTarget.equals(button.id()) : requestTarget.equalsIgnoreCase(button.label()))) {
            throw rejected("KNOWN_TARGET_MISMATCH");
        }
        if (meaning == null ? known.unresolved().contains("AMBIGUOUS_BUTTON_TARGET") : meaning.targetAmbiguous())
            throw rejected("KNOWN_TARGET_AMBIGUOUS");
        long matchingTargets = html.buttons().stream().filter(observed -> targetIsId
                ? requestTarget.equals(observed.id()) : requestTarget.equalsIgnoreCase(observed.label())).count();
        boolean mismatch = script.supported() && !html.ids().contains(script.selector().substring(1));
        List<StaticWebDiagnostic> diagnostics = mismatch
                ? List.of(new StaticWebDiagnostic(StaticWebDiagnostic.Kind.JAVASCRIPT_MISSING_ID,
                    facts.jsFile(), facts.htmlFile(), List.of(script.selector().substring(1)),
                    "The listener lookup has no matching ID in the retained HTML.")) : List.of();
        var defaults = AnswerRequirements.standalonePrecision(request);
        List<ReadOnlyAnswerComponents.Component> components = new ArrayList<>();
        Map<String, Calculated> calculated = new LinkedHashMap<>();
        Map<String, NativeProposalBinding.BoundResult> bindings = new LinkedHashMap<>();
        Set<String> comparisons = new java.util.HashSet<>();
        for (int index = 0; index < binding.results().size(); index++) {
            var bound = binding.results().get(index);
            bindings.put(bound.slotId(), bound);
            Result entry = proposal.results().get(index);
            Data data;
            try {
                if (!bound.rejectionReason().isEmpty()) throw rejected(bound.rejectionReason());
                Kind expected = nativeKind(bound.slot().role());
                if (entry.kind() != expected) throw rejected("NATIVE_RESULT_ROLE_MISMATCH");
                Span resultSpan = new Span(bound.slot().start(), bound.slot().end());
                nativeReference(request, entry.requestQuote(), resultSpan);
                if (catalog.version() == 9 && (bound.slot().role() == AnswerResultRequirements.Role.DIAGNOSIS
                        || bound.slot().role() == AnswerResultRequirements.Role.SELECTOR)) {
                    if (requestTarget.isEmpty()) throw rejected("KNOWN_TARGET_UNBOUND");
                    if (matchingTargets != 1) throw rejected("KNOWN_TARGET_AMBIGUOUS");
                }
                if (bound.slot().calculation()) {
                    nativeFormulaRole(entry.calculation(), bound, sources, scriptSource);
                    var local = AnswerRequirements.fromCalculationClause(request, resultSpan.start(), resultSpan.end());
                    Scope scope = new Scope(resultSpan, resultSpan, local, true, List.of());
                    var value = calculation(request, entry.calculation(), sources, html, script, htmlSource,
                            scriptSource, scope, defaults, bound.slotId(), bound);
                    calculated.put(bound.slotId(), value);
                    data = value.data();
                } else {
                    data = switch (bound.slot().role()) {
                        case DIAGNOSIS -> diagnosis(mismatch, script, button, htmlSource, scriptSource);
                        case SELECTOR -> selector(mismatch, script, button, htmlSource, scriptSource);
                        case SCRIPT_DISTINCTION -> meaning == null ? distinction(request, entry, sources, selected, known)
                                : meaningDistinction(entry.scripts(), sources, selected, meaning);
                        case COMPARISON -> unmet("COMPARISON_PEER_UNAVAILABLE", "The requested comparison remains unresolved.");
                        case UNKNOWN -> nativeDecisionData(nativeDecisions.decisions().get(bound.slotId()));
                        default -> unmet("NATIVE_REQUIREMENT_UNRESOLVED", "An additional requested answer component remains unresolved.");
                    };
                    if (bound.slot().role() == AnswerResultRequirements.Role.COMPARISON) comparisons.add(bound.slotId());
                }
            } catch (Rejected invalid) {
                data = unmet(invalid.reason, "The requested " + nativeComponentName(bound.slot().role())
                        + ("NATIVE_RESULT_UNSUPPORTED".equals(invalid.reason)
                            ? " remains unresolved because an unsupported result decision was supplied."
                            : " remains unresolved because its references or supported bindings could not be established."));
            }
            var decision = nativeDecisions.decisions().get(bound.slotId());
            if (decision == null) {
                if (meaning != null) data = new Data(data.support(), data.reason(), data.text(), data.references(),
                        ReadOnlyWebDiagnosticResult.textHash("request-meaning-result-v1\n" + meaning.identity()
                                + "\n" + bound.slotId() + "\n" + data.derivation()));
                components.add(component(bound.slotId(), nativeComponentKind(bound.slot().role()), data));
            }
            else components.add(new ReadOnlyAnswerComponents.Component(bound.slotId(), "UNKNOWN", data.support(),
                    data.reason(), data.text(), data.references(), nativeDecisionHash(decision), null, decision.decision()));
        }
        for (int index = 0; index < binding.results().size(); index++) {
            var bound = binding.results().get(index);
            if (!comparisons.contains(bound.slotId())) continue;
            Data compared = nativeComparison(bound, bindings, calculated);
            if (meaning != null) compared = new Data(compared.support(), compared.reason(), compared.text(), compared.references(),
                    ReadOnlyWebDiagnosticResult.textHash("request-meaning-result-v1\n" + meaning.identity()
                            + "\n" + bound.slotId() + "\n" + compared.derivation()));
            components.set(index, component(bound.slotId(), "COMPARISON", compared));
        }
        for (var interpretation : nativeDecisions.decisions().values()) {
            if (interpretation.decision() != ReadOnlyAnswerInterpretation.Decision.SELECTED) continue;
            var relation = nativeDecisions.relations().get(interpretation.regionId());
            Data observed = nativeObservation(request, relation, sources, selected, known,
                    mismatch, script, button, htmlSource, scriptSource);
            String type = switch (relation.kind()) {
                case DIAGNOSIS -> "diagnosis";
                case SELECTOR -> "selector correction";
                case SCRIPT_DISTINCTION -> "script distinction";
            };
            Data supplement = observed.support() == Support.ATTRIBUTED_INTERPRETATION
                    ? new Data(Support.ATTRIBUTED_INTERPRETATION, "MODEL_INTERPRETED_REGION_SOURCE_RELATION",
                        "An additional " + type + " source observation was selected through model interpretation for unresolved request region "
                            + code(interpretation.regionId()) + ". Its relevance to that region was not established. "
                            + observed.text(), observed.references(),
                        ReadOnlyWebDiagnosticResult.textHash("native-answer-interpretation-v6\n"
                            + interpretation.requestSha256() + "\n" + interpretation.catalogId() + "\n"
                            + interpretation.regionId() + "\n" + interpretation.relationId() + "\n" + interpretation.targetId()
                            + "\n" + relation.kind() + "\n" + String.join("\n", relation.sourceIds())))
                    : unmet("NATIVE_INTERPRETATION_RELATION_UNAVAILABLE",
                        "An additional source observation was not established from the retained source relations.");
            ReadOnlyAnswerComponents.ObservationPresentation presentation = null;
            if (supplement.support() == Support.ATTRIBUTED_INTERPRETATION) {
                String groupId = ReadOnlyWebDiagnosticResult.textHash("native-observation-presentation-v6\n"
                        + interpretation.catalogId() + "\n" + relation.kind() + "\n" + relation.targetId()
                        + "\n" + String.join("\n", relation.sourceIds()));
                presentation = new ReadOnlyAnswerComponents.ObservationPresentation(groupId, relation.kind().name(),
                        interpretation.regionId(), observed.text());
            }
            components.add(new ReadOnlyAnswerComponents.Component(interpretation.regionId() + ":" + interpretation.relationId(),
                    "OBSERVATION", supplement.support(), supplement.reason(), supplement.text(), supplement.references(),
                    supplement.derivation(), presentation, null));
        }
        boolean covered = nativeDecisions.decisions().isEmpty() && binding.requestCovered() && ledger.complete()
                && components.stream().allMatch(ReadOnlyAnswerComponents.Component::met);
        var origin = meaning != null ? ReadOnlyAnswerComponents.CoverageOrigin.REQUEST_MEANING
                : nativeDecisions.decisions().isEmpty() ? ReadOnlyAnswerComponents.CoverageOrigin.NATIVE_LEDGER
                : ReadOnlyAnswerComponents.CoverageOrigin.NATIVE_LEDGER_WITH_INTERPRETATIONS;
        return result(components, covered, diagnostics, origin);
    }

    private record NativeDecisions(Map<String, ReadOnlyAnswerInterpretation> decisions,
                                   Map<String, NativeReadOnlyAnswerProposal.SourceRelation> relations) {}

    private static NativeDecisions nativeInterpretations(
            String request, NativeProposalBinding binding, List<Source> sources, AnswerResultRequirements ledger,
            NativeReadOnlyAnswerProposal.Catalog catalog) {
        long selectedCount = binding.interpretations().stream()
                .filter(value -> value.decision() == ReadOnlyAnswerInterpretation.Decision.SELECTED).count();
        if (binding.interpretations().size() > 32 || selectedCount > 8 || binding.results().size() + selectedCount > 32)
            throw rejected("NATIVE_INTERPRETATION_COMPONENT_BOUND");
        var unknown = ledger.slots().stream().filter(slot -> slot.role() == AnswerResultRequirements.Role.UNKNOWN).toList();
        if (unknown.isEmpty() && binding.interpretations().isEmpty()) return new NativeDecisions(Map.of(), Map.of());
        Map<String, ReadOnlyAnswerInterpretation> supplied = new LinkedHashMap<>();
        Map<String, NativeReadOnlyAnswerProposal.SourceRelation> relations = new LinkedHashMap<>();
        var owners = catalog.targets().stream().filter(target -> target.buttonId().equals(binding.proposal().target().buttonId())
                && target.requestSpan().equals(binding.targetSpan())).toList();
        if (owners.size() != 1) throw rejected("NATIVE_INTERPRETATION_TARGET_MISMATCH");
        for (var interpretation : binding.interpretations()) {
            if (!interpretation.requestSha256().equals(ledger.requestSha256())
                    || !interpretation.catalogId().equals(catalog.id())) throw rejected("NATIVE_INTERPRETATION_IDENTITY_MISMATCH");
            if (unknown.stream().noneMatch(slot -> slot.id().equals(interpretation.regionId())))
                throw rejected("NATIVE_INTERPRETATION_REGION_INVALID");
            if (!owners.getFirst().id().equals(interpretation.targetId())) throw rejected("NATIVE_INTERPRETATION_TARGET_MISMATCH");
            if (supplied.putIfAbsent(interpretation.regionId(), interpretation) != null)
                throw rejected("NATIVE_INTERPRETATION_DUPLICATE");
            if (interpretation.decision() != ReadOnlyAnswerInterpretation.Decision.SELECTED) {
                if (!interpretation.relationId().isEmpty()) throw rejected("NATIVE_INTERPRETATION_RELATION_INVALID");
                continue;
            }
            var matching = catalog.relations().stream().filter(relation -> relation.id().equals(interpretation.relationId())
                    && relation.regionId().equals(interpretation.regionId())
                    && relation.targetId().equals(interpretation.targetId())).toList();
            if (matching.size() != 1) throw rejected("NATIVE_INTERPRETATION_RELATION_INVALID");
            relations.put(interpretation.regionId(), matching.getFirst());
        }
        Map<String, ReadOnlyAnswerInterpretation> decisions = new LinkedHashMap<>();
        for (var slot : unknown) decisions.put(slot.id(), supplied.getOrDefault(slot.id(),
                new ReadOnlyAnswerInterpretation(ledger.requestSha256(), catalog.id(), slot.id(), "",
                        owners.getFirst().id(), ReadOnlyAnswerInterpretation.Decision.MISSING)));
        return new NativeDecisions(java.util.Collections.unmodifiableMap(decisions), Map.copyOf(relations));
    }

    private static Data nativeDecisionData(ReadOnlyAnswerInterpretation interpretation) {
        String reason = switch (interpretation.decision()) {
            case SELECTED -> "NATIVE_INTERPRETATION_SELECTED_UNCONFIRMED";
            case UNSUPPORTED -> "NATIVE_INTERPRETATION_UNSUPPORTED";
            case MISSING -> "NATIVE_INTERPRETATION_DECISION_MISSING";
        };
        String decision = switch (interpretation.decision()) {
            case SELECTED -> "A source observation was selected through model interpretation, without establishing request fulfillment.";
            case UNSUPPORTED -> "No supported source interpretation was selected.";
            case MISSING -> "An interpretation decision was not supplied.";
        };
        return unmet(reason, "An additional requested answer component remains unresolved for region "
                + code(interpretation.regionId()) + ". " + decision);
    }

    private static String nativeDecisionHash(ReadOnlyAnswerInterpretation interpretation) {
        return ReadOnlyWebDiagnosticResult.textHash("native-interpretation-decision-v6\n"
                + interpretation.requestSha256() + "\n" + interpretation.catalogId() + "\n"
                + interpretation.regionId() + "\n" + interpretation.decision() + "\n"
                + interpretation.relationId() + "\n" + interpretation.targetId());
    }

    private static Data nativeObservation(String request, NativeReadOnlyAnswerProposal.SourceRelation relation,
                                          List<Source> sources, ObservedStaticWebSources.Selection selected,
                                          AnswerRequirements known, boolean mismatch, ObservedWebAnswerSources.Script script,
                                          ObservedWebAnswerSources.Button button, Source htmlSource, Source scriptSource) {
        List<String> sourceIds = relation.sourceIds();
        int expected = relation.kind() == NativeReadOnlyAnswerProposal.RelationKind.SCRIPT_DISTINCTION ? 3 : 2;
        if (sourceIds.size() != expected || new java.util.HashSet<>(sourceIds).size() != expected
                || !source(sources, sourceIds.get(0)).equals(htmlSource)
                || !source(sources, sourceIds.get(1)).equals(scriptSource)) {
            throw rejected("NATIVE_INTERPRETATION_SOURCE_MISMATCH");
        }
        return switch (relation.kind()) {
            case DIAGNOSIS -> diagnosis(mismatch, script, button, htmlSource, scriptSource);
            case SELECTOR -> selector(mismatch, script, button, htmlSource, scriptSource);
            case SCRIPT_DISTINCTION -> distinction(request, sourceIds.subList(1, 3), sources, selected, known);
        };
    }

    private static Kind nativeKind(AnswerResultRequirements.Role role) {
        return switch (role) {
            case DIAGNOSIS -> Kind.DIAGNOSIS;
            case SELECTOR -> Kind.SELECTOR;
            case SCRIPT_DISTINCTION -> Kind.SCRIPT_DISTINCTION;
            case SOURCE_DEFINITION, IMPLEMENTATION, REQUEST_EXPRESSION -> Kind.CALCULATION;
            case COMPARISON, UNKNOWN -> Kind.UNKNOWN;
        };
    }

    private static String nativeComponentKind(AnswerResultRequirements.Role role) {
        return role == AnswerResultRequirements.Role.COMPARISON ? "COMPARISON" : nativeKind(role).name();
    }

    private static String nativeComponentName(AnswerResultRequirements.Role role) {
        return role == AnswerResultRequirements.Role.COMPARISON ? "comparison" : componentName(nativeKind(role));
    }

    private static void nativeFormulaRole(Calculation proposed, NativeProposalBinding.BoundResult bound,
                                          List<Source> sources, Source scriptSource) {
        if (proposed == null) throw rejected("NATIVE_FORMULA_ROLE_MISMATCH");
        FormulaOrigin expected = switch (bound.slot().role()) {
            case SOURCE_DEFINITION -> FormulaOrigin.SOURCE;
            case IMPLEMENTATION -> FormulaOrigin.IMPLEMENTATION;
            case REQUEST_EXPRESSION -> FormulaOrigin.REQUEST;
            default -> throw rejected("NATIVE_FORMULA_ROLE_MISMATCH");
        };
        if (proposed.formulaOrigin() != expected) throw rejected("NATIVE_FORMULA_ROLE_MISMATCH");
        if (expected != FormulaOrigin.REQUEST) {
            Source formulaSource = source(sources, proposed.formulaReference().sourceId());
            if (!bound.slot().sourcePath().isEmpty()
                    && !ObservedStaticWebSources.resolve("", bound.slot().sourcePath())
                        .equals(ObservedStaticWebSources.resolve("", formulaSource.path()))) {
                throw rejected("NATIVE_FORMULA_SOURCE_MISMATCH");
            }
            if (expected == FormulaOrigin.IMPLEMENTATION && !formulaSource.equals(scriptSource)) {
                throw rejected("IMPLEMENTATION_SOURCE_MISMATCH");
            }
        } else if (bound.formulaSpan() == null
                || bound.formulaSpan().start() != bound.slot().expressionStart()
                || bound.formulaSpan().end() != bound.slot().expressionEnd()
                || !bound.slot().quantity().equals(proposed.formulaReference().uniqueQuote())) {
            throw rejected("KNOWN_EXPRESSION_CHANGED");
        }
    }

    private static Data nativeComparison(NativeProposalBinding.BoundResult bound,
                                         Map<String, NativeProposalBinding.BoundResult> bindings,
                                         Map<String, Calculated> calculated) {
        Data unavailable = unmet("COMPARISON_PEER_UNAVAILABLE",
                "The requested comparison remains unresolved because both role-bound results with compatible supplied inputs were not established.");
        List<String> peers = bound.slot().peerSlotIds();
        if (peers.size() != 2 || peers.getFirst().equals(peers.getLast())) return unavailable;
        var firstBinding = bindings.get(peers.getFirst());
        var secondBinding = bindings.get(peers.getLast());
        if (firstBinding == null || secondBinding == null
                || firstBinding.slot().role() != AnswerResultRequirements.Role.SOURCE_DEFINITION
                || secondBinding.slot().role() != AnswerResultRequirements.Role.IMPLEMENTATION
                || firstBinding.slot().operandStart() != secondBinding.slot().operandStart()
                || firstBinding.slot().operandEnd() != secondBinding.slot().operandEnd()) return unavailable;
        var first = calculated.get(peers.getFirst());
        var second = calculated.get(peers.getLast());
        if (first == null || second == null) return unavailable;
        Data compared = comparison(first, second);
        return compared == null ? unavailable : compared;
    }

    private static Data diagnosis(boolean mismatch, ObservedWebAnswerSources.Script script,
                                  ObservedWebAnswerSources.Button button, Source htmlSource, Source scriptSource) {
        return mismatch
                ? attributed("The listener in " + code(ObservedStaticWebSources.resolve("", scriptSource.path()))
                    + " queries " + code(script.selector()) + ", which has no matching declared ID in "
                    + code(ObservedStaticWebSources.resolve("", htmlSource.path()))
                    + ". Under standard document query behavior for that declared DOM, the lookup would be null and the direct listener attachment would fail. This is a source-conditioned inference, not an observed browser result.",
                    List.of(lineReference(scriptSource, script.queryLine()), lineReference(scriptSource, script.listenerLine()),
                            lineReference(htmlSource, button.line())))
                : unmet("DIAGNOSIS_RELATION_UNAVAILABLE", "The requested button failure was not established by the supported source relations.");
    }

    private static Data selector(boolean mismatch, ObservedWebAnswerSources.Script script,
                                 ObservedWebAnswerSources.Button button, Source htmlSource, Source scriptSource) {
        return mismatch
                ? attributed("For the declared " + code(button.label()) + " button, " + code(script.selector())
                    + " should be replaced by " + code("#" + button.id())
                    + " in that listener lookup. This addresses the selector mismatch, not every possible application defect.",
                    List.of(lineReference(scriptSource, script.queryLine()), lineReference(htmlSource, button.line())))
                : unmet("SELECTOR_RELATION_UNAVAILABLE", "A replacement selector was not established by the supported source relations.");
    }

    private static Data distinction(String request, Result entry, List<Source> sources,
                                    ObservedStaticWebSources.Selection selected, AnswerRequirements known) {
        return distinction(request, entry.scripts(), sources, selected, known);
    }

    private static Data meaningDistinction(List<String> scripts, List<Source> sources,
                                           ObservedStaticWebSources.Selection selected, ReadOnlyRequestMeaning meaning) {
        if (meaning.scriptsAmbiguous() || meaning.scriptTargets().size() != 2)
            throw rejected("KNOWN_SCRIPT_TARGET_AMBIGUOUS");
        if (scripts.size() != 2) throw rejected("SCRIPT_REFERENCE_INVALID");
        Set<String> expected = new java.util.HashSet<>();
        for (String path : meaning.scriptTargets()) expected.add(ObservedStaticWebSources.resolve("", path));
        Set<String> actual = new java.util.HashSet<>();
        for (String id : scripts) actual.add(ObservedStaticWebSources.resolve("", source(sources, id).path()));
        if (expected.size() != 2 || !expected.equals(actual)) throw rejected("KNOWN_SCRIPT_TARGET_MISMATCH");
        return scriptLinkage(scripts, sources, selected);
    }

    private static Data distinction(String request, List<String> scripts, List<Source> sources,
                                    ObservedStaticWebSources.Selection selected, AnswerRequirements known) {
        if (scripts.size() != 2) throw rejected("SCRIPT_REFERENCE_INVALID");
        if (known.unresolved().contains("AMBIGUOUS_SCRIPT_TARGETS")) {
            Set<Set<String>> namedPairs = new java.util.HashSet<>();
            for (var requirement : known.requirements()) {
                if (requirement.kind() != AnswerRequirements.Kind.SCRIPT_DISTINCTION) continue;
                var clause = AnswerRequirements.fromRequest(request.substring(requirement.start(), requirement.end()));
                if (!clause.scriptTargets().isEmpty()) {
                    Set<String> pair = new java.util.HashSet<>();
                    for (String path : clause.scriptTargets()) pair.add(ObservedStaticWebSources.resolve("", path));
                    namedPairs.add(Set.copyOf(pair));
                }
            }
            long scriptCount = sources.stream().map(source -> ObservedStaticWebSources.resolve("", source.path()))
                    .filter(path -> path.toLowerCase(Locale.ROOT).endsWith(".js")).distinct().count();
            if (namedPairs.size() > 1 || scriptCount > 2) throw rejected("KNOWN_SCRIPT_TARGET_AMBIGUOUS");
        }
        Source first = source(sources, scripts.get(0));
        Source second = source(sources, scripts.get(1));
        String firstPath = ObservedStaticWebSources.resolve("", first.path());
        String secondPath = ObservedStaticWebSources.resolve("", second.path());
        String linked = selected.facts().jsFile();
        if (!firstPath.toLowerCase(Locale.ROOT).endsWith(".js") || !secondPath.toLowerCase(Locale.ROOT).endsWith(".js")
                || firstPath.equals(secondPath) || !Set.of(firstPath, secondPath).contains(linked)) {
            throw rejected("SCRIPT_REFERENCE_INVALID");
        }
        if (!known.scriptTargets().isEmpty()) {
            Set<String> expected = new java.util.HashSet<>();
            for (String path : known.scriptTargets()) expected.add(ObservedStaticWebSources.resolve("", path));
            if (!expected.equals(Set.of(firstPath, secondPath))) throw rejected("KNOWN_SCRIPT_TARGET_MISMATCH");
        }
        return scriptLinkage(scripts, sources, selected);
    }

    private static Data scriptLinkage(List<String> scripts, List<Source> sources,
                                      ObservedStaticWebSources.Selection selected) {
        if (scripts.size() != 2) throw rejected("SCRIPT_REFERENCE_INVALID");
        Source first = source(sources, scripts.get(0));
        Source second = source(sources, scripts.get(1));
        String firstPath = ObservedStaticWebSources.resolve("", first.path());
        String secondPath = ObservedStaticWebSources.resolve("", second.path());
        String linked = selected.facts().jsFile();
        if (firstPath.equals(secondPath) || !firstPath.toLowerCase(Locale.ROOT).endsWith(".js")
                || !secondPath.toLowerCase(Locale.ROOT).endsWith(".js")
                || !Set.of(firstPath, secondPath).contains(linked)) throw rejected("SCRIPT_REFERENCE_INVALID");
        Source other = firstPath.equals(linked) ? second : first;
        return attributed(code(selected.facts().htmlFile()) + " links " + code(linked) + ". "
                + code(other.path()) + " was also read, but is not that document's declared script. Its execution elsewhere was not assessed.",
                List.of(fullReference(pathSource(sources, selected.facts().htmlFile())), fullReference(other)));
    }

    private static Calculated calculation(String request, Calculation proposed, List<Source> sources,
                                    ObservedWebAnswerSources.Html html, ObservedWebAnswerSources.Script script,
                                    Source htmlSource, Source scriptSource, Scope scope,
                                    AnswerRequirements defaults, String resultId) {
        return calculation(request, proposed, sources, html, script, htmlSource, scriptSource,
                scope, defaults, resultId, null);
    }

    private static Calculated calculation(String request, Calculation proposed, List<Source> sources,
                                    ObservedWebAnswerSources.Html html, ObservedWebAnswerSources.Script script,
                                    Source htmlSource, Source scriptSource, Scope scope,
                                    AnswerRequirements defaults, String resultId,
                                    NativeProposalBinding.BoundResult nativeResult) {
        AnswerRequirements known = scope.known();
        NativeInputs nativeInputs = nativeResult == null ? null : nativeInputs(request, nativeResult);
        for (String reason : List.of("CONFLICTING_REQUEST_PRECISION", "PRECISION_BOUND_EXCEEDED",
                "DECIMAL_LITERAL_BOUND_EXCEEDED", "OPERAND_BOUND_EXCEEDED", "DUPLICATE_OPERAND_NAME",
                "UNSUPPORTED_OPERAND_BINDING", "OPERAND_NAME_OR_UNIT_REQUIRED")) {
            if (known.unresolved().contains(reason) && scope.concrete()) throw rejected("KNOWN_" + reason);
        }
        if (defaults.unresolved().contains("CONFLICTING_REQUEST_PRECISION")
                || defaults.unresolved().contains("PRECISION_BOUND_EXCEEDED")) {
            throw rejected("KNOWN_GLOBAL_PRECISION_INVALID");
        }
        boolean implementation = proposed.formulaOrigin() == FormulaOrigin.IMPLEMENTATION;
        Source formulaSource = null;
        Span formulaSpan = null;
        String expressionText;
        if (implementation) {
            formulaSource = source(sources, proposed.formulaReference().sourceId());
            if (!formulaSource.equals(scriptSource)) throw rejected("IMPLEMENTATION_SOURCE_MISMATCH");
            if (!script.calculationSupported()) throw rejected("IMPLEMENTATION_EXPRESSION_UNAVAILABLE");
            expressionText = script.expression();
        } else {
            String formulaText;
            if (proposed.formulaOrigin() == FormulaOrigin.REQUEST) {
                if (!proposed.formulaReference().sourceId().equals("request")) throw rejected("FORMULA_ORIGIN_INVALID");
                formulaText = request;
                formulaSpan = nativeResult == null
                        ? scopedReference(request, proposed.formulaReference().uniqueQuote(), scope)
                        : nativeReference(request, proposed.formulaReference().uniqueQuote(), nativeResult.formulaSpan());
            } else {
                formulaSource = source(sources, proposed.formulaReference().sourceId());
                formulaText = formulaSource.text();
                formulaSpan = nativeResult == null
                        ? unique(formulaText, proposed.formulaReference().uniqueQuote())
                        : nativeReference(formulaText, proposed.formulaReference().uniqueQuote(), nativeResult.formulaSpan());
            }
            if (!tokenBoundary(formulaText, formulaSpan)) throw rejected("FORMULA_REFERENCE_BOUNDARY_INVALID");
            expressionText = formulaText.substring(formulaSpan.start(), formulaSpan.end());
        }
        var expression = BoundedAnswerArithmetic.parse(expressionText);
        if (!expression.successful()) throw rejected("CALCULATION_" + expression.status().name());
        if (proposed.formulaOrigin() == FormulaOrigin.SOURCE && expression.variables().isEmpty()) {
            throw rejected("SOURCE_FORMULA_VARIABLE_REQUIRED");
        }
        Map<String, BoundOperand> bound = new LinkedHashMap<>();
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, String> inputValues = new LinkedHashMap<>();
        Map<String, String> formulaInputIds = new LinkedHashMap<>();
        if (nativeResult != null && nativeResult.operandSpans().size() != proposed.operands().size()) {
            throw rejected("NATIVE_OPERAND_BINDING_INVALID");
        }
        for (int operandIndex = 0; operandIndex < proposed.operands().size(); operandIndex++) {
            var operand = proposed.operands().get(operandIndex);
            Span span = nativeResult == null ? scopedReference(request, operand.requestQuote(), scope)
                    : nativeReference(request, operand.requestQuote(), nativeResult.operandSpans().get(operandIndex));
            if (nativeResult != null) nativeOperandScope(span, nativeResult);
            Matcher literal = OPERAND.matcher(operand.requestQuote());
            if (!literal.matches() || !tokenBoundary(request, span)) throw rejected("REQUEST_OPERAND_INVALID");
            String name = literal.group(1) == null ? "" : literal.group(1);
            String value = literal.group(2);
            String unit = literal.group(3) == null ? "" : literal.group(3);
            if (nativeInputs != null && nativeInputs.operands().stream().noneMatch(expected ->
                    expected.start() == span.start() && expected.end() == span.end()
                            && expected.name().equals(name) && expected.value().equals(value) && expected.unit().equals(unit))) {
                throw rejected("NATIVE_OPERAND_REFERENCE_MISMATCH");
            }
            if (nativeResult != null && (bound.containsKey(operand.variable())
                    || proposed.formulaOrigin() != FormulaOrigin.REQUEST && inputValues.containsKey(operand.inputId()))) {
                throw rejected("NATIVE_OPERAND_BINDING_REUSED");
            }
            if (proposed.formulaOrigin() == FormulaOrigin.REQUEST) {
                if (!name.equals(operand.variable()) || !unit.isEmpty()) throw rejected("REQUEST_OPERAND_BINDING_INVALID");
                bound.put(operand.variable(), new BoundOperand(operand, value, unit, name, span));
                values.put(operand.variable(), value);
                continue;
            }
            var input = html.inputs().get(operand.inputId());
            if (input == null) throw rejected("INPUT_REFERENCE_INVALID");
            boolean matchedVariable = implementation
                    ? operand.inputId().equals(script.operands().get(operand.variable()))
                    : labelName(input.label()).equalsIgnoreCase(operand.variable());
            if (!html.labelReason().isEmpty() || input.label().isEmpty() || !matchedVariable
                    || input.unit().isEmpty() || !input.unit().equals(unit)) {
                throw rejected("SOURCE_OPERAND_BINDING_INVALID");
            }
            if (!name.isEmpty() && !name.equalsIgnoreCase(operand.variable())
                    && !name.equalsIgnoreCase(labelName(input.label()))) {
                throw rejected("REQUEST_OPERAND_BINDING_INVALID");
            }
            bound.put(operand.variable(), new BoundOperand(operand, value, unit, name, span));
            values.put(operand.variable(), value);
            var exactInput = BoundedAnswerArithmetic.parse(value).evaluate(Map.of(), 0);
            if (!exactInput.successful()) throw rejected("REQUEST_OPERAND_INVALID");
            inputValues.put(operand.inputId(), exactInput.exact());
            formulaInputIds.put(operand.variable(), operand.inputId());
        }
        if (!expression.variables().equals(values.keySet())) throw rejected("FORMULA_VARIABLE_BINDING_INVALID");
        for (var operand : nativeInputs == null ? known.operands() : nativeInputs.operands()) {
            if (nativeInputs != null && implementation && unusedImplementationInput(operand, html, script, expression.variables())) continue;
            boolean matched = bound.values().stream().anyMatch(item -> item.span().overlaps(operand.start(), operand.end())
                    && (nativeInputs == null || item.span().start() == operand.start() && item.span().end() == operand.end())
                    && operand.value().equals(item.value()) && operand.unit().equals(item.unit())
                    && (operand.name().isEmpty() || operand.name().equalsIgnoreCase(item.proposal().variable())
                        || proposed.formulaOrigin() != FormulaOrigin.REQUEST
                            && operand.name().equalsIgnoreCase(labelName(html.inputs().get(item.proposal().inputId()).label()))));
            if (!matched) throw rejected("KNOWN_OPERAND_OMITTED_OR_CHANGED");
        }
        if (!known.expression().isEmpty() && (proposed.formulaOrigin() != FormulaOrigin.REQUEST
                || !known.expression().equals(expressionText))) throw rejected("KNOWN_EXPRESSION_CHANGED");
        Integer localPrecision = nativeInputs != null ? nativeInputs.precision() : known.precision();
        Integer expectedPrecision = localPrecision != null ? localPrecision : defaults.precision();
        int precision;
        String precisionOrigin;
        if (proposed.precisionRequestQuote() != null) {
            Span precisionSpan;
            if (nativeResult != null) {
                precisionSpan = nativeReference(request, proposed.precisionRequestQuote(), nativeResult.precisionSpan());
                if (!contains(scope.span(), precisionSpan) && !contains(nativeOperandScope(nativeResult), precisionSpan)
                        && !AnswerRequirements.standalonePrecisionReference(request, precisionSpan.start(), precisionSpan.end())) {
                    throw rejected("RESULT_REFERENCE_OUTSIDE_SCOPE");
                }
            } else {
                try {
                    precisionSpan = scopedReference(request, proposed.precisionRequestQuote(), scope);
                } catch (Rejected outside) {
                    precisionSpan = unique(request, proposed.precisionRequestQuote());
                    if (!AnswerRequirements.standalonePrecisionReference(request, precisionSpan.start(), precisionSpan.end())) throw outside;
                }
            }
            if (!tokenBoundary(request, precisionSpan)) throw rejected("REQUEST_PRECISION_INVALID");
            Matcher match = PRECISION.matcher(proposed.precisionRequestQuote());
            if (!match.matches()) throw rejected("REQUEST_PRECISION_INVALID");
            precision = Integer.parseInt(match.group(1));
            if (precision > BoundedAnswerArithmetic.MAX_DECIMAL_PLACES) throw rejected("REQUEST_PRECISION_INVALID");
            precisionOrigin = "the quoted request precision";
        } else if (nativeResult != null && nativeResult.precisionSpan() != null) {
            throw rejected("NATIVE_PRECISION_BINDING_INVALID");
        } else if (expectedPrecision != null) {
            precision = expectedPrecision;
            precisionOrigin = "the explicit request precision";
        } else if (script.precision() != null) {
            precision = script.precision();
            precisionOrigin = "the linked script's display precision";
        } else {
            precision = 2;
            precisionOrigin = "the default mathematical display precision";
        }
        if (expectedPrecision != null && expectedPrecision != precision) throw rejected("KNOWN_PRECISION_CHANGED");
        var value = expression.evaluate(values, precision);
        List<ReadOnlyAnswerComponents.Reference> references = new ArrayList<>();
        if (formulaSource != null) references.add(implementation ? fullReference(formulaSource) : reference(formulaSource, formulaSpan));
        references.add(fullReference(htmlSource));
        if (precisionOrigin.equals("the linked script's display precision")
                && !references.contains(fullReference(scriptSource))) references.add(fullReference(scriptSource));
        String origin = implementation ? "the mathematical expression retained from " + code(scriptSource.path())
                : formulaSource == null ? "the expression selected from the original request"
                : "the source formula interpreted from " + code(formulaSource.path());
        String text = value.successful()
                ? "Using " + origin + ", " + code(value.expression()) + " equals " + code(value.exact())
                    + ". The mathematical result is " + code(value.rounded()) + " to " + precision
                    + (precision == 1 ? " decimal place" : " decimal places")
                    + ", with half-up rounding and " + precisionOrigin + "."
                : "The calculation using " + origin + " could not be completed within the bounded arithmetic rules. "
                    + numericFailure(value.status());
        text += " This is a bounded mathematical evaluation, not an observed application output or a claim of JavaScript floating-point equivalence.";
        String derivation = ReadOnlyWebDiagnosticResult.textHash("bounded-result-rational-v2\n" + resultId + "\n"
                + proposed.formulaOrigin() + "\n" + expressionText + "\n" + values + "\n"
                + value.exact() + "\n" + value.rounded() + "\n" + precision);
        Data data = new Data(value.successful() ? Support.ATTRIBUTED_INTERPRETATION : Support.UNSUPPORTED,
                implementation ? "RUNTIME_IMPLEMENTATION_CALCULATION" : "RUNTIME_CALCULATION_FROM_INTERPRETED_EXPRESSION",
                text, List.copyOf(references), derivation);
        return new Calculated(resultId, proposed.formulaOrigin(), data, expression, value,
                Map.copyOf(inputValues), Map.copyOf(formulaInputIds));
    }

    private static Span nativeReference(String text, String expected, Span span) {
        if (text == null || expected == null || expected.isBlank() || expected.length() > MAX_QUOTE_CHARS
                || span == null || span.start() < 0 || span.end() <= span.start() || span.end() > text.length()
                || span.start() > 0 && Character.isLowSurrogate(text.charAt(span.start()))
                || span.end() < text.length() && Character.isLowSurrogate(text.charAt(span.end()))
                || !text.substring(span.start(), span.end()).equals(expected)) {
            throw rejected("NATIVE_REFERENCE_INVALID");
        }
        return span;
    }

    private static boolean contains(Span outer, Span inner) {
        return inner.start() >= outer.start() && inner.end() <= outer.end();
    }

    private static Span nativeOperandScope(NativeProposalBinding.BoundResult bound) {
        int start = bound.slot().operandStart();
        int end = bound.slot().operandEnd();
        if (start < 0 || end <= start) throw rejected("NATIVE_OPERAND_SCOPE_INVALID");
        return new Span(start, end);
    }

    private static void nativeOperandScope(Span span, NativeProposalBinding.BoundResult bound) {
        if (!contains(nativeOperandScope(bound), span)) throw rejected("RESULT_REFERENCE_OUTSIDE_SCOPE");
    }

    private static NativeInputs nativeInputs(String request, NativeProposalBinding.BoundResult bound) {
        Span scope = nativeOperandScope(bound);
        if (scope.end() > request.length()) throw rejected("NATIVE_OPERAND_SCOPE_INVALID");
        String text = request.substring(scope.start(), scope.end());
        Integer precision = null;
        Matcher suffix = PRECISION_SUFFIX.matcher(text);
        if (suffix.matches()) {
            try { precision = Integer.valueOf(suffix.group(3)); }
            catch (NumberFormatException invalid) { throw rejected("KNOWN_PRECISION_BOUND_EXCEEDED"); }
            if (precision > BoundedAnswerArithmetic.MAX_DECIMAL_PLACES) throw rejected("KNOWN_PRECISION_BOUND_EXCEEDED");
            text = suffix.group(1);
        }
        List<AnswerRequirements.Operand> operands = new ArrayList<>();
        Matcher joins = OPERAND_JOIN.matcher(text);
        int start = 0;
        while (joins.find()) {
            nativeExpectedOperand(text.substring(start, joins.start()), scope.start() + start, operands);
            start = joins.end();
        }
        nativeExpectedOperand(text.substring(start), scope.start() + start, operands);
        return new NativeInputs(List.copyOf(operands), precision);
    }

    private static void nativeExpectedOperand(String text, int offset, List<AnswerRequirements.Operand> operands) {
        int start = 0;
        int end = text.length();
        while (start < end && Character.isWhitespace(text.charAt(start))) start++;
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
        Matcher match = OPERAND.matcher(text.substring(start, end));
        if (!match.matches() || operands.size() == MAX_ITEMS) throw rejected("KNOWN_UNSUPPORTED_OPERAND_BINDING");
        String name = match.group(1) == null ? "" : match.group(1);
        String unit = match.group(3) == null ? "" : match.group(3);
        if (name.isEmpty() && unit.isEmpty()) throw rejected("KNOWN_OPERAND_NAME_OR_UNIT_REQUIRED");
        if (!name.isEmpty() && operands.stream().anyMatch(operand -> operand.name().equalsIgnoreCase(name))) {
            throw rejected("KNOWN_DUPLICATE_OPERAND_NAME");
        }
        operands.add(new AnswerRequirements.Operand(name, match.group(2), unit, offset + start, offset + end));
    }

    private static boolean unusedImplementationInput(AnswerRequirements.Operand operand,
                                                     ObservedWebAnswerSources.Html html,
                                                     ObservedWebAnswerSources.Script script,
                                                     Set<String> expressionVariables) {
        var matches = html.inputs().values().stream()
                .filter(input -> html.labelReason().isEmpty() && input.unit().equals(operand.unit())
                        && (operand.name().isEmpty() || labelName(input.label()).equalsIgnoreCase(operand.name())))
                .toList();
        return matches.size() == 1 && expressionVariables.stream()
                .map(script.operands()::get).noneMatch(matches.getFirst().id()::equals);
    }

    private static Span scopedReference(String request, String quote, Scope scope) {
        String text = request.substring(scope.span().start(), scope.span().end());
        if (quote != null && text.contains(quote)) {
            Span local = unique(text, quote);
            return new Span(scope.span().start() + local.start(), scope.span().start() + local.end());
        }
        if (scope.concrete()) throw rejected("RESULT_REFERENCE_OUTSIDE_SCOPE");
        Span shared = unique(request, quote);
        if (scope.otherClauses().stream().anyMatch(clause -> clause.overlaps(shared.start(), shared.end()))) {
            throw rejected("RESULT_REFERENCE_OUTSIDE_SCOPE");
        }
        return shared;
    }

    private static Data comparison(Calculated first, Calculated second) {
        Calculated definition = first.origin() == FormulaOrigin.SOURCE ? first
                : second.origin() == FormulaOrigin.SOURCE ? second : null;
        Calculated implementation = first.origin() == FormulaOrigin.IMPLEMENTATION ? first
                : second.origin() == FormulaOrigin.IMPLEMENTATION ? second : null;
        if (definition == null || implementation == null || !first.value().successful() || !second.value().successful()) return null;
        if (!first.inputs().keySet().containsAll(second.inputs().keySet())
                && !second.inputs().keySet().containsAll(first.inputs().keySet())) return null;
        for (var input : first.inputs().entrySet()) {
            if (second.inputs().containsKey(input.getKey()) && !input.getValue().equals(second.inputs().get(input.getKey()))) return null;
        }
        boolean equal = first.value().numerator().equals(second.value().numerator())
                && first.value().denominator().equals(second.value().denominator());
        Map<String, String> canonicalInputs = new LinkedHashMap<>();
        java.util.stream.Stream.concat(first.inputs().keySet().stream(), second.inputs().keySet().stream())
                .distinct().sorted().forEach(key -> canonicalInputs.put(key, "v" + canonicalInputs.size()));
        boolean sameStructure = first.expression().renameVariables(names(first.variables(), canonicalInputs)).canonical()
                .equals(second.expression().renameVariables(names(second.variables(), canonicalInputs)).canonical());
        String text = "The definition result " + code(definition.id()) + " and implementation result "
                + code(implementation.id()) + " were compared using compatible supplied input bindings. ";
        text += !equal ? "Their exact mathematical values disagree for those inputs."
                : sameStructure ? "Their exact values agree and their bounded arithmetic structures match under those bindings."
                : "Their arithmetic structures differ, but their exact values agree for those inputs. General formula equivalence was not established.";
        List<ReadOnlyAnswerComponents.Reference> references = new ArrayList<>(first.data().references());
        for (var reference : second.data().references()) if (!references.contains(reference)) references.add(reference);
        return new Data(Support.ATTRIBUTED_INTERPRETATION, "RUNTIME_RESULT_COMPARISON", text, List.copyOf(references),
                ReadOnlyWebDiagnosticResult.textHash("bounded-result-comparison-v2\n" + first.data().derivation() + "\n" + second.data().derivation()));
    }

    private static String numericFailure(BoundedAnswerArithmetic.Status status) {
        return status == BoundedAnswerArithmetic.Status.DIVISION_BY_ZERO
                ? "Division by zero was identified." : "A numeric or resource bound was reached.";
    }

    private static Map<String, String> names(Map<String, String> variables, Map<String, String> canonicalInputs) {
        Map<String, String> names = new LinkedHashMap<>();
        variables.forEach((variable, inputId) -> names.put(variable, canonicalInputs.get(inputId)));
        return names;
    }

    private static boolean tokenBoundary(String request, Span span) {
        return (span.start() == 0 || !Character.isLetterOrDigit(request.charAt(span.start() - 1))
                    && "._$+-\u2212".indexOf(request.charAt(span.start() - 1)) < 0)
                && (span.end() == request.length() || !Character.isLetterOrDigit(request.charAt(span.end()))
                    && "_$/%^-+*".indexOf(request.charAt(span.end())) < 0
                    && (request.charAt(span.end()) != '.' || span.end() + 1 == request.length()
                        || !Character.isDigit(request.charAt(span.end() + 1))));
    }

    static ReadOnlyWebDiagnosticResult limited(String reason) {
        return result(List.of(component("proposal-invalid", "UNKNOWN", unmet(reason,
                "A usable bounded answer interpretation was not established. The requested answer remains incomplete."))), false, List.of());
    }

    private static ReadOnlyWebDiagnosticResult result(List<ReadOnlyAnswerComponents.Component> components,
                                                     boolean covered, List<StaticWebDiagnostic> diagnostics) {
        return result(components, covered, diagnostics, ReadOnlyAnswerComponents.CoverageOrigin.INTERPRETED);
    }

    private static ReadOnlyWebDiagnosticResult result(List<ReadOnlyAnswerComponents.Component> components,
                                                     boolean covered, List<StaticWebDiagnostic> diagnostics,
                                                     ReadOnlyAnswerComponents.CoverageOrigin coverageOrigin) {
        var content = new ReadOnlyAnswerComponents(components, covered, coverageOrigin);
        var assessment = content.complete()
                ? new ReadOnlyWebAnswerAssessment(ReadOnlyWebAnswerAssessment.Disposition.RUNTIME_DERIVED,
                        ReadOnlyWebAnswerAssessment.Reason.RUNTIME_DERIVED)
                : ReadOnlyWebAnswerAssessment.limited(ReadOnlyWebAnswerAssessment.Reason.ANSWER_REQUIREMENTS_UNMET);
        return new ReadOnlyWebDiagnosticResult(content.render(), assessment, diagnostics,
                ReadOnlyWebDiagnosticResult.Association.empty(), content);
    }

    private static ReadOnlyAnswerComponents.Component component(String id, String kind, Data data) {
        return new ReadOnlyAnswerComponents.Component(id, kind, data.support(), data.reason(), data.text(), data.references(), data.derivation());
    }
    private static Data attributed(String text, List<ReadOnlyAnswerComponents.Reference> references) {
        return new Data(Support.ATTRIBUTED_INTERPRETATION, "SOURCE_RELATION_WITH_INTERPRETED_TARGET", text, references, "");
    }
    private static Data unmet(String reason, String text) { return new Data(Support.UNSUPPORTED, reason, text, List.of(), ""); }
    private static boolean unresolved(Kind kind) { return kind == Kind.UNKNOWN || kind == Kind.INPUT_VALIDATION; }
    private static String componentName(Kind kind) {
        return switch (kind) {
            case DIAGNOSIS -> "diagnosis";
            case SELECTOR -> "selector correction";
            case CALCULATION -> "calculation";
            case SCRIPT_DISTINCTION -> "script distinction";
            case INPUT_VALIDATION -> "input-validation analysis";
            case UNKNOWN -> "additional answer component";
        };
    }
    private static Source pathSource(List<Source> sources, String path) {
        return sources.stream().filter(source -> path.equals(ObservedStaticWebSources.resolve("", source.path())))
                .findFirst().orElseThrow(() -> rejected("SOURCE_REFERENCE_INVALID"));
    }
    private static ReadOnlyAnswerComponents.Reference fullReference(Source source) {
        return reference(source, new Span(0, source.text().length()));
    }
    private static ReadOnlyAnswerComponents.Reference lineReference(Source source, int line) {
        int start = 0;
        for (int number = 1; number < line && start < source.text().length(); number++) {
            int end = source.text().indexOf('\n', start);
            start = end < 0 ? source.text().length() : end + 1;
        }
        int end = source.text().indexOf('\n', start);
        return reference(source, new Span(start, end < 0 ? source.text().length() : end));
    }
    private static ReadOnlyAnswerComponents.Reference reference(Source source, Span span) {
        return new ReadOnlyAnswerComponents.Reference(source.observationId(), ReadOnlyWebDiagnosticResult.textHash(source.text()), span.start(), span.end());
    }
    private static String labelName(String label) { return label.strip().replace(' ', '_'); }
    private static String code(String value) { return "`" + value.replace("`", "\\`").replace("\n", " ").replace("\r", " ") + "`"; }
    private record BoundOperand(Operand proposal, String value, String unit, String name, Span span) {}
    private record NativeInputs(List<AnswerRequirements.Operand> operands, Integer precision) {}
    private record Scope(Span span, Span requestSpan, AnswerRequirements known, boolean concrete, List<Span> otherClauses) {}
    private record Calculated(String id, FormulaOrigin origin, Data data, BoundedAnswerArithmetic.Parsed expression,
                              BoundedAnswerArithmetic.Evaluation value, Map<String, String> inputs,
                              Map<String, String> variables) {}
    private record Data(Support support, String reason, String text, List<ReadOnlyAnswerComponents.Reference> references, String derivation) {}
}
