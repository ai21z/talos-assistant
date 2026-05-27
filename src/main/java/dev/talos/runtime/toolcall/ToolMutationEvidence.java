package dev.talos.runtime.toolcall;

/**
 * Structured mutation proof captured from tool-call inputs and prior read evidence.
 */
public record ToolMutationEvidence(
        String kind,
        String oldString,
        String newString
) {
    public ToolMutationEvidence {
        kind = kind == null ? "" : kind;
        oldString = oldString == null ? "" : oldString;
        newString = newString == null ? "" : newString;
    }

    public static ToolMutationEvidence none() {
        return new ToolMutationEvidence("", "", "");
    }

    public static ToolMutationEvidence exactEdit(String oldString, String newString) {
        return new ToolMutationEvidence("EXACT_EDIT_REPLACEMENT", oldString, newString);
    }

    public static ToolMutationEvidence fullWriteReplacement(String previousContent, String newContent) {
        return new ToolMutationEvidence("FULL_WRITE_REPLACEMENT", previousContent, newContent);
    }

    public boolean exactEditReplacement() {
        return "EXACT_EDIT_REPLACEMENT".equals(kind);
    }

    public boolean fullWriteReplacement() {
        return "FULL_WRITE_REPLACEMENT".equals(kind);
    }
}
