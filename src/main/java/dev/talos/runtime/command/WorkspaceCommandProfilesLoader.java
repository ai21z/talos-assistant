package dev.talos.runtime.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses and validates {@code <workspace>/.talos/profiles.yaml} into
 * {@code ws:}-prefixed {@link CommandProfile}s.
 *
 * <p>Trust posture: the declaration is workspace content - untrusted,
 * model-reachable input (writable only through the protected-path flow
 * since T788). The loader therefore validates fail-closed (one bad profile
 * rejects the whole file with a single human-readable reason, unknown keys
 * are rejected so typos cannot silently default), never throws to the
 * caller, declares NOTHING executable by itself (registration and the
 * trust pin happen elsewhere), and deliberately imports nothing from the
 * execution path.
 *
 * <p>Declared shape:
 * <pre>
 * profiles:
 *   - id: check                      # [a-z0-9_-]{1,32}; registered as ws:check
 *     executable: ./gradlew.bat      # bare name (PATH), absolute, or workspace-relative
 *     args: ["--no-daemon", "check"] # fixed argv; no templating, no placeholders
 *     timeout_ms: 300000             # optional, clamped 1s-600s
 *     expected_writes: ["build/"]    # optional, workspace-relative
 * </pre>
 *
 * <p>Non-declarable by design: {@code requiresApproval} (always true),
 * {@code networkAccess}/{@code interactive} (always false), risk (always
 * BUILD_OR_TEST), checkpointing (never - verification commands are not
 * mutations).
 */
public final class WorkspaceCommandProfilesLoader {

    public static final String DECLARATION_RELATIVE_PATH = ".talos/profiles.yaml";
    public static final String PROFILE_ID_PREFIX = "ws:";

    static final int MAX_PROFILES = 8;
    static final int MAX_ARGS = 32;
    static final int MAX_ARG_LENGTH = 256;
    static final int MAX_EXECUTABLE_LENGTH = 260;
    static final long MAX_FILE_BYTES = 64 * 1024;

    private static final Pattern ID_SHAPE = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final Set<String> ALLOWED_KEYS =
            Set.of("id", "executable", "args", "timeout_ms", "expected_writes");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** The loaded declaration plus the SHA-256 of its raw bytes (for the trust pin). */
    public record Loaded(WorkspaceCommandProfiles profiles, String declarationSha256) {}

    private WorkspaceCommandProfilesLoader() {}

    public static Loaded load(Path workspace) {
        if (workspace == null) {
            return new Loaded(WorkspaceCommandProfiles.none(), "");
        }
        Path declaration = workspace.toAbsolutePath().normalize()
                .resolve(".talos").resolve("profiles.yaml");
        if (!Files.isRegularFile(declaration)) {
            return new Loaded(WorkspaceCommandProfiles.none(), "");
        }
        byte[] raw;
        try {
            long size = Files.size(declaration);
            if (size > MAX_FILE_BYTES) {
                return new Loaded(WorkspaceCommandProfiles.invalid(
                        "declaration exceeds " + MAX_FILE_BYTES + " bytes"), "");
            }
            raw = Files.readAllBytes(declaration);
        } catch (Exception e) {
            return new Loaded(WorkspaceCommandProfiles.invalid(
                    "declaration unreadable: " + e.getMessage()), "");
        }
        String sha256 = sha256Hex(raw);
        return new Loaded(parse(raw, workspace.toAbsolutePath().normalize()), sha256);
    }

    static Loaded loadBytes(byte[] raw, Path workspace) {
        if (raw == null) {
            return new Loaded(WorkspaceCommandProfiles.invalid("declaration is empty"), "");
        }
        Path ws = workspace == null ? Path.of(".").toAbsolutePath().normalize()
                : workspace.toAbsolutePath().normalize();
        return new Loaded(parse(raw, ws), sha256Hex(raw));
    }

    private static WorkspaceCommandProfiles parse(byte[] raw, Path workspace) {
        JsonNode root;
        try {
            root = YAML.readTree(new String(raw, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return WorkspaceCommandProfiles.invalid("YAML parse failed: " + e.getMessage());
        }
        if (root == null || root.isNull() || root.isMissingNode()) {
            return WorkspaceCommandProfiles.invalid("declaration is empty");
        }
        JsonNode profilesNode = root.path("profiles");
        if (!profilesNode.isArray()) {
            return WorkspaceCommandProfiles.invalid(
                    "top-level `profiles` must be a list");
        }
        if (profilesNode.size() > MAX_PROFILES) {
            return WorkspaceCommandProfiles.invalid(
                    "at most " + MAX_PROFILES + " profiles may be declared, found "
                            + profilesNode.size());
        }
        List<CommandProfile> out = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        int index = 0;
        for (JsonNode profileNode : profilesNode) {
            index++;
            try {
                out.add(parseProfile(profileNode, index, seenIds, workspace));
            } catch (CommandPlanRejectedException e) {
                return WorkspaceCommandProfiles.invalid(e.getMessage());
            }
        }
        if (out.isEmpty()) {
            return WorkspaceCommandProfiles.invalid("`profiles` list is empty");
        }
        return WorkspaceCommandProfiles.of(out);
    }

    private static CommandProfile parseProfile(
            JsonNode node, int index, Set<String> seenIds, Path workspace) {
        if (node == null || !node.isObject()) {
            throw new CommandPlanRejectedException(
                    "profile #" + index + " must be a mapping");
        }
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            if (!ALLOWED_KEYS.contains(key)) {
                throw new CommandPlanRejectedException(
                        "profile #" + index + " has unknown key `" + key
                                + "` (allowed: id, executable, args, timeout_ms,"
                                + " expected_writes; approval, network, interactivity,"
                                + " and risk are not declarable)");
            }
        }

        String id = node.path("id").asText("").strip().toLowerCase(Locale.ROOT);
        if (!ID_SHAPE.matcher(id).matches()) {
            throw new CommandPlanRejectedException(
                    "profile #" + index + " id must match [a-z0-9_-]{1,32}");
        }
        if (!seenIds.add(id)) {
            throw new CommandPlanRejectedException("duplicate profile id: " + id);
        }

        String executable = resolveExecutable(
                node.path("executable").asText("").strip(), id, workspace);

        List<String> args = new ArrayList<>();
        JsonNode argsNode = node.path("args");
        if (!argsNode.isMissingNode() && !argsNode.isNull()) {
            if (!argsNode.isArray()) {
                throw new CommandPlanRejectedException(
                        "profile `" + id + "` args must be a list of strings");
            }
            if (argsNode.size() > MAX_ARGS) {
                throw new CommandPlanRejectedException(
                        "profile `" + id + "` declares more than " + MAX_ARGS + " args");
            }
            for (JsonNode argNode : argsNode) {
                if (!argNode.isTextual()) {
                    throw new CommandPlanRejectedException(
                            "profile `" + id + "` args must be strings");
                }
                String arg = argNode.asText();
                if (arg.isBlank() || arg.length() > MAX_ARG_LENGTH) {
                    throw new CommandPlanRejectedException(
                            "profile `" + id + "` has a blank or over-"
                                    + MAX_ARG_LENGTH + "-char arg");
                }
                screenShellSyntax(id, arg);
                args.add(arg.strip());
            }
        }

        long timeoutMs = CommandProfile.DEFAULT_TIMEOUT_MS;
        JsonNode timeoutNode = node.path("timeout_ms");
        if (!timeoutNode.isMissingNode() && !timeoutNode.isNull()) {
            if (!timeoutNode.canConvertToLong()) {
                throw new CommandPlanRejectedException(
                        "profile `" + id + "` timeout_ms must be an integer");
            }
            timeoutMs = Math.max(CommandToolPlanner.MIN_TIMEOUT_MS,
                    Math.min(CommandToolPlanner.MAX_TIMEOUT_MS, timeoutNode.asLong()));
        }

        List<String> expectedWrites = new ArrayList<>();
        JsonNode writesNode = node.path("expected_writes");
        if (!writesNode.isMissingNode() && !writesNode.isNull()) {
            if (!writesNode.isArray()) {
                throw new CommandPlanRejectedException(
                        "profile `" + id + "` expected_writes must be a list of strings");
            }
            for (JsonNode writeNode : writesNode) {
                String value = writeNode.isTextual() ? writeNode.asText().strip() : "";
                if (value.isBlank() || value.length() > MAX_ARG_LENGTH) {
                    throw new CommandPlanRejectedException(
                            "profile `" + id + "` has a blank or oversize expected_writes entry");
                }
                rejectEscape(id, value, workspace);
                expectedWrites.add(value);
            }
        }

        return new CommandProfile(
                PROFILE_ID_PREFIX + id,
                "workspace " + id,
                executable,
                List.copyOf(args),
                CommandRisk.BUILD_OR_TEST,
                false,
                false,
                List.copyOf(expectedWrites),
                true,
                false,
                timeoutMs,
                CommandProfile.DEFAULT_IDLE_TIMEOUT_MS,
                CommandOutputLimits.defaults());
    }

    /**
     * Executable forms: a bare program name (PATH-resolved by the OS at run
     * time), an absolute path, or a workspace-relative path (wrappers like
     * {@code ./gradlew} - the norm in non-Java repos). Relative and absolute
     * forms must exist and relative forms must stay inside the workspace;
     * the resolved absolute path is what registers (and what every trust
     * and approval prompt displays - owner decision 2026-06-12).
     */
    private static String resolveExecutable(String raw, String id, Path workspace) {
        if (raw.isBlank()) {
            throw new CommandPlanRejectedException(
                    "profile `" + id + "` is missing `executable`");
        }
        if (raw.length() > MAX_EXECUTABLE_LENGTH) {
            throw new CommandPlanRejectedException(
                    "profile `" + id + "` executable exceeds "
                            + MAX_EXECUTABLE_LENGTH + " chars");
        }
        screenShellSyntax(id, raw);
        boolean pathLike = raw.contains("/") || raw.contains("\\");
        if (!pathLike) {
            return raw; // bare program name - the OS resolves it from PATH
        }
        Path candidate;
        try {
            Path requested = Path.of(raw);
            candidate = requested.isAbsolute()
                    ? requested.toAbsolutePath().normalize()
                    : workspace.resolve(requested).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            throw new CommandPlanRejectedException(
                    "profile `" + id + "` executable is not a valid path: " + raw);
        }
        if (!Path.of(raw).isAbsolute() && !candidate.startsWith(workspace)) {
            throw new CommandPlanRejectedException(
                    "profile `" + id + "` executable escapes the workspace: " + raw);
        }
        if (!Files.isRegularFile(candidate)) {
            throw new CommandPlanRejectedException(
                    "profile `" + id + "` executable not found: " + candidate);
        }
        return candidate.toString();
    }

    private static void screenShellSyntax(String id, String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        for (String marker : CommandArgumentPolicy.SHELL_SYNTAX) {
            if (lower.contains(marker)) {
                // Argv-only execution makes metacharacters inert, but
                // rejecting them blocks confusion attacks cheaply.
                throw new CommandPlanRejectedException(
                        "profile `" + id + "` contains shell syntax: " + marker);
            }
        }
    }

    private static void rejectEscape(String id, String relative, Path workspace) {
        Path resolved;
        try {
            resolved = workspace.resolve(relative).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            throw new CommandPlanRejectedException(
                    "profile `" + id + "` expected_writes entry is invalid: " + relative);
        }
        if (!resolved.startsWith(workspace)) {
            throw new CommandPlanRejectedException(
                    "profile `" + id + "` expected_writes escapes the workspace: " + relative);
        }
    }

    private static String sha256Hex(byte[] raw) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(raw));
        } catch (Exception e) {
            return "";
        }
    }
}
