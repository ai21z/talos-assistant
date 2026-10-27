package dev.talos.runtime.intent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.function.UnaryOperator;

/** Request-local evidence selection. No facts or execution authority are supplied. */
public record ConversationEvidenceRequest(Source source, String positiveIntentText) {
    public enum Source { NORMAL, RUNTIME_IDENTITY, SESSION_ACTIONS }

    private static final int MAX_LENGTH = 4096;
    private static final String PREFIX = "(?:please )?(?:(?:can|could|would) you )?(?:please )?";
    private static final String SCOPE = "(?:in|during|for) (?:this|the current) (?:conversation|chat|session)";
    private static final Pattern DISCOURSE = Pattern.compile("(?:hi|hello|hey|by the way)");
    private static final Pattern DISCOURSE_PREFIX = Pattern.compile("^(?:hi|hello|hey|by the way),? +");
    private static final Pattern IDENTITY = Pattern.compile(PREFIX + "(?:"
            + "(?:who|what) (?:are you|is talos)"
            + "|(?:tell me (?:who|what) you are|tell me about yourself|identify yourself)"
            + "|(?:what|which) (?:ai |language )?(?:model|llm|backend) "
            + "(?:are you(?: (?:currently )?(?:using|running))?"
            + "|is talos (?:using|running)"
            + "|is (?:currently )?(?:loaded|running|configured)"
            + "|is (?:being )?used (?:for|in) this (?:conversation|chat|session)"
            + "|(?:powers|is powering) this (?:conversation|chat|session)"
            + "|is (?:actually |currently )?(?:answering|serving) (?:this|the current) (?:conversation|chat|session)"
            + "|is (?:actually |currently )?(?:answering|responding to) me(?: " + SCOPE + ")?)"
            + "|(?:what is|what's) (?:your|the current) (?:model|model name|backend))");
    private static final String ACTION = "(?:run (?:any )?(?:tests|commands)"
            + "|(?:changed|edited|modified|read|inspected) (?:any )?files)";
    private static final Pattern ACTION_HISTORY = Pattern.compile(PREFIX
            + "(?:have you|has talos) (?:actually |already )?" + ACTION
            + "(?: (?:or|and) " + ACTION + "){0,3}(?: " + SCOPE + ")?"
            + "(?:,? or only (?:read|inspected) them)?");
    private static final Pattern ACCURACY = Pattern.compile("(?:"
            + "if you (?:cannot|can't) (?:verify|confirm|establish) (?:it|that|the identity), (?:say so|state that)"
            + "|(?:do not|don't|dont) (?:guess|infer) (?:a |the )?(?:model(?: name)?|identity)"
            + "(?: from (?:how you write|(?:your |the )?(?:writing )?style))?"
            + "|(?:state|include) (?:any )?(?:uncertainty|unknowns|limitations)"
            + "|be honest|briefly|keep (?:it|the answer) (?:short|brief))");
    private static final Pattern NO_NEW_ACTION = Pattern.compile("(?:do not|don't|dont) "
            + "(?:run|execute|change|edit|inspect|read)(?: or (?:run|execute|change|edit|inspect|read))? "
            + "(?:anything|any files|files|tests|commands)(?: now)?");
    private static final Pattern ACCOUNTING = Pattern.compile("(?:separate|distinguish) "
            + "(?:observed|external) file changes from (?:work|actions) (?:performed|done) by (?:you|talos) "
            + "and (?:from )?proposed (?:fixes|changes|work)");
    private static final String INSPECTION = "(?:(?:workspace|project|repository|repo|file) "
            + "(?:inspection|search|access)|web search)";
    private static final String INSPECTIONS = INSPECTION + "(?: (?:or|and) " + INSPECTION + "){0,3}";
    private static final Pattern DECLINED_INSPECTION = Pattern.compile("(?:no " + INSPECTIONS
            + " (?:is|are) (?:needed|required)|" + INSPECTIONS + " (?:is|are) not (?:needed|required))");

    public record IdentityClause(int start, int end) {}

    /** Only independent identity clauses are selected, using original source offsets. */
    public static List<IdentityClause> standaloneRuntimeIdentityClauses(String request) {
        if (request == null || request.isBlank() || request.length() > MAX_LENGTH) return List.of();
        var lexical = RequestLexicalView.of(request);
        if (lexical.containsMalformedDelimiter(0, request.length())
                || lexical.containsProviderRemovedControl(0, request.length())) return List.of();
        var selected = new ArrayList<IdentityClause>();
        int clauses = 0;
        for (int start = 0; start < request.length();) {
            int end = lexical.sentenceEndAfter(start);
            var span = lexical.trimmedSpan(start, end);
            if (++clauses > 16) return List.of();
            if (lexical.isUnquotedRange(span.start(), span.end()) && lexical.isTopLevel(span.start())) {
                String clause = lexical.substring(span).toLowerCase(Locale.ROOT)
                        .replace('\u2019', '\'').replaceAll("\\s+", " ").replaceAll("[.!?]+$", "");
                if (IDENTITY.matcher(DISCOURSE_PREFIX.matcher(clause).replaceFirst("")).matches()) {
                    selected.add(new IdentityClause(span.start(), span.end()));
                }
            }
            start = end;
        }
        return List.copyOf(selected);
    }

    /** A requested component is selected, without declaring the remaining request covered. */
    public static boolean requestsRuntimeIdentity(String request) {
        return requestsRuntimeIdentity(request, UnaryOperator.identity());
    }

    /** A trusted leading qualifier is projected only after original lexical validation. */
    public static boolean requestsRuntimeIdentity(String request, UnaryOperator<String> leadingQualifierProjection) {
        if (request == null || request.isBlank() || request.length() > MAX_LENGTH) return false;
        var lexical = RequestLexicalView.of(request);
        if (lexical.containsMalformedDelimiter(0, request.length())
                || lexical.containsProviderRemovedControl(0, request.length())) return false;
        boolean found = false;
        int clauses = 0;
        for (int start = 0; start < request.length();) {
            int end = lexical.sentenceEndAfter(start);
            var span = lexical.trimmedSpan(start, end);
            if (++clauses > 16) return false;
            if (lexical.isUnquotedRange(span.start(), span.end()) && lexical.isTopLevel(span.start())) {
                String clause = lexical.substring(span).strip().toLowerCase(Locale.ROOT)
                        .replace('\u2019', '\'').replaceAll("\\s+", " ").replaceAll("[.!?]+$", "");
                String core = leadingQualifierProjection.apply(clause);
                found |= IDENTITY.matcher(DISCOURSE_PREFIX.matcher(core).replaceFirst("")).matches();
            }
            start = end;
        }
        return found;
    }

    public static ConversationEvidenceRequest interpret(String request) {
        String original = request == null ? "" : request;
        ConversationEvidenceRequest normal = new ConversationEvidenceRequest(Source.NORMAL, original);
        if (original.isBlank() || original.length() > MAX_LENGTH) return normal;
        var lexical = RequestLexicalView.of(original);
        if (lexical.containsMalformedDelimiter(0, original.length())
                || lexical.containsProviderRemovedControl(0, original.length())) return normal;
        List<String> identityParts = new ArrayList<>();
        List<String> historyParts = new ArrayList<>();
        StringBuilder positive = new StringBuilder();
        int clauses = 0;
        for (int start = 0; start < original.length();) {
            int end = lexical.sentenceEndAfter(start);
            var span = lexical.trimmedSpan(start, end);
            String raw = lexical.substring(span);
            String clause = raw.strip().toLowerCase(Locale.ROOT).replace('\u2019', '\'')
                    .replaceAll("\\s+", " ").replaceAll("[.!?]+$", "");
            boolean eligible = lexical.isUnquotedRange(span.start(), span.end())
                    && lexical.isTopLevel(span.start());
            if (++clauses > 16) return normal;
            boolean declined = eligible && DECLINED_INSPECTION.matcher(clause).matches();
            if (!declined) positive.append(raw).append(' ');
            if (!clause.isBlank()) {
                String core = DISCOURSE_PREFIX.matcher(clause).replaceFirst("");
                boolean common = eligible && (DISCOURSE.matcher(clause).matches()
                        || ACCURACY.matcher(clause).matches() || NO_NEW_ACTION.matcher(clause).matches()
                        || declined);
                if (!common) {
                    identityParts.add(eligible ? core : raw);
                    if (!(eligible && ACCOUNTING.matcher(clause).matches())) {
                        historyParts.add(eligible ? core : raw);
                    }
                }
            }
            start = end;
        }
        if (original.length() <= 512 && identityParts.size() == 1
                && IDENTITY.matcher(identityParts.getFirst()).matches()) {
            return new ConversationEvidenceRequest(Source.RUNTIME_IDENTITY, positive.toString().strip());
        }
        if (original.length() <= 1024 && historyParts.size() == 1
                && ACTION_HISTORY.matcher(historyParts.getFirst()).matches()) {
            return new ConversationEvidenceRequest(Source.SESSION_ACTIONS, positive.toString().strip());
        }
        return new ConversationEvidenceRequest(Source.NORMAL, positive.toString().strip());
    }
}
