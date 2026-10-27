package dev.talos.runtime.intent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Request-only ownership of bounded product events, never execution authority. */
final class MutationPostfixOwnership {
    static final int MAX_CHARACTERS = 4096;
    static final int MAX_TARGETS = 64;
    private static final String ID = "(?:#[A-Za-z_][A-Za-z0-9_-]*|`#[A-Za-z_][A-Za-z0-9_-]*`)";
    private static final String PASSIVE = "(?:clicked|pressed|activated|focused|selected)";
    private static final Pattern BEHAVIOR = Pattern.compile(
            "\\s+so\\s+(?:that\\s+)?(?:the\\s+)?(?<subject>" + ID + ")"
                    + "\\s+(?:updates|changes|shows|hides|enables|disables)"
                    + "\\s+(?:the\\s+)?(?<object>" + ID + ")"
                    + "\\s+(?<event>when\\s+(?:"
                    + PASSIVE
                    + "|(?:the\\s+)?(?<passiveSubject>" + ID + ")\\s+is\\s+" + PASSIVE
                    + "|(?:the\\s+)?user\\s+(?:clicks|presses|activates|focuses|selects)"
                    + "\\s+(?<activeSubject>" + ID + ")))(?![A-Za-z0-9_-])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ADDITIONAL_CONDITION = Pattern.compile(
            "\\b(?:provided|until)\\b|\\bonly\\s+after\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern BEHAVIOR_OPENING = Pattern.compile("\\s+so\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern RAW_SO_CONNECTOR = Pattern.compile("\\s+so(?=\\s|,)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TERMINAL = Pattern.compile("\\s*[.!?]?\\s*");
    private static final Pattern TARGET_PREFIX = Pattern.compile(
            "[\\s:,-]*(?:(?:exactly|only)\\s+)*"
                    + "(?:(?:the|a|an|both|current|existing|local)\\s+)*"
                    + "(?:(?:file|files)\\s+)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern TARGET_GAP = Pattern.compile(
            "\\s*(?:,\\s*(?:(?:and|&)\\s+)?|(?:and|&)\\s+)(?:also\\s+)?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DISJUNCTION = Pattern.compile(
            "\\s*(?:,\\s*)?(?:or|and\\s*/\\s*or)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern INCOMPLETE_COORDINATION = Pattern.compile(
            "\\s+(?:and|&)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PATH = Pattern.compile("[A-Za-z0-9_./\\\\-]+");
    private static final Set<String> NAMED_FILES = Set.of(
            "readme", "license", "notice", "changelog", "contributing", "authors",
            "makefile", "dockerfile");

    private MutationPostfixOwnership() {}

    enum Kind { NONE, OPTIONAL, REPORTED, UNSUPPORTED }

    record Analysis(Kind kind, RequestLexicalView.Span behaviorSpan,
                    RequestLexicalView.Span eventSpan) {
        boolean closedBehavior() { return behaviorSpan != null; }
    }

    record Target(String path, int start, int end) {}

    record TargetList(int actionStart, int actionEnd, List<Target> targets, int outputEnd) {
        TargetList { targets = List.copyOf(targets); }
        int firstTargetEnd() { return targets.getFirst().end(); }
    }

    static Analysis analyze(RequestLexicalView view, int targetEnd, int clauseEnd) {
        if (!safe(view, targetEnd, clauseEnd)) return result(Kind.UNSUPPORTED);
        String request = view.request();
        Matcher behavior = BEHAVIOR.matcher(request).region(targetEnd, clauseEnd);
        if (!behavior.lookingAt() || !ownedAtoms(view, behavior)) {
            return result(rawScope(view, targetEnd, clauseEnd));
        }
        String subject = unquote(behavior.group("subject"));
        String passiveSubject = behavior.group("passiveSubject");
        String activeSubject = behavior.group("activeSubject");
        if (passiveSubject != null && !subject.equals(unquote(passiveSubject))
                || activeSubject != null && !subject.equals(unquote(activeSubject))) {
            return result(Kind.UNSUPPORTED);
        }
        int ownedEnd = behavior.end();
        Kind residual = rawScope(view, ownedEnd, clauseEnd);
        if (residual == Kind.NONE
                && !TERMINAL.matcher(request).region(ownedEnd, clauseEnd).matches()) {
            residual = Kind.UNSUPPORTED;
        }
        return new Analysis(residual,
                new RequestLexicalView.Span(targetEnd, ownedEnd),
                new RequestLexicalView.Span(behavior.start("event"), behavior.end("event")));
    }

    static Analysis analyzeConstraint(RequestLexicalView view, int targetEnd, int clauseEnd) {
        if (!safe(view, targetEnd, clauseEnd)) return result(Kind.UNSUPPORTED);
        Matcher opening = BEHAVIOR_OPENING.matcher(view.request()).region(targetEnd, clauseEnd);
        if (!opening.lookingAt() || !view.isUnquotedRange(targetEnd, opening.end())) {
            return result(Kind.NONE);
        }
        return analyze(view, targetEnd, clauseEnd);
    }

    static boolean rawConstraintTainted(RequestLexicalView view, int actionStart) {
        if (view == null || actionStart < 0 || actionStart >= view.request().length()) return true;
        int length = view.request().length();
        if (!hasRawControl(view, 0, length)) return false;
        int indexedStart = view.sentenceStartBefore(0, actionStart);
        int indexedEnd = view.sentenceEndAfter(actionStart);
        boolean indexedBoundsTopLevel = (indexedStart == 0 || view.isTopLevel(indexedStart - 1))
                && (indexedEnd == length || view.isTopLevel(indexedEnd - 1));
        if (indexedBoundsTopLevel && !hasRawControl(view, indexedStart, indexedEnd)) return false;
        int lower = indexedBoundsTopLevel ? indexedStart : 0;
        int upper = indexedBoundsTopLevel ? indexedEnd : length;
        int start = actionStart;
        int visited = 0;
        while (start > lower) {
            if (visited++ >= MAX_CHARACTERS) return true;
            int offset = start - 1;
            int resetEnd = rawHardResetEnd(view, offset);
            if (resetEnd >= 0 && resetEnd <= actionStart) {
                start = resetEnd;
                break;
            }
            start--;
        }
        int end = actionStart;
        while (end < upper) {
            if (visited++ >= MAX_CHARACTERS) return true;
            int resetEnd = rawHardResetEnd(view, end);
            if (resetEnd >= 0) {
                end = resetEnd;
                break;
            }
            end++;
        }
        if (end - start > MAX_CHARACTERS) return true;
        if (!hasRawControl(view, start, end)) return false;
        return containsConstraint(view, start, end);
    }

    static boolean containsConstraint(RequestLexicalView view, int start, int end) {
        if (view == null || start < 0 || end < start || end > view.request().length()
                || end - start > MAX_CHARACTERS) return true;
        if (CoordinatedMutationTailParser.firstUnquotedConstraintBoundary(view, start, end) >= 0) return true;
        Matcher so = RAW_SO_CONNECTOR.matcher(view.request()).region(start, end);
        while (so.find()) {
            if (view.isUnquotedRange(so.start(), so.end()) && view.isTopLevel(so.end() - 2)) return true;
        }
        return false;
    }

    private static boolean hasRawControl(RequestLexicalView view, int start, int end) {
        return view.containsProviderRemovedControl(start, end)
                || view.containsLoneCarriageReturn(start, end);
    }

    private static int rawHardResetEnd(RequestLexicalView view, int offset) {
        if (view.isQuoted(offset) || !view.isTopLevel(offset)) return -1;
        char value = view.request().charAt(offset);
        if (value == ';' || value == '\n' || value == '\u2028' || value == '\u2029'
                || view.isSentenceBoundaryAt(offset)) return offset + 1;
        if (value == '\r' && offset + 1 < view.request().length()
                && view.request().charAt(offset + 1) == '\n'
                && view.isUnquotedRange(offset, offset + 2)) return offset + 2;
        return -1;
    }

    static TargetList bindDirectTargets(
            RequestLexicalView view,
            CanonicalMutationClauseMatcher.Action action,
            int matchedFirstTargetEnd,
            int clauseEnd
    ) {
        if (action == null || !safe(view, action.start(), clauseEnd)
                || action.end() <= action.start() || action.end() > clauseEnd
                || !view.isUnquotedRange(action.start(), action.end())
                || !view.isTopLevel(action.start())
                || !view.request().substring(action.start(), action.end()).equalsIgnoreCase(action.verb())) {
            return null;
        }
        Matcher prefix = TARGET_PREFIX.matcher(view.request()).region(action.end(), clauseEnd);
        if (!prefix.lookingAt() || !view.isUnquotedRange(action.end(), prefix.end())) return null;
        Target first = target(view, prefix.end(), clauseEnd);
        if (first == null || first.end() != matchedFirstTargetEnd) return null;
        List<Target> targets = new ArrayList<>();
        targets.add(first);
        int end = first.end();
        while (end < clauseEnd) {
            if (DISJUNCTION.matcher(view.request()).region(end, clauseEnd).lookingAt()) return null;
            if (INCOMPLETE_COORDINATION.matcher(view.request()).region(end, clauseEnd).matches()) return null;
            Matcher gap = TARGET_GAP.matcher(view.request()).region(end, clauseEnd);
            if (!gap.lookingAt()) break;
            if (!view.isUnquotedRange(end, gap.end()) || targets.size() >= MAX_TARGETS) return null;
            Target next = target(view, gap.end(), clauseEnd);
            if (next == null) return null;
            targets.add(next);
            end = next.end();
        }
        return new TargetList(action.start(), action.end(), targets, end);
    }

    private static Target target(RequestLexicalView view, int start, int limit) {
        if (start >= limit || !view.isTopLevel(start)) return null;
        String request = view.request();
        int end;
        String path;
        if (request.charAt(start) == '`') {
            RequestLexicalView.Span content = view.exactQuotedContentStartingAt(start, limit);
            if (content == null) return null;
            RequestLexicalView.Span envelope = view.exactQuotedEnvelope(content.start(), content.end());
            if (envelope == null || envelope.start() != start || envelope.end() > limit
                    || request.charAt(envelope.end() - 1) != '`') return null;
            path = request.substring(content.start(), content.end());
            if (path.isBlank() || path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0
                    || path.indexOf('`') >= 0) return null;
            end = envelope.end();
        } else {
            Matcher token = PATH.matcher(request).region(start, limit);
            if (!token.lookingAt()) return null;
            end = token.end();
            while (end > start && request.charAt(end - 1) == '.') end--;
            if (end == start || !view.isUnquotedRange(start, end)) return null;
            path = request.substring(start, end);
            if (path.indexOf('.') < 0 && path.indexOf('/') < 0 && path.indexOf('\\') < 0
                    && !NAMED_FILES.contains(path.toLowerCase(java.util.Locale.ROOT))) return null;
        }
        if (end < limit && !Character.isWhitespace(request.charAt(end))
                && ",.!?;:".indexOf(request.charAt(end)) < 0) return null;
        try {
            return new Target(TargetRef.normalizePath(path), start, end);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean ownedAtoms(RequestLexicalView view, Matcher match) {
        int prior = match.start();
        for (String group : List.of("subject", "object", "passiveSubject", "activeSubject")) {
            int start = match.start(group);
            if (start < 0) continue;
            int end = match.end(group);
            if (!view.isUnquotedRange(prior, start) || !view.isTopLevel(start)) return false;
            if (view.request().charAt(start) == '`') {
                RequestLexicalView.Span content = view.exactQuotedContentStartingAt(start, end);
                if (content == null || content.start() != start + 1 || content.end() != end - 1) return false;
            } else if (!view.isUnquotedRange(start, end)) {
                return false;
            }
            prior = end;
        }
        return view.isUnquotedRange(prior, match.end());
    }

    private static Kind rawScope(RequestLexicalView view, int start, int end) {
        Kind kind = switch (CanonicalMutationClauseMatcher.finitePostfixScopeKind(view.masked(), start, end)) {
            case OPTIONAL -> Kind.OPTIONAL;
            case REPORTED_ADVISORY -> Kind.REPORTED;
            case NONE -> Kind.NONE;
            case PROHIBITED -> Kind.UNSUPPORTED;
        };
        return kind == Kind.NONE && ADDITIONAL_CONDITION.matcher(view.masked()).region(start, end).find()
                ? Kind.OPTIONAL : kind;
    }

    private static boolean safe(RequestLexicalView view, int start, int end) {
        return view != null && start >= 0 && end >= start && end <= view.request().length()
                && end - start <= MAX_CHARACTERS
                && !view.containsProviderRemovedControl(start, end)
                && !view.containsLoneCarriageReturn(start, end)
                && !view.containsMalformedDelimiter(start, end)
                && closedQuotes(view, start, end);
    }

    private static boolean closedQuotes(RequestLexicalView view, int start, int end) {
        for (int offset = start; offset < end; offset++) {
            if (!view.isQuoted(offset)) continue;
            RequestLexicalView.Span content = view.exactQuotedContentStartingAt(offset, end);
            if (content == null) return false;
            RequestLexicalView.Span envelope = view.exactQuotedEnvelope(content.start(), content.end());
            if (envelope == null || envelope.start() != offset || envelope.end() > end) return false;
            offset = envelope.end() - 1;
        }
        return true;
    }

    private static String unquote(String atom) {
        return atom.startsWith("`") ? atom.substring(1, atom.length() - 1) : atom;
    }

    private static Analysis result(Kind kind) { return new Analysis(kind, null, null); }
}
