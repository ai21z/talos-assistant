package dev.talos.safety;

import java.util.Objects;

/** Destination permissions for document-derived content. Restrictions are only intersected. */
public record ContentRestrictions(boolean modelAllowed, boolean artifactAllowed,
                                  boolean historyAllowed, boolean indexAllowed) {
    public static final ContentRestrictions UNRESTRICTED = new ContentRestrictions(true, true, true, true);
    public static final String WITHHELD = "[Document-derived content was withheld by privacy policy.]";

    public ContentRestrictions intersect(ContentRestrictions other) {
        Objects.requireNonNull(other, "content restrictions");
        return new ContentRestrictions(modelAllowed && other.modelAllowed,
                artifactAllowed && other.artifactAllowed, historyAllowed && other.historyAllowed,
                indexAllowed && other.indexAllowed);
    }

    public String forHistory(String content) {
        return historyAllowed ? Objects.toString(content, "") : WITHHELD;
    }
}
