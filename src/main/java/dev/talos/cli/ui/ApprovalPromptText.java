package dev.talos.cli.ui;

/**
 * Byte-frozen approval-prompt chrome strings (T765).
 *
 * <p>These strings are load-bearing evidence-chain contracts, not cosmetics:
 * the true-PTY manual-audit validator requires them verbatim in real-terminal
 * transcripts, the talosbench live-prompt matrix forbids them in redirected
 * transcripts (proof that no approval prompt leaked into a deny path), and the
 * scripted e2e harness publishes them into audit artifacts. Production, the
 * harness, and the validator must therefore agree on the exact bytes.
 *
 * <p>Two forms exist deliberately:
 * <ul>
 *   <li><b>Core form</b> ({@link #SESSION_PROMPT}, {@link #ONCE_PROMPT}) - the
 *       unindented prompt text that transcript validators substring-match and
 *       the scripted harness records in approval events.</li>
 *   <li><b>Line form</b> ({@link #SESSION_PROMPT_LINE}, {@link #ONCE_PROMPT_LINE})
 *       - the exact string production passes to the terminal line reader:
 *       two-space indent matching the approval window rail, one trailing space
 *       before the cursor. Derived from the core form so the two can never
 *       drift apart.</li>
 * </ul>
 *
 * <p>Any change here MUST keep {@code ApprovalPromptContractTest} (e2e) and the
 * characterization pins in {@code CliApprovalGateTest} in lockstep, and is a
 * PTY-revalidation-worthy event: external string-match surfaces
 * (talosbench-cases.json, recorded manual-audit packets) reference these bytes.
 */
public final class ApprovalPromptText {

    /** Tri-state session prompt (approve once / approve for session / deny). */
    public static final String SESSION_PROMPT = "Allow? [y=yes, a=yes for session, N=no]";

    /** One-turn-only prompt; deliberately offers no session-remember choice. */
    public static final String ONCE_PROMPT = "Allow? [y=yes, N=no]";

    /** Exact line passed to the line reader for {@link #SESSION_PROMPT}. */
    public static final String SESSION_PROMPT_LINE = "  " + SESSION_PROMPT + " ";

    /** Exact line passed to the line reader for {@link #ONCE_PROMPT}. */
    public static final String ONCE_PROMPT_LINE = "  " + ONCE_PROMPT + " ";

    /**
     * Common prefix of both prompt forms. Matched by the scripted process
     * driver, the approval smoke harness, and talosbench forbidden-substring
     * banks, which must not depend on which prompt variant rendered.
     */
    public static final String PROMPT_PREFIX = "Allow? [y=yes";

    /** Title rendered into the approval window border. */
    public static final String WINDOW_TITLE = "approval required";

    private ApprovalPromptText() {
    }
}
