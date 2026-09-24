package dev.talos.runtime.task;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded answer obligations captured from the original user request. */
public final class AnswerRequirements {
    public static final int MAX_REQUEST_CHARS = 16_000;
    public static final int MAX_REQUIREMENTS = 32;
    private static final int MAX_OPERANDS = 32;
    private static final String NAME = "[A-Za-z][A-Za-z0-9_]*";
    private static final String WORDS = NAME + "(?:[ \\t]+" + NAME + "){0,7}";
    private static final String FILE = "`?[A-Za-z0-9_./\\\\-]+\\.[A-Za-z0-9]{1,12}`?";
    private static final String FILES = FILE + "(?:\\s*(?:,\\s*(?:and\\s+)?|and\\s+)" + FILE + "){0,7}";
    private static final String NUMBER = "[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?";
    private static final Pattern READ = pattern("(?:read|inspect)\\s+(" + FILES + ")");
    private static final Pattern FILE_TOKEN = Pattern.compile(FILE);
    private static final Pattern DIAGNOSIS = pattern("explain\\s+why\\s+(?:the\\s+)?"
            + "(`?#[A-Za-z_][A-Za-z0-9_:-]*`?|" + WORDS + ")\\s+button\\s+(?:fails|does\\s+not\\s+work)");
    private static final Pattern SELECTOR = pattern("(?:give|identify)\\s+(?:the\\s+)?(?:correct|exact)\\s+selector");
    private static final Pattern NAMED_CALCULATION = pattern("(?:give|calculate)\\s+(?:the\\s+)?(" + WORDS + ")\\s+for\\s+(.+)");
    private static final Pattern IMPLICIT_CALCULATION = pattern("the\\s+(" + WORDS + ")\\s+for\\s+(.+)");
    private static final Pattern EXPRESSION = pattern("(?:evaluate\\s+(?:the\\s+expression\\s+)?|calculate\\s+the\\s+expression\\s+)"
            + "(.{1,2048}?)\\s+(?:for|with|where)\\s+(.+)");
    private static final Pattern DISTINCTION = pattern("(?:distinguish|compare)\\s+(?:the\\s+)?two\\s+(?:JavaScript|JS)\\s+files");
    private static final Pattern NAMED_DISTINCTION = pattern("(?:distinguish|compare)\\s+(" + FILE + ")\\s+and\\s+(" + FILE + ")");
    private static final Pattern RESTRICTION = pattern("(?:do\\s+not|don't)\\s+(?:(?:edit|modify|change)(?:\\s+(?:any\\s+)?files)?"
            + "(?:\\s+or\\s+(?:claim\\s+(?:that\\s+)?tests\\s+were\\s+run|run\\s+tests))?"
            + "|claim\\s+(?:that\\s+)?tests\\s+were\\s+run|run\\s+tests)");
    private static final Pattern PRECISION_SUFFIX = pattern("(.+?)\\s+(?:rounded\\s+)?to\\s+([0-9]+)\\s+(?:decimal\\s+places?|fractional\\s+digits?|decimals?)");
    private static final Pattern PRECISION_CLAUSE = pattern("use\\s+([0-9]+)\\s+(?:decimal\\s+places?|fractional\\s+digits?|decimals?)");
    private static final Pattern OPERAND = pattern("(?:((?:" + NAME + "))\\s*(?:=\\s*|\\s+))?(" + NUMBER + ")"
            + "(?:\\s+([A-Za-z][A-Za-z0-9_/%^-]*))?");
    private static final Pattern CLAUSE_JOIN = pattern("(?:,\\s*(?:and\\s+)?|\\band\\s+)"
            + "(?=(?:read|inspect|explain|give|identify|calculate|evaluate|distinguish|compare|do\\s+not|don't|use)\\s+|the\\s+"
            + WORDS + "\\s+for\\b)");
    private static final Pattern OPERAND_JOIN = pattern("(?:,\\s*(?:and\\s+)?|\\band\\b)\\s*");
    private static final Pattern UNKNOWN_CONTEXT = pattern("(?:if|suppose|assuming|imagine|for\\s+example|example)\\b.*");
    private static final Pattern NEGATED_COMMAND = pattern("(?:do\\s+not|don't)\\b.*");
    private static final Pattern DISTINCTION_ATTEMPT = pattern("(?:distinguish|compare)\\b.*");
    private static final Pattern CALCULATION_ATTEMPT = pattern("(?:give|calculate|evaluate)\\b.*");
    private static final Pattern NUMBERED_BULLET = Pattern.compile("[0-9]{1,2}[.)]\\s+");

    public enum Kind { DIAGNOSIS, SELECTOR, CALCULATION, SCRIPT_DISTINCTION, UNKNOWN }

    /** End offsets are exclusive and refer to the unchanged request. */
    public record Requirement(String id, Kind kind, int start, int end) { }

    /** The original decimal text is retained without floating point conversion. */
    public record Operand(String name, String value, String unit, int start, int end) { }

    private final boolean applicable;
    private final List<Requirement> requirements;
    private final List<String> unresolved;
    private final String target;
    private final boolean targetIsId;
    private final String quantity;
    private final String expression;
    private final List<Operand> operands;
    private final Integer precision;
    private final List<String> scriptTargets;

    private AnswerRequirements(Parser parsed) {
        applicable = parsed.read && parsed.diagnosis && (parsed.extension || !parsed.unresolved.isEmpty());
        requirements = List.copyOf(parsed.requirements);
        unresolved = List.copyOf(parsed.unresolved);
        target = parsed.target;
        targetIsId = parsed.targetIsId;
        quantity = parsed.quantity;
        expression = parsed.expression;
        operands = List.copyOf(parsed.operands);
        precision = parsed.precision;
        scriptTargets = List.copyOf(parsed.scriptTargets);
    }

    public static AnswerRequirements fromRequest(String request) {
        return new Parser(request == null ? "" : request).parse();
    }

    /** A retained clause is parsed without replacing its original coordinates. */
    public static AnswerRequirements fromCalculationClause(String request, int start, int end) {
        if (request == null || start < 0 || end < start || end > request.length()
                || request.length() > MAX_REQUEST_CHARS) throw new IllegalArgumentException("Invalid request scope.");
        Parser parsed = new Parser(request);
        parsed.clause(parsed.trim(new Span(start, end), false), true);
        return new AnswerRequirements(parsed);
    }

    /** Standalone precision is retained separately from calculation-local precision. */
    public static AnswerRequirements standalonePrecision(String request) {
        Parser parsed = new Parser(request == null ? "" : request);
        for (Span scope : standalonePrecisionScopes(parsed)) {
            Matcher match = PRECISION_CLAUSE.matcher(parsed.request.substring(scope.start(), scope.end()));
            if (match.matches()) parsed.setPrecision(match.group(1));
        }
        return new AnswerRequirements(parsed);
    }

    public static boolean standalonePrecisionReference(String request, int start, int end) {
        if (request == null || start < 0 || end < start || end > request.length()) return false;
        return standalonePrecisionScopes(new Parser(request)).stream()
                .anyMatch(scope -> start >= scope.start() && end <= scope.end());
    }

    private static List<Span> standalonePrecisionScopes(Parser parsed) {
        List<Span> scopes = new ArrayList<>();
        for (Span sentence : parsed.sentences(Math.min(parsed.request.length(), MAX_REQUEST_CHARS))) {
            Span scope = parsed.trim(sentence, true);
            String text = parsed.request.substring(scope.start(), scope.end());
            if (text.startsWith(">") || text.startsWith("\"") || text.startsWith("'") || text.startsWith("`")
                    || UNKNOWN_CONTEXT.matcher(text).matches() || NEGATED_COMMAND.matcher(text).matches()) continue;
            int begin = scope.start();
            Matcher joins = CLAUSE_JOIN.matcher(parsed.request).region(begin, scope.end());
            while (joins.find()) {
                Span clause = parsed.trim(new Span(begin, joins.start()), false);
                if (PRECISION_CLAUSE.matcher(parsed.request.substring(clause.start(), clause.end())).matches()) scopes.add(clause);
                begin = joins.end();
            }
            Span clause = parsed.trim(new Span(begin, scope.end()), false);
            if (PRECISION_CLAUSE.matcher(parsed.request.substring(clause.start(), clause.end())).matches()) scopes.add(clause);
        }
        return List.copyOf(scopes);
    }

    public boolean applicable() { return applicable; }
    public List<Requirement> requirements() { return requirements; }
    public List<String> unresolved() { return unresolved; }
    public String target() { return target; }
    public boolean targetIsId() { return targetIsId; }
    public String quantity() { return quantity; }
    public String expression() { return expression; }
    public List<Operand> operands() { return operands; }
    public Integer precision() { return precision; }
    public List<String> scriptTargets() { return scriptTargets; }
    public boolean has(Kind kind) { return requirements.stream().anyMatch(value -> value.kind() == kind); }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof AnswerRequirements that)) return false;
        return applicable == that.applicable && targetIsId == that.targetIsId
                && requirements.equals(that.requirements) && unresolved.equals(that.unresolved)
                && target.equals(that.target) && quantity.equals(that.quantity) && expression.equals(that.expression)
                && operands.equals(that.operands) && Objects.equals(precision, that.precision)
                && scriptTargets.equals(that.scriptTargets);
    }

    @Override
    public int hashCode() {
        return Objects.hash(applicable, requirements, unresolved, target, targetIsId, quantity,
                expression, operands, precision, scriptTargets);
    }

    private static Pattern pattern(String expression) {
        return Pattern.compile(expression, Pattern.CASE_INSENSITIVE);
    }

    private static String unquoteCode(String value) {
        return value.startsWith("`") && value.endsWith("`") ? value.substring(1, value.length() - 1) : value;
    }

    private record Span(int start, int end) { }

    private static final class Parser {
        private final String request;
        private final List<Requirement> requirements = new ArrayList<>();
        private final Set<String> unresolved = new LinkedHashSet<>();
        private final Set<String> readTargets = new LinkedHashSet<>();
        private final List<Operand> operands = new ArrayList<>();
        private final List<String> scriptTargets = new ArrayList<>();
        private boolean read;
        private boolean diagnosis;
        private boolean extension;
        private boolean targetIsId;
        private boolean genericDistinction;
        private String target = "";
        private String quantity = "";
        private String expression = "";
        private Integer precision;

        private Parser(String request) { this.request = request; }

        private AnswerRequirements parse() {
            int limit = Math.min(request.length(), MAX_REQUEST_CHARS);
            for (Span sentence : sentences(limit)) {
                Span trimmed = trim(sentence, true);
                if (trimmed.start() == trimmed.end()) continue;
                String text = request.substring(trimmed.start(), trimmed.end());
                // Unknown premises and quoted examples are retained as complete unknown spans.
                if (text.startsWith(">") || text.startsWith("\"") || text.startsWith("'")
                        || text.startsWith("`") || UNKNOWN_CONTEXT.matcher(text).matches()
                        || NEGATED_COMMAND.matcher(text).matches() && !RESTRICTION.matcher(text).matches()) {
                    unknown(trimmed, "UNRECOGNIZED_REQUEST_CLAUSE");
                    continue;
                }
                int begin = trimmed.start();
                Matcher joins = CLAUSE_JOIN.matcher(request).region(begin, trimmed.end());
                boolean afterSelector = false;
                while (joins.find()) {
                    afterSelector = clause(trim(new Span(begin, joins.start()), false), afterSelector);
                    begin = joins.end();
                }
                clause(trim(new Span(begin, trimmed.end()), false), afterSelector);
            }
            if (request.length() > limit) unknown(new Span(limit, request.length()), "REQUEST_BOUND_EXCEEDED");
            if (genericDistinction) {
                List<String> scripts = readTargets.stream().filter(path -> path.toLowerCase(Locale.ROOT).endsWith(".js")).toList();
                if (scripts.size() == 2 && scriptTargets.isEmpty()) scriptTargets.addAll(scripts);
                else if (scripts.size() != 2 || !Set.copyOf(scripts).equals(Set.copyOf(scriptTargets))) {
                    unresolved.add("AMBIGUOUS_SCRIPT_TARGETS");
                }
            }
            if (!scriptTargets.isEmpty() && (Set.copyOf(scriptTargets).size() != 2 || !readTargets.containsAll(scriptTargets))) {
                unresolved.add("SCRIPT_TARGET_NOT_EXPLICITLY_READ");
            }
            return new AnswerRequirements(this);
        }

        private boolean clause(Span span, boolean afterSelector) {
            if (span.start() == span.end()) return false;
            String text = request.substring(span.start(), span.end());
            Matcher match = READ.matcher(text);
            if (match.matches()) {
                read = true;
                Matcher paths = FILE_TOKEN.matcher(match.group(1));
                while (paths.find()) readTargets.add(unquoteCode(paths.group()));
                if (readTargets.size() > 8) unresolved.add("READ_TARGET_BOUND_EXCEEDED");
                return false;
            }
            match = DIAGNOSIS.matcher(text);
            if (match.matches()) {
                add(Kind.DIAGNOSIS, span);
                String parsedTarget = unquoteCode(match.group(1));
                boolean parsedId = parsedTarget.startsWith("#");
                parsedTarget = parsedId ? parsedTarget.substring(1) : parsedTarget;
                if (diagnosis && (!target.equals(parsedTarget) || targetIsId != parsedId)) unresolved.add("AMBIGUOUS_BUTTON_TARGET");
                else { target = parsedTarget; targetIsId = parsedId; }
                diagnosis = true;
                return false;
            }
            if (SELECTOR.matcher(text).matches()) {
                add(Kind.SELECTOR, span);
                return true;
            }
            if (RESTRICTION.matcher(text).matches()) return false;
            match = PRECISION_CLAUSE.matcher(text);
            if (match.matches()) { setPrecision(match.group(1)); return false; }
            if (DISTINCTION.matcher(text).matches()) {
                extension = true;
                genericDistinction = true;
                add(Kind.SCRIPT_DISTINCTION, span);
                return false;
            }
            match = NAMED_DISTINCTION.matcher(text);
            if (match.matches()) {
                extension = true;
                add(Kind.SCRIPT_DISTINCTION, span);
                List<String> targets = List.of(unquoteCode(match.group(1)), unquoteCode(match.group(2)));
                if (!scriptTargets.isEmpty() && !Set.copyOf(scriptTargets).equals(Set.copyOf(targets))) unresolved.add("AMBIGUOUS_SCRIPT_TARGETS");
                else if (scriptTargets.isEmpty()) scriptTargets.addAll(targets);
                return false;
            }
            match = EXPRESSION.matcher(text);
            if (match.matches()) {
                startCalculation(span);
                if (expression.isEmpty() && quantity.isEmpty()) {
                    expression = unquoteCode(match.group(1));
                    parseOperands(match.group(2), span.start() + match.start(2), true);
                }
                return false;
            }
            match = NAMED_CALCULATION.matcher(text);
            if (!match.matches() && afterSelector) match = IMPLICIT_CALCULATION.matcher(text);
            if (match.matches()) {
                startCalculation(span);
                if (quantity.isEmpty() && expression.isEmpty()) {
                    quantity = match.group(1);
                    parseOperands(match.group(2), span.start() + match.start(2), false);
                }
                return false;
            }
            if (DISTINCTION_ATTEMPT.matcher(text).matches()) {
                extension = true;
                add(Kind.SCRIPT_DISTINCTION, span);
                unresolved.add("UNSUPPORTED_SCRIPT_DISTINCTION");
            } else if (CALCULATION_ATTEMPT.matcher(text).matches()
                    || afterSelector && text.toLowerCase(Locale.ROOT).startsWith("the ")) {
                startCalculation(span);
                unresolved.add("UNSUPPORTED_CALCULATION_REQUEST");
            } else unknown(span, "UNRECOGNIZED_REQUEST_CLAUSE");
            return false;
        }

        private void startCalculation(Span span) {
            if (requirements.stream().anyMatch(item -> item.kind() == Kind.CALCULATION)) unresolved.add("MULTIPLE_CALCULATION_REQUIREMENTS");
            extension = true;
            add(Kind.CALCULATION, span);
        }

        private void parseOperands(String text, int offset, boolean requireNames) {
            Matcher precisionMatch = PRECISION_SUFFIX.matcher(text);
            if (precisionMatch.matches()) {
                setPrecision(precisionMatch.group(2));
                text = precisionMatch.group(1);
            }
            Matcher joins = OPERAND_JOIN.matcher(text);
            int start = 0;
            while (joins.find()) {
                operand(text.substring(start, joins.start()), offset + start, requireNames);
                start = joins.end();
            }
            operand(text.substring(start), offset + start, requireNames);
        }

        private void operand(String raw, int offset, boolean requireNames) {
            int first = 0;
            int last = raw.length();
            while (first < last && Character.isWhitespace(raw.charAt(first))) first++;
            while (last > first && Character.isWhitespace(raw.charAt(last - 1))) last--;
            String text = raw.substring(first, last);
            Matcher match = OPERAND.matcher(text);
            if (!match.matches()) { unresolved.add("UNSUPPORTED_OPERAND_BINDING"); return; }
            String name = match.group(1) == null ? "" : match.group(1);
            String value = match.group(2);
            String unit = match.group(3) == null ? "" : match.group(3);
            if (requireNames && name.isEmpty() || !requireNames && name.isEmpty() && unit.isEmpty()) unresolved.add("OPERAND_NAME_OR_UNIT_REQUIRED");
            if (!boundedDecimal(value)) unresolved.add("DECIMAL_LITERAL_BOUND_EXCEEDED");
            if (!name.isEmpty() && operands.stream().anyMatch(item -> name.equalsIgnoreCase(item.name()))) unresolved.add("DUPLICATE_OPERAND_NAME");
            if (operands.size() >= MAX_OPERANDS) { unresolved.add("OPERAND_BOUND_EXCEEDED"); return; }
            operands.add(new Operand(name, value, unit, offset + first, offset + last));
        }

        private boolean boundedDecimal(String value) {
            if (value.length() > 64) return false;
            String[] parts = value.replaceFirst("^[+-]", "").split("[eE]", -1);
            String digits = parts[0].replace(".", "").replaceFirst("^0+", "");
            if (digits.length() > 32) return false;
            int dot = parts[0].indexOf('.');
            int fractional = dot < 0 ? 0 : parts[0].length() - dot - 1;
            try {
                long exponent = parts.length == 1 ? 0 : Long.parseLong(parts[1]);
                return exponent >= fractional - 18L && exponent <= fractional + 18L;
            } catch (NumberFormatException ignored) { return false; }
        }

        private void setPrecision(String value) {
            try {
                int parsed = Integer.parseInt(value);
                if (parsed > 12) unresolved.add("PRECISION_BOUND_EXCEEDED");
                else if (precision != null && precision != parsed) unresolved.add("CONFLICTING_REQUEST_PRECISION");
                else precision = parsed;
            } catch (NumberFormatException ignored) { unresolved.add("PRECISION_BOUND_EXCEEDED"); }
        }

        private void unknown(Span span, String reason) {
            unresolved.add(reason);
            add(Kind.UNKNOWN, span);
        }

        private void add(Kind kind, Span span) {
            if (requirements.size() >= MAX_REQUIREMENTS) {
                unresolved.add("REQUIREMENT_BOUND_EXCEEDED");
                Requirement last = requirements.get(MAX_REQUIREMENTS - 1);
                requirements.set(MAX_REQUIREMENTS - 1,
                        new Requirement(last.id(), Kind.UNKNOWN, last.start(), request.length()));
                return;
            }
            requirements.add(new Requirement("answer-" + (requirements.size() + 1), kind, span.start(), span.end()));
        }

        private Span trim(Span span, boolean bullet) {
            int start = span.start();
            int end = span.end();
            while (start < end && Character.isWhitespace(request.charAt(start))) start++;
            while (end > start && Character.isWhitespace(request.charAt(end - 1))) end--;
            if (bullet && start + 1 < end && "-*".indexOf(request.charAt(start)) >= 0 && Character.isWhitespace(request.charAt(start + 1))) {
                start += 2;
                while (start < end && Character.isWhitespace(request.charAt(start))) start++;
            }
            if (bullet) {
                Matcher number = NUMBERED_BULLET.matcher(request).region(start, end);
                if (number.lookingAt()) start = number.end();
            }
            return new Span(start, end);
        }

        private List<Span> sentences(int limit) {
            List<Span> spans = new ArrayList<>();
            int start = 0;
            char quote = 0;
            for (int i = 0; i < limit; i++) {
                char value = request.charAt(i);
                if (quote != 0) {
                    if (value == quote) quote = 0;
                } else if (value == '"' || value == '`' || value == '\'' && (i == 0 || !Character.isLetterOrDigit(request.charAt(i - 1)))) quote = value;
                else if (value == '.' && request.substring(start, i).strip().matches("[0-9]{1,2}")) continue;
                else if (value == '\n' || value == '\r' || value == ';'
                        || value == '.' && (i + 1 == limit || Character.isWhitespace(request.charAt(i + 1)))) {
                    spans.add(new Span(start, i));
                    start = i + 1;
                }
            }
            if (start < limit) spans.add(new Span(start, limit));
            return spans;
        }
    }
}
