package dev.talos.core;

import java.util.*;

public final class CfgUtil {
    private CfgUtil() {}

    @SuppressWarnings("unchecked")
    public static Map<String,Object> map(Object o) {
        if (o == null) return Map.of();
        if (o instanceof Map<?,?> m) return (Map<String,Object>) m;
        return Map.of();
    }

    public static int intAt(Map<String,Object> m, String key, int def) {
        Object o = m.get(key);
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) { try { return Integer.parseInt(s.trim()); } catch (Exception ignore) {} }
        return def;
    }

    public static long longAt(Map<String,Object> m, String key, long def) {
        Object o = m.get(key);
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s) { try { return Long.parseLong(s.trim()); } catch (Exception ignore) {} }
        return def;
    }

    public static double doubleAt(Map<String,Object> m, String key, double def) {
        Object o = m.get(key);
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) { try { return Double.parseDouble(s.trim()); } catch (Exception ignore) {} }
        return def;
    }

    public static boolean boolAt(Map<String,Object> m, String key, boolean def) {
        Object o = m.get(key);
        if (o instanceof Boolean b) return b;
        if (o instanceof String s) {
            String v = s.trim().toLowerCase(Locale.ROOT);
            if (v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("on")) return true;
            if (v.equals("false") || v.equals("0") || v.equals("no") || v.equals("off")) return false;
        }
        return def;
    }

    public static List<String> strList(Object o) {
        if (o instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object e : list) if (e != null) out.add(e.toString());
            return out;
        }
        return List.of();
    }

    /**
     * Deep merge: overlays 'override' onto 'base', mutating base.
     * If both values are maps, recurse; otherwise override wins.
     */
    @SuppressWarnings("unchecked")
    public static void deepMerge(Map<String, Object> base, Map<String, Object> override) {
        if (override == null) return;
        for (Map.Entry<String, Object> e : override.entrySet()) {
            String k = e.getKey();
            Object vOver = e.getValue();
            Object vBase = base.get(k);
            if (vBase instanceof Map && vOver instanceof Map) {
                // Both maps: recurse
                deepMerge((Map<String, Object>) vBase, (Map<String, Object>) vOver);
            } else {
                // Override wins
                base.put(k, vOver);
            }
        }
    }

    /**
     * Parse ENV vars with TALOS__ prefix into a nested map.
     * Convention: TALOS__rag__top_k=8 -> rag.top_k=8
     * Double underscore separates path segments.
     */
    public static Map<String, Object> parseEnvOverrides() {
        Map<String, Object> result = new LinkedHashMap<>();
        System.getenv().forEach((key, val) -> {
            if (!key.startsWith("TALOS__")) return;
            String rest = key.substring(6); // strip "TALOS__"
            String[] parts = rest.split("__");
            if (parts.length == 0) return;

            // Parse value to appropriate type
            Object parsed = parseEnvValue(val);

            // Build nested structure
            Map<String, Object> current = result;
            for (int i = 0; i < parts.length - 1; i++) {
                String seg = parts[i].toLowerCase(Locale.ROOT);
                Object next = current.get(seg);
                if (!(next instanceof Map)) {
                    Map<String, Object> newMap = new LinkedHashMap<>();
                    current.put(seg, newMap);
                    current = newMap;
                } else {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cast = (Map<String, Object>) next;
                    current = cast;
                }
            }
            String leaf = parts[parts.length - 1].toLowerCase(Locale.ROOT);
            current.put(leaf, parsed);
        });
        return result;
    }

    private static Object parseEnvValue(String val) {
        if (val == null) return "";
        String trimmed = val.trim();

        // Try boolean
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.equals("true") || lower.equals("yes") || lower.equals("on")) return Boolean.TRUE;
        if (lower.equals("false") || lower.equals("no") || lower.equals("off")) return Boolean.FALSE;

        // Try number
        try {
            if (trimmed.contains(".")) return Double.parseDouble(trimmed);
            return Long.parseLong(trimmed);
        } catch (NumberFormatException ignore) {}

        // Default to string
        return trimmed;
    }
}
