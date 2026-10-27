package dev.talos.runtime.verification;

import dev.talos.runtime.task.AnswerRequirements;
import dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidence.Source;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.talos.runtime.verification.ReadOnlyAnswerComponents.Support;

/** Checked components are derived from the original requirements and selected observations. */
final class RuntimeWebDiagnosticAnswer {
    private RuntimeWebDiagnosticAnswer() {}

    static ReadOnlyWebDiagnosticResult assess(AnswerRequirements request, List<Source> sources,
                                             ObservedStaticWebSources.Selection selected) {
        if (request == null || !request.applicable()) return null;
        var facts = selected.facts();
        var inspected = ObservedWebAnswerSources.inspect(facts.html(), facts.js());
        var html = inspected.html();
        var script = inspected.script();
        List<ObservedWebAnswerSources.Button> targets = html.buttons().stream().filter(button ->
                request.targetIsId() ? button.id().equals(request.target())
                        : button.label().equalsIgnoreCase(request.target())).toList();
        boolean targetBound = html.supported() && targets.size() == 1 && !request.unresolved().contains("AMBIGUOUS_BUTTON_TARGET");
        boolean mismatch = html.supported() && script.supported() && !html.ids().contains(script.selector().substring(1));
        Source htmlSource = source(sources, facts.htmlFile());
        Source scriptSource = source(sources, facts.jsFile());
        List<ReadOnlyAnswerComponents.Component> components = new ArrayList<>();
        List<StaticWebDiagnostic> diagnostics = new ArrayList<>();
        if (mismatch) diagnostics.add(new StaticWebDiagnostic(StaticWebDiagnostic.Kind.JAVASCRIPT_MISSING_ID,
                facts.jsFile(), facts.htmlFile(), List.of(script.selector().substring(1)),
                "The listener lookup has no matching ID in the retained HTML."));
        for (var requirement : request.requirements()) {
            ComponentData data = switch (requirement.kind()) {
                case DIAGNOSIS -> {
                    if (!mismatch || !targetBound) yield unmet("DIAGNOSIS_RELATION_UNSUPPORTED", "The requested button failure could not be established within the supported source relations.");
                    String text = "The listener in " + code(facts.jsFile()) + " uses " + code(script.selector())
                            + ", which has no matching declared ID in " + code(facts.htmlFile())
                            + ". Under standard document query behavior for that declared DOM, the lookup would be null and the direct listener attachment would fail. This is a source-conditioned inference, not an observed browser result.";
                    yield established(text, List.of(reference(scriptSource, script.queryLine()), reference(scriptSource, script.listenerLine()), reference(htmlSource, targets.getFirst().line())));
                }
                case SELECTOR -> {
                    if (!mismatch || !targetBound) yield unmet("TARGET_BINDING_UNAVAILABLE", "The requested replacement selector remains unresolved.");
                    String replacement = "#" + targets.getFirst().id();
                    yield established("For the declared " + code(targets.getFirst().label()) + " button, " + code(script.selector())
                            + " should be replaced by " + code(replacement) + " in that listener lookup. This addresses the observed mismatch, not every possible application defect.",
                            List.of(reference(scriptSource, script.queryLine()), reference(htmlSource, targets.getFirst().line())));
                }
                case SCRIPT_DISTINCTION -> distinction(request, sources, selected);
                case CALCULATION -> calculation(request, sources, html, script, htmlSource, scriptSource);
                case UNKNOWN -> unmet("REQUEST_COVERAGE_UNRESOLVED", "An additional requested component remains outside the supported interpretation.");
            };
            components.add(new ReadOnlyAnswerComponents.Component(requirement.id(), requirement.kind().name(), data.support,
                    data.reason, data.text, data.references, data.derivation));
        }
        boolean covered = request.unresolved().isEmpty();
        if (facts.selectorDiagnostics().stream().anyMatch(diagnostic -> !diagnostic.kind().isJavaScript())) covered = false;
        ReadOnlyAnswerComponents result = new ReadOnlyAnswerComponents(components, covered);
        ReadOnlyWebAnswerAssessment assessment = result.complete()
                ? new ReadOnlyWebAnswerAssessment(ReadOnlyWebAnswerAssessment.Disposition.RUNTIME_DERIVED,
                        ReadOnlyWebAnswerAssessment.Reason.RUNTIME_DERIVED)
                : ReadOnlyWebAnswerAssessment.limited(ReadOnlyWebAnswerAssessment.Reason.ANSWER_REQUIREMENTS_UNMET);
        return new ReadOnlyWebDiagnosticResult(result.render(), assessment, diagnostics,
                ReadOnlyWebDiagnosticResult.Association.empty(), result);
    }

    private static ComponentData distinction(AnswerRequirements request, List<Source> sources,
                                             ObservedStaticWebSources.Selection selected) {
        List<String> paths = request.scriptTargets().stream().map(path -> ObservedStaticWebSources.resolve("", path)).toList();
        if (paths.size() != 2 || !paths.contains(selected.facts().jsFile())
                || paths.stream().anyMatch(path -> path == null || sources.stream().noneMatch(item -> path.equals(ObservedStaticWebSources.resolve("", item.path())))))
            return unmet("SCRIPT_DISTINCTION_UNAVAILABLE", "The requested JavaScript file distinction remains unresolved.");
        String other = paths.stream().filter(path -> !path.equals(selected.facts().jsFile())).findFirst().orElse("");
        if (other.isEmpty()) return unmet("SCRIPT_DISTINCTION_UNAVAILABLE", "Two distinct observed JavaScript paths were not established.");
        return established(code(selected.facts().htmlFile()) + " links " + code(selected.facts().jsFile()) + ". "
                + code(other) + " was also read, but is not that document's declared script. Its execution elsewhere was not assessed.",
                List.of(reference(source(sources, selected.facts().htmlFile()), 0), reference(source(sources, other), 0)));
    }

    private static ComponentData calculation(AnswerRequirements request, List<Source> sources,
                                             ObservedWebAnswerSources.Html html, ObservedWebAnswerSources.Script script,
                                             Source htmlSource, Source scriptSource) {
        if (request.requirements().stream().filter(item -> item.kind() == AnswerRequirements.Kind.CALCULATION).count() != 1
                || request.unresolved().stream().anyMatch(reason -> reason.contains("OPERAND")
                    || reason.contains("PRECISION") || reason.contains("DECIMAL") || reason.contains("CALCULATION")))
            return unmet("CALCULATION_REQUEST_UNRESOLVED", "The calculation request contains unresolved or conflicting operand, precision or expression requirements.");
        if (!request.expression().isEmpty()) {
            var expression = BoundedAnswerArithmetic.parse(request.expression());
            Map<String, String> values = new LinkedHashMap<>();
            for (var operand : request.operands()) {
                if (operand.name().isEmpty() || !operand.unit().isEmpty() || values.putIfAbsent(operand.name(), operand.value()) != null)
                    return unmet("REQUEST_OPERAND_BINDING_UNSUPPORTED", "The expression operands could not be bound unambiguously to the request.");
            }
            if (!expression.variables().equals(values.keySet())) return unmet("REQUEST_OPERAND_BINDING_UNSUPPORTED", "The expression and requested operand names do not match exactly.");
            return evaluated(expression, values, request.precision() == null ? 2 : request.precision(),
                    "The expression supplied in the request", List.of(), "");
        }
        if (request.quantity().isBlank() || !html.supported() || !html.labelReason().isEmpty()
                || !script.calculationSupported() || !html.ids().contains(script.outputId()))
            return unmet("CALCULATION_SOURCE_UNSUPPORTED", "The requested calculation remains unresolved because its source, input or output relations are unsupported.");
        List<ObservedWebAnswerSources.Button> targets = html.buttons().stream().filter(button ->
                request.targetIsId() ? button.id().equals(request.target())
                        : button.label().equalsIgnoreCase(request.target())).toList();
        String targetLabel = targets.size() == 1 ? targets.getFirst().label() : "";
        Definition definition = definition(sources, request.quantity(), targetLabel);
        if (definition == null) return unmet("FORMULA_DEFINITION_UNAVAILABLE", "A unique affirmative definition of the requested quantity was not established in a supported declaration document. Surrounding project prose is not interpreted as a formula contract.");
        var parsed = BoundedAnswerArithmetic.parse(definition.expression);
        if (!parsed.successful()) return unmet("FORMULA_EXPRESSION_UNSUPPORTED", "The declared quantity expression is outside the bounded arithmetic grammar.");
        Map<String, ObservedWebAnswerSources.Input> variableInputs = new LinkedHashMap<>();
        Map<String, String> canonicalInputs = new LinkedHashMap<>();
        for (String id : html.inputs().keySet().stream().sorted().toList()) canonicalInputs.put(id, "v" + canonicalInputs.size());
        for (String variable : parsed.variables()) {
            List<ObservedWebAnswerSources.Input> matches = html.inputs().values().stream()
                    .filter(input -> !input.label().isEmpty() && labelName(input.label()).equalsIgnoreCase(variable)).toList();
            if (matches.size() != 1 || matches.getFirst().unit().isEmpty()) return unmet("FORMULA_OPERAND_AMBIGUOUS", "The formula operands could not be associated with unique labeled inputs and units.");
            variableInputs.put(variable, matches.getFirst());
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (var operand : request.operands()) {
            List<String> matches = variableInputs.entrySet().stream().filter(entry ->
                    operand.unit().equals(entry.getValue().unit())
                            && (operand.name().isEmpty() || labelName(entry.getValue().label()).equalsIgnoreCase(operand.name())
                                || entry.getKey().equalsIgnoreCase(operand.name()))).map(Map.Entry::getKey).toList();
            if (matches.size() != 1 || values.putIfAbsent(matches.getFirst(), operand.value()) != null)
                return unmet("REQUEST_OPERAND_AMBIGUOUS", "The requested values or units could not be bound uniquely to the declared formula.");
        }
        if (!values.keySet().equals(parsed.variables())) return unmet("REQUEST_OPERAND_MISSING", "A required formula operand was not supplied in the request.");
        Map<String, String> formulaNames = new LinkedHashMap<>();
        variableInputs.forEach((variable, input) -> formulaNames.put(variable, canonicalInputs.get(input.id())));
        var code = BoundedAnswerArithmetic.parse(script.expression());
        Map<String, String> codeNames = new LinkedHashMap<>();
        for (String variable : code.variables()) {
            String id = script.operands().get(variable);
            if (id == null || !canonicalInputs.containsKey(id)) return unmet("CODE_OPERAND_UNAVAILABLE", "A source expression operand is not bound to a declared input.");
            codeNames.put(variable, canonicalInputs.get(id));
        }
        if (!code.successful() || !parsed.renameVariables(formulaNames).canonical().equals(code.renameVariables(codeNames).canonical()))
            return contradicted("FORMULA_SOURCE_DISAGREEMENT", "The project definition and the linked script use different arithmetic structures. The requested calculation is not presented as an agreed application result.");
        int precision = request.precision() != null ? request.precision() : script.precision() != null ? script.precision() : 2;
        String origin = "Using the definition of " + code(request.quantity()) + " in " + code(definition.source.path());
        return evaluated(parsed, values, precision, origin,
                List.of(new ReadOnlyAnswerComponents.Reference(definition.source.observationId(),
                                ReadOnlyWebDiagnosticResult.textHash(definition.source.text()), definition.start, definition.end),
                        reference(htmlSource, 0), reference(scriptSource, 0)), request.quantity());
    }

    private static ComponentData evaluated(BoundedAnswerArithmetic.Parsed expression, Map<String, String> values,
                                            int precision, String origin, List<ReadOnlyAnswerComponents.Reference> refs, String quantity) {
        var result = expression.evaluate(values, precision);
        if (!result.successful()) return unmet("CALCULATION_" + result.status().name(), "The requested calculation could not be completed within the bounded arithmetic rules.");
        String text = origin + ", " + code(result.expression()) + " equals " + code(result.exact()) + ". The mathematical result is "
                + code(result.rounded()) + " to " + precision + " decimal places, with half-up rounding. This is not a claim of browser execution or general JavaScript floating-point equivalence.";
        String derivation = ReadOnlyWebDiagnosticResult.textHash("bounded-rational-v1\n" + quantity + "\n" + result.expression()
                + "\n" + result.exact() + "\n" + result.rounded() + "\n" + precision + "\nHALF_UP");
        return new ComponentData(Support.ESTABLISHED, "RUNTIME_DERIVED_CALCULATION", text, refs, derivation);
    }

    private static Definition definition(List<Source> sources, String quantity, String targetLabel) {
        List<Definition> found = new ArrayList<>();
        String q = Pattern.quote(quantity);
        Pattern declaration = Pattern.compile("(?i)The ([A-Za-z][A-Za-z0-9 _-]{0,63}) button should compute (" + q + ") as (.+)");
        Pattern equation = Pattern.compile("(?i)(" + q + ")\\s*=\\s*(.+)");
        for (Source source : sources) {
            String path = source.path().toLowerCase(Locale.ROOT);
            if (!(path.endsWith(".md") || path.endsWith(".txt"))) continue;
            // A closed declaration document is required. Surrounding prose is not discarded.
            String text = source.text();
            if (text.indexOf('<') >= 0 || text.indexOf('>') >= 0 || text.indexOf('`') >= 0
                    || text.indexOf('~') >= 0 || text.indexOf('\t') >= 0
                    || Pattern.compile("(?m)^ {4}").matcher(text).find()) return null;
            int start = 0;
            for (String line : text.split("\n", -1)) {
                String statementText = line.strip();
                if (!statementText.isEmpty()) {
                    if (statementText.endsWith(".")) statementText = statementText.substring(0, statementText.length() - 1).stripTrailing();
                    Matcher statement = declaration.matcher(statementText);
                    Matcher assignment = equation.matcher(statementText);
                    String expression;
                    if (statement.matches() && statement.group(1).equalsIgnoreCase(targetLabel)) expression = statement.group(3);
                    else if (assignment.matches()) expression = assignment.group(2);
                    else return null;
                    if (!BoundedAnswerArithmetic.parse(expression).successful()) return null;
                    found.add(new Definition(source, expression, start, start + line.length()));
                }
                start += line.length() + 1;
            }
        }
        if (found.isEmpty()) return null;
        String canonical = BoundedAnswerArithmetic.parse(found.getFirst().expression).canonical();
        if (canonical.isEmpty() || found.stream().anyMatch(item -> !BoundedAnswerArithmetic.parse(item.expression).canonical().equals(canonical))) return null;
        return found.getFirst();
    }

    private static Source source(List<Source> sources, String path) {
        return sources.stream().filter(item -> path.equals(ObservedStaticWebSources.resolve("", item.path()))).findFirst().orElseThrow();
    }
    private static ReadOnlyAnswerComponents.Reference reference(Source source, int line) {
        int start = 0, end = source.text().length();
        if (line > 0) {
            for (int number = 1; number < line && start < source.text().length(); number++) {
                int next = source.text().indexOf('\n', start);
                start = next < 0 ? source.text().length() : next + 1;
            }
            int next = source.text().indexOf('\n', start);
            end = next < 0 ? source.text().length() : next;
        }
        return new ReadOnlyAnswerComponents.Reference(source.observationId(), ReadOnlyWebDiagnosticResult.textHash(source.text()), start, end);
    }
    private static String labelName(String label) { return label.strip().replace(' ', '_'); }
    private static String code(String value) { return "`" + Objects.toString(value, "").replace("`", "\\`").replace("\n", " ").replace("\r", " ") + "`"; }
    private static ComponentData established(String text, List<ReadOnlyAnswerComponents.Reference> references) { return new ComponentData(Support.ESTABLISHED, "SOURCE_RELATION_ESTABLISHED", text, references, ""); }
    private static ComponentData unmet(String reason, String text) { return new ComponentData(Support.UNSUPPORTED, reason, text, List.of(), ""); }
    private static ComponentData contradicted(String reason, String text) { return new ComponentData(Support.CONTRADICTED, reason, text, List.of(), ""); }
    private record Definition(Source source, String expression, int start, int end) {}
    private record ComponentData(Support support, String reason, String text, List<ReadOnlyAnswerComponents.Reference> references, String derivation) {}
}
