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

    private static final Pattern LOCATION = Pattern.compile(
            "\\bwhere\\s+(?:exactly\\s+)?(?:are\\s+we|is\\s+(?:(?:this|the|our|current|active)\\s+)*(?:workspace|directory|folder))\\b"
                    + "|\\b(?:(?:what|which)\\s+is\\s+(?:(?:this|the|our|current|active)\\s+)*)?"
                    + "(?:workspace|directory|folder)\\s+(?:location|path)\\b");
    private static final Pattern CONTENTS = Pattern.compile(
            "\\b(?:is|whether)\\s+(?:(?:this|the|our|current|active)\\s+)*(?:it|workspace|directory|folder)\\s+empty\\b"
                    + "|\\b(?:list|show)\\s+(?:(?:me|the|all|top\\s+level)\\s+)*(?:files|entries|contents|directories)\\b"
                    + "|\\bwhat\\s+(?:(?:files|entries)\\s+are\\s+(?:in|here)|(?:is|does\\s+it)\\s+(?:in|contain))\\b");
    private static final Pattern PURPOSE = Pattern.compile(
            "\\bwhat\\s+does\\s+(?:this|the|our)\\s+(?:project|workspace)\\s+do\\b"
                    + "|\\bwhat\\s+is\\s+(?:this|the|our)\\s+(?:project|workspace)\\b"
                    + "|\\b(?:project|workspace)\\s+(?:purpose|about)\\b"
                    + "|\\b(?:explain|summarize|describe)\\s+(?:this|the|our)\\s+(?:project|workspace)\\b");
    // Only grammatical framing and workspace scope may remain after requested results are bound.
    private static final Pattern FRAME = Pattern.compile(
            "\\b(?:can|could|would|will|you|please|tell|me|read|inspect|check|access|look|at|this|the|our|current|active|"
                    + "workspace|directory|folder|here|and|also|then|in|of|for|top|level|now|exactly)\\b|[\\s?,.!:;-]+");

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
                || view.containsProviderRemovedControl(0, request.length());
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

    private static RequestedResultInventory unestablished(String request) {
        return new RequestedResultInventory(request, List.of(),
                request.isEmpty() ? List.of() : List.of(new SourceRegion(0, request.length())));
    }
}
