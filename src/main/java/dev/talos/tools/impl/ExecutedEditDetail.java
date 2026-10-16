package dev.talos.tools.impl;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.talos.core.io.StrictUtf8;
import dev.talos.core.security.Redactor;
import dev.talos.core.util.TextReplacement;
import dev.talos.safety.ProtectedContentSanitizer;
import dev.talos.safety.ProtectedWorkspacePaths;
import dev.talos.tools.ToolMutationEffects;
import dev.talos.tools.VerificationStatus;

import java.nio.file.Path;
import java.nio.charset.CharacterCodingException;

/** Ephemeral producer evidence, not permission to retain, publish, or claim task completion. */
public final class ExecutedEditDetail {
    public enum ReadBack {
        NOT_RECORDED, MATCHED_AT_EXECUTION, MISMATCH_AT_EXECUTION, UNAVAILABLE_AT_EXECUTION
    }

    static final int MAX_FRAGMENT_CHARS = 512;
    static final int MAX_SOURCE_BYTES = 2 * 1024 * 1024;
    private static final Redactor REDACTOR = new Redactor();
    private static final ExecutedEditDetail NONE = new ExecutedEditDetail(
            "", "", "", ReadBack.NOT_RECORDED, VerificationStatus.UNKNOWN);

    @JsonIgnore private final String target;
    @JsonIgnore private final String before;
    @JsonIgnore private final String after;
    @JsonIgnore private final ReadBack readBack;
    @JsonIgnore private final VerificationStatus verification;

    private ExecutedEditDetail(String target, String before, String after,
                               ReadBack readBack, VerificationStatus verification) {
        this.target = target;
        this.before = before;
        this.after = after;
        this.readBack = readBack;
        this.verification = verification;
    }

    public static ExecutedEditDetail none() { return NONE; }
    @JsonProperty("available") public boolean available() { return !target.isEmpty(); }
    @JsonIgnore public String target() { return target; }
    @JsonIgnore public String before() { return before; }
    @JsonIgnore public String after() { return after; }
    @JsonIgnore public ReadBack readBack() { return readBack; }
    @JsonIgnore public VerificationStatus verification() { return verification; }

    public boolean associatedWith(ToolMutationEffects effects) {
        return available() && effects != null && effects.uncertainPaths().isEmpty()
                && effects.appliedPaths().size() == 1 && target.equals(effects.appliedPaths().getFirst());
    }

    /** Called only after the edit producer's atomic replacement has definitely succeeded. */
    static ExecutedEditDetail capture(Path workspace, String effectPath, String source, String postimage,
                                     TextReplacement.Match match, ContentVerifier.VerifyResult verified,
                                     ToolMutationEffects effects) {
        try {
            if (workspace == null || effectPath == null || source == null || postimage == null
                    || match == null || verified == null || verified.status() == null
                    || match.count() != 1 || match.startRaw() < 0 || match.endRaw() > source.length()
                    || match.endRaw() <= match.startRaw() || match.replacement() == null
                    || match.endRaw() - match.startRaw() > MAX_FRAGMENT_CHARS
                    || match.replacement().length() > MAX_FRAGMENT_CHARS
                    || source.length() > MAX_SOURCE_BYTES || postimage.length() > MAX_SOURCE_BYTES
                    || source.equals(postimage)) return none();
            String target = effectPath.replace('\\', '/');
            if (!safeTarget(workspace, target) || !boundedWholeText(source) || !boundedWholeText(postimage)
                    || !intact(source) || !intact(postimage))
                return none();
            // Validate the complete producer relation without creating another complete postimage.
            int suffixLength = source.length() - match.endRaw();
            if (postimage.length() != match.startRaw() + match.replacement().length() + suffixLength
                    || !postimage.regionMatches(0, source, 0, match.startRaw())
                    || !postimage.regionMatches(match.startRaw(), match.replacement(), 0, match.replacement().length())
                    || !postimage.regionMatches(match.startRaw() + match.replacement().length(),
                            source, match.endRaw(), suffixLength)) return none();
            String before = source.substring(match.startRaw(), match.endRaw());
            if (!safeUnicode(before, true) || !safeUnicode(match.replacement(), true)
                    || !intact(before) || !intact(match.replacement())) return none();
            var detail = new ExecutedEditDetail(target, before,
                    match.replacement(), verified.readBack(), verified.status());
            return detail.associatedWith(effects) ? detail : none();
        } catch (Exception | StackOverflowError unavailable) {
            // Optional evidence must not change an already-observed mutation outcome.
            return none();
        }
    }

    private static boolean safeTarget(Path workspace, String target) {
        if (target.isBlank() || target.length() > 1024 || !target.equals(target.strip())
                || target.startsWith("/") || target.contains(":") || !safeUnicode(target, false)) return false;
        for (String segment : target.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                    || !segment.equals(segment.strip()) || segment.endsWith(".")) return false;
        }
        var decision = ProtectedWorkspacePaths.classify(workspace, target);
        return decision.hasPath() && decision.insideWorkspace() && !decision.workspaceEscape()
                && !decision.protectedPath() && target.equals(decision.relativePath())
                && intact(target);
    }

    private static boolean boundedWholeText(String text) throws CharacterCodingException {
        return text.length() <= MAX_SOURCE_BYTES && safeUnicode(text, true)
                && StrictUtf8.encode(text).length <= MAX_SOURCE_BYTES;
    }

    private static boolean intact(String text) {
        return text.equals(ProtectedContentSanitizer.sanitizeText(text))
                && text.equals(REDACTOR.redactBlock(text));
    }

    private static boolean safeUnicode(String text, boolean allowLayout) {
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset);
            if (Character.isSurrogate(text.charAt(offset)) && Character.charCount(cp) == 1) return false;
            if (Character.getType(cp) == Character.FORMAT || cp == 0x2028 || cp == 0x2029
                    || (cp >= 0xfdd0 && cp <= 0xfdef) || (cp & 0xffff) >= 0xfffe
                    || (Character.isISOControl(cp) && !(allowLayout && (cp == '\n' || cp == '\r' || cp == '\t'))))
                return false;
            offset += Character.charCount(cp);
        }
        return true;
    }

    @Override public String toString() { return "ExecutedEditDetail[available=" + available() + "]"; }
}
