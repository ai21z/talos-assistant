package dev.talos.runtime.verification;

import dev.talos.core.util.TextReplacement;

/** Shared text-presence checks for replacement verification. */
final class ReplacementTextPresence {
    private ReplacementTextPresence() {}

    static boolean oldTextRemainsOutsideReplacement(String content, String oldText, String newText) {
        return TextReplacement.oldTextRemainsOutsideReplacement(content, oldText, newText);
    }

    static boolean replacementTextObserved(String content, String newText) {
        return TextReplacement.containsNormalized(content, newText);
    }
}
