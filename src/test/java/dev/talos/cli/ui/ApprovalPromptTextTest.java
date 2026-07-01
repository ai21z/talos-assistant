package dev.talos.cli.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Byte-identity pins for {@link ApprovalPromptText} (T765).
 *
 * <p>Every assertion compares against a string literal typed here - never
 * against another constant - so that an accidental edit to the constants
 * class cannot silently re-pin itself. These bytes are matched by the PTY
 * manual-audit validator, talosbench forbidden-substring banks, and recorded
 * audit packets; changing them invalidates external evidence.
 */
class ApprovalPromptTextTest {

    @Test
    void sessionPromptCoreFormIsByteFrozen() {
        assertEquals("Allow? [y=yes, a=yes for session, N=no]",
                ApprovalPromptText.SESSION_PROMPT);
    }

    @Test
    void oncePromptCoreFormIsByteFrozen() {
        assertEquals("Allow? [y=yes, N=no]", ApprovalPromptText.ONCE_PROMPT);
    }

    @Test
    void sessionPromptLineFormIsByteFrozen() {
        assertEquals("  Allow? [y=yes, a=yes for session, N=no] ",
                ApprovalPromptText.SESSION_PROMPT_LINE);
    }

    @Test
    void oncePromptLineFormIsByteFrozen() {
        assertEquals("  Allow? [y=yes, N=no] ", ApprovalPromptText.ONCE_PROMPT_LINE);
    }

    @Test
    void promptPrefixIsByteFrozenAndPrefixesBothForms() {
        assertEquals("Allow? [y=yes", ApprovalPromptText.PROMPT_PREFIX);
        assertTrue(ApprovalPromptText.SESSION_PROMPT.startsWith(ApprovalPromptText.PROMPT_PREFIX));
        assertTrue(ApprovalPromptText.ONCE_PROMPT.startsWith(ApprovalPromptText.PROMPT_PREFIX));
    }

    @Test
    void windowTitleIsByteFrozen() {
        assertEquals("approval required", ApprovalPromptText.WINDOW_TITLE);
    }
}
