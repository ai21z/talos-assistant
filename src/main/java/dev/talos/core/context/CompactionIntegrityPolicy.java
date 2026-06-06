package dev.talos.core.context;

import dev.talos.safety.ProtectedContentSanitizer;
import dev.talos.spi.types.ChatMessage;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic safety checks for LLM-produced conversation compaction sketches.
 *
 * <p>Compaction is destructive only when the manager prunes summarized turns, so
 * a sketch must clear a small evidence-preservation gate before it can be marked
 * successful. This is intentionally conservative and non-LLM: redact protected
 * content, reject vacuous summaries, and require critical operational anchors
 * from represented history to survive.
 */
final class CompactionIntegrityPolicy {
    private static final Pattern TOOL_ANCHOR = Pattern.compile("\\btalos\\.[A-Za-z0-9_]+\\b");
    private static final Pattern CHECKPOINT_ANCHOR = Pattern.compile("\\bchk-[A-Za-z0-9_-]+\\b");
    private static final Pattern PATH_ANCHOR = Pattern.compile(
            "(?i)\\b[A-Za-z0-9_.\\-/\\\\]+\\.(?:html|css|js|java|md|json|ya?ml|toml|properties|txt|docx|pdf|xlsx|csv)\\b");

    private static final List<String> CRITICAL_PHRASES = List.of(
            "verification failed",
            "approval denied",
            "blocked by policy",
            "forbidden target",
            "expected target");

    private static final Set<String> TRIVIAL_SUMMARIES = Set.of(
            "summary omitted",
            "no context",
            "nothing to summarize",
            "n/a",
            "none",
            "omitted");

    private static final int MAX_REQUIRED_PATH_ANCHORS = 4;
    private static final int MAX_REQUIRED_GENERIC_ANCHORS = 8;

    private CompactionIntegrityPolicy() {}

    record Result(String sketch, boolean succeeded, String reason) {}

    static Result validate(String existingSketch, List<ChatMessage> oldTurns, String proposedSketch) {
        String sanitized = ProtectedContentSanitizer.sanitizeText(proposedSketch);
        if (sanitized == null || sanitized.isBlank()) {
            return failed(existingSketch, "empty-output");
        }
        sanitized = sanitized.strip();

        if (ProtectedContentSanitizer.containsRawCanary(sanitized)
                || ProtectedContentSanitizer.containsRawPrivateDocumentFactCanary(sanitized)) {
            return failed(existingSketch, "protected-content");
        }

        if (isTrivial(sanitized, oldTurns)) {
            return failed(existingSketch, "trivial-summary");
        }

        String oldText = join(oldTurns);
        String normalizedSketch = sanitized.toLowerCase(Locale.ROOT);
        List<String> missing = missingCriticalAnchors(oldText, normalizedSketch);
        if (!missing.isEmpty()) {
            return failed(existingSketch, "critical-evidence-missing:" + missing.getFirst());
        }

        return new Result(sanitized, true, "success");
    }

    private static Result failed(String existingSketch, String reason) {
        return new Result(existingSketch, false, reason);
    }

    private static boolean isTrivial(String sketch, List<ChatMessage> oldTurns) {
        String normalized = sketch.strip().toLowerCase(Locale.ROOT);
        if (TRIVIAL_SUMMARIES.contains(normalized)) return substantive(oldTurns);
        if (normalized.length() < 20 && substantive(oldTurns)) return true;
        return false;
    }

    private static boolean substantive(List<ChatMessage> oldTurns) {
        return oldTurns != null
                && oldTurns.stream()
                .map(ChatMessage::content)
                .filter(content -> content != null && !content.isBlank())
                .mapToInt(String::length)
                .sum() >= 80;
    }

    private static List<String> missingCriticalAnchors(String oldText, String normalizedSketch) {
        List<String> required = new ArrayList<>();
        required.addAll(firstAnchors(TOOL_ANCHOR, oldText, MAX_REQUIRED_GENERIC_ANCHORS));
        required.addAll(firstAnchors(CHECKPOINT_ANCHOR, oldText, MAX_REQUIRED_GENERIC_ANCHORS));
        for (String phrase : CRITICAL_PHRASES) {
            if (containsIgnoreCase(oldText, phrase)) {
                required.add(phrase);
            }
        }
        if (containsCriticalOperationalPhrase(oldText) || TOOL_ANCHOR.matcher(oldText).find()) {
            required.addAll(firstAnchors(PATH_ANCHOR, oldText, MAX_REQUIRED_PATH_ANCHORS));
        }

        List<String> missing = new ArrayList<>();
        for (String anchor : unique(required)) {
            if (!normalizedSketch.contains(anchor.toLowerCase(Locale.ROOT))) {
                missing.add(anchor);
            }
        }
        return missing;
    }

    private static boolean containsCriticalOperationalPhrase(String value) {
        for (String phrase : CRITICAL_PHRASES) {
            if (containsIgnoreCase(value, phrase)) return true;
        }
        return false;
    }

    private static boolean containsIgnoreCase(String value, String needle) {
        return value != null
                && needle != null
                && value.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private static List<String> firstAnchors(Pattern pattern, String text, int max) {
        if (text == null || text.isBlank()) return List.of();
        LinkedHashSet<String> anchors = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find() && anchors.size() < max) {
            anchors.add(matcher.group());
        }
        return List.copyOf(anchors);
    }

    private static List<String> unique(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private static String join(List<ChatMessage> oldTurns) {
        if (oldTurns == null || oldTurns.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (ChatMessage turn : oldTurns) {
            if (turn == null || turn.content() == null) continue;
            out.append(turn.role()).append(": ").append(turn.content()).append('\n');
        }
        return out.toString();
    }
}
