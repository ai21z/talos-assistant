package dev.talos.runtime.intent;

import dev.talos.runtime.context.ActiveTaskContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Automatic continuation is limited to direct proposal requests with explicit file scopes.
 * Unsupported scope is represented by a requested binding with no targets.
 * Relative paths with filename suffixes are supported, other path forms remain unbound.
 */
public record ProposalRequestBinding(
        boolean requested, List<String> targets, List<DeferredRestriction> deferredRestrictions) {
    public static final int MAX_REQUEST_CHARS = 8192;
    private static final String PATH = "(?:\\./)?[A-Za-z0-9_.-]+(?:[/\\\\][A-Za-z0-9_.-]+)*\\.[A-Za-z0-9]{1,12}";
    private static final String FILE = "(?:`" + PATH + "`|\"" + PATH + "\"|'" + PATH + "'|" + PATH + ")";
    private static final String FILE_LIST = FILE + "(?:\\s*(?:,\\s*(?:and\\s+)?|and\\s+)" + FILE + "){0,5}";
    private static final String POLITENESS = "(?:(?:please|kindly)\\s+)?";
    private static final String DIRECT_HEAD = "(?<lead>" + POLITENESS
            + "(?:(?:can|could|would)\\s+you\\s+" + POLITENESS + ")?)"
            + "(?<verb>propose|suggest|recommend)\\b";
    private static final String HEADER = DIRECT_HEAD + "\\s+(?:(?<determiner>the|these|those|some)\\s+)?"
            + "(?:changes|edits|updates|fixes)\\b";
    private static final Pattern AFFIRMATIVE = Pattern.compile("(?is)^" + DIRECT_HEAD + "(?:\\s+.*)?$");
    private static final Pattern NEGATED_OBJECT = Pattern.compile("(?i)^\\s*(?:no|not|nothing|neither)\\b");
    private static final Pattern PROPOSAL = Pattern.compile("(?i)^" + HEADER
            + "(?:\\s+(?:to|for|in)\\s+(?<paths>" + FILE_LIST + "))?"
            + "(?:\\s+(?:first|only))?\\s*[.!?]?$");
    private static final Pattern POSTPONED = Pattern.compile("(?i)^" + POLITENESS
            + "(?:do\\s+not|don['\\u2019]t)\\s+(?:edit|modify|change|update)\\s+"
            + "(?<path>" + FILE + ")\\s+yet\\s*[.!?]?$");
    private static final Pattern FILE_TOKEN = Pattern.compile(FILE);
    private static final Pattern PROPOSAL_WORD = Pattern.compile(
            "(?i)\\b(?:propos(?:e|es|ed|ing|al)|suggest(?:s|ed|ing|ion)?|recommend(?:s|ed|ing|ation)?)\\b");
    private static final Pattern DIRECT_MODAL = Pattern.compile(
            "(?i)^" + POLITENESS + "(?:can|could|would)\\s+you\\s+" + POLITENESS + "$");
    private static final ProposalRequestBinding NONE = new ProposalRequestBinding(false, List.of());
    private static final ProposalRequestBinding UNBOUND = new ProposalRequestBinding(true, List.of());

    public ProposalRequestBinding(boolean requested, List<String> targets) {
        this(requested, targets, List.of());
    }

    public ProposalRequestBinding {
        targets = targets == null ? List.of() : List.copyOf(targets);
        deferredRestrictions = deferredRestrictions == null ? List.of() : List.copyOf(deferredRestrictions);
        if ((!requested && !targets.isEmpty()) || targets.size() > ActiveTaskContext.MAX_TARGETS
                || targets.stream().anyMatch(path -> !safePath(path))
                || new LinkedHashSet<>(targets).size() != targets.size()
                || deferredRestrictions.size() > ActiveTaskContext.MAX_TARGETS
                || !targets.containsAll(deferredRestrictions.stream().map(DeferredRestriction::target).toList())) {
            throw new IllegalArgumentException("proposal binding requires a bounded exact target set");
        }
    }

    /** Current-turn restrictions are retained separately from later proposal scope. */
    public record DeferredRestriction(String target, int start, int end) {
        public DeferredRestriction {
            if (!safePath(target) || start < 0 || end <= start || end > MAX_REQUEST_CHARS) {
                throw new IllegalArgumentException("deferred restriction requires an exact bounded source span");
            }
        }
    }

    public List<String> deferredTargets() {
        return deferredRestrictions.stream().map(DeferredRestriction::target).distinct().toList();
    }

    public static ProposalRequestBinding fromRequest(String request) {
        if (request == null || request.isBlank() || request.length() > MAX_REQUEST_CHARS
                || !validUnicode(request)) return NONE;
        RequestLexicalView view = RequestLexicalView.of(request);
        if (view.containsProviderRemovedControl(0, request.length())
                || view.containsLoneCarriageReturn(0, request.length())
                || view.containsMalformedDelimiter(0, request.length())) return NONE;
        List<Candidate> candidates = new ArrayList<>();
        List<DeferredRestriction> postponed = new ArrayList<>();
        boolean affirmative = false;
        boolean ambiguous = false;
        boolean fullyAccounted = true;
        int explicitOccurrences = 0;
        Matcher allPaths = FILE_TOKEN.matcher(request);
        while (allPaths.find()) explicitOccurrences++;
        for (RequestLexicalView.Span span : sentences(view)) {
            String sentence = view.substring(span);
            Matcher intent = AFFIRMATIVE.matcher(sentence);
            Matcher proposal = PROPOSAL.matcher(sentence);
            boolean accounted = false;
            if (intent.matches() && direct(view, span, intent)) {
                affirmative = true;
                if (!proposal.matches()) {
                    ambiguous = true;
                    continue;
                }
                String paths = proposal.group("paths");
                List<String> selected = paths == null ? List.of() : parseTargets(paths);
                if (paths != null && selected.isEmpty()) return UNBOUND;
                boolean deictic = List.of("the", "these", "those").contains(
                        proposal.group("determiner") == null ? ""
                                : proposal.group("determiner").toLowerCase(java.util.Locale.ROOT));
                candidates.add(new Candidate(span.start(), selected, deictic));
                accounted = true;
            } else if (PROPOSAL_WORD.matcher(sentence).find()) {
                ambiguous = true;
            }
            Matcher deferred = POSTPONED.matcher(sentence);
            if (deferred.matches() && topLevel(view, span)
                    && view.isUnquotedRange(span.start(), span.start() + deferred.start("path"))) {
                List<String> selected = parseTargets(deferred.group("path"));
                if (selected.size() == 1) {
                    postponed.add(new DeferredRestriction(selected.getFirst(), span.start(), span.end()));
                    accounted = true;
                }
            }
            fullyAccounted &= accounted;
        }
        if (!affirmative) return NONE;
        if (ambiguous || candidates.isEmpty()) return UNBOUND;
        if (!postponed.isEmpty() && (!fullyAccounted
                || postponed.size() > ActiveTaskContext.MAX_TARGETS)) return UNBOUND;
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (Candidate candidate : candidates) {
            if (!candidate.targets().isEmpty()) targets.addAll(candidate.targets());
            else if (candidate.deictic() && candidates.size() == 1 && explicitOccurrences == 1
                    && postponed.size() == 1 && deferredBefore(view, candidate.start())) {
                targets.add(postponed.getFirst().target());
            } else return UNBOUND;
        }
        return targets.size() > ActiveTaskContext.MAX_TARGETS
                || postponed.stream().anyMatch(restriction -> !targets.contains(restriction.target())) ? UNBOUND
                : new ProposalRequestBinding(true, List.copyOf(targets), postponed);
    }

    private static boolean direct(RequestLexicalView view, RequestLexicalView.Span span, Matcher proposal) {
        if (!topLevel(view, span)
                || !view.isUnquotedRange(span.start(), span.start() + proposal.end("verb"))) return false;
        String lead = proposal.group("lead");
        if (!DIRECT_MODAL.matcher(lead).matches()
                && CanonicalMutationClauseMatcher.finiteScopeKind(lead)
                != CanonicalMutationClauseMatcher.ScopeKind.NONE) return false;
        String suffix = authorityText(view, span.start() + proposal.end("verb"), span.end());
        if (NEGATED_OBJECT.matcher(suffix).find()) return false;
        var suffixScope = CanonicalMutationClauseMatcher.finiteScopeKind(suffix);
        // Affirmative proposal intent is retained when later file restrictions leave its scope unbound.
        return suffixScope != CanonicalMutationClauseMatcher.ScopeKind.OPTIONAL
                && suffixScope != CanonicalMutationClauseMatcher.ScopeKind.REPORTED_ADVISORY
                && CanonicalMutationClauseMatcher.finitePostfixScopeKind(suffix)
                == CanonicalMutationClauseMatcher.ScopeKind.NONE;
    }

    private static String authorityText(RequestLexicalView view, int start, int end) {
        String original = view.masked().substring(start, end);
        StringBuilder authority = new StringBuilder(original);
        Matcher files = FILE_TOKEN.matcher(original);
        while (files.find()) {
            if (files.start() > 0 && pathCharacter(original.charAt(files.start() - 1))
                    || files.end() < original.length() && pathCharacter(original.charAt(files.end()))
                    && !(original.charAt(files.end()) == '.' && (files.end() + 1 == original.length()
                    || Character.isWhitespace(original.charAt(files.end() + 1))))) continue;
            for (int index = files.start(); index < files.end(); index++) authority.setCharAt(index, ' ');
        }
        return authority.toString();
    }

    private static boolean pathCharacter(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '-' || value == '.'
                || value == '/' || value == '\\' || value == ':';
    }

    private static boolean deferredBefore(RequestLexicalView view, int proposalStart) {
        for (RequestLexicalView.Span span : sentences(view)) {
            if (span.end() <= proposalStart && POSTPONED.matcher(view.substring(span)).matches()) return true;
        }
        return false;
    }

    private static boolean topLevel(RequestLexicalView view, RequestLexicalView.Span span) {
        for (int index = span.start(); index < span.end(); index++) {
            if (!view.isTopLevel(index) || view.request().charAt(index) == '>') return false;
        }
        return true;
    }

    private static List<RequestLexicalView.Span> sentences(RequestLexicalView view) {
        List<RequestLexicalView.Span> spans = new ArrayList<>();
        int start = 0;
        while (start < view.request().length()) {
            int end = view.sentenceEndAfter(start);
            RequestLexicalView.Span span = view.trimmedSpan(start, end);
            if (span.end() > span.start()) spans.add(span);
            start = end;
        }
        return spans;
    }

    private static List<String> parseTargets(String raw) {
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        Matcher matcher = FILE_TOKEN.matcher(raw);
        while (matcher.find()) {
            String path = matcher.group();
            if (path.charAt(0) == '`' || path.charAt(0) == '\'' || path.charAt(0) == '"') {
                path = path.substring(1, path.length() - 1);
            }
            path = TargetRef.normalizePath(path);
            if (!safePath(path) || !targets.add(path)) return List.of();
        }
        return targets.size() > ActiveTaskContext.MAX_TARGETS ? List.of() : List.copyOf(targets);
    }

    private static boolean safePath(String path) {
        if (path == null || path.isBlank() || !path.equals(TargetRef.normalizePath(path))
                || !path.matches("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*\\.[A-Za-z0-9]{1,12}")) return false;
        for (String segment : path.split("/")) {
            if (segment.equals(".") || segment.equals("..") || segment.endsWith(".")) return false;
        }
        return true;
    }

    private static boolean validUnicode(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.getType(current) == Character.FORMAT
                    || Character.isISOControl(current) && current != '\t' && current != '\r' && current != '\n') return false;
            if (Character.isHighSurrogate(current)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) return false;
            } else if (Character.isLowSurrogate(current)) return false;
        }
        return true;
    }

    private record Candidate(int start, List<String> targets, boolean deictic) {}
}
