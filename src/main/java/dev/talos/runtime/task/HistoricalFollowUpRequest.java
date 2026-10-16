package dev.talos.runtime.task;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Whole-request eligibility is shared by classification and bounded historical renderers. */
public final class HistoricalFollowUpRequest {
    private static final int MAX_REQUEST_LENGTH = 1024;
    private static final String PREFIX =
            "(?:please )?(?:(?:can|could|would|will) you )?(?:please )?";
    private static final String SCOPE =
            "(?: (?:now|yet|so far|(?:in|during|from) (?:this|the current) "
                    + "(?:focused )?(?:audit|session)))?";
    private static final String SUMMARY =
            "(?:(?:(?:summari[sz]e|tell me|list|describe|explain) )?(?:"
                    + "what (?:changed|was changed|did you (?:change|do))"
                    + "|(?:what|which) files (?:changed|were changed|were modified|did you (?:change|modify))"
                    + "|files (?:changed|modified))"
                    + "|(?:(?:give|show|provide)(?: me)? )?(?:a |the )?summary of (?:the )?changes"
                    + "|state (?:any )?uncertainty (?:you have )?about files (?:changed|modified))";
    private static final String PRIOR_OBJECT =
            "(?:it|this|that|(?:the |these |those )?(?:change|changes|edit|edits|fix|fixes|repair|repairs|"
                    + "update|updates|task|work|files))";
    private static final String STATUS =
            "(?:(?:did you (?:make|apply|do|finish|complete|update|change|edit|fix|repair|write|create|save)"
                    + "|have you (?:made|applied|done|finished|completed|updated|changed|edited|written|created|saved))"
                    + "(?: " + PRIOR_OBJECT + ")?"
                    + "|did " + PRIOR_OBJECT + " (?:work|apply|finish|complete)"
                    + "|(?:is|are) " + PRIOR_OBJECT + " (?:done|finished|complete|completed|working|applied)"
                    + "|what have you (?:made|done|changed|updated|edited|written|created)"
                    + "|why did nothing (?:change|update|happen|apply))";
    private static final String VERIFICATION =
            "(?:is (?:it|this|that) verified"
                    + "|what(?:, if anything,)? (?:is|remains) (?:still )?unverified"
                    + "|is anything (?:still )?unverified"
                    + "|(?:what is (?:the )?|show (?:me )?(?:the )?)?(?:static )?verification status)";
    private static final String HISTORY =
            PREFIX + "(?:" + SUMMARY + "|" + STATUS + "|" + VERIFICATION + ")" + SCOPE;
    private static final String QUALIFIER =
            "(?:please )?(?:(?:do not|don't) (?:read|inspect) (?:protected )?files"
                    + "|without (?:reading|inspecting) (?:protected )?files"
                    + "|(?:do not|don't) claim unverified facts"
                    + "|(?:include|state) (?:any )?(?:uncertainty|unknowns|confidence)"
                    + "|be honest|briefly|short and evidence-based)";
    private static final String SEPARATOR = "(?:[.!?;]+\\s*|,?\\s+and\\s+|,\\s+)";
    private static final Pattern PURE_HISTORY = Pattern.compile(
            "(?:" + QUALIFIER + SEPARATOR + ")*" + HISTORY
                    + "(?:" + SEPARATOR + QUALIFIER + ")*[.!?]*");
    private static final String VERIFICATION_REQUEST = PREFIX + VERIFICATION + SCOPE;
    private static final Pattern VERIFICATION_ONLY = Pattern.compile(
            "(?:" + QUALIFIER + SEPARATOR + ")*" + VERIFICATION_REQUEST
                    + "(?:" + SEPARATOR + "(?:" + VERIFICATION_REQUEST + "|" + QUALIFIER + "))*[.!?]*");

    private static final String HISTORY_SCOPE =
            "(?: (?:(?:earlier|previously|already)(?: (?:in|during|from) (?:this|the current) "
                    + "(?:session|audit))?|(?:in|during|from) (?:this|the current) (?:session|audit)))?";
    private static final String PRESENTATION =
            "(?:please )?(?:be honest|briefly|short and evidence-based"
                    + "|answer yes or no(?: and (?:in )?one sentence)?"
                    + "|(?:do not|don't) (?:read|inspect) (?:protected )?files"
                    + "|without (?:reading|inspecting) (?:protected )?files"
                    + "|(?:do not|don't) claim unverified facts)";
    private static final String EVIDENCE_PREFIX =
            "(?:based (?:only )?on (?:verified(?: file)?|runtime) evidence "
                    + "(?:from|in) (?:this|the current) (?:session|audit), )?";
    private static final Pattern SESSION_UNCERTAINTY = singleRequest(
            PREFIX + "(?:what are you (?:unsure|uncertain|not sure) about"
                    + "|what (?:uncertainty|unknowns) (?:remain|remains))"
                    + " (?:from|in|during) (?:this|the current) (?:session|audit)");
    private static final Pattern STATIC_DIAGNOSTIC = singleRequest(
            PREFIX + "based (?:only )?on (?:verified(?: file)? evidence from )?"
                    + "(?:the )?previous (?:answer|response|diagnostic), "
                    + "(?:list|summari[sz]e|explain|describe) (?:the )?"
                    + "(?:blockers|issues|problems|findings)"
                    + "(?: that prevent the (?:button|page) from working)?");
    private static final String DOCUMENT_FORMAT =
            "(?:pdf|docx|word)(?: (?:file|files|document|documents))?";
    private static final Pattern DOCUMENT_STATUS = singleRequest(
            PREFIX + "(?:did (?:you|talos) create|have you created|has talos created) "
                    + "(?:any |a |the )?valid (?<firstFormat>" + DOCUMENT_FORMAT + ")"
                    + "(?: (?:or|and) (?<secondFormat>" + DOCUMENT_FORMAT + "))?" + HISTORY_SCOPE);
    private static final Pattern NEGATIVE_PRIOR_STATUS = singleRequest(
            PREFIX + "why did you not (?:make|apply|do|update|change|edit|write|create|save) "
                    + PRIOR_OBJECT + SCOPE);

    private HistoricalFollowUpRequest() {}

    /** Unknown clauses retain normal execution. This predicate never changes task authority. */
    public static boolean isPureHistoryRequest(String request) {
        return matches(PURE_HISTORY, request);
    }

    public static boolean isPriorStatusRequest(String request) {
        return isPureHistoryRequest(request) || matches(NEGATIVE_PRIOR_STATUS, request);
    }

    /** A live pending referent may fill an object slot, but never discard another clause. */
    public static boolean isPriorStatusRequest(String request, Set<String> explicitReferents) {
        if (request == null || request.length() > MAX_REQUEST_LENGTH || explicitReferents == null
                || explicitReferents.isEmpty() || explicitReferents.size() > 32) return false;
        if (explicitReferents.stream().anyMatch(referent -> referent == null || referent.isBlank()
                || referent.length() > 128 || !referent.matches("[\\p{L}\\p{N} -]+"))) return false;
        String projected = normalizedRequest(request);
        boolean referenced = false;
        for (String referent : explicitReferents.stream().sorted(
                java.util.Comparator.comparingInt(String::length).reversed()).toList()) {
            String normalized = normalizedRequest(referent);
            var occurrence = Pattern.compile("(?<![\\p{L}\\p{N}_./\\\\])" + Pattern.quote(normalized)
                    + "(?![\\p{L}\\p{N}_./\\\\])").matcher(projected);
            if (occurrence.find()) {
                referenced = true;
                projected = occurrence.replaceAll("it");
            }
        }
        return referenced && isPriorStatusRequest(projected);
    }

    /** The verification renderer reports both status and unresolved verifier findings. */
    public static boolean isVerificationRequest(String request) {
        return matches(VERIFICATION_ONLY, request);
    }

    public static boolean isSessionUncertaintyRequest(String request) {
        return matches(SESSION_UNCERTAINTY, request);
    }

    public enum SessionAction { READ, MUTATE, NONE }

    public static boolean isSessionEvidenceRequest(String request, Set<String> targets) {
        return sessionEvidenceAction(request, targets) != SessionAction.NONE;
    }

    /** One action may cover explicit target tokens, never additional prose or a second action. */
    public static SessionAction sessionEvidenceAction(String request, Set<String> targets) {
        if (request == null || request.length() > MAX_REQUEST_LENGTH
                || targets == null || targets.isEmpty() || targets.size() > 16) return SessionAction.NONE;
        if (targets.stream().anyMatch(target -> target == null || target.isBlank()
                || target.length() > 256 || target.chars().anyMatch(Character::isWhitespace))) {
            return SessionAction.NONE;
        }
        String token = targets.stream().map(target -> Pattern.quote(target.toLowerCase(Locale.ROOT)))
                .sorted().collect(Collectors.joining("|", "(?:", ")"));
        String quoted = "(?:" + token + "|`" + token + "`|'" + token + "'|\"" + token + "\")";
        String targetList = " (?:the file )?" + quoted + "(?:(?:, |,? and )" + quoted + ")*" + HISTORY_SCOPE;
        String readAction = "(?:did (?:you|talos) (?:read|open|inspect)"
                + "|(?:have you|has talos) (?:read|opened|inspected))";
        String mutationAction = "(?:did (?:you|talos) (?:write|edit|change|modify|update)"
                + "|(?:have you|has talos) (?:written|edited|changed|modified|updated))";
        if (matches(singleRequest(EVIDENCE_PREFIX + PREFIX + readAction + targetList), request)) {
            return SessionAction.READ;
        }
        if (matches(singleRequest(EVIDENCE_PREFIX + PREFIX + mutationAction + targetList), request)) {
            return SessionAction.MUTATE;
        }
        return SessionAction.NONE;
    }

    public static boolean isStaticDiagnosticRequest(String request) {
        return matches(STATIC_DIAGNOSTIC, request);
    }

    public static boolean isDocumentStatusRequest(String request) {
        return !documentStatusFormats(request).isEmpty();
    }

    public static Set<String> documentStatusFormats(String request) {
        var matcher = DOCUMENT_STATUS.matcher(normalizedRequest(request));
        if (!matcher.matches()) return Set.of();
        var formats = new LinkedHashSet<String>();
        for (String group : new String[]{"firstFormat", "secondFormat"}) {
            String format = matcher.group(group);
            if (format != null) formats.add(format.startsWith("pdf") ? "pdf" : "docx");
        }
        return Set.copyOf(formats);
    }

    private static Pattern singleRequest(String body) {
        return Pattern.compile("(?:" + PRESENTATION + SEPARATOR + ")*" + body
                + "(?:" + SEPARATOR + PRESENTATION + ")*[.!?]*");
    }

    private static boolean matches(Pattern pattern, String request) {
        return pattern.matcher(normalizedRequest(request)).matches();
    }

    private static String normalizedRequest(String request) {
        if (request == null || request.length() > MAX_REQUEST_LENGTH) return "";
        return request.strip().toLowerCase(Locale.ROOT)
                .replace('\u2019', '\'').replaceAll("\\s+", " ");
    }
}
