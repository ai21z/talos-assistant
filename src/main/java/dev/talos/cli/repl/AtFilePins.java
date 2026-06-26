package dev.talos.cli.repl;

import dev.talos.core.security.Sandbox;
import dev.talos.runtime.policy.ProtectedContentPolicy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolver for {@code @file} pins in a prompt line (T802).
 *
 * <p>Explicit paths only — no fuzzy lookup, no workspace walk: what the
 * user typed either resolves or produces a visible notice. Checks run in
 * privacy-conscious order (sandbox, then protected classification, then
 * existence) so a protected path gets the same refusal whether or not it
 * exists, and an outside-workspace token never reveals what is there.
 * The {@code @token} stays visible in the prompt; pinning only ADDS
 * content, it never rewrites the user's words.
 *
 * <p>Caps: {@value #MAX_PINS} pins per prompt, {@value #PER_FILE_CHARS}
 * chars per file, {@value #TOTAL_CHARS} chars total,
 * 2&nbsp;MiB per-file size ceiling, UTF-8 text only (binary fails
 * closed with a notice).
 */
public final class AtFilePins {

    public static final int MAX_PINS = 4;
    public static final int PER_FILE_CHARS = 4_000;
    public static final int TOTAL_CHARS = 12_000;
    public static final long MAX_FILE_BYTES = 2L * 1024 * 1024;

    /**
     * {@code @"quoted path"} or {@code @bare-token}; the leading {@code @}
     * must start the line or follow whitespace so e-mail addresses and
     * code like {@code user@host} never become pins.
     */
    private static final Pattern TOKEN =
            Pattern.compile("(?<!\\S)@(?:\"([^\"]+)\"|([^\\s\"]+))");

    /** Trailing sentence punctuation on bare tokens ("see @Foo.java.") is not part of the path. */
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[.,;:!?)\\]}'`]+$");

    private AtFilePins() {}

    /**
     * Last prompt's resolution, recorded by the router for the
     * {@code /context} pinned-bytes row (T803; LastPromptCapture
     * precedent). The resolver itself stays pure — recording is an
     * explicit call at the dispatch site.
     */
    private static volatile Resolution lastResolution = new Resolution(List.of(), List.of());

    public static void recordLast(Resolution resolution) {
        lastResolution = resolution == null ? Resolution.empty() : resolution;
    }

    public static Resolution lastResolution() {
        return lastResolution;
    }

    /**
     * One pinned file: workspace-relative display path (forward slashes),
     * the capped content head, and whether/how much was cut.
     */
    public record PinnedFile(String path, String content, boolean truncated, int totalChars) {}

    /** Resolution outcome: the pins plus human-facing notices for everything skipped. */
    public record Resolution(List<PinnedFile> pins, List<String> notices) {
        public static Resolution empty() {
            return new Resolution(List.of(), List.of());
        }
        public int pinnedChars() {
            return pins.stream().mapToInt(p -> p.content().length()).sum();
        }
    }

    public static Resolution resolve(String rawLine, Path workspace, Sandbox sandbox) {
        if (rawLine == null || rawLine.indexOf('@') < 0 || workspace == null) {
            return Resolution.empty();
        }
        Set<String> tokens = new LinkedHashSet<>();
        Matcher matcher = TOKEN.matcher(rawLine);
        while (matcher.find()) {
            String quoted = matcher.group(1);
            String token = quoted != null
                    ? quoted
                    : TRAILING_PUNCTUATION.matcher(matcher.group(2)).replaceAll("");
            if (!token.isBlank()) tokens.add(token);
        }
        if (tokens.isEmpty()) return Resolution.empty();

        List<PinnedFile> pins = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        Path ws = workspace.toAbsolutePath().normalize();
        int remaining = TOTAL_CHARS;
        int accepted = 0;
        List<String> overCap = new ArrayList<>();

        for (String token : tokens) {
            if (accepted >= MAX_PINS) {
                overCap.add("@" + token);
                continue;
            }
            Path resolved;
            try {
                resolved = ws.resolve(token).toAbsolutePath().normalize();
            } catch (Exception e) {
                notices.add("@-pin '" + token + "' skipped: not a valid path.");
                continue;
            }
            if (sandbox != null && !sandbox.allowedPath(resolved)) {
                notices.add("@-pin '" + token + "' skipped: outside the workspace.");
                continue;
            }
            if (ProtectedContentPolicy.isProtectedPath(ws, resolved)) {
                notices.add("@-pin '" + token + "' refused: protected path. "
                        + "Ask for it in the prompt instead; read_file is approval-gated.");
                continue;
            }
            if (!Files.exists(resolved)) {
                notices.add("@-pin '" + token + "' skipped: no such file in the workspace.");
                continue;
            }
            if (Files.isDirectory(resolved)) {
                notices.add("@-pin '" + token + "' skipped: it is a directory; pin individual files.");
                continue;
            }
            String relative = displayPath(ws, resolved, token);
            try {
                if (Files.size(resolved) > MAX_FILE_BYTES) {
                    notices.add("@-pin '" + relative + "' skipped: file exceeds 2 MiB.");
                    continue;
                }
                if (remaining <= 0) {
                    notices.add("@-pin '" + relative + "' skipped: "
                            + TOTAL_CHARS + "-character pin budget exhausted.");
                    continue;
                }
                // UTF-8 decode replaces malformed bytes with U+FFFD; that
                // (or a NUL) marks binary content — fail closed.
                String content = new String(
                        Files.readAllBytes(resolved), StandardCharsets.UTF_8);
                if (content.indexOf('�') >= 0 || content.indexOf('\0') >= 0) {
                    notices.add("@-pin '" + relative + "' skipped: not readable as UTF-8 text.");
                    continue;
                }
                int take = Math.min(Math.min(PER_FILE_CHARS, remaining), content.length());
                String head = content.substring(0, take);
                pins.add(new PinnedFile(relative, head, take < content.length(), content.length()));
                remaining -= take;
                accepted++;
            } catch (Exception e) {
                notices.add("@-pin '" + relative + "' skipped: not readable as UTF-8 text.");
            }
        }
        if (!overCap.isEmpty()) {
            notices.add("@-pin limit is " + MAX_PINS + " files per prompt; ignored: "
                    + String.join(", ", overCap));
        }
        return new Resolution(List.copyOf(pins), List.copyOf(notices));
    }

    private static String displayPath(Path workspace, Path resolved, String token) {
        try {
            return workspace.relativize(resolved).toString().replace('\\', '/');
        } catch (Exception e) {
            return token;
        }
    }
}
