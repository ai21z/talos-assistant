package dev.talos.runtime.context;

import java.util.List;

/**
 * The runtime offer that ends a read-only answer to an indirect change request (MICA invariant I1, decision D6).
 *
 * <p>The offer names the files of the active task. A plain acceptance on the next turn applies the change the user
 * described to exactly those files, and each change still follows the approval settings. The line is appended by the
 * runtime as the last line of the answer, so a context is bound only when the delivered answer ends with the line for
 * the same files.
 */
public final class ChangeOffer {
    private static final String PREFIX = "Reply yes to let Talos apply this change to ";
    private static final String SUFFIX = ". File changes still follow your approval settings.";

    private ChangeOffer() {}

    /** The offer line for the ordered targets. */
    public static String line(List<String> targets) {
        if (targets == null || targets.isEmpty()) return "";
        String names;
        if (targets.size() == 1) {
            names = targets.getFirst();
        } else {
            names = String.join(", ", targets.subList(0, targets.size() - 1)) + " and " + targets.getLast();
        }
        return PREFIX + names + SUFFIX;
    }

    /** The answer with the offer as its final paragraph. */
    public static String appendTo(String answer, List<String> targets) {
        String line = line(targets);
        if (line.isEmpty()) return answer;
        String body = answer == null ? "" : answer.stripTrailing();
        return body.isEmpty() ? line : body + "\n\n" + line;
    }

    /** True when the delivered answer ends with the offer line for exactly these targets. */
    public static boolean renderedAtEnd(String answer, List<String> targets) {
        String line = line(targets);
        if (line.isEmpty() || answer == null) return false;
        String delivered = answer.stripTrailing();
        return delivered.equals(line) || delivered.endsWith("\n\n" + line);
    }
}
