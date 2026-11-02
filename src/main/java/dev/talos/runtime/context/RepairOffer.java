package dev.talos.runtime.context;

import java.util.List;

/**
 * The runtime-rendered offer that lets a plain affirmation resume repair of recorded verifier findings
 * (MICA invariant I1, decision D2, part D-d).
 *
 * <p>Every runtime static-verification failure header carries this offer. A context is bound to it only when the
 * delivered answer contains one of those headers followed by the offer, so model text cannot create an offer the
 * runtime did not already make. The offer resumes repair of the recorded targets, and each change still goes through
 * the approval settings.
 */
public final class RepairOffer {
    public static final String LINE =
            "Reply yes to let Talos try to repair the unresolved problems. File changes still follow your approval settings.";

    /** The runtime-owned failure headers that carry the offer. */
    public static final List<String> FAILURE_HEADERS = List.of(
            "[Task incomplete: Static verification failed - ",
            "[Partial verification: static checks failed - ");

    private RepairOffer() {}

    /** True when the delivered answer carries a runtime failure header followed by the offer. */
    public static boolean renderedIn(String answer) {
        if (answer == null || answer.isBlank()) return false;
        int offer = answer.indexOf(LINE);
        if (offer < 0) return false;
        for (String header : FAILURE_HEADERS) {
            int at = answer.indexOf(header);
            if (at >= 0 && at < offer) return true;
        }
        return false;
    }
}
