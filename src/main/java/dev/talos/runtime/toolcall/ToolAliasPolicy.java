package dev.talos.runtime.toolcall;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Explicit policy for canonical Talos tool names and accepted model/backend aliases. */
public final class ToolAliasPolicy {
    private static final Pattern TOOL_LIKE_TOKEN = Pattern.compile(
            "(?i)\\b([a-z][a-z0-9_-]*(?:[.:][a-z][a-z0-9_-]*)+)\\b");

    private static final Set<String> CANONICAL_TOOL_NAMES = Set.of(
            "talos.read_file",
            "talos.write_file",
            "talos.edit_file",
            "talos.apply_workspace_batch",
            "talos.mkdir",
            "talos.move_path",
            "talos.copy_path",
            "talos.rename_path",
            "talos.list_dir",
            "talos.grep",
            "talos.retrieve"
    );

    private static final Set<String> READ_ONLY_CANONICAL = Set.of(
            "talos.read_file",
            "talos.list_dir",
            "talos.grep",
            "talos.retrieve"
    );

    private static final Set<String> MUTATING_CANONICAL = Set.of(
            "talos.write_file",
            "talos.edit_file",
            "talos.apply_workspace_batch",
            "talos.mkdir",
            "talos.move_path",
            "talos.copy_path",
            "talos.rename_path"
    );

    private static final Map<String, AliasTarget> ALIASES = aliases();

    private ToolAliasPolicy() {}

    public enum AliasDecisionStatus {
        CANONICAL,
        ACCEPTED_ALIAS,
        REJECTED_UNKNOWN_NAMESPACE,
        UNKNOWN
    }

    public record Decision(
            String rawName,
            String canonicalToolName,
            AliasDecisionStatus status,
            BackendToolProfile profile
    ) {
        public boolean accepted() {
            return status == AliasDecisionStatus.CANONICAL
                    || status == AliasDecisionStatus.ACCEPTED_ALIAS;
        }

        public boolean traceWorthy() {
            return status == AliasDecisionStatus.ACCEPTED_ALIAS
                    || status == AliasDecisionStatus.REJECTED_UNKNOWN_NAMESPACE;
        }

        public boolean readOnly() {
            return READ_ONLY_CANONICAL.contains(canonicalToolName);
        }

        public boolean mutating() {
            return MUTATING_CANONICAL.contains(canonicalToolName);
        }

        public String localCanonicalName() {
            if (canonicalToolName == null || !canonicalToolName.startsWith("talos.")) {
                return "";
            }
            return canonicalToolName.substring("talos.".length());
        }
    }

    public static Decision resolve(String rawName) {
        String raw = rawName == null ? "" : rawName.strip();
        if (raw.isBlank()) {
            return unknown(raw, "");
        }

        String normalized = normalizeTalosSeparator(raw.toLowerCase(Locale.ROOT));
        if (CANONICAL_TOOL_NAMES.contains(normalized)) {
            return new Decision(raw, normalized, AliasDecisionStatus.CANONICAL, BackendToolProfile.TALOS);
        }

        AliasTarget direct = ALIASES.get(normalized);
        if (direct != null) {
            return new Decision(raw, direct.canonicalToolName(), AliasDecisionStatus.ACCEPTED_ALIAS, direct.profile());
        }

        if (normalized.startsWith("talos.")) {
            AliasTarget stripped = ALIASES.get(normalized.substring("talos.".length()));
            if (stripped != null) {
                return new Decision(raw, stripped.canonicalToolName(), AliasDecisionStatus.ACCEPTED_ALIAS,
                        BackendToolProfile.TALOS);
            }
        }

        String suffix = suffixAfterNamespace(normalized);
        if (!suffix.isBlank()) {
            AliasTarget suffixTarget = ALIASES.get(suffix);
            if (suffixTarget != null || CANONICAL_TOOL_NAMES.contains("talos." + suffix)) {
                String canonical = suffixTarget == null ? "talos." + suffix : suffixTarget.canonicalToolName();
                return new Decision(raw, canonical, AliasDecisionStatus.REJECTED_UNKNOWN_NAMESPACE,
                        BackendToolProfile.UNKNOWN);
            }
        }

        return unknown(raw, normalized);
    }

    public static boolean isReadOnly(String rawName) {
        return resolve(rawName).readOnly();
    }

    public static boolean isMutating(String rawName) {
        return resolve(rawName).mutating();
    }

    public static String localCanonicalName(String rawName) {
        return resolve(rawName).localCanonicalName();
    }

    public static Optional<String> firstToolAliasToken(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        Matcher matcher = TOOL_LIKE_TOKEN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group(1);
            Decision decision = resolve(token);
            if (decision.accepted()
                    || decision.status() == AliasDecisionStatus.REJECTED_UNKNOWN_NAMESPACE) {
                return Optional.of(token);
            }
        }
        return Optional.empty();
    }

    public static String normalizeTalosSeparator(String rawName) {
        if (rawName == null) return "";
        String normalized = rawName.strip();
        if (normalized.length() > 5 && normalized.regionMatches(true, 0, "talos", 0, 5)) {
            char c = normalized.charAt(5);
            if (c == ':' || c == '/' || c == '-' || c == '_') {
                normalized = "talos." + normalized.substring(6);
            }
        }
        return normalized;
    }

    private static Decision unknown(String raw, String normalized) {
        return new Decision(raw, normalized == null ? "" : normalized, AliasDecisionStatus.UNKNOWN,
                BackendToolProfile.UNKNOWN);
    }

    private static String suffixAfterNamespace(String normalized) {
        int colon = normalized.lastIndexOf(':');
        int dot = normalized.lastIndexOf('.');
        int index = Math.max(colon, dot);
        if (index <= 0 || index >= normalized.length() - 1) return "";
        return normalized.substring(index + 1);
    }

    private static Map<String, AliasTarget> aliases() {
        Map<String, AliasTarget> out = new LinkedHashMap<>();
        addAliases(out, BackendToolProfile.TALOS, "talos.write_file",
                "file_write", "write_file", "file_create", "create_file", "writefile", "createfile");
        addAliases(out, BackendToolProfile.TALOS, "talos.read_file",
                "file_read", "read_file", "readfile");
        addAliases(out, BackendToolProfile.TALOS, "talos.edit_file",
                "file_edit", "edit_file", "editfile");
        addAliases(out, BackendToolProfile.TALOS, "talos.apply_workspace_batch",
                "apply_workspace_batch", "workspace_batch", "batch_apply", "apply_batch");
        addAliases(out, BackendToolProfile.TALOS, "talos.mkdir",
                "mkdir", "make_dir", "make_directory", "create_dir", "create_directory");
        addAliases(out, BackendToolProfile.TALOS, "talos.move_path",
                "move_path", "move", "mv");
        addAliases(out, BackendToolProfile.TALOS, "talos.copy_path",
                "copy_path", "copy", "cp");
        addAliases(out, BackendToolProfile.TALOS, "talos.rename_path",
                "rename_path", "rename");
        addAliases(out, BackendToolProfile.TALOS, "talos.list_dir",
                "list_dir", "list_directory", "dir_list", "ls", "listdir", "listdirectory");
        addAliases(out, BackendToolProfile.TALOS, "talos.grep",
                "grep", "search", "grepsearch");
        addAliases(out, BackendToolProfile.TALOS, "talos.retrieve",
                "retrieve");

        addBackendAliases(out, BackendToolProfile.TOOL_USE, "tool_use");
        addBackendAliases(out, BackendToolProfile.FILE_UTILS, "file_utils");
        return Map.copyOf(out);
    }

    private static void addBackendAliases(Map<String, AliasTarget> out, BackendToolProfile profile, String namespace) {
        addAliases(out, profile, "talos.write_file", namespace + ":write_file", namespace + ".write_file");
        addAliases(out, profile, "talos.read_file", namespace + ":read_file", namespace + ".read_file");
        addAliases(out, profile, "talos.edit_file", namespace + ":edit_file", namespace + ".edit_file");
        addAliases(out, profile, "talos.apply_workspace_batch",
                namespace + ":apply_workspace_batch", namespace + ".apply_workspace_batch");
        addAliases(out, profile, "talos.mkdir", namespace + ":mkdir", namespace + ".mkdir");
        addAliases(out, profile, "talos.move_path", namespace + ":move_path", namespace + ".move_path");
        addAliases(out, profile, "talos.copy_path", namespace + ":copy_path", namespace + ".copy_path");
        addAliases(out, profile, "talos.rename_path", namespace + ":rename_path", namespace + ".rename_path");
        addAliases(out, profile, "talos.list_dir", namespace + ":list_dir", namespace + ".list_dir");
        addAliases(out, profile, "talos.grep", namespace + ":grep", namespace + ".grep");
        addAliases(out, profile, "talos.retrieve", namespace + ":retrieve", namespace + ".retrieve");
    }

    private static void addAliases(
            Map<String, AliasTarget> out,
            BackendToolProfile profile,
            String canonicalToolName,
            String... aliases
    ) {
        AliasTarget target = new AliasTarget(canonicalToolName, profile);
        for (String alias : aliases) {
            out.put(alias, target);
        }
    }

    private record AliasTarget(String canonicalToolName, BackendToolProfile profile) {}
}
