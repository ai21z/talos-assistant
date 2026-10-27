package dev.talos.runtime.intent;

import dev.talos.runtime.task.TaskContract;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Bounded requested-result interpretation. It grants no tool or mutation authority. */
public record RequestedResultInventory(String originalRequest, List<RequestedResult> results,
                                       List<SourceRegion> unresolvedRegions) {
    private static final int MAX_REQUEST_CHARACTERS = 8192;
    private static final int MAX_RESULTS = 16;
    public enum Kind {
        WORKSPACE_LOCATION, DIRECTORY_CONTENTS, DIRECTORY_COUNT, DIRECTORY_EMPTINESS, PROJECT_PURPOSE,
        RETAINED_SAVES, RETAINED_VERIFICATION, RETAINED_UNCHECKED, UNSUPPORTED
    }
    public enum EvidenceBasis { TURN_WORKSPACE, SCOPED_DIRECTORY_OBSERVATION, ADMITTED_PROJECT_SOURCES,
        RETAINED_RUNTIME_RECORDS, NONE }
    public enum Disposition { ACTIVE, CANCELLED, REPLACED, UNSUPPORTED, WITHHELD }
    public record SourceRegion(int start, int end) {
        public SourceRegion {
            if (start < 0 || end < start) throw new IllegalArgumentException("Invalid result source region");
        }
    }
    public record RequestedResult(String id, SourceRegion region, Kind kind, String subject,
                                  String scope, EvidenceBasis evidenceBasis, Disposition disposition,
                                  String replacementId) {
        public RequestedResult {
            disposition = disposition == null ? Disposition.ACTIVE : disposition;
            replacementId = replacementId == null ? "" : replacementId;
        }
        public RequestedResult(String id, SourceRegion region, Kind kind, String subject,
                               String scope, EvidenceBasis evidenceBasis) {
            this(id, region, kind, subject, scope, evidenceBasis,
                    kind == Kind.UNSUPPORTED ? Disposition.UNSUPPORTED : Disposition.ACTIVE, "");
        }
    }

    // Ordinary paraphrases are recognized only as whole results. A foreign subject, another directory or a
    // restriction anywhere in the request still leaves unresolved text, so the request stays unbounded.
    private static final String SCOPE = "(?:(?:this|the|our|current|active)\\s+)*(?:workspace|directory|folder)\\b";
    private static final String OBJECT = "(?:in\\s+(?:it\\b|" + SCOPE + ")|here\\b)";
    private static final Pattern LOCATION = Pattern.compile(
            "\\bwhere\\s+(?:exactly\\s+)?(?:are\\s+we|am\\s+i)(?:\\s+(?:working|located|right\\s+now))?\\b"
                    + "|\\bwhere\\s+(?:exactly\\s+)?is\\s+(?:(?:this|the|our|current|active)\\s+)*"
                    + "(?:workspace|directory|folder)(?:\\s+located)?\\b"
                    + "|\\b(?:(?:what|which)\\s+is\\s+(?:(?:this|the|our|current|active)\\s+)*)?"
                    + "(?:workspace|directory|folder)\\s+(?:location|path)\\b"
                    + "|\\bwhat(?:'s|’s|\\s+is)\\s+the\\s+(?:(?:full|absolute|current)\\s+)?(?:path|location)\\s+"
                    + "(?:of|to|for)\\s+(?:(?:this|the|our|current|active)\\s+)*(?:workspace|directory|folder)\\b"
                    + "|\\b(?:which|what)\\s+(?:workspace|directory|folder)\\s+(?:are\\s+we|am\\s+i)\\s+(?:working\\s+)?in\\b"
                    + "|\\b(?:(?:tell|give|show)\\s+me\\s+)?the\\s+(?:(?:full|absolute|current)\\s+)?(?:path|location)\\s+"
                    + "(?:of|to|for)\\s+(?:(?:this|the|our|current|active)\\s+)*(?:workspace|directory|folder)\\b");
    private static final Pattern CONTENTS = Pattern.compile(
            "\\b(?:is|whether)\\s+(?:(?:this|the|our|current|active)\\s+)*(?:it|workspace|directory|folder)\\s+empty\\b"
                    + "|\\b(?:list|show)\\s+(?:(?:me|the|all|top\\s+level)\\s+)*(?:files|entries|contents|directories)\\b"
                    + "|\\bwhat\\s+(?:(?:files|entries)\\s+are\\s+(?:in|here)|(?:is|does\\s+it)\\s+(?:in|contain))\\b"
                    + "|\\bwhat(?:'s|’s)\\s+" + OBJECT
                    + "|\\bwhat\\s+(?:can|do)\\s+you\\s+see\\s+" + OBJECT
                    + "|\\bis\\s+(?:there\\s+)?(?:anything|any\\s+files?)\\s+" + OBJECT
                    + "|\\bare\\s+there\\s+any\\s+(?:files|entries)\\s+" + OBJECT);
    private static final Pattern PURPOSE = Pattern.compile(
            "\\bwhat\\s+does\\s+(?:this|the|our)\\s+(?:project|workspace)\\s+do\\b"
                    + "|\\bwhat\\s+is\\s+(?:this|the|our)\\s+(?:project|workspace)\\b"
                    + "|\\b(?:project|workspace)\\s+(?:purpose|about)\\b"
                    + "|\\b(?:explain|summarize|describe)\\s+(?:this|the|our)\\s+(?:project|workspace)\\b"
                    // "it" after a workspace noun elsewhere in the request, which compile() requires.
                    + "|\\bwhat\\s+does\\s+it\\s+do\\b|\\bwhat\\s+is\\s+it\\s+(?:for|about)\\b");
    // An inspection restriction is framing: it withholds tools, it does not ask for a result of its own.
    private static final Pattern INSPECTION_RESTRICTION = Pattern.compile(
            "\\b(?:do\\s+not|don['’]?t|never|without)\\s+"
                    + "(?:read(?:ing)?|inspect(?:ing)?|open(?:ing)?|search(?:ing)?|check(?:ing)?|scan(?:ning)?)\\s+"
                    + "(?:(?:any|the|my|our|these|those|all)\\s+)?(?:files?|workspace|folder|directory|anything)\\b");
    // Only grammatical framing and workspace scope may remain after requested results are bound.
    private static final Pattern FRAME = Pattern.compile(
            "\\b(?:can|could|would|will|you|please|tell|me|read|inspect|check|access|look|at|this|the|our|current|active|"
                    + "workspace|directory|folder|here|and|also|then|in|of|for|top|level|now|exactly|just)\\b|[\\s?,.!:;-]+");
    // A greeting is framing only where the request opens with it.
    private static final Pattern GREETING = Pattern.compile("^[\\s,.!]*(?:hey|hi|hello)\\b");
    // Any other word directly before a scope noun names a different folder, such as "the hello folder".
    // After a determiner only a scope adjective may follow, so "the check folder" and "the now folder" name one.
    // A hyphenated compound such as "check-in folder" is a name too.
    private static final Pattern SCOPE_QUALIFIER = Pattern.compile(
            "(?U)(?:\\b(this|the|our|current|active)[\\s-]+)?([\\p{L}\\p{N}_]+)[\\s-]+(?=(?:workspace|directory|folder)\\b)");
    private static final java.util.Set<String> SCOPE_WORDS = java.util.Set.of(
            "this", "the", "our", "current", "active", "workspace", "which", "what",
            "is", "in", "of", "for", "at", "to", "and", "also", "then", "can", "you", "me", "please", "tell", "now", "exactly");
    private static final java.util.Set<String> FRAME_VERBS = java.util.Set.of("read", "inspect", "check", "access", "look");
    private static final java.util.Set<String> SCOPE_ADJECTIVES = java.util.Set.of("current", "active", "workspace");

    public RequestedResultInventory {
        originalRequest = originalRequest == null ? "" : originalRequest;
        results = results == null ? List.of() : List.copyOf(results);
        unresolvedRegions = unresolvedRegions == null ? List.of() : List.copyOf(unresolvedRegions);
        for (RequestedResult result : results) {
            if (result == null || result.region().end() > originalRequest.length())
                throw new IllegalArgumentException("Requested result does not bind its source");
        }
        for (SourceRegion region : unresolvedRegions) {
            if (region.end() > originalRequest.length())
                throw new IllegalArgumentException("Unresolved result does not bind its source");
        }
    }

    public boolean requires(Kind kind) { return results.stream().anyMatch(result -> result.kind() == kind
            && result.disposition() == Disposition.ACTIVE); }
    public boolean retainedWork() {
        return results.stream().anyMatch(result -> isRetainedKind(result.kind()));
    }
    public static boolean isRetainedKind(Kind kind) {
        return kind == Kind.RETAINED_SAVES || kind == Kind.RETAINED_VERIFICATION || kind == Kind.RETAINED_UNCHECKED;
    }
    public boolean boundedWorkspaceQuery() {
        return !results.isEmpty() && unresolvedRegions.isEmpty() && !retainedWork()
                && results.stream().noneMatch(result -> result.kind() == Kind.UNSUPPORTED
                    || result.disposition() == Disposition.UNSUPPORTED);
    }

    /** Compiled before acquisition, with offsets retained against the original immutable request. */
    public static RequestedResultInventory compile(TaskContract contract) {
        String request = contract == null ? "" : contract.originalUserRequest();
        if (request == null) request = "";
        if (request.length() > MAX_REQUEST_CHARACTERS
                || contract == null || contract.mutationAllowed() || contract.mutationRequested()
                || !contract.expectedTargets().isEmpty() || !contract.sourceEvidenceTargets().isEmpty()) {
            return unestablished(request);
        }
        RequestLexicalView view = RequestLexicalView.of(request);
        String masked = view.maskedLower();
        // A bare pronoun or an unbound location question cannot invent the active-workspace subject.
        if (!Pattern.compile("\\b(?:workspace|directory|folder)\\b").matcher(masked).find())
            return unestablished(request);
        List<RequestedResult> results = new ArrayList<>();
        char[] remaining = masked.toCharArray();
        var greeting = GREETING.matcher(masked);
        if (greeting.find()) java.util.Arrays.fill(remaining, greeting.start(), greeting.end(), ' ');
        var restriction = INSPECTION_RESTRICTION.matcher(masked);
        while (restriction.find()) java.util.Arrays.fill(remaining, restriction.start(), restriction.end(), ' ');
        String scoped = new String(remaining);
        collect(results, remaining, masked, LOCATION, Kind.WORKSPACE_LOCATION, EvidenceBasis.TURN_WORKSPACE);
        collect(results, remaining, masked, CONTENTS, Kind.DIRECTORY_CONTENTS, EvidenceBasis.SCOPED_DIRECTORY_OBSERVATION);
        collect(results, remaining, masked, PURPOSE, Kind.PROJECT_PURPOSE, EvidenceBasis.ADMITTED_PROJECT_SOURCES);
        if (results.size() > MAX_RESULTS) return unestablished(request);
        results.sort(java.util.Comparator.comparingInt(result -> result.region().start()));
        List<RequestedResult> stable = new ArrayList<>();
        for (RequestedResult result : results) stable.add(new RequestedResult("result-" + (stable.size() + 1),
                result.region(), result.kind(), result.subject(), result.scope(), result.evidenceBasis()));
        boolean unresolved = !FRAME.matcher(new String(remaining)).replaceAll("").isBlank()
                || masked.length() != request.length() || view.containsMalformedDelimiter(0, request.length())
                || view.containsProviderRemovedControl(0, request.length()) || namesAnotherFolder(scoped);
        // Quoted material has not been interpreted as a requested result by this migration.
        for (int i = 0; i < request.length(); i++) if (view.isQuoted(i)) unresolved = true;
        return new RequestedResultInventory(request, stable,
                unresolved || stable.isEmpty() ? List.of(new SourceRegion(0, request.length())) : List.of());
    }

    private static void collect(List<RequestedResult> results, char[] remaining, String source,
                                Pattern pattern, Kind kind, EvidenceBasis basis) {
        var matcher = pattern.matcher(source);
        while (matcher.find()) {
            boolean owned = false;
            for (RequestedResult existing : results) {
                if (matcher.start() < existing.region().end() && matcher.end() > existing.region().start()) {
                    owned = true;
                    break;
                }
            }
            if (owned) continue;
            results.add(new RequestedResult("", new SourceRegion(matcher.start(), matcher.end()), kind,
                    "active workspace", kind == Kind.DIRECTORY_CONTENTS ? "top-level, observation time" : "current turn", basis));
            java.util.Arrays.fill(remaining, matcher.start(), matcher.end(), ' ');
        }
    }

    private static boolean namesAnotherFolder(String text) {
        var qualifier = SCOPE_QUALIFIER.matcher(text);
        while (qualifier.find()) {
            String word = qualifier.group(2);
            int start = qualifier.start(2);
            boolean compound = start > 1 && text.charAt(start - 1) == '-'
                    && Character.isLetterOrDigit(text.charAt(start - 2));
            if (qualifier.group(1) != null || compound ? !SCOPE_ADJECTIVES.contains(word)
                    : !FRAME_VERBS.contains(word) && !SCOPE_WORDS.contains(word)) return true;
        }
        return false;
    }

    private static RequestedResultInventory unestablished(String request) {
        return new RequestedResultInventory(request, List.of(),
                request.isEmpty() ? List.of() : List.of(new SourceRegion(0, request.length())));
    }
}
