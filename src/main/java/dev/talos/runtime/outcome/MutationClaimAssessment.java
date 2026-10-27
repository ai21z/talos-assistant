package dev.talos.runtime.outcome;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded lexical attribution of answer claims. No execution evidence is inferred from prose. */
public record MutationClaimAssessment(List<MutationClaimAssessment.Claim> claims) {
    public enum Actor { SELF, TALOS, EXTERNAL, UNKNOWN }
    public enum TemporalScope { CURRENT, HISTORICAL, CONDITIONAL, UNKNOWN }
    public enum Assertion { ASSERTED, NEGATED, QUOTED, PROPOSED }

    public record Claim(int start, int end, String text, Actor actor, TemporalScope temporalScope,
                        Assertion assertion, List<String> targets, boolean fileMutation,
                        boolean completedFileNarrative, boolean reportedQuotation) {
        public Claim {
            targets = List.copyOf(targets);
        }

        public boolean isCurrentRuntimeClaim() {
            return fileMutation && assertion == Assertion.ASSERTED && temporalScope == TemporalScope.CURRENT
                    && (actor == Actor.SELF || actor == Actor.TALOS);
        }

        public boolean isUnattributedClaim() {
            if (!fileMutation || actor == Actor.EXTERNAL || reportedQuotation
                    || assertion == Assertion.NEGATED || assertion == Assertion.PROPOSED
                    || temporalScope == TemporalScope.CONDITIONAL) return false;
            return actor == Actor.UNKNOWN || temporalScope != TemporalScope.CURRENT
                    || assertion == Assertion.QUOTED;
        }
    }

    private static final String ACTION = "(?:updated|edited|changed|applied|written|wrote|created|modified|saved|replaced|deleted|removed|renamed|moved|"
            + "update|edit|change|apply|write|create|modify|save|replace|delete|remove|rename|move)";
    private static final Pattern VERB = pattern("\\b" + ACTION + "\\b");
    private static final Pattern COMPLETED_VERB = pattern("updated|edited|changed|applied|written|wrote|created|modified|saved|replaced|deleted|removed|renamed|moved");
    private static final Pattern SUBJECT = pattern("\\b(i|talos|you|the operator|operator|the user|user|they|he|she|we)"
            + "(?:['’]ve|['’]d|['’]ll)?(?:\\s+(?:haven['’]t|hasn['’]t|hadn['’]t|didn['’]t|don['’]t|have|has|had|did|do|was|were|will|would|could|should|can|may|might|not|never|already|just|now|successfully|also|only|actually|been)){0,6}\\s*$");
    private static final Pattern BY_ACTOR = pattern("\\bby\\s+(me|talos|you|the operator|operator|the user|user|them|him|her)\\b");
    private static final Pattern PASSIVE = pattern("\\b(?:has|have|had|was|were|is|are|be|been)\\s+(?:(?:not|never|already|just|been)\\s+)*$");
    private static final Pattern PATH = Pattern.compile("(?<![\\w])(?:(?:[A-Za-z]:)?[\\\\/])?(?:[\\w.@+-]+[\\\\/])*[\\w@+-]+\\.[A-Za-z][\\w.-]*|(?<![\\w.])\\.[A-Za-z][\\w.-]*|\\b(?:README|LICENSE|Dockerfile|Makefile|AGENTS)\\b");
    private static final Pattern FILE_OBJECT = pattern("\\b(?:files?|changes?|readme|code|source|configuration|config|settings|scripts?|stylesheets?|pages?|headline|heading|title|button|css|html|disk|workspace|repository)\\b");
    private static final Pattern NON_FILE_OBJECT = pattern("^\\s*(?:(?:my|the|our|an?|this)\\s+)?(?:assessment|mind|opinion|understanding|answer|response|plan|proposal|recommendation|suggestion|interpretation|estimate|analysis)\\b");
    private static final Pattern NEGATION = pattern("\\b(?:not|never|nothing|no|zero|0)\\b|n['’]t\\b");
    private static final Pattern CONDITIONAL = pattern("\\b(?:if|once|unless|when|would|could|might|will)\\b");
    private static final Pattern PROPOSAL = pattern("\\b(?:proposed|proposal|suggested|suggestion|should|could|would|plan to|intend to|example)\\b");
    private static final Pattern HISTORICAL = pattern("\\b(?:yesterday|last (?:turn|session|week|month|year)|previous (?:turn|session)|prior (?:turn|session)|before this turn)\\b");
    private static final Pattern UNCERTAIN_TIME = pattern("\\b(?:earlier|previously|before|once)\\b");
    private static final Pattern CURRENT_TIME = pattern("\\b(?:this (?:turn|session)|just now|during this request)\\b");
    private static final Pattern ZERO_OBJECT = pattern("^\\s*(?:(?:and|or)\\s+" + ACTION + "\\s+)*(?:exactly\\s+)?(?:no|zero|0|nothing)\\b");
    private static final Pattern OBJECT_END = pattern("\\s+(?:after|because|to|with|using|from|based on)\\s+");
    private static final Pattern REPORT = pattern("\\b(?:says?|said|wrote|reported|report|note|log|quote|quoted|example|string|transcript|proposed|proposal)\\b");
    private static final Pattern REJECTION = pattern("\\b(?:inaccurate|incorrect|false|untrue|not (?:true|accurate)|did not happen)\\b");
    private static final Pattern EXTERNAL_REPORT = pattern("\\b(?:as|according to)\\s+(?:(?:you|the operator|the user|they)\\s+(?:said|reported)|your report)\\b");
    private static final Pattern NARRATIVE = pattern("(?:\\bhere (?:is|are)\\s+|\\bbelow (?:is|are)\\s+|^\\s*#{1,6}\\s*|^\\s*)(?:the\\s+)?(?:updated|edited|modified|revised|new)\\b");
    private static final String CLAUSE_SUBJECT = "(?:i|talos|you|the operator|operator|the user|user|they|he|she|we|here|below)\\b";
    private static final String COORDINATED_ACTION = "(?:(?:have|has|had|did|do|will|would|could|should|not|never|already|just|also|haven['’]t|didn['’]t)\\s+){0,6}" + ACTION + "\\b(?![./\\\\])";
    private static final Pattern CLAUSE = pattern(";\\s*|(?:,\\s*(?:(?:but|and|or|while)\\s+)?|\\s+(?:but|and|or|while)\\s+)(?="
            + CLAUSE_SUBJECT + "|" + COORDINATED_ACTION + ")");
    private static final Pattern INHERITED_PREFIX = pattern("^(?:(?:have|has|had|did|do|will|would|could|should|not|never|already|just|also|haven['’]t|didn['’]t)\\s+)*$");

    public MutationClaimAssessment {
        claims = List.copyOf(claims);
    }

    public boolean hasCurrentRuntimeClaim() {
        return claims.stream().anyMatch(Claim::isCurrentRuntimeClaim);
    }

    public boolean hasUnattributedClaim() {
        return claims.stream().anyMatch(Claim::isUnattributedClaim);
    }

    public boolean hasCompletedFileNarrative() {
        return claims.stream().anyMatch(Claim::completedFileNarrative);
    }

    public static MutationClaimAssessment assess(String answer) {
        if (answer == null || answer.isBlank()) return new MutationClaimAssessment(List.of());
        List<Quote> quotes = quotes(answer);
        List<Quote> targetQuotes = new ArrayList<>();
        List<Claim> claims = new ArrayList<>();
        Matcher actions = VERB.matcher(answer);
        while (actions.find()) {
            if (containing(targetQuotes, actions.start()) != null) continue;
            if (actions.end() < answer.length() && ".\\/".indexOf(answer.charAt(actions.end())) >= 0) continue;
            int start = sentenceStart(answer, actions.start());
            int end = sentenceEnd(answer, actions.end());
            String sentence = answer.substring(start, end);
            int verbStart = actions.start() - start;
            int verbEnd = actions.end() - start;
            int clauseStart = 0;
            int delimiterStart = -1;
            Quote actionQuote = containing(quotes, actions.start());
            Matcher clauses = CLAUSE.matcher(sentence);
            while (clauses.find() && clauses.end() <= verbStart) {
                Quote delimiterQuote = containing(quotes, start + clauses.start());
                if (delimiterQuote != null && delimiterQuote != actionQuote) continue;
                delimiterStart = clauses.start();
                clauseStart = clauses.end();
            }
            int clauseEnd = sentence.length();
            clauses.reset();
            while (clauses.find()) {
                Quote delimiterQuote = containing(quotes, start + clauses.start());
                if (delimiterQuote != null && delimiterQuote != actionQuote) continue;
                if (clauses.start() >= verbEnd) { clauseEnd = clauses.start(); break; }
            }
            String prefix = sentence.substring(clauseStart, verbStart);
            String suffix = sentence.substring(verbEnd, clauseEnd);
            String local = prefix + actions.group() + suffix;
            String conditionalPrefix = prefix;
            if (clauseStart > 0 && sentence.substring(0, clauseStart).stripLeading()
                    .matches("(?is)^(?:if|once|unless|when)\\b.*")) {
                conditionalPrefix = sentence.substring(0, verbStart);
            }
            Matcher subject = SUBJECT.matcher(prefix);
            Actor actor = subject.find() ? actor(subject.group(1)) : Actor.UNKNOWN;
            if (actor == Actor.UNKNOWN && delimiterStart >= 0 && INHERITED_PREFIX.matcher(prefix).matches()
                    && !sentence.substring(delimiterStart, clauseStart).contains(";")) {
                for (int previous = claims.size() - 1; previous >= 0; previous--) {
                    Claim earlier = claims.get(previous);
                    if (earlier.start() < start) break;
                    if (earlier.end() <= start + clauseStart) {
                        actor = earlier.actor();
                        break;
                    }
                }
            }
            boolean presentation = NARRATIVE.matcher(local).find();
            boolean passive = actor == Actor.UNKNOWN && !presentation && PASSIVE.matcher(prefix).find();
            if (passive) {
                Matcher by = BY_ACTOR.matcher(suffix);
                if (by.find()) actor = actor(by.group(1));
                else if (EXTERNAL_REPORT.matcher(sentence).find()) actor = Actor.EXTERNAL;
            }
            String object = passive ? prefix : suffix;
            if (!passive) {
                Matcher objectEnd = OBJECT_END.matcher(object);
                List<Quote> objectQuotes = quotes(object);
                while (objectEnd.find()) {
                    if (containing(objectQuotes, objectEnd.start()) == null) {
                        object = object.substring(0, objectEnd.start());
                        break;
                    }
                }
            }
            List<String> targets = targets(object);
            boolean file = !NON_FILE_OBJECT.matcher(suffix).find()
                    && (!targets.isEmpty() || FILE_OBJECT.matcher(object).find());
            // An abstract direct object remains abstract even when a source file is mentioned later.
            if (passive) file = !targets.isEmpty() || FILE_OBJECT.matcher(prefix).find();
            if (!file) targets = List.of();
            if (file && actionQuote == null && !passive) {
                for (Quote candidate : quotes) {
                    if (candidate.start >= actions.end() && candidate.end <= start + clauseEnd
                            && !candidate.fenced && targets.contains(
                                    answer.substring(candidate.start + 1, candidate.end - 1))) {
                        targetQuotes.add(candidate);
                    }
                }
            }
            Assertion assertion = Assertion.ASSERTED;
            boolean negative = NEGATION.matcher(prefix).find()
                    || ZERO_OBJECT.matcher(suffix).find();
            if (delimiterStart >= 0 && prefix.isBlank()
                    && sentence.substring(0, delimiterStart).stripLeading()
                            .matches("(?is)^(?:nothing|no\\s+\\w+|zero\\s+\\w+|0\\s+\\w+)\\b.*")) {
                negative = true;
            }
            if (negative) assertion = Assertion.NEGATED;
            else if (PROPOSAL.matcher(prefix).find()) assertion = Assertion.PROPOSED;
            TemporalScope temporal = actor == Actor.SELF || actor == Actor.TALOS
                    ? TemporalScope.CURRENT : TemporalScope.UNKNOWN;
            if (HISTORICAL.matcher(local).find()) temporal = TemporalScope.HISTORICAL;
            else if (CONDITIONAL.matcher(conditionalPrefix).find()) temporal = TemporalScope.CONDITIONAL;
            else if (UNCERTAIN_TIME.matcher(prefix).find()) temporal = TemporalScope.UNKNOWN;
            else if (CURRENT_TIME.matcher(local).find()) temporal = TemporalScope.CURRENT;
            if (!COMPLETED_VERB.matcher(actions.group()).matches()
                    && !pattern("\\bdid\\s+$").matcher(prefix).find()
                    && temporal == TemporalScope.CURRENT) temporal = TemporalScope.UNKNOWN;
            Quote quote = containing(quotes, actions.start());
            boolean reported = false;
            if (quote != null) {
                int beforeStart = sentenceStart(answer, quote.start);
                if (quote.fenced && beforeStart == quote.start && quote.start > 0) {
                    beforeStart = sentenceStart(answer, quote.start - 1);
                }
                String before = answer.substring(beforeStart, quote.start);
                String after = answer.substring(quote.end, sentenceEnd(answer, quote.end));
                reported = REPORT.matcher(before).find() || REJECTION.matcher(after).find();
                if (assertion != Assertion.NEGATED) {
                    assertion = PROPOSAL.matcher(before).find() ? Assertion.PROPOSED : Assertion.QUOTED;
                }
                if (reported) actor = Actor.EXTERNAL;
                else actor = Actor.UNKNOWN;
            } else if (REPORT.matcher(prefix).find() && actor == Actor.SELF) {
                // Unquoted reported speech does not establish that the current speaker performed it.
                actor = Actor.UNKNOWN;
            }
            boolean narrative = file && assertion == Assertion.ASSERTED && quote == null
                    && temporal != TemporalScope.CONDITIONAL && temporal != TemporalScope.HISTORICAL
                    && actor != Actor.EXTERNAL && presentation;
            if (actor == Actor.UNKNOWN && !passive && !narrative && !subject.reset().find()
                    && !file) continue;
            claims.add(new Claim(start + clauseStart, start + clauseEnd,
                    answer.substring(start + clauseStart, start + clauseEnd), actor, temporal,
                    assertion, targets, file, narrative, reported));
        }
        return new MutationClaimAssessment(claims);
    }

    private static Actor actor(String subject) {
        return switch (subject.toLowerCase(Locale.ROOT)) {
            case "i", "me" -> Actor.SELF;
            case "talos" -> Actor.TALOS;
            default -> Actor.EXTERNAL;
        };
    }

    private static List<String> targets(String text) {
        record Target(int start, String path) {}
        List<Target> found = new ArrayList<>();
        List<Quote> quoted = quotes(text);
        for (Quote quote : quoted) {
            if (quote.fenced) continue;
            String candidate = text.substring(quote.start + 1, quote.end - 1);
            if (PATH.matcher(candidate).find()) {
                // Preserve the complete quoted target, including spaces. Never match only its basename.
                found.add(new Target(quote.start, candidate));
            }
        }
        Matcher paths = PATH.matcher(text);
        int previousPathEnd = 0;
        while (paths.find()) {
            if (containing(quoted, paths.start()) != null) continue;
            String path = paths.group().replaceAll("[.]+$", "");
            int targetStart = paths.start();
            String before = text.substring(previousPathEnd, paths.start());
            Matcher contextBoundary = pattern("(?:^|[\\s,])(?:(?:and|or|but|in|at|to|from|the|a|an|both)\\s+|files?(?:\\s*:\\s*|\\s+))")
                    .matcher(before);
            int componentStart = 0;
            while (contextBoundary.find()) componentStart = contextBoundary.end();
            while (componentStart < before.length() && Character.isWhitespace(before.charAt(componentStart))) {
                componentStart++;
            }
            if (componentStart < before.length() && before.substring(componentStart).indexOf(',') < 0) {
                // Ambiguous bare path words are retained, not collapsed to a matching suffix.
                targetStart = previousPathEnd + componentStart;
                path = text.substring(targetStart, paths.end()).replaceAll("[.]+$", "");
            }
            if (!path.isBlank()) found.add(new Target(targetStart, path));
            previousPathEnd = paths.end();
        }
        found.sort(java.util.Comparator.comparingInt(Target::start));
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Target target : found) result.add(target.path());
        return List.copyOf(result);
    }

    private static Pattern pattern(String source) {
        return Pattern.compile(source, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static boolean boundary(String text, int at) {
        char c = text.charAt(at);
        return c == '\n' || c == '\r' || c == '!' || c == '?'
                || (c == '.' && (at + 1 == text.length()
                || (!Character.isLetterOrDigit(text.charAt(at + 1)) && "./\\_@+-".indexOf(text.charAt(at + 1)) < 0)));
    }

    private static int sentenceStart(String text, int at) {
        for (int i = Math.min(at, text.length()) - 1; i >= 0; i--) {
            if (boundary(text, i)) return i + 1;
        }
        return 0;
    }

    private static int sentenceEnd(String text, int at) {
        for (int i = Math.min(at, text.length()); i < text.length(); i++) {
            if (boundary(text, i)) return i + 1;
        }
        return text.length();
    }

    private record Quote(int start, int end, boolean fenced) {}

    private static Quote containing(List<Quote> quotes, int at) {
        for (Quote quote : quotes) if (at >= quote.start && at < quote.end) return quote;
        return null;
    }

    private static List<Quote> quotes(String text) {
        List<Quote> result = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            String delimiter;
            if (text.startsWith("```", i)) delimiter = "```";
            else if (text.startsWith("~~~", i)) delimiter = "~~~";
            else if (c == '`' || c == '"') delimiter = String.valueOf(c);
            else if (c == '“') delimiter = "”";
            else if (c == '‘') delimiter = "’";
            else if (c == '\'' && (i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1)))) delimiter = "'";
            else continue;
            int close = text.indexOf(delimiter, i + delimiter.length());
            while (close > 0 && (delimiter.equals("'") || delimiter.equals("’"))
                    && close + 1 < text.length() && Character.isLetterOrDigit(text.charAt(close - 1))
                    && Character.isLetterOrDigit(text.charAt(close + 1))) {
                close = text.indexOf(delimiter, close + 1);
            }
            if (close < 0) continue;
            if (delimiter.length() == 1 && text.substring(i, close).contains("\n")) continue;
            result.add(new Quote(i, close + delimiter.length(), delimiter.length() == 3));
            i = close + delimiter.length() - 1;
        }
        return result;
    }
}
