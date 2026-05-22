package dev.talos.safety;

/** Pure protected-content user-visible notes for sink-safe tool output. */
public final class ProtectedContentMessages {
    private ProtectedContentMessages() {}

    public static final String PROTECTED_CONTENT_NOTE =
            "Matches were found or may exist in protected content, but matching lines were not returned.";

    public static String protectedContentNote(int skippedCount) {
        if (skippedCount <= 0) return "";
        return "\n\n" + PROTECTED_CONTENT_NOTE;
    }
}
