package dev.talos.cli.repl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Workspace template commands (T806): {@code <workspace>/.talos/commands/*.md}.
 *
 * <p>A template named {@code review.md} makes {@code /review} expand to the
 * file's content and run through the unmodified prompt pipeline — workspace
 * content is untrusted, so a template gets exactly typed-input capability:
 * the same classification, the same tool policy, the same approvals as if
 * the user had typed the text. Expansion is single-level (the result is
 * never re-classified as a command), and since T788 the directory is a
 * protected CONTROL path the model cannot write with an ordinary approval.
 *
 * <p>This catalog is deliberately NOT registered into {@link
 * dev.talos.cli.repl.slash.CommandRegistry} — {@code register()} overwrites
 * blindly, so a workspace file could shadow a built-in. Lookup order is
 * builtin-wins by construction: the router consults templates only on a
 * registry miss, and names colliding with built-ins or aliases are dropped
 * at load. Loaded once at startup; restart to reload.
 *
 * <p>Limits: ≤{@value #MAX_TEMPLATES} templates, ≤16&nbsp;KiB each, names
 * {@code [a-z0-9][a-z0-9-]*}. {@code $ARGS} is replaced with the typed
 * arguments (appended on a new line when the placeholder is absent).
 */
public final class WorkspaceCommandTemplates {

    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceCommandTemplates.class);

    public static final int MAX_TEMPLATES = 24;
    public static final long MAX_FILE_BYTES = 16 * 1024;
    public static final String ARGS_PLACEHOLDER = "$ARGS";

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]*");

    private final Map<String, String> bodies;

    private WorkspaceCommandTemplates(Map<String, String> bodies) {
        this.bodies = bodies;
    }

    public static WorkspaceCommandTemplates none() {
        return new WorkspaceCommandTemplates(Map.of());
    }

    /**
     * Scan {@code <workspace>/.talos/commands} once. Never throws — a
     * broken template directory must not break startup; offenders are
     * skipped with a log line.
     */
    public static WorkspaceCommandTemplates load(Path workspace, Set<String> reservedNames) {
        if (workspace == null) return none();
        Path dir = workspace.resolve(".talos").resolve("commands");
        if (!Files.isDirectory(dir)) return none();

        Map<String, String> bodies = new LinkedHashMap<>();
        try (var stream = Files.list(dir)) {
            List<Path> files = new ArrayList<>(stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .toList());
            files.sort(java.util.Comparator.comparing(p -> p.getFileName().toString()));
            for (Path file : files) {
                String fileName = file.getFileName().toString();
                String name = fileName.substring(0, fileName.length() - ".md".length());
                if (!NAME.matcher(name).matches()) {
                    LOG.warn("Skipping workspace template '{}': name must match [a-z0-9][a-z0-9-]*", fileName);
                    continue;
                }
                if (reservedNames != null && reservedNames.contains(name)) {
                    LOG.warn("Skipping workspace template '{}': collides with a built-in command", fileName);
                    continue;
                }
                if (bodies.size() >= MAX_TEMPLATES) {
                    LOG.warn("Skipping workspace template '{}': limit is {} templates", fileName, MAX_TEMPLATES);
                    continue;
                }
                try {
                    if (Files.size(file) > MAX_FILE_BYTES) {
                        LOG.warn("Skipping workspace template '{}': larger than 16 KiB", fileName);
                        continue;
                    }
                    String body = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).strip();
                    if (body.isBlank()) {
                        LOG.warn("Skipping workspace template '{}': empty", fileName);
                        continue;
                    }
                    bodies.put(name, body);
                } catch (Exception e) {
                    LOG.warn("Skipping workspace template '{}': {}",
                            dev.talos.safety.SafeLogFormatter.value(fileName),
                            dev.talos.safety.SafeLogFormatter.throwableMessage(e));
                }
            }
        } catch (Exception e) {
            LOG.warn("Could not scan workspace templates: {}",
                    dev.talos.safety.SafeLogFormatter.throwableMessage(e));
            return none();
        }
        return new WorkspaceCommandTemplates(Map.copyOf(bodies));
    }

    public boolean has(String name) {
        return name != null && bodies.containsKey(name);
    }

    /** Template names, sorted, for /help and tab completion. */
    public List<String> names() {
        return bodies.keySet().stream().sorted().toList();
    }

    public boolean isEmpty() {
        return bodies.isEmpty();
    }

    /**
     * Expand a template with the typed arguments, or null when no such
     * template exists. {@code $ARGS} substitution; args append on a new
     * paragraph when the placeholder is absent.
     */
    public String expand(String name, String args) {
        String body = name == null ? null : bodies.get(name);
        if (body == null) return null;
        String safeArgs = args == null ? "" : args.trim();
        if (body.contains(ARGS_PLACEHOLDER)) {
            return body.replace(ARGS_PLACEHOLDER, safeArgs);
        }
        return safeArgs.isBlank() ? body : body + "\n\n" + safeArgs;
    }
}
