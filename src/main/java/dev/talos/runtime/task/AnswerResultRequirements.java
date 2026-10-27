package dev.talos.runtime.task;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A bounded output ledger is retained independently of a proposed answer. */
public final class AnswerResultRequirements {
    public static final int MAX_REQUEST_CHARS = AnswerRequirements.MAX_REQUEST_CHARS;
    public static final int MAX_SLOTS = AnswerRequirements.MAX_REQUIREMENTS;
    private static final String NAME = "[A-Za-z][A-Za-z0-9_]*";
    private static final String WORDS = NAME + "(?:[ \\t]+" + NAME + "){0,7}";
    private static final String FILE = "`?[A-Za-z0-9_./\\\\-]+\\.[A-Za-z0-9]{1,12}`?";
    private static final String NUMBER = "[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?";
    private static final String VERB = "(?:give|calculate|compute|determine|evaluate)";
    private static final String ATTRIBUTION = "(?:according\\s+to|as\\s+(?:defined|documented)\\s+in|from)";
    private static final Pattern COMMAND = pattern("^" + VERB + "\\s+");
    private static final Pattern JOIN = pattern("(?:,\\s*(?:and\\s+)?|\\band\\s+)"
            + "(?=(?:read|inspect|explain|give|identify|calculate|compute|determine|evaluate|distinguish|compare|do\\s+not|don't|use)\\s+"
            + "|(?:the\\s+)?(?:mathematical\\s+)?(?:value|result|expression|formula)\\s+"
            + "|(?:the\\s+)?" + WORDS + "\\s+(?:for|with|according\\s+to|as\\s+(?:defined|documented)\\s+in|from)\\b"
            + "|(?:the\\s+)?" + WORDS + "$)");
    private static final Pattern SHARED = pattern("^for\\s+(.+?),\\s*(?=" + VERB + "\\s+)");
    private static final Pattern DEFINITION = pattern("(?:the\\s+)?(" + WORDS + ")\\s+" + ATTRIBUTION
            + "\\s+(" + FILE + ")(?:\\s+(?:for|with)\\s+(.+))?");
    private static final Pattern IMPLEMENTATION = pattern("(?:the\\s+)?(?:(?:mathematical\\s+)?(?:value|result)\\s+of\\s+)?"
            + "(?:the\\s+)?(?:expression|formula)\\s+in\\s+(" + FILE + ")(?:\\s+(?:for|with)\\s+(.+))?");
    private static final Pattern NAMED = pattern("(?:the\\s+)?(" + WORDS + ")\\s+(?:for|with)\\s+(.+)");
    private static final Pattern EXPLICIT = pattern("(?:the\\s+expression\\s+)?(.{1,2048}?)\\s+(?:for|with|where)\\s+(.+)");
    private static final Pattern QUANTITY = pattern("(?:the\\s+)?(" + WORDS + ")");
    private static final Pattern AMBIGUOUS_QUANTITY = pattern(".*\\b(?:and|or|of)\\b.*");
    private static final Pattern COMPARISON = pattern("(?:explain\\s+(?:(?:any|the)\\s+)?(?:disagreement|difference|differences)"
            + "(?:\\s+between\\s+(?:the\\s+)?(?:results|values))?"
            + "|explain\\s+why\\s+(?:the|these)\\s+(?:results|values)\\s+differ"
            + "|compare\\s+(?:the|these)\\s+(?:results|values))");
    private static final Pattern READ = pattern("(?:read|inspect)\\s+" + FILE
            + "(?:\\s*(?:,\\s*(?:and\\s+)?|and\\s+)" + FILE + "){0,7}");
    private static final Pattern DISTINCTION = pattern("(?:distinguish|compare)\\s+(?:(?:the\\s+)?two\\s+(?:JavaScript|JS)\\s+files|"
            + FILE + "\\s+and\\s+" + FILE + ")");
    private static final Pattern RESTRICTION = pattern("(?:do\\s+not|don't)\\s+(?:(?:edit|modify|change)(?:\\s+(?:any\\s+)?files)?"
            + "(?:\\s+or\\s+(?:claim\\s+(?:that\\s+)?tests\\s+were\\s+run|run\\s+tests))?"
            + "|claim\\s+(?:that\\s+)?tests\\s+were\\s+run|run\\s+tests)");
    private static final Pattern UNTRUSTED_CLAUSE = pattern("(?:if|suppose|assuming|imagine|for\\s+example|example|do\\s+not|don't)\\b.*");
    private static final Pattern PRECISION = pattern("use\\s+([0-9]+)\\s+(?:decimal\\s+places?|fractional\\s+digits?|decimals?)");
    private static final Pattern PRECISION_SUFFIX = pattern("(.+?)\\s+(?:rounded\\s+)?to\\s+([0-9]+)\\s+(?:decimal\\s+places?|fractional\\s+digits?|decimals?)");
    private static final Pattern OPERAND = pattern("(?:(" + NAME + ")\\s*(?:=\\s*|\\s+))?(" + NUMBER + ")"
            + "(?:\\s+([A-Za-z][A-Za-z0-9_/%^-]*))?");
    private static final Pattern OPERAND_JOIN = pattern("(?:,\\s*(?:and\\s+)?|\\band\\b)\\s*");
    private static final Pattern BULLET = Pattern.compile("(?:[-*]\\s+|[0-9]{1,2}[.)]\\s+)");

    public enum Role {
        DIAGNOSIS, SELECTOR, SCRIPT_DISTINCTION, SOURCE_DEFINITION, IMPLEMENTATION,
        REQUEST_EXPRESSION, COMPARISON, UNKNOWN
    }

    /** UTF-16 end offsets are exclusive. Absent operand scopes are represented by minus one. */
    public record Slot(String id, Role role, int start, int end, int operandStart, int operandEnd,
                       int expressionStart, int expressionEnd, String quantity, String sourcePath, List<String> peerSlotIds) {
        public Slot { peerSlotIds = List.copyOf(peerSlotIds); }
        public boolean calculation() {
            return role == Role.SOURCE_DEFINITION || role == Role.IMPLEMENTATION || role == Role.REQUEST_EXPRESSION;
        }
    }

    public enum ContextKind { READ, CONSTRAINT, PRECISION }
    public record ContextRegion(ContextKind kind, int start, int end) { }

    private final String requestSha256;
    private final List<Slot> slots;
    private final List<String> unresolved;
    private final Map<String, List<String>> regionReasons;
    private final List<ContextRegion> contextRegions;

    private AnswerResultRequirements(Parser parser) {
        requestSha256 = sha256(parser.request);
        slots = List.copyOf(parser.slots);
        unresolved = List.copyOf(parser.unresolved);
        var reasons = new LinkedHashMap<String, List<String>>();
        parser.regionReasons.forEach((id, values) -> reasons.put(id, List.copyOf(values)));
        regionReasons = java.util.Collections.unmodifiableMap(reasons);
        contextRegions = List.copyOf(parser.contextRegions);
    }

    private AnswerResultRequirements(AnswerResultRequirements original, List<Slot> slots,
                                     Map<String, List<String>> reasons) {
        requestSha256 = original.requestSha256;
        this.slots = List.copyOf(slots);
        var retained = new LinkedHashMap<String, List<String>>();
        reasons.forEach((id, values) -> retained.put(id, List.copyOf(values)));
        regionReasons = java.util.Collections.unmodifiableMap(retained);
        unresolved = List.copyOf(new LinkedHashSet<>(retained.values().stream().flatMap(List::stream).toList()));
        contextRegions = original.contextRegions;
    }

    static AnswerResultRequirements project(AnswerResultRequirements original, List<Slot> slots,
                                            Map<String, List<String>> reasons) {
        return new AnswerResultRequirements(original, slots, reasons);
    }

    public static AnswerResultRequirements fromRequest(String request) {
        return new Parser(request == null ? "" : request).parse();
    }

    public String requestSha256() { return requestSha256; }
    public List<Slot> slots() { return slots; }
    public List<String> unresolved() { return unresolved; }
    public Map<String, List<String>> regionReasons() { return regionReasons; }
    public List<ContextRegion> contextRegions() { return contextRegions; }

    /** Complete construction does not establish that an answer satisfies the ledger. */
    public boolean complete() { return unresolved.isEmpty() && slots.stream().noneMatch(slot -> slot.role() == Role.UNKNOWN); }

    private static Pattern pattern(String expression) { return Pattern.compile(expression, Pattern.CASE_INSENSITIVE); }
    private static String unquote(String text) {
        return text.length() >= 2 && text.startsWith("`") && text.endsWith("`") ? text.substring(1, text.length() - 1) : text;
    }
    private static String sha256(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private record Span(int start, int end) { }

    private static final class Parser {
        private final String request;
        private final List<Slot> slots = new ArrayList<>();
        private final Set<String> unresolved = new LinkedHashSet<>();
        private final Map<String, Set<String>> regionReasons = new LinkedHashMap<>();
        private final List<ContextRegion> contextRegions = new ArrayList<>();
        private final List<AnswerRequirements.Requirement> legacy;
        private List<Slot> precedingCalculations = List.of();
        private final List<Slot> sentenceCalculations = new ArrayList<>();
        private Span shared;
        private boolean calculationContinuation;

        private Parser(String request) {
            this.request = request;
            legacy = request.length() <= MAX_REQUEST_CHARS ? AnswerRequirements.fromRequest(request).requirements() : List.of();
        }

        private AnswerResultRequirements parse() {
            if (request.length() > MAX_REQUEST_CHARS) {
                unknown(new Span(0, request.length()), "REQUEST_BOUND_EXCEEDED");
                return new AnswerResultRequirements(this);
            }
            for (Span sentence : sentences()) {
                Span scope = trim(sentence, true);
                if (scope.start() == scope.end()) continue;
                sentenceCalculations.clear();
                shared = null;
                calculationContinuation = false;
                String text = text(scope);
                if (text.startsWith(">") || text.startsWith("\"") || text.startsWith("'") || text.startsWith("`")
                        || UNTRUSTED_CLAUSE.matcher(text).matches() && !RESTRICTION.matcher(text).matches()) {
                    unknown(scope, "UNRESOLVED_REQUEST_REGION");
                    precedingCalculations = List.of();
                    continue;
                }
                Matcher leading = SHARED.matcher(text);
                if (leading.find()) {
                    shared = trim(new Span(scope.start() + leading.start(1), scope.start() + leading.end(1)), false);
                    if (!operands(shared)) {
                        unknown(scope, "UNSUPPORTED_SHARED_INPUTS");
                        precedingCalculations = List.of();
                        continue;
                    }
                    scope = new Span(scope.start() + leading.end(), scope.end());
                }
                int begin = scope.start();
                Matcher joins = JOIN.matcher(request).region(begin, scope.end());
                while (joins.find()) {
                    clause(trim(new Span(begin, joins.start()), false));
                    begin = joins.end();
                }
                clause(trim(new Span(begin, scope.end()), false));
                if (!sentenceCalculations.isEmpty()) precedingCalculations = List.copyOf(sentenceCalculations);
            }
            return new AnswerResultRequirements(this);
        }

        private void clause(Span scope) {
            if (scope.start() == scope.end()) return;
            String text = text(scope);
            for (AnswerRequirements.Requirement item : legacy) {
                if (item.start() != scope.start() || item.end() != scope.end()) continue;
                Role role = switch (item.kind()) {
                    case DIAGNOSIS -> Role.DIAGNOSIS;
                    case SELECTOR -> Role.SELECTOR;
                    case SCRIPT_DISTINCTION -> Role.SCRIPT_DISTINCTION;
                    default -> null;
                };
                if (role != null) {
                    if (role == Role.SCRIPT_DISTINCTION && !DISTINCTION.matcher(text).matches()) {
                        if (COMPARISON.matcher(text).matches()) break;
                        unknown(scope, "UNSUPPORTED_SCRIPT_DISTINCTION");
                        return;
                    }
                    add(role, scope, null, "", "", List.of());
                    calculationContinuation = role == Role.SELECTOR;
                    return;
                }
            }
            if (READ.matcher(text).matches()) {
                contextRegions.add(new ContextRegion(ContextKind.READ, scope.start(), scope.end()));
                return;
            }
            if (RESTRICTION.matcher(text).matches()) {
                contextRegions.add(new ContextRegion(ContextKind.CONSTRAINT, scope.start(), scope.end()));
                return;
            }
            Matcher precision = PRECISION.matcher(text);
            if (precision.matches()) {
                if (!AnswerRequirements.standalonePrecision(request).unresolved().isEmpty()) unknown(scope, "UNSUPPORTED_PRECISION");
                else contextRegions.add(new ContextRegion(ContextKind.PRECISION, scope.start(), scope.end()));
                return;
            }
            if (COMPARISON.matcher(text).matches()) {
                List<Slot> peers = sentenceCalculations.isEmpty() ? precedingCalculations : sentenceCalculations;
                List<Slot> definition = peers.stream().filter(slot -> slot.role() == Role.SOURCE_DEFINITION).toList();
                List<Slot> implementation = peers.stream().filter(slot -> slot.role() == Role.IMPLEMENTATION).toList();
                if (peers.size() == 2 && definition.size() == 1 && implementation.size() == 1
                        && definition.getFirst().operandStart() == implementation.getFirst().operandStart()
                        && definition.getFirst().operandEnd() == implementation.getFirst().operandEnd()) {
                    add(Role.COMPARISON, scope, null, "", "", List.of(definition.getFirst().id(), implementation.getFirst().id()));
                } else unknown(scope, "AMBIGUOUS_COMPARISON_SCOPE");
                calculationContinuation = false;
                return;
            }
            Matcher verb = COMMAND.matcher(text);
            boolean explicitCommand = verb.find();
            String command = explicitCommand ? verb.group().strip() : "";
            int contentStart = explicitCommand ? scope.start() + verb.end() : scope.start();
            String content = request.substring(contentStart, scope.end()).replaceFirst("(?i)\\s+separately$", "");
            if (!explicitCommand && !calculationContinuation) {
                unknown(scope, "UNRESOLVED_REQUEST_REGION");
                return;
            }
            Matcher match = IMPLEMENTATION.matcher(content);
            if (match.matches()) {
                calculation(scope, Role.IMPLEMENTATION, "", unquote(match.group(1)), input(match, 2, contentStart));
                return;
            }
            match = DEFINITION.matcher(content);
            if (match.matches()) {
                calculation(scope, Role.SOURCE_DEFINITION, match.group(1), unquote(match.group(2)), input(match, 3, contentStart));
                return;
            }
            if (command.equalsIgnoreCase("evaluate") || content.matches("(?i)^the\\s+expression\\s+.*")) {
                match = EXPLICIT.matcher(content);
                if (match.matches()) {
                    int first = contentStart + match.start(1);
                    int last = contentStart + match.end(1);
                    if (match.group(1).length() >= 2 && match.group(1).startsWith("`") && match.group(1).endsWith("`")) { first++; last--; }
                    calculation(scope, Role.REQUEST_EXPRESSION, unquote(match.group(1)), "", input(match, 2, contentStart),
                            new Span(first, last));
                    return;
                }
            }
            match = NAMED.matcher(content);
            if (match.matches()) {
                calculation(scope, Role.SOURCE_DEFINITION, match.group(1), "", input(match, 2, contentStart));
                return;
            }
            match = QUANTITY.matcher(content);
            if (match.matches() && shared != null) {
                calculation(scope, Role.SOURCE_DEFINITION, match.group(1), "", shared);
                return;
            }
            unknown(scope, "UNSUPPORTED_RESULT_REQUEST");
            calculationContinuation = false;
        }

        private Span input(Matcher match, int group, int contentStart) {
            return match.group(group) == null ? shared
                    : trim(new Span(contentStart + match.start(group), contentStart + match.end(group)), false);
        }

        private void calculation(Span scope, Role role, String quantity, String path, Span input) {
            calculation(scope, role, quantity, path, input, null);
        }

        private void calculation(Span scope, Role role, String quantity, String path, Span input, Span expression) {
            if (role == Role.SOURCE_DEFINITION && AMBIGUOUS_QUANTITY.matcher(quantity).matches()) {
                unknown(scope, "AMBIGUOUS_RESULT_QUANTITY");
                calculationContinuation = false;
                return;
            }
            if (input == null || !operands(input)) {
                unknown(scope, "UNSUPPORTED_RESULT_INPUTS");
                calculationContinuation = false;
                return;
            }
            Slot added = add(role, scope, input, quantity, path, List.of(), expression);
            if (added.role() != Role.UNKNOWN) sentenceCalculations.add(added);
            calculationContinuation = true;
        }

        private boolean operands(Span scope) {
            String inputs = text(scope);
            Matcher suffix = PRECISION_SUFFIX.matcher(inputs);
            if (suffix.matches()) {
                try { if (Integer.parseInt(suffix.group(2)) > 12) return false; }
                catch (NumberFormatException invalid) { return false; }
                inputs = suffix.group(1);
            }
            String[] parts = OPERAND_JOIN.split(inputs, -1);
            if (parts.length == 0 || parts.length > 32) return false;
            Set<String> names = new LinkedHashSet<>();
            for (String part : parts) {
                Matcher operand = OPERAND.matcher(part.strip());
                if (!operand.matches() || operand.group(1) == null && operand.group(3) == null) return false;
                if (operand.group(1) != null && !names.add(operand.group(1).toLowerCase(java.util.Locale.ROOT))) return false;
                String number = operand.group(2);
                if (number.length() > 64) return false;
                String[] decimal = number.replaceFirst("^[+-]", "").split("[eE]", -1);
                if (decimal[0].replace(".", "").replaceFirst("^0+", "").length() > 32) return false;
                int point = decimal[0].indexOf('.');
                int fractional = point < 0 ? 0 : decimal[0].length() - point - 1;
                try {
                    long exponent = decimal.length == 1 ? 0 : Long.parseLong(decimal[1]);
                    if (exponent < fractional - 18L || exponent > fractional + 18L) return false;
                } catch (NumberFormatException invalid) { return false; }
            }
            return true;
        }

        private void unknown(Span scope, String reason) {
            unresolved.add(reason);
            Slot slot = add(Role.UNKNOWN, scope, null, "", "", List.of());
            regionReasons.computeIfAbsent(slot.id(), ignored -> new LinkedHashSet<>()).add(reason);
        }

        private Slot add(Role role, Span scope, Span input, String quantity, String path, List<String> peers) {
            return add(role, scope, input, quantity, path, peers, null);
        }

        private Slot add(Role role, Span scope, Span input, String quantity, String path, List<String> peers, Span expression) {
            if (slots.size() >= MAX_SLOTS) {
                unresolved.add("RESULT_SLOT_BOUND_EXCEEDED");
                Slot previous = slots.getLast();
                Slot overflow = new Slot(previous.id(), Role.UNKNOWN, previous.start(), request.length(), -1, -1,
                        -1, -1, "", "", List.of());
                slots.set(slots.size() - 1, overflow);
                regionReasons.computeIfAbsent(overflow.id(), ignored -> new LinkedHashSet<>()).add("RESULT_SLOT_BOUND_EXCEEDED");
                return overflow;
            }
            Slot slot = new Slot("result-" + (slots.size() + 1) + "-" + scope.start() + "-" + scope.end(), role,
                    scope.start(), scope.end(), input == null ? -1 : input.start(), input == null ? -1 : input.end(),
                    expression == null ? -1 : expression.start(), expression == null ? -1 : expression.end(), quantity, path, peers);
            slots.add(slot);
            return slot;
        }

        private String text(Span span) { return request.substring(span.start(), span.end()); }

        private Span trim(Span span, boolean bullet) {
            int start = span.start();
            int end = span.end();
            while (start < end && Character.isWhitespace(request.charAt(start))) start++;
            while (end > start && Character.isWhitespace(request.charAt(end - 1))) end--;
            if (bullet) {
                Matcher marker = BULLET.matcher(request).region(start, end);
                if (marker.lookingAt()) start = marker.end();
            }
            return new Span(start, end);
        }

        private List<Span> sentences() {
            List<Span> spans = new ArrayList<>();
            int start = 0;
            char quote = 0;
            for (int index = 0; index < request.length(); index++) {
                char value = request.charAt(index);
                if (quote != 0) {
                    if (value == quote) quote = 0;
                } else if (value == '"' || value == '`' || value == '\''
                        && (index == 0 || !Character.isLetterOrDigit(request.charAt(index - 1)))) quote = value;
                else if (value == '.' && request.substring(start, index).strip().matches("[0-9]{1,2}")) continue;
                else if (value == '\n' || value == '\r' || value == ';'
                        || value == '.' && (index + 1 == request.length() || Character.isWhitespace(request.charAt(index + 1)))) {
                    spans.add(new Span(start, index));
                    start = index + 1;
                }
            }
            if (start < request.length()) spans.add(new Span(start, request.length()));
            return spans;
        }
    }
}
