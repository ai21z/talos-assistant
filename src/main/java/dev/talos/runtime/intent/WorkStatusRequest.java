package dev.talos.runtime.intent;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A bounded retained-work request grammar. No execution authority or evidence is supplied. */
public record WorkStatusRequest(String originalRequest, boolean selected, Coverage coverage,
                                List<Component> components) {
    public enum Coverage { COVERED, PARTIAL, INVALID }
    public enum EvidenceDomain { FRAME, RUNTIME, PROJECT, UNSUPPORTED }
    public enum Role {
        ACTIONS, VERIFICATION, UNCERTAINTY, TESTING, RELEASE, NEXT_STEPS,
        PROPOSALS, PROJECT_HANDOFF, UNSUPPORTED, PRESENTATION
    }

    private static final int MAX_LENGTH = 4096;
    private static final int MAX_COMPONENTS = 16;
    private static final String PREFIX = "(?:(?:please|briefly) )?(?:(?:can|could|would) you )?(?:please )?";
    private static final String SCOPE = "(?:in|during|for) (?:this|the current) (?:conversation|chat|session)";
    private static final String WORK = "(?:the work|this work|the changes|these changes|the implementation)";
    private static final String SUBJECT = "(?:" + WORK + "|this|it|that|they|the project|this project)";
    private static final String RESULT_PREDICATE = "(?:tested|ready to ship|ready for release)";
    private static final String PREDICATE = "(?:tested|verified|ready to ship|ready for release)";
    private static final Pattern HANDOFF = Pattern.compile(PREFIX
            + "(?:(?:give|show) me (?:a |the )?(?:short |brief )?(?:handoff|work status)"
            + "|summarize (?:the |our )?(?:work status|work|handoff))");
    private static final Pattern HANDOFF_CUE = Pattern.compile(HANDOFF.pattern() + "\\b");
    private static final Pattern QUESTION_CUE = Pattern.compile(PREFIX + "(?:"
            + "(?:is|are|was|were|has|have|can) .{1,100}?\\b" + PREDICATE
            + "|(?:summarize|tell me) whether .{1,160}?\\b" + PREDICATE
            + "|did (?:the |any )?tests run"
            + "|what (?:is known|was done|has been done|was performed|remains|is still|should happen next"
            + "|are the next steps|should be the next steps)"
            + "|which (?:work|changes) (?:is|are|was|were|has been|have been))\\b");
    private static final Pattern RESULT_QUESTION_CUE = Pattern.compile(PREFIX + "(?:"
            + "(?:is|are|was|were|has|have|can) .{1,100}?\\b" + RESULT_PREDICATE
            + "|(?:summarize|tell me) whether .{1,160}?\\b" + RESULT_PREDICATE
            + "|did (?:the |any )?tests run)\\b");
    private static final Pattern WORK_SUBJECT_CUE = Pattern.compile(PREFIX
            + "(?:(?:is|are|was|were) " + WORK + " (?:already |not |never )?"
            + "|(?:has|have) " + WORK + " (?:not )?been "
            + "|can " + WORK + " be called )" + PREDICATE + "\\b");
    private static final Pattern RETAINED_SCOPE = Pattern.compile(" " + SCOPE + "\\b");
    private static final Pattern STATUS = Pattern.compile(PREFIX
            + "(?:(?:is|are) (" + SUBJECT + ") (?:already )?"
            + "|(?:has|have) (" + SUBJECT + ") been "
            + "|can (" + SUBJECT + ") be called )(" + PREDICATE + ")"
            + "(?: " + SCOPE + ")?");
    private static final Pattern PREDICATE_ONLY = Pattern.compile("(" + PREDICATE + ")(?: " + SCOPE + ")?");
    private static final Pattern KNOWN = Pattern.compile(PREFIX
            + "(?:what (?:is known|was done|has been done|was performed)"
            + "|which (?:work|changes) (?:was|were|has been|have been) (?:done|performed))"
            + "(?: " + SCOPE + ")?");
    // Event components extend an independently selected status request, not its ownership anchors.
    private static final Pattern EVENT = Pattern.compile(PREFIX
            + "what (?:actually )?(?:changed|was changed|was denied|was rejected|was blocked)"
            + "(?: earlier| afterwards| before| later)?(?: " + SCOPE + ")?");
    private static final Pattern UNCERTAIN = Pattern.compile(PREFIX
            + "(?:what (?:remains|is still) (?:unverified|unchecked|unknown|uncertain)"
            + "|what remains)(?: " + SCOPE + ")?");
    private static final Pattern NEXT = Pattern.compile(PREFIX
            + "(?:what should happen next|what (?:are|should be) the next steps)(?: " + SCOPE + ")?");
    private static final Pattern TEST_HISTORY = Pattern.compile(PREFIX
            + "did (?:the |any )?tests run(?: " + SCOPE + ")?");
    private static final Pattern SEPARATE = Pattern.compile(PREFIX
            + "(?:keep proposed work separate from (?:anything actually done|work (?:actually )?(?:done|performed))"
            + "|separate proposed work from (?:performed work|work (?:done|performed)))"
            + "(?: " + SCOPE + ")?");
    private static final String ACTION = "(?:read|inspect|check|test|run|execute|edit|change|modify|write|"
            + "build|open|review|examine|search|create|delete|remove|fix|implement|deploy|install|verify|"
            + "update|replace|rename|move|copy|patch|apply|reset|commit|push|release|ship)";
    private static final Pattern NO_ACTION = Pattern.compile(PREFIX + "(?:do not|don't|dont|never) "
            + ACTION + "(?: (?:or|and) " + ACTION + "){0,5}"
            + " (?:anything|any files|files|tests|commands)(?: now)?");
    private static final String ACTION_OBJECT = ACTION + " (?:anything|any files|files|tests|commands)(?: now)?";
    private static final Pattern DISTRIBUTED_NO_ACTION = Pattern.compile(PREFIX
            + "(?:do not|don't|dont|never) " + ACTION_OBJECT + "(?: or " + ACTION_OBJECT + "){1,5}");
    private static final Pattern PRESENTATION = Pattern.compile(PREFIX
            + "(?:let's stop here|stop here|keep (?:it|the answer|the handoff) (?:short|brief)"
            + "|be brief|briefly|please|no (?:workspace|project|repository) inspection is (?:needed|required))");
    private static final Pattern FRESH_ACTION = Pattern.compile("(?:^|[(,;:]|\\b(?:and|or|but|then) )\\s*"
            + "(?:(?:also|now|please) )*(?:(?:can|could|would|will) you (?:please )?|"
            + "i (?:want|need) (?:you )?to |let's )?" + ACTION + "\\b");
    private static final Pattern JOIN = Pattern.compile("(?:,\\s*(?:(?:and|or|but|then) )?|\\s+(?:and|or|but|then) )"
            + "(?=(?:please |can you |could you |would you )?(?:what |which |is |are |has |have |can "
            + "|give |show |summarize |keep |separate |do not |don't |" + ACTION + "\\b|" + PREDICATE + "\\b))");

    public record Component(int start, int end, Set<Role> roles) {
        public Component {
            if (start < 0 || end <= start) throw new IllegalArgumentException("Empty or reversed component");
            roles = Set.copyOf(Objects.requireNonNull(roles));
            if (roles.isEmpty()) throw new IllegalArgumentException("Component roles are required");
        }
    }

    public WorkStatusRequest {
        Objects.requireNonNull(originalRequest);
        Objects.requireNonNull(coverage);
        components = List.copyOf(Objects.requireNonNull(components));
        if (!selected) {
            if (!components.isEmpty() || coverage != Coverage.COVERED) {
                throw new IllegalArgumentException("An unselected request has no owned components");
            }
        } else {
            if (components.isEmpty() || components.size() > MAX_COMPONENTS) {
                throw new IllegalArgumentException("Selected component bounds");
            }
            boolean unsupported = false;
            boolean substantive = false;
            int end = 0;
            for (Component component : components) {
                if (component.start() < end || component.end() > originalRequest.length()
                        || !separators(originalRequest, end, component.start())) {
                    throw new IllegalArgumentException("Components must cover ordered source spans");
                }
                unsupported |= component.roles().contains(Role.UNSUPPORTED);
                substantive |= component.roles().stream().anyMatch(role -> role != Role.PRESENTATION
                        && role != Role.UNSUPPORTED);
                end = component.end();
            }
            if (!separators(originalRequest, end, originalRequest.length()) || !substantive
                    || coverage == Coverage.COVERED && unsupported
                    || coverage == Coverage.PARTIAL && !unsupported
                    || originalRequest.length() > MAX_LENGTH && coverage != Coverage.INVALID) {
                throw new IllegalArgumentException("Inconsistent selected coverage");
            }
            if (coverage != Coverage.INVALID && malformed(RequestLexicalView.of(originalRequest))) {
                throw new IllegalArgumentException("Malformed requests require invalid coverage");
            }
        }
    }

    public Set<Role> roles() {
        EnumSet<Role> roles = EnumSet.noneOf(Role.class);
        components.forEach(component -> roles.addAll(component.roles()));
        return Set.copyOf(roles);
    }

    public boolean hasUnsupported() {
        return components.stream().anyMatch(component -> component.roles().contains(Role.UNSUPPORTED));
    }

    /** True when an explicit handoff or work-status frame, such as "give me a short handoff", owns the request. */
    public boolean handoffFrame() {
        return selected && components.stream().anyMatch(component -> HANDOFF.matcher(normalized(
                originalRequest.substring(component.start(), component.end()).toLowerCase(java.util.Locale.ROOT)))
                .matches());
    }

    /** Original text of the components outside the bounded grammar, for restriction checks only. */
    public List<String> unsupportedText() {
        return components.stream().filter(component -> component.roles().contains(Role.UNSUPPORTED))
                .map(component -> originalRequest.substring(component.start(), component.end())).toList();
    }

    /** Request ownership is separated from the sufficiency of retained evidence. */
    public boolean runtimeOnly() {
        return selected && coverage == Coverage.COVERED
                && components.stream().anyMatch(component -> domain(component) == EvidenceDomain.RUNTIME)
                && components.stream().allMatch(component -> domain(component) == EvidenceDomain.RUNTIME
                        || domain(component) == EvidenceDomain.FRAME);
    }

    public boolean requiresOperationDetails() {
        return components.stream().anyMatch(component -> domain(component) == EvidenceDomain.RUNTIME
                && component.roles().contains(Role.ACTIONS));
    }

    public EvidenceDomain domain(Component component) {
        if (!components.contains(component)) throw new IllegalArgumentException("An owned component is required");
        if (component.roles().contains(Role.UNSUPPORTED)) return EvidenceDomain.UNSUPPORTED;
        String text = normalized(originalRequest.substring(component.start(), component.end())
                .toLowerCase(java.util.Locale.ROOT));
        if (HANDOFF.matcher(text).matches() || PRESENTATION.matcher(text).matches() || isNoAction(text))
            return EvidenceDomain.FRAME;
        if (EVENT.matcher(text).matches() || KNOWN.matcher(text).matches() && !text.contains("what is known")
                || STATUS.matcher(text).matches() || PREDICATE_ONLY.matcher(text).matches()
                || TEST_HISTORY.matcher(text).matches()) return EvidenceDomain.RUNTIME;
        if (UNCERTAIN.matcher(text).matches() && text.matches(".*\\b(?:unverified|unchecked)(?: " + SCOPE + ")?$"))
            return EvidenceDomain.RUNTIME;
        return EvidenceDomain.PROJECT;
    }

    public static boolean hasFreshAction(String request) {
        String bounded = request == null ? "" : request.substring(0, Math.min(request.length(), MAX_LENGTH));
        var view = RequestLexicalView.of(bounded);
        return sentences(view).stream().map(span -> normalized(view.maskedLower().substring(span.start(), span.end())))
                .anyMatch(text -> !isNoAction(text) && FRESH_ACTION.matcher(text).find());
    }

    public static WorkStatusRequest parse(String request) {
        String original = request == null ? "" : request;
        if (original.isBlank()) return unselected(original);
        String bounded = original.substring(0, Math.min(original.length(), MAX_LENGTH));
        RequestLexicalView view = RequestLexicalView.of(bounded);
        List<Span> spans = segments(view);
        boolean selected = false;
        boolean frame = false;
        boolean freshAction = hasFreshAction(bounded);
        // A verification or uncertainty cue alone does not bind retained-work ownership.
        for (Span span : spans.subList(0, Math.min(spans.size(), MAX_COMPONENTS))) {
            String text = normalized(view.maskedLower().substring(span.start(), span.end()));
            boolean eligible = view.isTopLevel(span.start()) && !view.isQuoted(span.start());
            if (!eligible) continue;
            frame |= HANDOFF.matcher(text).matches();
            selected |= HANDOFF_CUE.matcher(text).lookingAt()
                    || WORK_SUBJECT_CUE.matcher(text).lookingAt()
                    || RESULT_QUESTION_CUE.matcher(text).lookingAt() || hasRetainedScopeAnchor(text);
        }
        int recognitionEnd = spans.isEmpty() ? 0 : spans.get(Math.min(spans.size(), MAX_COMPONENTS) - 1).end();
        // Action admission is inspected before conjunction segmentation so a prohibition retains scope.
        for (Span span : sentences(view)) {
            String text = normalized(view.maskedLower().substring(span.start(), span.end()));
            // A composed result question may start with verification and end with testing or release.
            if (span.start() < recognitionEnd && view.isTopLevel(span.start()) && !view.isQuoted(span.start())) {
                String recognized = normalized(view.maskedLower().substring(span.start(), Math.min(span.end(), recognitionEnd)));
                selected |= RESULT_QUESTION_CUE.matcher(recognized).lookingAt();
            }
        }
        if (!selected || freshAction) return unselected(original);
        List<Component> components = new ArrayList<>();
        boolean invalid = original.length() > MAX_LENGTH || malformed(view);
        boolean predicateBound = false;
        int previousEnd = 0;
        for (int index = 0; index < Math.min(spans.size(), MAX_COMPONENTS); index++) {
            Span span = spans.get(index);
            if (bounded.substring(previousEnd, span.start()).matches("(?s).*[.!?;\\n].*")) predicateBound = false;
            previousEnd = span.end();
            String text = normalized(view.maskedLower().substring(span.start(), span.end()));
            EnumSet<Role> roles = EnumSet.noneOf(Role.class);
            boolean eligible = view.isUnquotedRange(span.start(), span.end()) && view.isTopLevel(span.start());
            Matcher status = STATUS.matcher(text);
            Matcher predicate = PREDICATE_ONLY.matcher(text);
            if (eligible && HANDOFF.matcher(text).matches()) {
                roles.addAll(Set.of(Role.ACTIONS, Role.VERIFICATION, Role.UNCERTAINTY));
                if (!text.endsWith("work status")) roles.add(Role.PROJECT_HANDOFF);
            } else if (eligible && status.matches()) {
                String subject = status.group(1) != null ? status.group(1)
                        : status.group(2) != null ? status.group(2) : status.group(3);
                predicateBound = subject.matches(WORK) || frame && subject.matches("this|it|that");
                roles.add(predicateRole(status.group(4)));
                if (!predicateBound) roles.add(Role.UNSUPPORTED);
            } else if (eligible && predicate.matches()) {
                roles.add(predicateRole(predicate.group(1)));
                if (!predicateBound) roles.add(Role.UNSUPPORTED);
            } else if (eligible && (KNOWN.matcher(text).matches() || EVENT.matcher(text).matches())) {
                roles.add(Role.ACTIONS);
                if (text.contains("what is known")) roles.add(Role.PROJECT_HANDOFF);
                if (!frame && !text.matches(".*" + SCOPE + "$")) roles.add(Role.UNSUPPORTED);
            } else if (eligible && UNCERTAIN.matcher(text).matches()) {
                roles.add(Role.UNCERTAINTY);
                if (!frame && !text.matches(".*" + SCOPE + "$")) roles.add(Role.UNSUPPORTED);
            } else if (eligible && NEXT.matcher(text).matches()) {
                roles.add(Role.NEXT_STEPS);
                if (!frame) roles.add(Role.UNSUPPORTED);
            } else if (eligible && TEST_HISTORY.matcher(text).matches()) {
                roles.add(Role.TESTING);
            } else if (eligible && SEPARATE.matcher(text).matches()) {
                roles.addAll(Set.of(Role.PROPOSALS, Role.ACTIONS));
            } else if (eligible && (PRESENTATION.matcher(text).matches() || isNoAction(text))) {
                roles.add(Role.PRESENTATION);
            } else {
                roles.add(Role.UNSUPPORTED);
                // Recognized result predicates are retained even when their subject or scope is unresolved.
                boolean visibleLead = view.isTopLevel(span.start()) && !view.isQuoted(span.start());
                if (visibleLead && QUESTION_CUE.matcher(text).lookingAt()) {
                    if (text.matches(".*\\btested\\b.*")) roles.add(Role.TESTING);
                    if (text.matches(".*\\bdid (?:the |any )?tests run\\b.*")) roles.add(Role.TESTING);
                    if (text.matches(".*\\bverified\\b.*")) roles.add(Role.VERIFICATION);
                    if (text.matches(".*\\bready (?:to ship|for release)\\b.*")) roles.add(Role.RELEASE);
                    if (text.matches(".*\\bwhat (?:is known|was done|has been done)\\b.*")) roles.add(Role.ACTIONS);
                    if (roles.size() == 1) roles.add(Role.UNCERTAINTY);
                }
                if (visibleLead && HANDOFF_CUE.matcher(text).lookingAt()) roles.add(Role.ACTIONS);
            }
            boolean overflow = index == MAX_COMPONENTS - 1 && spans.size() > MAX_COMPONENTS;
            if (overflow) {
                roles.add(Role.UNSUPPORTED);
                invalid = true;
            }
            components.add(new Component(span.start(), overflow ? original.length() : span.end(), roles));
            if (!predicate.matches() && !status.matches()) predicateBound = false;
        }
        if (original.length() > bounded.length()
                && components.getLast().end() < original.length()) {
            Component last = components.removeLast();
            EnumSet<Role> roles = EnumSet.copyOf(last.roles());
            roles.add(Role.UNSUPPORTED);
            components.add(new Component(last.start(), original.length(), roles));
        }
        boolean unsupported = components.stream().anyMatch(c -> c.roles().contains(Role.UNSUPPORTED));
        return new WorkStatusRequest(original, true,
                invalid ? Coverage.INVALID : unsupported ? Coverage.PARTIAL : Coverage.COVERED, components);
    }

    private static Role predicateRole(String predicate) {
        return switch (predicate) {
            case "tested" -> Role.TESTING;
            case "verified" -> Role.VERIFICATION;
            default -> Role.RELEASE;
        };
    }

    private static boolean hasRetainedScopeAnchor(String text) {
        Matcher scope = RETAINED_SCOPE.matcher(text);
        if (!scope.find()) return false;
        String scopedClause = text.substring(0, scope.end());
        return KNOWN.matcher(scopedClause).matches() || UNCERTAIN.matcher(scopedClause).matches()
                || NEXT.matcher(scopedClause).matches() || STATUS.matcher(scopedClause).matches();
    }

    private static WorkStatusRequest unselected(String original) {
        return new WorkStatusRequest(original, false, Coverage.COVERED, List.of());
    }

    private static List<Span> sentences(RequestLexicalView view) {
        List<Span> spans = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < view.request().length(); i++) {
            if (!view.isQuoted(i) && view.isTopLevel(i)
                    && (view.isSentenceBoundaryAt(i) || view.request().charAt(i) == ';'
                    || view.request().charAt(i) == '\n')) {
                addSpan(spans, view.request(), start, i + 1);
                start = i + 1;
            }
        }
        addSpan(spans, view.request(), start, view.request().length());
        return spans;
    }

    private static List<Span> segments(RequestLexicalView view) {
        List<Span> spans = new ArrayList<>();
        for (Span sentence : sentences(view)) {
            String whole = normalized(view.maskedLower().substring(sentence.start(), sentence.end()));
            if (isNoAction(whole) || SEPARATE.matcher(whole).matches()) {
                spans.add(sentence);
                continue;
            }
            Matcher join = JOIN.matcher(view.maskedLower()).region(sentence.start(), sentence.end());
            int start = sentence.start();
            while (join.find()) {
                if (!view.isTopLevel(join.start()) || view.isQuoted(join.start())) continue;
                String before = normalized(view.maskedLower().substring(start, join.start()));
                if (before.isEmpty() || before.matches("(?:please|can you|could you|would you)")) continue;
                addSpan(spans, view.request(), start, join.start());
                // The connector belongs to the following span and cannot disappear from coverage.
                start = join.start();
            }
            addSpan(spans, view.request(), start, sentence.end());
        }
        return spans;
    }

    private static void addSpan(List<Span> spans, String text, int start, int end) {
        while (start < end && separator(text.charAt(start))) start++;
        while (end > start && separator(text.charAt(end - 1))) end--;
        if (start < end) spans.add(new Span(start, end));
    }

    private static boolean isNoAction(String text) {
        // Disjunction keeps the prohibition across separate action/object groups.
        // The shared command parser must also find no fresh command authority.
        return (NO_ACTION.matcher(text).matches() || DISTRIBUTED_NO_ACTION.matcher(text).matches())
                && CommandExecutionRequest.parse(text).commandClauses().isEmpty();
    }

    private static String normalized(String text) {
        return text.replace('\u2019', '\'').strip().replaceAll("\\s+", " ")
                .replaceFirst("^(?:and|or|but|then) +", "");
    }

    private static boolean malformed(RequestLexicalView view) {
        String text = view.request();
        if (view.containsMalformedDelimiter(0, text.length())
                || view.containsProviderRemovedControl(0, text.length())
                || view.containsLoneCarriageReturn(0, text.length())) return true;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (Character.getType(value) == Character.FORMAT) return true;
            if (Character.isHighSurrogate(value)) {
                if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i))) return true;
            } else if (Character.isLowSurrogate(value)) return true;
            if (view.isQuoted(i) && (i == 0 || !view.isQuoted(i - 1))) {
                RequestLexicalView.Span content = view.exactQuotedContentStartingAt(i, text.length());
                if (content == null) return true;
            }
        }
        return false;
    }

    private static boolean separators(String text, int start, int end) {
        for (int i = start; i < end; i++) if (!separator(text.charAt(i))) return false;
        return true;
    }

    private static boolean separator(char value) {
        return Character.isWhitespace(value) || ".!?,;".indexOf(value) >= 0;
    }

    private record Span(int start, int end) {}
}
