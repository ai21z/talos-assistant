package dev.talos.runtime.policy;

import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.tools.ToolRiskLevel;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** One declarative permission rule from config. */
public record PermissionRule(
        PermissionAction action,
        List<String> tools,
        List<String> risks,
        List<String> phases,
        List<String> paths,
        Boolean withinWorkspace,
        String reason
) {
    public PermissionRule {
        tools = normalizeList(tools);
        risks = normalizeList(risks);
        phases = normalizeList(phases);
        paths = paths == null ? List.of() : paths.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::strip)
                .toList();
        reason = reason == null || reason.isBlank() ? "permission rule" : reason.strip();
    }

    @SuppressWarnings("unchecked")
    public static PermissionRule fromMap(Map<?, ?> raw) {
        if (raw == null) {
            return new PermissionRule(PermissionAction.DENY, List.of(), List.of(), List.of(), List.of(), null,
                    "Invalid empty permission rule");
        }
        String effect = string(raw.get("effect"));
        PermissionAction action = parseAction(effect);
        return new PermissionRule(
                action,
                list(raw.get("tools")),
                list(raw.get("risks")),
                list(raw.get("phases")),
                list(raw.get("paths")),
                bool(raw.get("within_workspace")),
                action == PermissionAction.DENY && parseActionOrNull(effect) == null
                        ? "Invalid permission rule effect: " + effect
                        : string(raw.get("reason")));
    }

    public boolean matches(PermissionRequest request, ResourceDecision resource) {
        String tool = normalize(request.call() == null ? "" : request.call().toolName());
        ToolRiskLevel risk = request.effectiveRisk();
        ExecutionPhase phase = request.effectivePhase();

        if (!tools.isEmpty() && !tools.contains(tool)) return false;
        if (!risks.isEmpty() && !risks.contains(risk.name().toLowerCase(Locale.ROOT))) return false;
        if (!phases.isEmpty() && !phases.contains(phase.name().toLowerCase(Locale.ROOT))) return false;
        if (withinWorkspace != null && resource != null && withinWorkspace != resource.insideWorkspace()) return false;
        if (!paths.isEmpty()) {
            if (resource == null || resource.relativePath().isBlank()) return false;
            return paths.stream().anyMatch(pattern -> globMatches(pattern, resource.relativePath()));
        }
        return true;
    }

    private static PermissionAction parseAction(String raw) {
        PermissionAction parsed = parseActionOrNull(raw);
        return parsed == null ? PermissionAction.DENY : parsed;
    }

    private static PermissionAction parseActionOrNull(String raw) {
        if (raw == null) return null;
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "allow" -> PermissionAction.ALLOW;
            case "ask" -> PermissionAction.ASK;
            case "deny" -> PermissionAction.DENY;
            default -> null;
        };
    }

    private static boolean globMatches(String pattern, String relativePath) {
        String normalizedPattern = normalizePath(pattern);
        String normalizedPath = normalizePath(relativePath);
        if (globRegex(normalizedPattern).matcher(normalizedPath).matches()) return true;
        if (normalizedPattern.startsWith("**/")) {
            return globRegex(normalizedPattern.substring(3)).matcher(normalizedPath).matches();
        }
        return false;
    }

    private static Pattern globRegex(String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    regex.append(".*");
                    i++;
                } else {
                    regex.append("[^/]*");
                }
            } else if (c == '?') {
                regex.append("[^/]");
            } else {
                if ("\\.[]{}()+-^$|".indexOf(c) >= 0) regex.append('\\');
                regex.append(c);
            }
        }
        regex.append('$');
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }

    private static List<String> normalizeList(List<String> input) {
        if (input == null) return List.of();
        return input.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(PermissionRule::normalize)
                .toList();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    private static String normalizePath(String value) {
        String s = value == null ? "" : value.strip().replace('\\', '/');
        while (s.startsWith("./")) s = s.substring(2);
        return s.toLowerCase(Locale.ROOT);
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Boolean bool(Object value) {
        if (value instanceof Boolean b) return b;
        if (value == null) return null;
        String s = String.valueOf(value).strip().toLowerCase(Locale.ROOT);
        if ("true".equals(s) || "yes".equals(s) || "1".equals(s)) return Boolean.TRUE;
        if ("false".equals(s) || "no".equals(s) || "0".equals(s)) return Boolean.FALSE;
        return null;
    }

    private static List<String> list(Object value) {
        if (value instanceof List<?> xs) {
            return xs.stream().map(String::valueOf).toList();
        }
        if (value == null) return List.of();
        return List.of(String.valueOf(value));
    }
}
