package dev.talos.tools.impl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Builds a "File not found" error message that includes a short listing of
 * candidate paths from the parent directory. Gives the LLM a grounding
 * signal to self-correct when it hallucinates a file name or directory.
 *
 * <p><b>Observed case</b> (real transcript, gemma4:26b): model invented
 * {@code horror_site/index.html} when the actual directory was
 * {@code horror-synth-site/}. The plain {@code "File not found: …"}
 * message gave no recovery signal; the model then burned 4+ iterations
 * guessing. With a parent-dir hint the next turn can pick the real name
 * on its own.
 *
 * <p>Output format example:
 * <pre>
 * File not found: horror_site/index.html
 * Parent directory "horror_site" does not exist. Closest existing parents: horror-synth-site/
 * </pre>
 * or when the parent exists:
 * <pre>
 * File not found: horror-synth-site/missing.html
 * Files in horror-synth-site/: index.html, script.js, style.css
 * </pre>
 */
final class NotFoundHint {

    private NotFoundHint() {}

    /** Max sibling entries to list; keeps the error tight and token-cheap. */
    private static final int MAX_ENTRIES = 12;

    /**
     * Build a "File not found" message augmented with a parent-directory
     * hint when possible. Never throws — silently falls back to the plain
     * message if listing the parent fails (permissions, IO, etc.).
     *
     * @param pathParam  the path the caller tried (as the model wrote it)
     * @param resolved   the sandbox-resolved absolute path (may or may not exist)
     * @param workspace  the workspace root, used to render parent paths
     *                   relative to the workspace rather than absolute
     */
    static String build(String pathParam, Path resolved, Path workspace) {
        StringBuilder msg = new StringBuilder("File not found: ").append(pathParam);
        try {
            Path parent = resolved.getParent();
            if (parent == null) return msg.toString();

            if (Files.isDirectory(parent)) {
                // Parent exists — list its contents so the model can pick the right file.
                List<String> names = listChildren(parent);
                if (!names.isEmpty()) {
                    String parentDisp = displayParent(parent, workspace);
                    msg.append("\nFiles in ").append(parentDisp).append("/: ")
                            .append(String.join(", ", names));
                }
                return msg.toString();
            }

            // Parent doesn't exist — walk up until we find one that does,
            // and list its directory children so the model sees sibling
            // folder names (catches the classic foo_bar vs foo-bar typo).
            Path walk = parent.getParent();
            while (walk != null && !Files.isDirectory(walk)) walk = walk.getParent();
            if (walk != null) {
                List<String> dirs = listDirectoryChildren(walk);
                if (!dirs.isEmpty()) {
                    String walkDisp = displayParent(walk, workspace);
                    msg.append("\nParent directory does not exist. ")
                            .append("Directories in ").append(walkDisp.isEmpty() ? "." : walkDisp)
                            .append("/: ").append(String.join(", ", dirs));
                }
            }
        } catch (Exception ignore) {
            // Best effort — never let the hint itself mask the original error.
        }
        return msg.toString();
    }

    private static List<String> listChildren(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            final List<String> out = new ArrayList<>();
            s.sorted().limit(MAX_ENTRIES + 1L).forEach(p -> {
                String n = p.getFileName().toString();
                if (Files.isDirectory(p)) n = n + "/";
                out.add(n);
            });
            return trim(out);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static List<String> listDirectoryChildren(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            final List<String> out = new ArrayList<>();
            s.filter(Files::isDirectory).sorted().limit(MAX_ENTRIES + 1L)
                    .forEach(p -> out.add(p.getFileName().toString() + "/"));
            return trim(out);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static List<String> trim(List<String> out) {
        if (out.size() > MAX_ENTRIES) {
            List<String> sub = new ArrayList<>(out.subList(0, MAX_ENTRIES));
            sub.add("…");
            return sub;
        }
        return out;
    }

    private static String displayParent(Path parent, Path workspace) {
        if (workspace == null) return parent.getFileName() == null ? "" : parent.toString();
        try {
            Path rel = workspace.toAbsolutePath().relativize(parent.toAbsolutePath());
            String s = rel.toString().replace('\\', '/');
            return s.isEmpty() ? "." : s;
        } catch (Exception e) {
            return parent.toString();
        }
    }
}


