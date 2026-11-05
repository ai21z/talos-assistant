package dev.talos.tools;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Ephemeral producer evidence: the content that a file mutation tool observed for its single target immediately before
 * a successful change, bound to the SHA-256 token of the replaced bytes. An absent target has empty text. It is not
 * permission to retain, publish or render the content. Its text is never serialized, and {@link #toString()} names only
 * the target.
 */
public final class MutationPreimage {
    /** The largest prior file, in bytes, whose content is kept. */
    public static final int MAX_BYTES = 2 * 1024 * 1024;

    private static final MutationPreimage NONE = new MutationPreimage("", false, "", "");

    private final String target;
    private final boolean existed;
    private final String sha256;
    private final String text;

    private MutationPreimage(String target, boolean existed, String sha256, String text) {
        this.target = target;
        this.existed = existed;
        this.sha256 = sha256;
        this.text = text;
    }

    public static MutationPreimage none() {
        return NONE;
    }

    /** An existing target's prior text, bound to the SHA-256 token of its exact bytes, or none when either is invalid. */
    public static MutationPreimage existing(String target, String sha256, String text) {
        String path = normalized(target);
        String hash = sha256 == null ? "" : sha256.toLowerCase(Locale.ROOT);
        if (path.isEmpty() || !hash.matches("[0-9a-f]{64}") || text == null || text.length() > MAX_BYTES) {
            return NONE;
        }
        return new MutationPreimage(path, true, hash, text);
    }

    /** A target that did not exist before the change. */
    public static MutationPreimage absent(String target) {
        String path = normalized(target);
        return path.isEmpty() ? NONE : new MutationPreimage(path, false, "", "");
    }

    @JsonProperty("available")
    public boolean available() {
        return !target.isEmpty();
    }

    @JsonIgnore
    public String target() {
        return target;
    }

    @JsonIgnore
    public boolean existed() {
        return existed;
    }

    @JsonIgnore
    public String sha256() {
        return sha256;
    }

    @JsonIgnore
    public String text() {
        return text;
    }

    /** The preimage belongs to a result whose effects name exactly one applied path, equal to its target. */
    public boolean associatedWith(ToolMutationEffects effects) {
        return available() && effects != null && effects.uncertainPaths().isEmpty()
                && effects.appliedPaths().size() == 1 && target.equals(effects.appliedPaths().getFirst());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MutationPreimage that
                && existed == that.existed && target.equals(that.target) && sha256.equals(that.sha256);
    }

    @Override
    public int hashCode() {
        return Objects.hash(target, existed, sha256);
    }

    @Override
    public String toString() {
        return available() ? "MutationPreimage[target=" + target + ", existed=" + existed + "]" : "MutationPreimage[none]";
    }

    private static String normalized(String path) {
        List<String> paths = new ToolMutationEffects(path == null ? List.of() : List.of(path), List.of()).appliedPaths();
        return paths.size() == 1 ? paths.getFirst() : "";
    }
}
