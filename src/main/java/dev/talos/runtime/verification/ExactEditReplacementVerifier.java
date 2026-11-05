package dev.talos.runtime.verification;

import dev.talos.core.util.TextReplacement;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.toolcall.ToolMutationEvidence;
import dev.talos.tools.MutationPreimage;
import dev.talos.tools.ToolAliasPolicy;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Verifies exact edit replacement evidence as a non-web static fallback. The final file is the observation point of the
 * last mutation of a target. Each later mutation of the same target is a further point, its producer preimage, which
 * holds the file as that mutation found it (P19, D13 option A). A later edit that refines an earlier one and one that
 * undoes it cannot be told apart here, so an edit that a recorded later change altered is stated and never counted
 * toward a pass. Whether the final file meets the request belongs to the request-derived expectations.
 */
final class ExactEditReplacementVerifier {

    private ExactEditReplacementVerifier() {}

    /** Where one exact edit stands at verification. */
    enum Standing {
        /** The edit's checks hold in the final file, whether or not a later mutation of the target follows. */
        KEPT,
        /**
         * The checks held after the edit and fail at a later point, and the recorded change just before that point is
         * shown to have altered the edit's result.
         */
        REPLACED_LATER,
        /** The checks fail in the final file, and a later point or the change before it cannot be observed. */
        UNOBSERVED,
        /**
         * The checks fail at a point that no recorded change explains: right after the edit, or after a recorded
         * change whose own result still held the edit. The target may also be unreadable after apply.
         */
        NOT_HELD
    }

    private enum Check {
        HOLDS,
        NEW_TEXT_MISSING,
        OLD_TEXT_REMAINS
    }

    private record Decision(Standing standing, Check failed) {}

    static Result verify(Path root, List<ToolCallLoop.ToolOutcome> outcomes) {
        List<String> facts = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        List<Standing> standings = new ArrayList<>();
        if (outcomes == null || outcomes.isEmpty()) {
            return new Result(false, false, false, facts, problems, standings);
        }

        boolean verifiedAny = false;
        boolean hasProblem = false;
        for (int position = 0; position < outcomes.size(); position++) {
            ToolCallLoop.ToolOutcome outcome = outcomes.get(position);
            if (!hasExactEditEvidence(outcome)) {
                continue;
            }
            verifiedAny = true;
            String pathHint = normalizePath(outcome.pathHint());
            Path target = resolveWorkspaceFile(root, pathHint);
            if (target == null || !StaticWebFileAccess.isRegularFile(root, target)) {
                problems.add(pathHint + ": exact edit replacement target is not readable after apply.");
                hasProblem = true;
                standings.add(Standing.NOT_HELD);
                continue;
            }
            String content;
            try {
                content = StaticWebFileAccess.readString(root, target);
            } catch (Exception e) {
                problems.add(pathHint + ": exact edit replacement target could not be read after apply ("
                        + e.getMessage() + ")");
                hasProblem = true;
                standings.add(Standing.NOT_HELD);
                continue;
            }

            ToolMutationEvidence evidence = outcome.mutationEvidence();
            Check inFinalFile = check(content, evidence);
            if (inFinalFile == Check.HOLDS) {
                facts.add(pathHint + ": exact edit replacement observed in post-apply file.");
                standings.add(Standing.KEPT);
                continue;
            }
            Decision decision = afterLaterChanges(root, pathHint, outcomes, position, evidence, inFinalFile);
            standings.add(decision.standing());
            switch (decision.standing()) {
                case REPLACED_LATER -> facts.add(pathHint + ": exact edit replacement observed before a later "
                        + "change of the same file altered it.");
                case UNOBSERVED -> facts.add(pathHint + ": exact edit replacement could not be confirmed after a "
                        + "later change of the same file.");
                default -> {
                    problems.add(pathHint + (decision.failed() == Check.NEW_TEXT_MISSING
                            ? ": exact edit replacement text was not observed after apply."
                            : ": exact edit replacement old text remained after apply."));
                    hasProblem = true;
                }
            }
        }

        return new Result(
                verifiedAny,
                verifiedAny
                        && allSuccessfulMutationsHaveExactEditEvidence(outcomes)
                        && !leavesEditUnasserted(standings),
                hasProblem,
                facts,
                problems,
                standings);
    }

    /**
     * The standing of an edit whose checks fail in the final file. The later mutations of the target are visited in
     * order, and the first point where the checks fail decides. At the first point, right after the edit, no recorded
     * change explains the failure. At a later point the recorded change just before it is examined. Its result is
     * derived from its own preimage, for an exact edit with the edit tool's unique match and for a full write from its
     * evidence. Only a derived result that no longer holds the edit makes it REPLACED_LATER. A derived result that still
     * holds it shows an unrecorded change, so the edit is NOT_HELD. A point without a preimage of the target, or a change
     * whose result cannot be derived, leaves the edit UNOBSERVED.
     */
    private static Decision afterLaterChanges(
            Path root,
            String pathHint,
            List<ToolCallLoop.ToolOutcome> outcomes,
            int position,
            ToolMutationEvidence evidence,
            Check inFinalFile
    ) {
        ToolCallLoop.ToolOutcome previous = null;
        String previousContent = null;
        for (int index = MutationTouches.nextTouch(root, pathHint, outcomes, position);
             index >= 0;
             index = MutationTouches.nextTouch(root, pathHint, outcomes, index)) {
            ToolCallLoop.ToolOutcome later = outcomes.get(index);
            MutationPreimage preimage = later.mutationPreimage();
            if (!preimage.available() || !MutationTouches.sameTarget(root, pathHint, preimage.target())) {
                return new Decision(Standing.UNOBSERVED, inFinalFile);
            }
            Check atPoint = check(preimage.text(), evidence);
            if (atPoint != Check.HOLDS) {
                return previous == null
                        ? new Decision(Standing.NOT_HELD, atPoint)
                        : attribute(previous, previousContent, evidence, atPoint);
            }
            previous = later;
            previousContent = preimage.text();
        }
        return previous == null
                ? new Decision(Standing.NOT_HELD, inFinalFile)
                : attribute(previous, previousContent, evidence, inFinalFile);
    }

    private static Decision attribute(
            ToolCallLoop.ToolOutcome change,
            String before,
            ToolMutationEvidence edit,
            Check failed
    ) {
        String after = derivedResult(change, before);
        if (after == null) return new Decision(Standing.UNOBSERVED, failed);
        return check(after, edit) == Check.HOLDS
                ? new Decision(Standing.NOT_HELD, failed)
                : new Decision(Standing.REPLACED_LATER, failed);
    }

    /** The content a change produced from the content it found, when its own evidence determines it, else null. */
    private static String derivedResult(ToolCallLoop.ToolOutcome change, String before) {
        ToolMutationEvidence evidence = change.mutationEvidence();
        if (evidence == null || before == null) return null;
        if (hasExactEditEvidence(change)) {
            TextReplacement.Match match =
                    TextReplacement.findUniqueMatch(before, evidence.oldString(), evidence.newString());
            if (match.count() != 1) return null;
            return before.substring(0, match.startRaw()) + match.replacement() + before.substring(match.endRaw());
        }
        if ("write_file".equals(ToolAliasPolicy.localCanonicalName(change.toolName()))
                && evidence.fullWriteReplacement()) {
            return evidence.newString();
        }
        return null;
    }

    /**
     * The verifier's outcome. A replaced or unobserved edit makes coversAllSuccessfulMutations false, so it never
     * contributes to the exact edit checks pass. The standings follow the order of the exact edits.
     */
    record Result(
            boolean verifiedAny,
            boolean coversAllSuccessfulMutations,
            boolean hasProblem,
            List<String> facts,
            List<String> problems,
            List<Standing> standings
    ) {
        Result {
            facts = facts == null ? List.of() : List.copyOf(facts);
            problems = problems == null ? List.of() : List.copyOf(problems);
            standings = standings == null ? List.of() : List.copyOf(standings);
        }

        Result(
                boolean verifiedAny,
                boolean coversAllSuccessfulMutations,
                boolean hasProblem,
                List<String> facts,
                List<String> problems
        ) {
            this(verifiedAny, coversAllSuccessfulMutations, hasProblem, facts, problems, List.of());
        }

        /** An exact edit was altered by a later change of its target, or could not be confirmed after it. */
        boolean leftEditUnasserted() {
            return leavesEditUnasserted(standings);
        }
    }

    private static boolean leavesEditUnasserted(List<Standing> standings) {
        return standings.contains(Standing.REPLACED_LATER) || standings.contains(Standing.UNOBSERVED);
    }

    private static Check check(String content, ToolMutationEvidence evidence) {
        String oldString = evidence.oldString();
        String newString = evidence.newString();
        if (!newString.isEmpty() && !ReplacementTextPresence.replacementTextObserved(content, newString)) {
            return Check.NEW_TEXT_MISSING;
        }
        if (ReplacementTextPresence.oldTextRemainsOutsideReplacement(content, oldString, newString)) {
            return Check.OLD_TEXT_REMAINS;
        }
        return Check.HOLDS;
    }

    private static boolean allSuccessfulMutationsHaveExactEditEvidence(List<ToolCallLoop.ToolOutcome> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) return false;
        for (ToolCallLoop.ToolOutcome outcome : outcomes) {
            if (outcome == null || !outcome.success() || !outcome.mutating()) continue;
            if (!hasExactEditEvidence(outcome)) return false;
        }
        return true;
    }

    private static boolean hasExactEditEvidence(ToolCallLoop.ToolOutcome outcome) {
        return outcome != null
                && outcome.success()
                && "edit_file".equals(ToolAliasPolicy.localCanonicalName(outcome.toolName()))
                && outcome.mutationEvidence() != null
                && outcome.mutationEvidence().exactEditReplacement();
    }

    private static Path resolveWorkspaceFile(Path root, String path) {
        if (root == null) return null;
        try {
            Path resolved = root.resolve(normalizePath(path)).normalize();
            return resolved.startsWith(root) ? resolved : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private static String normalizePath(String path) {
        if (path == null) return "";
        String normalized = path.replace('\\', '/');
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.startsWith("./") && normalized.length() > 2) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }
}
