package dev.talos.runtime.verification;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.talos.runtime.task.AnswerRequirements;
import dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidence.Source;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** An untrusted interpretation is parsed against retained observations. */
public final class ReadOnlyAnswerProposal {
    static final int MAX_CANDIDATE_CHARS = 16_000;
    static final int MAX_ITEMS = 32;
    static final int MAX_QUOTE_CHARS = 4_096;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(12)
                    .maxStringLength(MAX_QUOTE_CHARS).maxNumberLength(16).build()).build());

    private ReadOnlyAnswerProposal() {}

    public static boolean eligible(List<Source> sources) {
        try {
            var selected = ObservedStaticWebSources.select(sources);
            if (!selected.available()) return false;
            var inspected = ObservedWebAnswerSources.inspect(selected.facts().html(), selected.facts().js());
            return inspected.html().supported() && !inspected.html().buttons().isEmpty()
                    && inspected.script().supported();
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /** Only wire structure is constrained. References and meaning remain subject to runtime assessment. */
    public static String responseSchema(List<Source> sources) {
        if (!eligible(sources)) throw new IllegalArgumentException("PROPOSAL_SCHEMA_CONTEXT_UNAVAILABLE");
        var selected = ObservedStaticWebSources.select(sources);
        List<String> sourceIds = new ArrayList<>();
        String linkedScriptId = null;
        for (int index = 0; index < sources.size(); index++) {
            String id = "s" + index;
            sourceIds.add(id);
            if (selected.facts().jsFile().equals(ObservedStaticWebSources.resolve("", sources.get(index).path()))) {
                linkedScriptId = id;
            }
        }
        if (linkedScriptId == null) throw new IllegalArgumentException("PROPOSAL_SCHEMA_CONTEXT_UNAVAILABLE");
        ObjectNode alternatives = schemaUnion(
                resultSchema("DIAGNOSIS"), resultSchema("SELECTOR"),
                schemaObject(new String[]{"kind", "requestQuote", "calculation"},
                        schemaEnum("CALCULATION"), schemaString(MAX_QUOTE_CHARS),
                        schemaUnion(calculationSchema(FormulaOrigin.SOURCE, sourceIds),
                                calculationSchema(FormulaOrigin.REQUEST, List.of("request")),
                                calculationSchema(FormulaOrigin.IMPLEMENTATION, List.of(linkedScriptId)))),
                schemaObject(new String[]{"kind", "requestQuote", "sourceIds"},
                        schemaEnum("SCRIPT_DISTINCTION"), schemaString(MAX_QUOTE_CHARS),
                        schemaArray(schemaEnum(sourceIds.toArray(String[]::new)), 2, 2)),
                resultSchema("INPUT_VALIDATION"), resultSchema("UNKNOWN"));
        ObjectNode version = JSON.createObjectNode().put("type", "integer");
        version.putArray("enum").add(2);
        return schemaObject(new String[]{"version", "target", "results"}, version,
                schemaObject(new String[]{"buttonId", "requestQuote"}, schemaIdentifier(), schemaString(MAX_QUOTE_CHARS)),
                schemaArray(alternatives, 1, MAX_ITEMS)).toString();
    }

    private static ObjectNode resultSchema(String kind) {
        return schemaObject(new String[]{"kind", "requestQuote"}, schemaEnum(kind), schemaString(MAX_QUOTE_CHARS));
    }

    private static ObjectNode calculationSchema(FormulaOrigin origin, List<String> sourceIds) {
        ObjectNode quote = origin == FormulaOrigin.IMPLEMENTATION
                ? JSON.createObjectNode().put("type", "null") : schemaString(MAX_QUOTE_CHARS);
        ObjectNode operand = schemaObject(new String[]{"variable", "inputId", "requestQuote"},
                schemaIdentifier(), origin == FormulaOrigin.REQUEST ? schemaEnum("") : schemaIdentifier(), schemaString(256));
        return schemaObject(new String[]{"formulaOrigin", "formulaReference", "operands", "precisionRequestQuote"},
                schemaEnum(origin.name()),
                schemaObject(new String[]{"sourceId", "uniqueQuote"}, schemaEnum(sourceIds.toArray(String[]::new)), quote),
                schemaArray(operand, 0, MAX_ITEMS),
                schemaUnion(JSON.createObjectNode().put("type", "null"), schemaString(256)));
    }

    private static ObjectNode schemaObject(String[] names, JsonNode... fields) {
        if (names.length != fields.length) throw new IllegalArgumentException("PROPOSAL_SCHEMA_FIELDS_INVALID");
        ObjectNode schema = JSON.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        var required = schema.putArray("required");
        for (int index = 0; index < names.length; index++) {
            properties.set(names[index], fields[index]);
            required.add(names[index]);
        }
        return schema.put("additionalProperties", false);
    }

    private static ObjectNode schemaUnion(JsonNode... alternatives) {
        ObjectNode schema = JSON.createObjectNode();
        var union = schema.putArray("oneOf");
        for (JsonNode alternative : alternatives) union.add(alternative);
        return schema;
    }

    private static ObjectNode schemaArray(JsonNode item, int minimum, int maximum) {
        ObjectNode schema = JSON.createObjectNode().put("type", "array").put("minItems", minimum).put("maxItems", maximum);
        schema.set("items", item);
        return schema;
    }

    private static ObjectNode schemaString(int maximum) {
        ObjectNode schema = JSON.createObjectNode().put("type", "string").put("minLength", 1);
        // Long quote limits remain runtime checks because expanded grammar repetitions exceed backend limits.
        if (maximum != MAX_QUOTE_CHARS) schema.put("maxLength", maximum);
        return schema;
    }

    private static ObjectNode schemaIdentifier() {
        return schemaString(64).put("pattern", "^[A-Za-z_$][A-Za-z0-9_$-]{0,63}$");
    }

    private static ObjectNode schemaEnum(String... values) {
        ObjectNode schema = JSON.createObjectNode().put("type", "string");
        var enumeration = schema.putArray("enum");
        for (String value : values) enumeration.add(value);
        return schema;
    }

    public static String instructions(List<Source> sources) {
        StringBuilder text = new StringBuilder("""
                Return only one JSON object with exactly version, target and results. version is 2.
                No fences, prose, extra fields or calculated values. Use the supplied observations. No further reads.
                The runtime checks references, computes values and writes the answer. A reference does not prove meaning.
                target is {"buttonId":...,"requestQuote":...}, using the declared HTML button ID without #.
                results contains 1 to 32 entries, one per requested answer part, including repeated calculations.
                Each entry has kind and requestQuote, plus only its kind-specific payload:
                DIAGNOSIS (button failure), SELECTOR (replacement selector), INPUT_VALIDATION (unresolved validation)
                and UNKNOWN (other unsupported part) have no payload. SCRIPT_DISTINCTION adds sourceIds:[...,...].
                CALCULATION adds calculation:{formulaOrigin,formulaReference,operands,precisionRequestQuote}.
                Reads and action prohibitions are not answer parts. Retain supported entries alongside unresolved ones.

                Copy each requestQuote literally with unchanged case and words, preferably the full unique requested
                clause including its own inputs. Do not insert missing verbs. target's quote identifies the button.
                Each calculation has its own formula, operands and precision. Do not reuse another result's bindings.
                Operand and precision quotes resolve inside that result's matched request clause. Shared values may
                resolve elsewhere in the request only when they are outside every recognized requested clause.
                Use two CALCULATION entries when definition and implementation values are requested separately.
                Their agreement or disagreement is compared by the runtime, not an extra unsupported result.

                formulaReference is {"sourceId":...,"uniqueQuote":...}. formulaOrigin identifies the FORMULA origin:
                SOURCE selects the requested definition in an observed source, with its exact expression in uniqueQuote.
                REQUEST selects an explicit expression in the request, with sourceId "request" and its literal expression.
                IMPLEMENTATION selects the actual linked script's sourceId and uniqueQuote:null. The runtime extracts
                its typed expression. Never invent or quote an implementation expression for this origin.
                A named quantity alone is not a REQUEST formula. Preserve word operators: plus, minus, multiplied by,
                divided by and squared. Decimal literals, variables, parentheses and + - * / are also supported.
                SOURCE formulas need variables. Select the current requested definition using its full context.
                Obsolete, negated, quoted or irrelevant formulas do not become relevant just because their text occurs.

                operands contains {"variable":...,"inputId":...,"requestQuote":...} once per expression variable.
                Copy request values including units and operand names when supplied. HTML defaults are not request values.
                SOURCE and IMPLEMENTATION inputId is the matching observed HTML input ID with its label and unit.
                REQUEST inputId is "" and each operand quote names its variable and decimal value without a unit.
                No value conversion. precisionRequestQuote is null or a literal explicit decimal precision phrase.
                Otherwise the runtime uses source precision or a default. Source IDs are catalog IDs, never filenames.
                No answer, value, met, complete, confidence, permission or execution fields are permitted.

                The following unrelated example shows the structure only. None of its values is current evidence.
                Example request:
                Explain why the Power button fails and give the correct selector. For voltage 9 V and current 2 A, give the power according to guide.md and give the mathematical value of the expression in power.js separately. Explain any disagreement and distinguish the two JavaScript files.
                Example sources:
                s0 is guide.md, containing the current definition voltage multiplied by current.
                s1 is page.html, containing button ID power with label Power, input voltage with label Voltage (V),
                input current with label Current (A), and a declared link to power.js.
                s2 is power.js. s3 is spare.js. The runtime inspects their source relationships and supported code expression.
                Example output:
                {"version":2,"target":{"buttonId":"power","requestQuote":"Power button"},"results":[{"kind":"DIAGNOSIS","requestQuote":"Explain why the Power button fails"},{"kind":"SELECTOR","requestQuote":"give the correct selector"},{"kind":"CALCULATION","requestQuote":"give the power according to guide.md","calculation":{"formulaOrigin":"SOURCE","formulaReference":{"sourceId":"s0","uniqueQuote":"voltage multiplied by current"},"operands":[{"variable":"voltage","inputId":"voltage","requestQuote":"voltage 9 V"},{"variable":"current","inputId":"current","requestQuote":"current 2 A"}],"precisionRequestQuote":null}},{"kind":"CALCULATION","requestQuote":"give the mathematical value of the expression in power.js separately","calculation":{"formulaOrigin":"IMPLEMENTATION","formulaReference":{"sourceId":"s2","uniqueQuote":null},"operands":[{"variable":"voltage","inputId":"voltage","requestQuote":"voltage 9 V"},{"variable":"current","inputId":"current","requestQuote":"current 2 A"}],"precisionRequestQuote":null}},{"kind":"SCRIPT_DISTINCTION","requestQuote":"distinguish the two JavaScript files","sourceIds":["s2","s3"]}]}
                End example.

                Only the actual request and observed tool results below are current evidence, never the example.
                Quotes refer to normalized LF source content without the N | line prefixes. Every quote must occur
                exactly once in its referenced text or permitted result scope. The original request has source ID request.
                Actual source catalog:
                """);
        for (int index = 0; index < sources.size(); index++) {
            text.append('s').append(index).append(" = ").append(sources.get(index).path()).append('\n');
        }
        return text.toString();
    }

    public static ReadOnlyWebDiagnosticResult assess(String originalRequest, String candidate, List<Source> sources) {
        if (originalRequest == null || originalRequest.isBlank()
                || originalRequest.length() > AnswerRequirements.MAX_REQUEST_CHARS || !eligible(sources)) {
            return ProposedWebDiagnosticAnswer.limited("PROPOSAL_CONTEXT_UNAVAILABLE");
        }
        try {
            return ProposedWebDiagnosticAnswer.assess(originalRequest, parse(candidate), sources);
        } catch (Rejected invalid) {
            return ProposedWebDiagnosticAnswer.limited(invalid.reason);
        } catch (IOException | RuntimeException invalid) {
            return ProposedWebDiagnosticAnswer.limited("PROPOSAL_FORMAT_INVALID");
        }
    }

    enum FormulaOrigin { SOURCE, REQUEST, IMPLEMENTATION }
    enum Kind { DIAGNOSIS, SELECTOR, CALCULATION, SCRIPT_DISTINCTION, INPUT_VALIDATION, UNKNOWN }
    record Target(String buttonId, String requestQuote) {}
    record SourceReference(String sourceId, String uniqueQuote) {}
    record Operand(String variable, String inputId, String requestQuote) {}
    record Calculation(FormulaOrigin formulaOrigin, SourceReference formulaReference,
                       List<Operand> operands, String precisionRequestQuote) {}
    record Result(Kind kind, String requestQuote, Calculation calculation, List<String> scripts) {}
    record Proposal(Target target, List<Result> results) {}
    record Span(int start, int end) {
        boolean overlaps(int otherStart, int otherEnd) { return start < otherEnd && otherStart < end; }
    }

    static Span unique(String text, String quote) {
        if (text == null || quote == null || quote.isBlank() || quote.length() > MAX_QUOTE_CHARS) {
            throw rejected("REFERENCE_INVALID");
        }
        int start = text.indexOf(quote);
        if (start < 0 || text.indexOf(quote, start + 1) >= 0) throw rejected("REFERENCE_NOT_UNIQUE");
        int end = start + quote.length();
        if (start > 0 && Character.isLowSurrogate(text.charAt(start))
                || end < text.length() && Character.isLowSurrogate(text.charAt(end))) {
            throw rejected("REFERENCE_INVALID");
        }
        return new Span(start, end);
    }

    static Source source(List<Source> sources, String sourceId) {
        if (sourceId == null || !sourceId.matches("s[0-7]")) throw rejected("SOURCE_REFERENCE_INVALID");
        int index = sourceId.charAt(1) - '0';
        if (index >= sources.size()) throw rejected("SOURCE_REFERENCE_INVALID");
        return sources.get(index);
    }

    private static Proposal parse(String candidate) throws IOException {
        if (candidate == null || candidate.isBlank() || candidate.length() > MAX_CANDIDATE_CHARS) {
            throw rejected("PROPOSAL_SIZE_INVALID");
        }
        JsonNode root;
        try (JsonParser parser = JSON.createParser(candidate)) {
            root = JSON.readTree(parser);
            if (parser.nextToken() != null) throw rejected("PROPOSAL_TRAILING_CONTENT");
        }
        object(root, "version", "target", "results");
        if (!root.get("version").isIntegralNumber() || !root.get("version").canConvertToInt()
                || root.get("version").intValue() != 2) throw rejected("PROPOSAL_VERSION_INVALID");
        JsonNode targetNode = root.get("target");
        object(targetNode, "buttonId", "requestQuote");
        Target target = new Target(identifier(targetNode.get("buttonId")),
                string(targetNode.get("requestQuote"), MAX_QUOTE_CHARS));
        List<Result> results = new ArrayList<>();
        Set<String> resultKeys = new HashSet<>();
        for (JsonNode node : array(root.get("results"), false)) {
            if (node == null || !node.isObject()) throw rejected("PROPOSAL_FIELDS_INVALID");
            Kind kind = Kind.valueOf(string(node.get("kind"), 32));
            String quote = string(node.get("requestQuote"), MAX_QUOTE_CHARS);
            if (!resultKeys.add(kind + "\n" + quote)) throw rejected("DUPLICATE_RESULT");
            Calculation calculation = null;
            List<String> scripts = null;
            switch (kind) {
                case CALCULATION -> {
                    object(node, "kind", "requestQuote", "calculation");
                    calculation = calculation(node.get("calculation"));
                }
                case SCRIPT_DISTINCTION -> {
                    object(node, "kind", "requestQuote", "sourceIds");
                    List<JsonNode> ids = array(node.get("sourceIds"), false);
                    if (ids.size() != 2) throw rejected("SCRIPT_REFERENCE_INVALID");
                    scripts = List.of(string(ids.get(0), 16), string(ids.get(1), 16));
                    if (scripts.get(0).equals(scripts.get(1))) throw rejected("SCRIPT_REFERENCE_INVALID");
                }
                default -> object(node, "kind", "requestQuote");
            }
            results.add(new Result(kind, quote, calculation, scripts));
        }
        return new Proposal(target, List.copyOf(results));
    }

    private static Calculation calculation(JsonNode calculationNode) {
        object(calculationNode, "formulaOrigin", "formulaReference", "operands", "precisionRequestQuote");
        FormulaOrigin origin = FormulaOrigin.valueOf(string(calculationNode.get("formulaOrigin"), 16));
        JsonNode reference = calculationNode.get("formulaReference");
        object(reference, "sourceId", "uniqueQuote");
        JsonNode formulaQuote = reference.get("uniqueQuote");
        if (origin == FormulaOrigin.IMPLEMENTATION && !formulaQuote.isNull()) {
            throw rejected("FORMULA_REFERENCE_INVALID");
        }
        SourceReference formula = new SourceReference(string(reference.get("sourceId"), 16),
                origin == FormulaOrigin.IMPLEMENTATION ? null : string(formulaQuote, MAX_QUOTE_CHARS));
        List<Operand> operands = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<String> inputs = new HashSet<>();
        Set<String> quotes = new HashSet<>();
        for (JsonNode operand : array(calculationNode.get("operands"), true)) {
            object(operand, "variable", "inputId", "requestQuote");
            String variable = identifier(operand.get("variable"));
            String inputId;
            if (origin == FormulaOrigin.REQUEST) {
                JsonNode input = operand.get("inputId");
                if (!input.isTextual() || !input.textValue().isEmpty()) throw rejected("REQUEST_INPUT_REFERENCE_INVALID");
                inputId = "";
            } else inputId = identifier(operand.get("inputId"));
            String quote = string(operand.get("requestQuote"), 256);
            if (!names.add(variable) || origin != FormulaOrigin.REQUEST && !inputs.add(inputId) || !quotes.add(quote)) {
                throw rejected("DUPLICATE_OPERAND");
            }
            operands.add(new Operand(variable, inputId, quote));
        }
        JsonNode precision = calculationNode.get("precisionRequestQuote");
        return new Calculation(origin, formula, List.copyOf(operands),
                precision.isNull() ? null : string(precision, 256));
    }

    private static void object(JsonNode node, String... fields) {
        if (node == null || !node.isObject() || node.size() != fields.length) throw rejected("PROPOSAL_FIELDS_INVALID");
        for (String field : fields) if (!node.has(field)) throw rejected("PROPOSAL_FIELDS_INVALID");
    }

    private static List<JsonNode> array(JsonNode node, boolean emptyAllowed) {
        if (node == null || !node.isArray() || node.size() > MAX_ITEMS || !emptyAllowed && node.isEmpty()) {
            throw rejected("PROPOSAL_ARRAY_INVALID");
        }
        List<JsonNode> values = new ArrayList<>();
        node.forEach(values::add);
        return values;
    }

    private static String string(JsonNode node, int maximum) {
        if (node == null || !node.isTextual() || node.textValue().isBlank() || node.textValue().length() > maximum) {
            throw rejected("PROPOSAL_STRING_INVALID");
        }
        String value = node.textValue();
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    throw rejected("PROPOSAL_UNICODE_INVALID");
                }
            } else if (Character.isLowSurrogate(current) || current < 32 && current != '\n' && current != '\r' && current != '\t') {
                throw rejected("PROPOSAL_UNICODE_INVALID");
            }
        }
        return value;
    }

    private static String identifier(JsonNode node) {
        String value = string(node, 64);
        if (!value.matches("[A-Za-z_$][A-Za-z0-9_$-]{0,63}")) throw rejected("PROPOSAL_IDENTIFIER_INVALID");
        return value;
    }

    static Rejected rejected(String reason) { return new Rejected(reason); }
    static final class Rejected extends RuntimeException {
        final String reason;
        private Rejected(String reason) { super(null, null, false, false); this.reason = reason; }
    }
}
