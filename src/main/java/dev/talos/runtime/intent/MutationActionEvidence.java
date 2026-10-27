package dev.talos.runtime.intent;

import java.util.HashMap;
import java.util.Map;

/** Fallback matches are checked against their exact request action occurrence. */
public final class MutationActionEvidence {
    private static final int MAX_POSTFIX_CHARACTERS = 4096;
    private final RequestLexicalView view;
    private final String foldedRequest;
    private final Map<Integer, CanonicalMutationClauseMatcher.Action> actions;

    private MutationActionEvidence(String request) {
        view = RequestLexicalView.of(request);
        foldedRequest = RequestLexicalView.foldCasePreservingLength(view.request());
        Map<Integer, CanonicalMutationClauseMatcher.Action> indexed = new HashMap<>();
        for (var action : CanonicalMutationClauseMatcher.actions(view, 0, view.request().length())) {
            indexed.put(action.start(), action);
        }
        actions = Map.copyOf(indexed);
    }

    public static MutationActionEvidence fromRequest(String request) {
        return new MutationActionEvidence(request);
    }

    public String foldedRequest() {
        return foldedRequest;
    }

    public boolean permitsMarker(int start, int end) {
        if (end < 0 || end > view.request().length()) return false;
        boolean completeToken = end == view.request().length()
                || Character.isWhitespace(view.request().charAt(end))
                || view.isSentenceBoundaryAt(end);
        return completeToken && permits(start, end);
    }

    /** The typed content-save request frame does not widen legacy fallback recognition. */
    public boolean permitsLegacyFallback(int start, int end) {
        var action = actions.get(start);
        return action != null && !action.firstPersonDesireFrame() && permits(start, end);
    }

    public boolean permitsLegacyFallbackMarker(int start, int end) {
        var action = actions.get(start);
        return action != null && !action.firstPersonDesireFrame() && permitsMarker(start, end);
    }

    public boolean permits(int start, int end) {
        var action = actions.get(start);
        if (action == null || end < action.end() || end > view.request().length()) return false;
        if (action.scopeKind() != CanonicalMutationClauseMatcher.ScopeKind.NONE) return false;
        if (action.nonImperativeUse()
                && !CanonicalMutationClauseMatcher.directToolRequestPrefix(view, start)) return false;
        int sentenceStart = view.sentenceStartBefore(0, start);
        int sentenceEnd = view.sentenceEndAfter(action.end());
        if (end > sentenceEnd || sentenceEnd - end > MAX_POSTFIX_CHARACTERS) return false;
        if (view.containsProviderRemovedControl(sentenceStart, sentenceEnd)
                || view.containsLoneCarriageReturn(sentenceStart, sentenceEnd)
                || view.containsMalformedDelimiter(sentenceStart, sentenceEnd)) return false;
        var postfix = MutationPostfixOwnership.analyze(view, end, sentenceEnd);
        if (postfix.kind() == MutationPostfixOwnership.Kind.NONE
                && !postfix.closedBehavior()) return true;
        var targets = MutationPostfixOwnership.bindDirectTargets(view, action, end, sentenceEnd);
        if (targets == null) return false;
        var owned = MutationPostfixOwnership.analyze(view, targets.outputEnd(), sentenceEnd);
        return owned.closedBehavior() && owned.kind() == MutationPostfixOwnership.Kind.NONE;
    }
}
